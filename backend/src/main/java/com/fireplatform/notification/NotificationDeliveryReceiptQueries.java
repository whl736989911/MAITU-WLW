package com.fireplatform.notification;

import com.fireplatform.shared.*;
import com.fireplatform.transport.AliyunNotificationClient;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read projections for provider delivery receipts. Reads never trigger a vendor call. */
@Service
public class NotificationDeliveryReceiptQueries {
    public record Receipt(String state,String reason,String providerStatus,String providerCode,long revision,OffsetDateTime firstObservedAt,OffsetDateTime lastObservedAt) { }
    public record Snapshot(String destinationMasked,boolean destinationBound,boolean accountBound,LocalDate sendDate,String outId) { }
    public record Eligibility(boolean queryable,String reason) { }
    public record Delivery(String id,String tenantId,String eventKind,String sourceKind,String sourceId,String alarmId,String deviceId,String contactId,
                           String channel,int attemptNo,String status,String providerCode,String providerId,OffsetDateTime createdAt,OffsetDateTime finishedAt,
                           Snapshot snapshot,Receipt receipt,Eligibility eligibility,String bindingId,Long configRevision,String templateId,String payloadDigest) { }
    public record Observation(String id,String source,String state,String reason,String providerStatus,String providerCode,String providerBizId,
                              String providerPhoneMasked,String providerSendAt,String providerReceiveAt,String providerCreatedAt,Integer durationSeconds,
                              String queryRequestId,boolean networkQueried,String evidenceDigest,OffsetDateTime observedAt) { }
    public record Detail(Delivery delivery,List<Observation> observations,NotificationDeliveryReceiptPolicy.View config) { }

    private static final String BASE = " FROM notification_provider_attempts a JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id"
            + " LEFT JOIN notification_delivery_receipts r ON r.attempt_id=a.id AND r.tenant_id=a.tenant_id WHERE 1=1";

    private final JdbcTemplate jdbc;
    private final ScopeFilter tree;
    private final DeviceAccessScope deviceScope;
    private final AliyunNotificationClient client;
    private final NotificationDeliveryReceiptPolicy policy;
    private final Clock clock;

    public NotificationDeliveryReceiptQueries(JdbcTemplate jdbc,ScopeFilter tree,DeviceAccessScope deviceScope,AliyunNotificationClient client,
                                              NotificationDeliveryReceiptPolicy policy,Clock clock) {
        this.jdbc=jdbc;this.tree=tree;this.deviceScope=deviceScope;this.client=client;this.policy=policy;this.clock=clock;
    }

    @Transactional(readOnly=true)
    public Page<Delivery> list(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size) {
        var p=Pagination.of(page,size,20);
        var units=tree.units(actor,"a","tenant_id",tenant,selection);
        var access=deviceScope.predicate(actor,"d.tenant_id","d.id");
        var selected=tree.devices(actor,"d",tenant,selection);
        String from=BASE+units.and()+access.and()+selected.and();
        var args=new ArrayList<Object>();args.addAll(units.bindings());args.addAll(access.bindings());args.addAll(selected.bindings());
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
        var queryArgs=new ArrayList<>(args);queryArgs.add(p.size());queryArgs.add(p.offset());
        var items=jdbc.query("SELECT a.*,r.state AS receipt_state,r.reason AS receipt_reason,r.provider_status AS receipt_status,"
                + "r.provider_code AS receipt_code,r.revision AS receipt_revision,r.first_observed_at AS receipt_first_observed,"
                + "r.last_observed_at AS receipt_last_observed"+from
                + " ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",(rs,n)->delivery(rs),(Object[])queryArgs.toArray());
        return new Page<>(items,total,p.page(),p.size());
    }

    @Transactional(readOnly=true)
    public Detail detail(String actor,String tenant,ScopeFilter.Selection selection,String id) {
        var units=tree.units(actor,"a","tenant_id",tenant,selection);
        var access=deviceScope.predicate(actor,"d.tenant_id","d.id");
        var selected=tree.devices(actor,"d",tenant,selection);
        String from=BASE+units.and()+access.and()+selected.and()+" AND a.id=?";
        var args=new ArrayList<Object>();args.addAll(units.bindings());args.addAll(access.bindings());args.addAll(selected.bindings());args.add(id);
        var rows=jdbc.query("SELECT a.*,r.state AS receipt_state,r.reason AS receipt_reason,r.provider_status AS receipt_status,"
                + "r.provider_code AS receipt_code,r.revision AS receipt_revision,r.first_observed_at AS receipt_first_observed,"
                + "r.last_observed_at AS receipt_last_observed"+from,(rs,n)->delivery(rs),args.toArray());
        if(rows.isEmpty()) throw ApiError.missing();
        Delivery delivery=rows.getFirst();
        var observations=jdbc.query("SELECT id,source,state,reason,provider_status,provider_code,provider_biz_id,provider_phone_masked,"
                + "provider_send_at,provider_receive_at,provider_created_at,duration_seconds,query_request_id,network_queried,evidence_digest,created_at"
                + " FROM notification_delivery_receipt_observations WHERE attempt_id=? AND tenant_id=? ORDER BY created_at DESC,id DESC LIMIT 500",
                (rs,n)->new Observation(rs.getString("id"),rs.getString("source"),rs.getString("state"),rs.getString("reason"),
                        rs.getString("provider_status"),rs.getString("provider_code"),rs.getString("provider_biz_id"),
                        rs.getString("provider_phone_masked"),rs.getString("provider_send_at"),rs.getString("provider_receive_at"),
                        rs.getString("provider_created_at"),rs.getObject("duration_seconds",Integer.class),
                        rs.getString("query_request_id"),rs.getBoolean("network_queried"),rs.getString("evidence_digest"),
                        rs.getObject("created_at",OffsetDateTime.class)),id,delivery.tenantId());
        return new Detail(delivery,observations,policy.view());
    }

    /** Candidates for the optional periodic query; excludes terminal receipts, stale windows and non-retryable reasons. */
    @Transactional(readOnly=true)
    public List<String> pendingCandidates(int batch,int minAgeSeconds,int retrySeconds,int maxQueriesPerAttempt) {
        if(batch<1) return List.of();
        OffsetDateTime now=OffsetDateTime.now(clock);
        OffsetDateTime minAge=now.minusSeconds(Math.max(0,minAgeSeconds));
        OffsetDateTime retry=now.minusSeconds(Math.max(0,retrySeconds));
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        LocalDate smsFrom=today.minusDays(NotificationDeliveryReceiptEvidence.SMS_LOOKBACK_DAYS);
        LocalDate voiceFrom=today.minusMonths(NotificationDeliveryReceiptEvidence.VOICE_LOOKBACK_MONTHS);
        var reasons=NotificationDeliveryReceiptEvidence.RETRYABLE_REASONS.stream().sorted().toList();
        String placeholders=String.join(",",java.util.Collections.nCopies(reasons.size(),"?"));
        String sql="SELECT a.id FROM notification_provider_attempts a LEFT JOIN notification_delivery_receipts r ON r.attempt_id=a.id"
                + " WHERE a.status='SENT' AND a.channel<>'WECHAT' AND a.provider_id IS NOT NULL AND a.provider_id<>''"
                + " AND a.destination_cipher IS NOT NULL AND a.destination_nonce IS NOT NULL AND a.destination_mac IS NOT NULL"
                + " AND a.account_fingerprint IS NOT NULL AND a.snapshot_send_date IS NOT NULL"
                + " AND a.created_at<=?"
                + " AND ((a.channel='SMS' AND a.snapshot_send_date>=?) OR (a.channel='VOICE' AND a.snapshot_send_date>=? AND a.snapshot_send_date<=?))"
                + " AND (r.attempt_id IS NULL OR r.state='WAITING' OR (r.state='UNCONFIRMED' AND r.reason IN ("+placeholders+")))"
                + " AND (r.last_observed_at IS NULL OR r.last_observed_at<=?)"
                + " AND (SELECT COUNT(*) FROM notification_delivery_receipt_observations o WHERE o.attempt_id=a.id AND o.network_queried=TRUE)<?"
                + " ORDER BY a.created_at ASC LIMIT ?";
        var args=new ArrayList<Object>();args.add(minAge);args.add(smsFrom);args.add(voiceFrom);args.add(today);args.addAll(reasons);args.add(retry);args.add(Math.max(1,maxQueriesPerAttempt));args.add(batch);
        return jdbc.queryForList(sql,String.class,args.toArray());
    }

    private Delivery delivery(java.sql.ResultSet rs) throws java.sql.SQLException {
        String id=rs.getString("id"),tenant=rs.getString("tenant_id"),channel=rs.getString("channel");
        String cipher=rs.getString("destination_cipher"),nonce=rs.getString("destination_nonce"),mac=rs.getString("destination_mac");
        boolean destinationBound=cipher!=null && nonce!=null && mac!=null;
        String fingerprint=rs.getString("account_fingerprint");
        LocalDate sendDate=rs.getObject("snapshot_send_date",LocalDate.class);
        var snapshot=new Snapshot(rs.getString("destination_masked"),destinationBound,fingerprint!=null,sendDate,rs.getString("out_id"));
        String state=rs.getString("receipt_state");
        long revision=rs.getObject("receipt_revision")==null?0L:rs.getLong("receipt_revision");
        var receipt=state==null?null:new Receipt(state,rs.getString("receipt_reason"),rs.getString("receipt_status"),
                rs.getString("receipt_code"),revision,rs.getObject("receipt_first_observed",OffsetDateTime.class),
                rs.getObject("receipt_last_observed",OffsetDateTime.class));
        var delivery=new Delivery(id,tenant,rs.getString("event_kind"),rs.getString("source_kind"),rs.getString("source_id"),
                rs.getString("alarm_id"),rs.getString("device_id"),rs.getString("contact_id"),channel,rs.getInt("attempt_no"),
                rs.getString("status"),rs.getString("provider_code"),rs.getString("provider_id"),
                rs.getObject("created_at",OffsetDateTime.class),rs.getObject("finished_at",OffsetDateTime.class),
                snapshot,receipt,eligibility(channel,rs.getString("status"),rs.getString("provider_id"),cipher,nonce,mac,sendDate,fingerprint),
                rs.getString("binding_id"),rs.getObject("provider_config_revision",Long.class),rs.getString("template_id"),rs.getString("payload_digest"));
        return delivery;
    }

    /** Explains why a receipt can or cannot be proved right now; the command repeats every check before any call. */
    Eligibility eligibility(String channel,String status,String providerId,String cipher,String nonce,String mac,LocalDate sendDate,String accountFingerprint) {
        if("WECHAT".equals(channel)) return new Eligibility(false,NotificationDeliveryReceiptEvidence.WECHAT_CALLBACK_ONLY);
        if(!"SENT".equals(status)) return new Eligibility(false,"ATTEMPT_NOT_ACCEPTED");
        if(NotificationDeliveryReceiptEvidence.trim(providerId)==null) return new Eligibility(false,"PROVIDER_ID_UNAVAILABLE");
        if(cipher==null||nonce==null||mac==null) return new Eligibility(false,"DESTINATION_SNAPSHOT_UNAVAILABLE");
        if(accountFingerprint==null) return new Eligibility(false,"ACCOUNT_SNAPSHOT_UNAVAILABLE");
        if(!client.queryReady()) return new Eligibility(false,"PROVIDER_NOT_CONFIGURED");
        if(!accountFingerprint.equals(client.accountFingerprint())) return new Eligibility(false,"ACCOUNT_MISMATCH");
        if(sendDate==null) return new Eligibility(false,"SEND_DATE_UNAVAILABLE");
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        if(sendDate.isAfter(today)) return new Eligibility(false,"SEND_DATE_INVALID");
        if("SMS".equals(channel)) {
            if(sendDate.isBefore(today.minusDays(NotificationDeliveryReceiptEvidence.SMS_LOOKBACK_DAYS))) return new Eligibility(false,"QUERY_WINDOW_EXHAUSTED");
        } else {
            // QueryDate must be the original call's day, not today; the provider keeps the latest six months.
            if(sendDate.isBefore(today.minusMonths(NotificationDeliveryReceiptEvidence.VOICE_LOOKBACK_MONTHS))) return new Eligibility(false,"QUERY_WINDOW_EXHAUSTED");
        }
        return new Eligibility(true,null);
    }
}
