package com.fireplatform.ingestion;

import com.fireplatform.alarm.AlarmLifecycle;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.CoordinationFence;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.integration.BusinessFactPublisher;
import com.fireplatform.integration.NormalizedBusinessFact;
import com.fireplatform.product.PublishedMessageNormalizer;
import com.fireplatform.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies authenticated, capture-time-bound messages with ordered, atomic fact/outbox writes. */
@Service
public class MessageApplyCommands {
    private static final Set<String> APPLICABLE=Set.of("FIRE","FAULT","RECOVERY","HEARTBEAT","PARAM","OTHER");
    private final JdbcTemplate jdbc; private final CoordinationFence coordination; private final IngestionGuard guard; private final TenantFence tenants; private final PublishedMessageNormalizer normalizer; private final DeviceAccessScope scopes;
    private final AlarmLifecycle alarms; private final IdempotencyStore idem; private final AuditService audit; private final Clock clock; private final GenericFactBridge bridge; private final BusinessFactPublisher facts;
    public MessageApplyCommands(JdbcTemplate jdbc,CoordinationFence coordination,IngestionGuard guard,TenantFence tenants,PublishedMessageNormalizer normalizer,DeviceAccessScope scopes,AlarmLifecycle alarms,IdempotencyStore idem,AuditService audit,Clock clock,GenericFactBridge bridge,BusinessFactPublisher facts) { this.jdbc=jdbc;this.coordination=coordination;this.guard=guard;this.tenants=tenants;this.normalizer=normalizer;this.scopes=scopes;this.alarms=alarms;this.idem=idem;this.audit=audit;this.clock=clock;this.bridge=bridge;this.facts=facts; }

    private record Row(String id,String tenant,String sourceId,String deviceId,String deviceName,String address,String version,String state,String bindingId,String vendorDeviceId,byte[] payload,OffsetDateTime receivedAt,long revision,Long sequence,OffsetDateTime occurredAt,String applicationState,String provenance,String occurredAtRaw,String sourceMessageId) { }

    @Transactional public IngestionModels.ApplyResult apply(AuthService.Identity actor,String tenant,String id,String key,IngestionInputs.Apply in,String request) {
        coordination.lock();
        guard.begin(actor,tenant,"messages:apply",key,true);
        String fingerprint=idem.fingerprint(List.of(tenant,id,in.expectedRevision(),in.reason().trim()));
        var replay=idem.replay(actor.user().id(),"message-apply",key,fingerprint,IngestionModels.ApplyResult.class); if(replay!=null)return replay;
        lockSourceBeforeRaw(tenant,id);
        var result=applyLocked(tenant,id,in.expectedRevision(),in.reason(),request,actor.user().id(),false);
        idem.save(actor.user().id(),"message-apply",key,fingerprint,result);
        return result;
    }

    /** Durable worker entrypoint; only authenticated captures are eligible. */
    @Transactional public IngestionModels.ApplyResult applyCaptured(String id) {
        var discovered=jdbc.query("SELECT tenant_id,source_id FROM raw_messages WHERE id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},id);
        if(discovered.isEmpty())return null;
        String tenant=discovered.getFirst()[0]; coordination.lock(); tenants.lockEnabled(tenant); lockSourceBeforeRaw(tenant,id);
        return applyLocked(tenant,id,-1,"自动应用已认证来源报文", "generic-message-worker",null,true);
    }

    private void lockSourceBeforeRaw(String tenant,String id) {
        var sources=jdbc.query("SELECT source_id FROM raw_messages WHERE id=? AND tenant_id=?",(rs,n)->rs.getString(1),id,tenant);
        if(sources.isEmpty())throw ApiError.missing();
        jdbc.query("SELECT id FROM ingestion_sources WHERE id=? AND tenant_id=? FOR UPDATE",(rs,n)->rs.getString(1),sources.getFirst(),tenant);
    }
    private IngestionModels.ApplyResult applyLocked(String tenant,String id,long expectedRevision,String reason,String request,String actorId,boolean automatic) {
        var rows=jdbc.query("SELECT id,tenant_id,source_id,device_id,device_name,device_address,product_version_id,binding_state,binding_id,vendor_device_id,payload_utf8,received_at,revision,sequence_no,occurred_at,application_state,capture_provenance,occurred_at_raw,source_message_id FROM raw_messages WHERE id=? AND tenant_id=? FOR UPDATE",(rs,i)->new Row(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getBytes(11),rs.getObject(12,OffsetDateTime.class),rs.getLong(13),(Long)rs.getObject(14,Long.class),rs.getObject(15,OffsetDateTime.class),rs.getString(16),rs.getString(17),rs.getString(18),rs.getString(19)),id,tenant);
        if(rows.isEmpty())throw ApiError.missing(); var raw=rows.getFirst();
        if(!"AUTHENTICATED_SOURCE".equals(raw.provenance())) { if(automatic)return null; throw ApiError.conflict("AUTHENTICATED_CAPTURE_REQUIRED","仅新认证来源捕获可应用事实"); }
        if(!automatic && raw.revision()!=expectedRevision)throw ApiError.conflict("REVISION_CONFLICT","消息投影已更新，请刷新");
        if(!"NOT_APPLIED".equals(raw.applicationState()))return current(raw);
        if(!"BOUND".equals(raw.state())||raw.deviceId()==null||raw.version()==null) {
            if(automatic)return null;
            throw ApiError.conflict("CAPTURED_BINDING_REQUIRED","仅在捕获时已绑定设备的报文可应用");
        }
        if(!automatic)scopes.requireDevice(actorId,tenant,raw.deviceId());
        var source=jdbc.query("SELECT enabled,revision,is_simulated,protocol FROM ingestion_sources WHERE id=? AND tenant_id=? FOR UPDATE",(rs,i)->new Object[]{rs.getBoolean(1),rs.getLong(2),rs.getBoolean(3),rs.getString(4)},raw.sourceId(),tenant);
        if(source.isEmpty()||!Boolean.TRUE.equals(source.getFirst()[0])) { if(automatic)return reject(raw,"SOURCE_DISABLED",null,reason,request,actorId); throw ApiError.conflict("SOURCE_DISABLED","接入来源不可用"); }
        if(Boolean.TRUE.equals(source.getFirst()[2])) { if(automatic)return reject(raw,"SOURCE_RETIRED",null,reason,request,actorId); throw ApiError.conflict("SOURCE_RETIRED","模拟接入来源已停用"); }
        String protocol=(String)source.getFirst()[3];
        var binding=jdbc.queryForList("SELECT device_id FROM active_source_bindings WHERE binding_id=? AND source_id=? AND device_id=? AND vendor_device_id=?",String.class,raw.bindingId(),raw.sourceId(),raw.deviceId(),raw.vendorDeviceId());
        if(binding.isEmpty()) { if(automatic)return reject(raw,"BINDING_CHANGED",null,reason,request,actorId); throw ApiError.conflict("BINDING_CHANGED","捕获时的设备绑定已变化"); }
        var device=jdbc.query("SELECT id,is_simulated,physical_state FROM devices WHERE tenant_id=? AND id=? FOR UPDATE",(rs,i)->new Object[]{rs.getString(1),rs.getBoolean(2),rs.getString(3)},tenant,raw.deviceId());
        if(device.isEmpty())throw ApiError.missing(); if(Boolean.TRUE.equals(device.getFirst()[1])) { if(automatic)return reject(raw,"DEVICE_RETIRED_SIMULATION",null,reason,request,actorId); throw ApiError.conflict("DEVICE_RETIRED_SIMULATION","历史模拟设备不可应用真实来源事件"); }
        PublishedMessageNormalizer.Normalized normalized;
        try{normalized=normalizer.normalize(raw.version(),IngestionLimits.decode(raw.payload()));}
        catch(ApiError e){if(automatic&&e.status!=404&&e.status!=409)return reject(raw,"NORMALIZATION_REJECTED",null,reason,request,actorId);if(e.status==404||e.status==409){if(automatic)return reject(raw,"CAPTURED_VERSION_UNAVAILABLE",null,reason,request,actorId);throw ApiError.conflict("CAPTURED_VERSION_UNAVAILABLE","捕获版本不可用于应用");}throw e;}
        if(!APPLICABLE.contains(normalized.eventType()))return reject(raw,"EVENT_NOT_APPLICABLE",normalized,reason,request,actorId);
        boolean malformedTime=raw.occurredAt()==null && raw.occurredAtRaw()!=null && !raw.occurredAtRaw().isBlank();
        if(malformedTime)return reject(raw,"SOURCE_TIME_INVALID",normalized,reason,request,actorId);
        boolean knownTime=raw.occurredAt()!=null;
        // A recovery without a source time has no causal proof; it must never close an unknown-time or newer fire.
        if(!knownTime && "RECOVERY".equals(normalized.eventType()))return reject(raw,"SOURCE_TIME_REQUIRED_FOR_RECOVERY",normalized,reason,request,actorId);
        Long lastSeq=null; OffsetDateTime lastOccurred=null;
        if(knownTime) {
            var watermark=jdbc.query("SELECT last_sequence,last_occurred_at FROM device_message_watermark WHERE device_id=? AND tenant_id=? FOR UPDATE",(rs,i)->new Object[]{rs.getObject(1,Long.class),rs.getObject(2,OffsetDateTime.class)},raw.deviceId(),tenant);
            lastSeq=watermark.isEmpty()?null:(Long)watermark.getFirst()[0]; lastOccurred=watermark.isEmpty()?null:(OffsetDateTime)watermark.getFirst()[1];
            if(raw.sequence()!=null&&lastSeq!=null&&raw.sequence()<=lastSeq||lastOccurred!=null&&!raw.occurredAt().isAfter(lastOccurred)) {
                bridge.record(raw.tenant(),raw.deviceId(),raw.id(),protocol,raw.occurredAt(),normalized.eventType(),normalized.sourceEventValue(),normalized.readings(),false);
                return finish(raw,reason,"STALE","OUT_OF_ORDER",normalized,"MESSAGE_STALE",request,actorId);
            }
        }
        String currentPhysical=(String)device.getFirst()[2];
        String physical=currentPhysical,alarmId=null;
        if("FIRE".equals(normalized.eventType())) { alarmId=alarms.fire(tenant,raw.deviceId(),raw.deviceName(),raw.address(),raw.occurredAt()); physical="FIRE"; }
        else if("RECOVERY".equals(normalized.eventType())) { recoverKnownFires(tenant,raw.deviceId(),raw.occurredAt()); physical=alarms.hasActiveFire(tenant,raw.deviceId())?"FIRE":"NORMAL"; }
        BigDecimal battery=normalized.batteryPercent(); boolean batteryPresent=battery!=null; BigDecimal summaryBattery=batteryPresent&&battery.stripTrailingZeros().scale()<=0?battery:null;
        if(knownTime) {
            int updated=jdbc.update("UPDATE devices SET physical_state=?,last_message_at=?,battery_percent=CASE WHEN ? THEN ? ELSE battery_percent END,integration_state='CONNECTED',revision=revision+1 WHERE id=? AND tenant_id=?",physical,raw.occurredAt(),batteryPresent,summaryBattery,raw.deviceId(),tenant);
            if(updated!=1)throw ApiError.missing();
            if(batteryPresent)jdbc.update("INSERT INTO real_device_battery_observations(id,tenant_id,device_id,observed_at,battery_percent,source_kind,recorded_at) VALUES(?,?,?,?,?,'GENERIC_INGEST',?)",UUID.randomUUID().toString(),tenant,raw.deviceId(),raw.occurredAt(),battery,OffsetDateTime.now(clock));
            Long nextSeq=raw.sequence();
            if(nextSeq==null||lastSeq!=null&&nextSeq<lastSeq)nextSeq=lastSeq;
            OffsetDateTime nextOccurred=lastOccurred==null||raw.occurredAt().isAfter(lastOccurred)?raw.occurredAt():lastOccurred;
            if(watermarkMissing(tenant,raw.deviceId()))jdbc.update("INSERT INTO device_message_watermark(device_id,tenant_id,last_sequence,last_occurred_at,updated_at) VALUES(?,?,?,?,?)",raw.deviceId(),tenant,nextSeq,nextOccurred,OffsetDateTime.now(clock));
            else jdbc.update("UPDATE device_message_watermark SET last_sequence=?,last_occurred_at=?,updated_at=? WHERE device_id=? AND tenant_id=?",nextSeq,nextOccurred,OffsetDateTime.now(clock),raw.deviceId(),tenant);
        } else if("FIRE".equals(normalized.eventType())) {
            // Unknown source time is real risk, but it is never used as liveness, battery freshness or watermark time.
            int updated=jdbc.update("UPDATE devices SET physical_state='FIRE',revision=revision+1 WHERE id=? AND tenant_id=?",raw.deviceId(),tenant);
            if(updated!=1)throw ApiError.missing();
        }
        bridge.record(raw.tenant(),raw.deviceId(),raw.id(),protocol,raw.occurredAt(),normalized.eventType(),normalized.sourceEventValue(),normalized.readings(),true);
        if(automatic) facts.publish(fact(raw,protocol,normalized,alarmId));
        var now=OffsetDateTime.now(clock);
        var result=new IngestionModels.ApplyResult(raw.id(),"APPLIED",normalized.eventType(),alarmId,physical,battery,normalized.readings(),raw.sequence(),raw.occurredAt(),"MESSAGE_APPLIED");
        jdbc.update("UPDATE raw_messages SET application_state='APPLIED',applied_at=?,apply_reason=?,apply_error=NULL,revision=revision+1 WHERE id=? AND tenant_id=?",now,reason,raw.id(),tenant);
        outbox(raw,"MESSAGE_APPLIED",result,now); audit.record(actorId,tenant,automatic?"MESSAGE_AUTO_APPLY":"MESSAGE_APPLY",raw.id(),"APPLIED",request); return result;
    }
    private boolean watermarkMissing(String tenant,String deviceId) { return jdbc.queryForObject("SELECT COUNT(*) FROM device_message_watermark WHERE device_id=? AND tenant_id=?",Long.class,deviceId,tenant)==0; }
    /** Close only fires whose own source time is proven and not newer than this recovery occurrence. */
    private void recoverKnownFires(String tenant,String deviceId,OffsetDateTime occurredAt) {
        var active=jdbc.query("SELECT id FROM alarms WHERE tenant_id=? AND device_id=? AND is_simulated=FALSE AND physical_state='FIRE' AND source_time_known=TRUE AND occurred_at<=? AND (last_event_at IS NULL OR last_event_at<=?) ORDER BY occurred_at,id FOR UPDATE",
            (rs,n)->rs.getString(1),tenant,deviceId,occurredAt,occurredAt);
        for(String alarmId:active) alarms.recoverAlarm(tenant,deviceId,alarmId,occurredAt);
    }
    private NormalizedBusinessFact fact(Row raw,String protocol,PublishedMessageNormalizer.Normalized normalized,String alarmId) {
        var document=new LinkedHashMap<String,Object>();
        document.put("event_type",normalized.eventType());
        if(normalized.sourceEventValue()!=null) document.put("source_event_value",normalized.sourceEventValue());
        document.put("readings",normalized.readings());
        document.put("diagnostics",normalized.diagnostics());
        return new NormalizedBusinessFact("GENERIC:"+raw.id(),raw.tenant(),raw.deviceId(),raw.version(),null,protocol,null,null,null,null,
            raw.sourceMessageId(),normalized.eventType(),raw.occurredAt(),raw.receivedAt(),alarmId,idem.json(document),
            Base64.getEncoder().encodeToString(raw.payload()),"application/json",true,true);
    }
    private IngestionModels.ApplyResult reject(Row raw,String error,PublishedMessageNormalizer.Normalized normalized,String reason,String request,String actorId){return finish(raw,reason,"REJECTED",error,normalized,"MESSAGE_REJECTED",request,actorId);}
    private IngestionModels.ApplyResult current(Row raw){return new IngestionModels.ApplyResult(raw.id(),raw.applicationState(),null,null,null,null,List.of(),raw.sequence(),raw.occurredAt(),null);}
    private IngestionModels.ApplyResult finish(Row raw,String reason,String state,String error,PublishedMessageNormalizer.Normalized normalized,String type,String request,String actorId){var now=OffsetDateTime.now(clock);jdbc.update("UPDATE raw_messages SET application_state=?,applied_at=?,apply_reason=?,apply_error=?,revision=revision+1 WHERE id=? AND tenant_id=?",state,now,reason,error,raw.id(),raw.tenant());var result=new IngestionModels.ApplyResult(raw.id(),state,normalized==null?null:normalized.eventType(),null,null,normalized==null?null:normalized.batteryPercent(),normalized==null?List.of():normalized.readings(),raw.sequence(),raw.occurredAt(),type);outbox(raw,type,result,now);audit.record(actorId,raw.tenant(),actorId==null?"MESSAGE_AUTO_APPLY":"MESSAGE_APPLY",raw.id(),state.equals("STALE")?"STALE":"REJECTED",request);return result;}
    private void outbox(Row raw,String type,IngestionModels.ApplyResult result,OffsetDateTime now){jdbc.update("INSERT INTO message_outbox(id,tenant_id,raw_message_id,event_type,payload_json,created_at) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),raw.tenant(),raw.id(),type,idem.json(result),now);}
}
