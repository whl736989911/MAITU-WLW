package com.fireplatform.notification;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.fireplatform.notification.NotificationDeliveryReceiptEvidence.*;

/** Authorized receipt writes: local snapshot binding, immutable observations, monotonic state and idempotent replay. */
@Service
public class NotificationDeliveryReceiptCommands {
    public static final String PERMISSION = "notification-delivery:reconcile";

    public record Row(String id,String tenantId,String deviceId,String channel,String status,String providerId,
                      String destinationCipher,String destinationNonce,String destinationMac,LocalDate sendDate,String accountFingerprint,String outId) { }
    public record Plan(Row row,long expectedRevision,String key,String hash,String reason) { }
    public record Prepared(ReconcileResult result,Plan plan) { }
    public record ObservationPayload(String state,String reason,String providerStatus,String providerCode,String providerMessage,
                                     String providerBizId,String providerPhoneMasked,String providerSendAt,String providerReceiveAt,
                                     String providerCreatedAt,Integer durationSeconds,String requestId) { }
    public record ReconcileResult(String attemptId,String channel,String state,String reason,String providerStatus,String providerCode,
                                  Long revision,String observationId,boolean networkQueried,OffsetDateTime observedAt) { }

    private static final java.util.Set<String> LOCAL_ONLY_REFUSALS = java.util.Set.of(
            "PROVIDER_NOT_CONFIGURED","ACCOUNT_SNAPSHOT_UNAVAILABLE","ACCOUNT_MISMATCH","DESTINATION_UNAVAILABLE","DESTINATION_SNAPSHOT_TAMPERED","SEND_DATE_UNAVAILABLE","WECHAT_CALLBACK_ONLY");

    private static final String INSERT_OBSERVATION = "INSERT INTO notification_delivery_receipt_observations(id,attempt_id,tenant_id,channel,source,state,reason,provider_status,provider_code,provider_message,provider_biz_id,provider_phone_masked,provider_send_at,provider_receive_at,provider_created_at,duration_seconds,query_request_id,network_queried,evidence_digest,actor_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbc;
    private final ExecutionAuthorization auth;
    private final ManagementGuard guard;
    private final TenantFence tenants;
    private final DeviceAccessScope deviceScope;
    private final NotificationDeliveryReceiptQueries queries;
    private final NotificationDeliveryReceiptPolicy policy;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final DatabaseTime time;

    public NotificationDeliveryReceiptCommands(JdbcTemplate jdbc,ExecutionAuthorization auth,ManagementGuard guard,TenantFence tenants,
                                               DeviceAccessScope deviceScope,NotificationDeliveryReceiptQueries queries,NotificationDeliveryReceiptPolicy policy,IdempotencyStore idem,
                                               AuditService audit,DatabaseTime time) {
        this.jdbc=jdbc;this.auth=auth;this.guard=guard;this.tenants=tenants;this.deviceScope=deviceScope;this.queries=queries;this.policy=policy;this.idem=idem;this.audit=audit;this.time=time;
    }

    @Transactional
    public Prepared prepareManual(AuthService.Identity actor,String tenantHeader,ScopeFilter.Selection selection,String attemptId,
                                  NotificationDeliveryReceiptInputs.Reconcile input,String key,String requestId) {
        idem.validateKey(key);
        if(input.expectedRevision()==null) throw ApiError.bad("回执同步必须提供 expected_revision");
        String reason=NotificationPolicy.text(input.reason(),Limits.MAX_CHANGE_REASON_LENGTH);
        var visible=queries.detail(actor.user().id(),tenantHeader,selection,attemptId);
        String tenant=visible.delivery().tenantId();
        auth.lockRequest(actor,tenant,List.of(PERMISSION));
        tenants.lockEnabledRevision(tenant);
        var confirmed=queries.detail(actor.user().id(),null,selection,attemptId);
        if(!confirmed.delivery().tenantId().equals(tenant)) throw ApiError.missing();
        Row row=lock(attemptId,tenant);
        if(row==null) throw ApiError.missing();
        if(!"SMS".equals(row.channel())&&!"VOICE".equals(row.channel()))
            throw ApiError.conflict("WECHAT_CALLBACK_ONLY","公众号模板送达只能由微信服务器完成事件证明，不支持主动查询");
        if(!deviceScope.contains(actor.user().id(),tenant,row.deviceId())) throw ApiError.missing();
        String hash=idem.fingerprint(Arrays.asList(tenant,attemptId,input.expectedRevision(),reason));
        var replay=idem.replay(actor.user().id(),operation(),key,hash,ReconcileResult.class);
        if(replay!=null) return new Prepared(replay,null);
        long current=revision(attemptId);
        if(input.expectedRevision()!=current) throw ApiError.conflict("REVISION_CONFLICT","回执状态已变化，请刷新后重试");
        var eligibility=queries.eligibility(row.channel(),row.status(),row.providerId(),row.destinationCipher(),row.destinationNonce(),row.destinationMac(),row.sendDate(),row.accountFingerprint());
        if(!eligibility.queryable()) {
            var result=record(row,refusal(eligibility.reason()),"MANUAL",actor.user().id(),false);
            idem.save(actor.user().id(),operation(),key,hash,result);
            audit.recordReason(actor.user().id(),tenant,"NOTIFICATION_DELIVERY_RECONCILE",attemptId,result.state(),requestId,reason);
            return new Prepared(result,null);
        }
        return new Prepared(null,new Plan(row,current,key,hash,reason));
    }

    /** Global page-managed periodic query policy: current session authorization, CAS and idempotent replay; no vendor call. */
    @Transactional
    public NotificationDeliveryReceiptPolicy.View updateConfig(AuthService.Identity actor,NotificationDeliveryReceiptInputs.ConfigUpdate input,String key,String requestId) {
        guard.begin(actor,"notification-config:write",null,"management");
        idem.validateKey(key);
        if(input.expectedRevision()==null) throw ApiError.bad("回执查询策略必须提供 expected_revision");
        boolean enabled=NotificationPolicy.flag(input.periodicQueryEnabled());
        long minAge=NotificationPolicy.number(input.minAgeSeconds(),0,86400);
        long retry=NotificationPolicy.number(input.retrySeconds(),60,604800);
        long maxQueries=NotificationPolicy.number(input.maxQueriesPerAttempt(),1,500);
        long batch=NotificationPolicy.number(input.batchSize(),1,100);
        String reason=NotificationPolicy.text(input.reason(),Limits.MAX_CHANGE_REASON_LENGTH);
        String hash=idem.fingerprint(Arrays.asList(enabled,minAge,retry,maxQueries,batch,input.expectedRevision(),reason));
        var replay=idem.replay(actor.user().id(),"notification-delivery-receipt-policy",key,hash,NotificationDeliveryReceiptPolicy.View.class);
        if(replay!=null) return replay;
        var revisions=jdbc.queryForList("SELECT revision FROM notification_delivery_receipt_policy WHERE id=1 FOR UPDATE",Long.class);
        long current=revisions.isEmpty()?0L:revisions.getFirst();
        if(revisions.isEmpty()) {
            jdbc.update("INSERT INTO notification_delivery_receipt_policy(id,periodic_query_enabled,min_age_seconds,retry_seconds,max_queries_per_attempt,batch_size,revision,updated_at) VALUES (1,FALSE,300,600,48,20,0,?)",time.now());
        }
        if(input.expectedRevision()!=current) throw ApiError.conflict("REVISION_CONFLICT","回执查询策略已变化，请刷新后重试");
        int changed=jdbc.update("UPDATE notification_delivery_receipt_policy SET periodic_query_enabled=?,min_age_seconds=?,retry_seconds=?,max_queries_per_attempt=?,batch_size=?,revision=revision+1,updated_at=?,updated_by=? WHERE id=1 AND revision=?",
                enabled,minAge,retry,maxQueries,batch,time.now(),actor.user().id(),current);
        if(changed!=1) throw ApiError.conflict("REVISION_CONFLICT","回执查询策略已变化，请刷新后重试");
        var view=policy.view();
        idem.save(actor.user().id(),"notification-delivery-receipt-policy",key,hash,view);
        audit.recordReason(actor.user().id(),null,"NOTIFICATION_DELIVERY_RECEIPT_POLICY","receipt-query-policy","SUCCESS",requestId,reason);
        return view;
    }

    @Transactional(readOnly=true)
    public Plan prepareScheduled(String attemptId) {
        Row row=load(attemptId);
        if(row==null||!"SENT".equals(row.status())) return null;
        var eligibility=queries.eligibility(row.channel(),row.status(),row.providerId(),row.destinationCipher(),row.destinationNonce(),row.destinationMac(),row.sendDate(),row.accountFingerprint());
        if(!eligibility.queryable()) return null;
        return new Plan(row,revision(attemptId),null,null,null);
    }

    @Transactional
    public ReconcileResult applyManual(AuthService.Identity actor,Plan plan,ObservationPayload payload,String requestId) {
        guard.requireCurrent(actor,PERMISSION,plan.row().tenantId(),"management");
        Row row=lock(plan.row().id(),plan.row().tenantId());
        if(row==null) throw ApiError.missing();
        if(!deviceScope.contains(actor.user().id(),row.tenantId(),row.deviceId())) throw ApiError.missing();
        if(!sameSnapshot(plan.row(),row)) throw ApiError.conflict("RECEIPT_SNAPSHOT_CHANGED","发送尝试凭据已变化，请刷新后重试");
        if(revision(row.id())!=plan.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT","回执状态已变化，请刷新后重试");
        var result=record(row,payload,"MANUAL",actor.user().id(),networkQueried(payload));
        idem.save(actor.user().id(),operation(),plan.key(),plan.hash(),result);
        audit.recordReason(actor.user().id(),row.tenantId(),"NOTIFICATION_DELIVERY_RECONCILE",row.id(),result.state(),requestId,plan.reason());
        return result;
    }

    @Transactional
    public ReconcileResult applyScheduled(Plan plan,ObservationPayload payload) {
        Row row=lock(plan.row().id(),plan.row().tenantId());
        if(row==null||!"SENT".equals(row.status())||!sameSnapshot(plan.row(),row)) return null;
        return record(row,payload,"SCHEDULED",null,networkQueried(payload));
    }

    /** Local acceptance evidence: the provider replied errcode 0 with an optional exact msgid. */
    @Transactional
    public ReconcileResult recordWechatAccepted(String attemptId,String tenant,String msgid,String providerCode) {
        Row row=lock(attemptId,tenant);
        if(row==null||!"WECHAT".equals(row.channel())) return null;
        if(!"SENT".equals(row.status())) return null;
        var payload=new ObservationPayload(ACCEPTED,null,"ACCEPTED",providerCode,null,msgid,null,null,null,null,null,null);
        return record(row,payload,"SEND_RESULT",null,false);
    }

    /** Authenticated TEMPLATESENDJOBFINISH evidence; only published success/failure statuses are terminal. */
    @Transactional
    public ReconcileResult recordWechatCallback(String attemptId,String tenant,String providerStatus,String providerMessage,String eventDigest) {
        Row row=lock(attemptId,tenant);
        if(row==null||!"WECHAT".equals(row.channel())) return null;
        if(!"SENT".equals(row.status())) return null;
        var mapped=wechat(providerStatus);
        var payload=new ObservationPayload(mapped.state(),mapped.reason(),providerStatus,null,providerMessage,
                null,null,null,null,null,null,eventDigest);
        return record(row,payload,"PROVIDER_CALLBACK",null,false);
    }

    static boolean networkQueried(ObservationPayload payload) {
        return payload.reason() == null || !LOCAL_ONLY_REFUSALS.contains(payload.reason());
    }

    public static ObservationPayload refusal(String reason) {
        return new ObservationPayload(UNCONFIRMED,reason,null,null,null,null,null,null,null,null,null,null);
    }

    private String operation() { return "notification-delivery-reconcile"; }

    private Row load(String attemptId) {
        var rows=jdbc.query("SELECT id,tenant_id,device_id,channel,status,provider_id,destination_cipher,destination_nonce,destination_mac,snapshot_send_date,account_fingerprint,out_id FROM notification_provider_attempts WHERE id=?",(rs,n)->row(rs),attemptId);
        return rows.isEmpty()?null:rows.getFirst();
    }

    private Row lock(String attemptId,String tenant) {
        var rows=jdbc.query("SELECT id,tenant_id,device_id,channel,status,provider_id,destination_cipher,destination_nonce,destination_mac,snapshot_send_date,account_fingerprint,out_id FROM notification_provider_attempts WHERE id=? AND tenant_id=? FOR UPDATE",(rs,n)->row(rs),attemptId,tenant);
        return rows.isEmpty()?null:rows.getFirst();
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getString("id"),rs.getString("tenant_id"),rs.getString("device_id"),rs.getString("channel"),rs.getString("status"),
                rs.getString("provider_id"),rs.getString("destination_cipher"),rs.getString("destination_nonce"),rs.getString("destination_mac"),
                rs.getObject("snapshot_send_date",LocalDate.class),rs.getString("account_fingerprint"),rs.getString("out_id"));
    }

    private static boolean sameSnapshot(Row planned,Row current) {
        return Objects.equals(planned.providerId(),current.providerId())
                && Objects.equals(planned.destinationCipher(),current.destinationCipher())
                && Objects.equals(planned.destinationNonce(),current.destinationNonce())
                && Objects.equals(planned.destinationMac(),current.destinationMac())
                && Objects.equals(planned.sendDate(),current.sendDate())
                && Objects.equals(planned.accountFingerprint(),current.accountFingerprint());
    }

    private long revision(String attemptId) {
        var rows=jdbc.queryForList("SELECT revision FROM notification_delivery_receipts WHERE attempt_id=?",Long.class,attemptId);
        return rows.isEmpty()?0L:rows.getFirst();
    }

    private ReconcileResult record(Row row,ObservationPayload payload,String source,String actorId,boolean networkQueried) {
        OffsetDateTime observed=time.now();
        String observationId=UUID.randomUUID().toString();
        String digest=digest(row.id(),row.channel(),source,payload.state(),payload.reason(),payload.providerStatus(),payload.providerCode(),
                payload.providerBizId(),payload.providerPhoneMasked(),payload.providerSendAt(),payload.providerReceiveAt(),
                payload.durationSeconds()==null?null:payload.durationSeconds().toString(),payload.requestId(),observed.toString());
        jdbc.update(INSERT_OBSERVATION,observationId,row.id(),row.tenantId(),row.channel(),source,payload.state(),payload.reason(),
                truncate(payload.providerStatus(),32),truncate(payload.providerCode(),64),truncate(payload.providerMessage(),200),
                truncate(payload.providerBizId(),128),truncate(payload.providerPhoneMasked(),64),truncate(payload.providerSendAt(),40),
                truncate(payload.providerReceiveAt(),40),truncate(payload.providerCreatedAt(),40),
                new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.INTEGER,payload.durationSeconds()),
                truncate(payload.requestId(),128),networkQueried,digest,actorId,observed);
        var rows=jdbc.query("SELECT state,reason,provider_status,provider_code,revision FROM notification_delivery_receipts WHERE attempt_id=?",
                (rs,n)->new Object[]{rs.getString("state"),rs.getString("reason"),rs.getString("provider_status"),rs.getString("provider_code"),rs.getLong("revision")},row.id());
        String state,reason,status,code;
        long revision;
        if(rows.isEmpty()) {
            state=payload.state();reason=payload.reason();status=payload.providerStatus();code=payload.providerCode();revision=1;
            jdbc.update("INSERT INTO notification_delivery_receipts(attempt_id,tenant_id,channel,state,reason,provider_status,provider_code,revision,first_observed_at,last_observed_at,last_observation_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    row.id(),row.tenantId(),row.channel(),state,reason,truncate(status,32),truncate(code,64),revision,observed,observed,observationId);
        } else {
            String currentState=(String)rows.getFirst()[0],currentReason=(String)rows.getFirst()[1],currentStatus=(String)rows.getFirst()[2],currentCode=(String)rows.getFirst()[3];
            revision=((Number)rows.getFirst()[4]).longValue();
            String next=stronger(row.channel(),currentState,payload.state());
            boolean refresh=next.equals(payload.state())&&(!next.equals(currentState)||rank(row.channel(),currentState)<2);
            state=next;
            reason=refresh?payload.reason():currentReason;
            status=refresh?payload.providerStatus():currentStatus;
            code=refresh?payload.providerCode():currentCode;
            int changed=jdbc.update("UPDATE notification_delivery_receipts SET state=?,reason=?,provider_status=?,provider_code=?,revision=revision+1,last_observed_at=?,last_observation_id=? WHERE attempt_id=? AND revision=?",
                    state,reason,truncate(status,32),truncate(code,64),observed,observationId,row.id(),revision);
            if(changed!=1) throw ApiError.conflict("RECEIPT_REVISION_CONFLICT","回执状态已被其他请求更新，请刷新");
            revision++;
        }
        return new ReconcileResult(row.id(),row.channel(),state,reason,status,code,revision,observationId,networkQueried,observed);
    }

    private static String truncate(String value,int max) {
        if(value==null) return null;
        String trimmed=value.trim();
        if(trimmed.isEmpty()) return null;
        return trimmed.length()<=max?trimmed:trimmed.substring(0,max);
    }
}
