package com.fireplatform.notification;

import com.fireplatform.shared.Page;
import com.fireplatform.shared.Pagination;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.ScopeFilter;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** N09：真实厂商发送尝试。UNKNOWN 不自动重发，避免无法证明是否送达时重复通知。 */
@Service
public class NotificationProviderAttemptService {
    public record Attempt(String id,String tenantId,String eventKind,String sourceKind,String sourceId,String alarmId,String deviceId,String contactId,String channel,int attemptNo,String status,String providerCode,String providerId,OffsetDateTime createdAt,OffsetDateTime finishedAt) { }
    public static final int MAX_ATTEMPTS = 3;
    /** Aliyun voice OutId is limited to 1..15 characters; this stable 15-hex prefix binds a receipt to its attempt. */
    public static String outIdFor(String attemptId) {
        String hex = attemptId.replace("-", "");
        return hex.length() > 15 ? hex.substring(0, 15) : hex;
    }
    private final JdbcTemplate jdbc;
    private final ScopeFilter tree;
    private final DeviceAccessScope deviceScope;
    private final ContactPhoneStorage phones;
    private final java.time.Clock clock;
    public NotificationProviderAttemptService(JdbcTemplate jdbc,ScopeFilter tree,DeviceAccessScope deviceScope,ContactPhoneStorage phones,java.time.Clock clock) { this.jdbc=jdbc;this.tree=tree;this.deviceScope=deviceScope;this.phones=phones;this.clock=clock; }


    @Transactional(readOnly=true)
    public Attempt latest(String tenant,String eventKind,String sourceKind,String sourceId,String contact,String channel,String occurrenceKey) {
        var rows=jdbc.query("SELECT * FROM notification_provider_attempts WHERE tenant_id=? AND event_kind=? AND source_kind=? AND source_id=? AND contact_id=? AND channel=? AND occurrence_key=? ORDER BY attempt_no DESC LIMIT 1",(rs,n)->attempt(rs),tenant,eventKind,sourceKind,sourceId,contact,channel,occurrenceKey);
        return rows.isEmpty()?null:rows.getFirst();
    }

    /**
     * Snapshots the actual destination and provider account used for this submission so later queries never rely on a
     * changed contact row or the current account. Missing contact encryption leaves the snapshot absent (unprovable).
     */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Attempt begin(String tenant,String eventKind,String sourceKind,String sourceId,String device,String contact,String channel,String occurrenceKey,int attemptNo,String destinationPhone,String accountFingerprint) {
        String id=UUID.randomUUID().toString();
        String alarmId="FIRE".equals(eventKind)?sourceId:null;
        String cipher=null,nonce=null,masked=null,mac=null;
        if(destinationPhone!=null && !destinationPhone.isBlank()) {
            try {
                var protectedPhone=phones.protect(tenant,destinationPhone);
                cipher=protectedPhone.cipher(); nonce=protectedPhone.nonce(); masked=protectedPhone.masked(); mac=protectedPhone.mac();
            } catch (RuntimeException ignored) { /* the send may still proceed; the attempt stays unprovable instead of inventing a phone */ }
        }
        jdbc.update("INSERT INTO notification_provider_attempts(id,tenant_id,alarm_id,device_id,contact_id,channel,attempt_no,occurrence_key,status,created_at,event_kind,source_kind,source_id,destination_cipher,destination_nonce,destination_masked,destination_mac,account_fingerprint,out_id) VALUES (?,?,?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP,?,?,?,?,?,?,?,?,?)",id,tenant,alarmId,device,contact,channel,attemptNo,occurrenceKey,eventKind,sourceKind,sourceId,cipher,nonce,masked,mac,accountFingerprint,outIdFor(id));
        return jdbc.queryForObject("SELECT * FROM notification_provider_attempts WHERE id=?",(rs,n)->attempt(rs),id);
    }

    /**
     * Persists the frozen WeChat destination/account/template payload before any external call.
     * The OpenID is already encrypted by the Official Account secret storage; this service never
     * sees or logs a plaintext OpenID.
     */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Attempt beginWechat(String tenant,String eventKind,String sourceKind,String sourceId,String device,String contact,
                               String occurrenceKey,int attemptNo,String destinationCipher,String destinationNonce,String destinationMasked,
                               String destinationMac,String accountFingerprint,String bindingId,long configRevision,
                               String templateId,String payloadJson,String payloadDigest) {
        String id=UUID.randomUUID().toString();
        String alarmId="FIRE".equals(eventKind)?sourceId:null;
        jdbc.update("INSERT INTO notification_provider_attempts(id,tenant_id,alarm_id,device_id,contact_id,channel,attempt_no,occurrence_key,status,created_at,"
                + "event_kind,source_kind,source_id,destination_cipher,destination_nonce,destination_masked,destination_mac,account_fingerprint,"
                + "binding_id,provider_config_revision,template_id,payload_json,payload_digest) "
                + "VALUES (?,?,?,?,?,'WECHAT',?,?,'PENDING',CURRENT_TIMESTAMP,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,tenant,alarmId,device,contact,attemptNo,occurrenceKey,eventKind,sourceKind,sourceId,
            destinationCipher,destinationNonce,destinationMasked,destinationMac,accountFingerprint,
            bindingId,configRevision,templateId,payloadJson,payloadDigest);
        return jdbc.queryForObject("SELECT * FROM notification_provider_attempts WHERE id=?",(rs,n)->attempt(rs),id);
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void finish(String id,String status,String code,String providerId) {
        if ("SENT".equals(status)) {
            java.time.LocalDate providerDate = java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Shanghai")));
            jdbc.update("UPDATE notification_provider_attempts SET status=?,provider_code=?,provider_id=?,finished_at=CURRENT_TIMESTAMP,snapshot_send_date=? WHERE id=? AND status='PENDING'",status,code,providerId,providerDate,id);
        } else {
            jdbc.update("UPDATE notification_provider_attempts SET status=?,provider_code=?,provider_id=?,finished_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",status,code,providerId,id);
        }
    }

    @Transactional(readOnly=true)
    public Page<Attempt> list(String tenant,int page,Integer size) {
        var p=Pagination.of(page,size,20);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM notification_provider_attempts WHERE tenant_id=?",Long.class,tenant);
        var items=jdbc.query("SELECT * FROM notification_provider_attempts WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(rs,n)->attempt(rs),tenant,p.size(),p.offset());
        return new Page<>(items,total,p.page(),p.size());
    }
    @Transactional(readOnly=true)
    public Page<Attempt> list(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size) {
        var p=Pagination.of(page,size,20);
        var units=tree.units(actor,"a","tenant_id",tenant,selection);
        var access=deviceScope.predicate(actor,"d.tenant_id","d.id");
        var selected=tree.devices(actor,"d",tenant,selection);
        String from=" FROM notification_provider_attempts a JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id WHERE 1=1"+units.and()+access.and()+selected.and();
        var args=new ArrayList<Object>();args.addAll(units.bindings());args.addAll(access.bindings());args.addAll(selected.bindings());
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
        var queryArgs=new ArrayList<>(args);queryArgs.add(p.size());queryArgs.add(p.offset());
        var items=jdbc.query("SELECT a.*"+from+" ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",(rs,n)->attempt(rs),queryArgs.toArray());
        return new Page<>(items,total,p.page(),p.size());
    }
    private static Attempt attempt(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Attempt(rs.getString("id"),rs.getString("tenant_id"),rs.getString("event_kind"),rs.getString("source_kind"),rs.getString("source_id"),rs.getString("alarm_id"),rs.getString("device_id"),rs.getString("contact_id"),rs.getString("channel"),rs.getInt("attempt_no"),rs.getString("status"),rs.getString("provider_code"),rs.getString("provider_id"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("finished_at",OffsetDateTime.class));
    }
}
