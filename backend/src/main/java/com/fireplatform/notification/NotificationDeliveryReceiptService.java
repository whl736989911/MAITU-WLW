package com.fireplatform.notification;

import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.ScopeFilter;
import com.fireplatform.transport.AliyunNotificationClient;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import static com.fireplatform.notification.NotificationDeliveryReceiptEvidence.*;

/** Orchestrates authenticated provider queries strictly outside database locks and transactions. */
@Service
public class NotificationDeliveryReceiptService {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryReceiptService.class);
    private final NotificationDeliveryReceiptCommands commands;
    private final NotificationDeliveryReceiptQueries queries;
    private final NotificationDeliveryReceiptConfig config;
    private final NotificationDeliveryReceiptPolicy policy;
    private final AliyunNotificationClient client;
    private final ContactPhoneStorage phones;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public NotificationDeliveryReceiptService(NotificationDeliveryReceiptCommands commands,NotificationDeliveryReceiptQueries queries,
                                              NotificationDeliveryReceiptConfig config,NotificationDeliveryReceiptPolicy policy,
                                              AliyunNotificationClient client,ContactPhoneStorage phones) {
        this.commands=commands;this.queries=queries;this.config=config;this.policy=policy;this.client=client;this.phones=phones;
    }

    public NotificationDeliveryReceiptCommands.ReconcileResult reconcile(AuthService.Identity actor,String tenantHeader,ScopeFilter.Selection selection,
                                                                         String attemptId,NotificationDeliveryReceiptInputs.Reconcile input,String key,String requestId) {
        var prepared=commands.prepareManual(actor,tenantHeader,selection,attemptId,input,key,requestId);
        if(prepared.result()!=null) return prepared.result();
        var plan=prepared.plan();
        var payload=query(plan);
        return commands.applyManual(actor,plan,payload,requestId);
    }

    /** Optional disabled-by-default scheduled query. Never sends, never changes alarms, billing or device state. */
    public int poll() {
        var effective=policy.effective();
        if(!effective.enabled()) return 0;
        if(!running.compareAndSet(false,true)) return 0;
        try {
            int completed=0;
            for(String attemptId:queries.pendingCandidates(effective.batchSize(),effective.minAgeSeconds(),effective.retrySeconds(),effective.maxQueriesPerAttempt())) {
                var plan=commands.prepareScheduled(attemptId);
                if(plan==null) continue;
                var payload=query(plan);
                if(commands.applyScheduled(plan,payload)!=null) completed++;
            }
            if(completed>0) log.info("provider receipt scheduled query completed={}",completed);
            return completed;
        } catch (RuntimeException error) {
            log.warn("provider receipt scheduled query failed: {}",error.getClass().getSimpleName());
            return 0;
        } finally { running.set(false); }
    }

    private NotificationDeliveryReceiptCommands.ObservationPayload query(NotificationDeliveryReceiptCommands.Plan plan) {
        var row=plan.row();
        if(!client.queryReady()) return NotificationDeliveryReceiptCommands.refusal("PROVIDER_NOT_CONFIGURED");
        if(row.accountFingerprint()==null) return NotificationDeliveryReceiptCommands.refusal("ACCOUNT_SNAPSHOT_UNAVAILABLE");
        if(!row.accountFingerprint().equals(client.accountFingerprint()))
            return NotificationDeliveryReceiptCommands.refusal("ACCOUNT_MISMATCH");
        String phone;
        try {
            phone=phones.reveal(row.tenantId(),row.destinationCipher(),row.destinationNonce());
            if(row.destinationMac()==null||!row.destinationMac().equals(phones.fingerprintPhone(row.tenantId(),phone)))
                return NotificationDeliveryReceiptCommands.refusal("DESTINATION_SNAPSHOT_TAMPERED");
        } catch (RuntimeException error) { return NotificationDeliveryReceiptCommands.refusal("DESTINATION_UNAVAILABLE"); }
        if(row.sendDate()==null) return NotificationDeliveryReceiptCommands.refusal("SEND_DATE_UNAVAILABLE");
        try {
            if("SMS".equals(row.channel())) return mapSms(client.querySms(phone,row.providerId(),row.sendDate(),config.smsPageSize(),1),row,phone);
            return mapVoice(client.queryVoice(row.providerId(),row.sendDate()),row,phone);
        } catch (RuntimeException error) {
            log.warn("provider receipt query threw: {}",error.getClass().getSimpleName());
            return NotificationDeliveryReceiptCommands.refusal("PROVIDER_UNAVAILABLE");
        }
    }

    static NotificationDeliveryReceiptCommands.ObservationPayload mapSms(AliyunNotificationClient.SmsQuery query,NotificationDeliveryReceiptCommands.Row row,String destinationPhone) {
        if(!query.success()) {
            String reason="PROVIDER_UNAVAILABLE".equals(query.code())||"NOT_CONFIGURED".equals(query.code())?"PROVIDER_UNAVAILABLE":"QUERY_REJECTED";
            return new NotificationDeliveryReceiptCommands.ObservationPayload(UNCONFIRMED,reason,null,query.code(),query.message(),null,null,null,null,null,null,query.requestId());
        }
        String businessId=trim(row.providerId());
        var matches=query.details().stream().filter(detail->businessId!=null&&businessId.equals(trim(detail.bizId()))).toList();
        if(matches.isEmpty()) {
            String reason=query.totalCount()>query.details().size()?"PAGE_INCOMPLETE":"RECEIPT_NOT_FOUND";
            String state=query.totalCount()>query.details().size()?UNCONFIRMED:WAITING;
            return new NotificationDeliveryReceiptCommands.ObservationPayload(state,reason,null,null,query.message(),null,null,null,null,null,null,query.requestId());
        }
        if(matches.size()>1)
            return new NotificationDeliveryReceiptCommands.ObservationPayload(UNCONFIRMED,"MULTIPLE_MATCHES",null,null,query.message(),businessId,null,null,null,null,null,query.requestId());
        var detail=matches.getFirst();
        String status=Integer.toString(detail.sendStatus()),code=detail.errCode();
        String state,reason;
        if(!phoneCompatible(destinationPhone,detail.phoneMasked())) { state=UNCONFIRMED;reason="DESTINATION_MISMATCH"; }
        else if(row.outId()!=null&&detail.outId()!=null&&!row.outId().equalsIgnoreCase(detail.outId())) { state=UNCONFIRMED;reason="OUT_ID_MISMATCH"; }
        else { var mapped=sms(detail.sendStatus(),detail.errCode());state=mapped.state();reason=mapped.reason(); }
        return new NotificationDeliveryReceiptCommands.ObservationPayload(state,reason,status,code,query.message(),detail.bizId(),maskProviderPhone(detail.phoneMasked()),detail.sendAt(),detail.receiveAt(),null,null,query.requestId());
    }

    static NotificationDeliveryReceiptCommands.ObservationPayload mapVoice(AliyunNotificationClient.VoiceQuery query,NotificationDeliveryReceiptCommands.Row row,String destinationPhone) {
        if(!query.success()) {
            String reason="PROVIDER_UNAVAILABLE".equals(query.code())||"NOT_CONFIGURED".equals(query.code())?"PROVIDER_UNAVAILABLE":"QUERY_REJECTED";
            return new NotificationDeliveryReceiptCommands.ObservationPayload(UNCONFIRMED,reason,null,query.code(),query.message(),null,null,null,null,null,null,query.requestId());
        }
        var detail=query.detail();
        if(detail==null)
            return new NotificationDeliveryReceiptCommands.ObservationPayload(WAITING,"NOT_SYNCHRONIZED",null,null,query.message(),null,null,null,null,null,null,query.requestId());
        String status=detail.state()!=null?detail.state():detail.stateDesc();
        String state,reason;
        if(!phoneCompatible(destinationPhone,detail.calleeMasked())) { state=UNCONFIRMED;reason="DESTINATION_MISMATCH"; }
        else if(detail.callId()!=null&&!detail.callId().equals(queryCallId(row))) { state=UNCONFIRMED;reason="CORRELATION_MISMATCH"; }
        else { var mapped=voice(status,detail.duration(),detail.startAt());state=mapped.state();reason=mapped.reason(); }
        return new NotificationDeliveryReceiptCommands.ObservationPayload(state,reason,status,detail.stateDesc(),query.message(),queryCallId(row),maskProviderPhone(detail.calleeMasked()),detail.startAt(),detail.endAt(),detail.gmtCreate(),detail.duration(),query.requestId());
    }

    private static String queryCallId(NotificationDeliveryReceiptCommands.Row row) { return trim(row.providerId()); }
}
