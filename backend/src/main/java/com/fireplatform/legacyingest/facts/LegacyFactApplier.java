package com.fireplatform.legacyingest.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.legacyingest.LegacyEventSink;
import com.fireplatform.legacyingest.LegacyNormalizedEvent;
import com.fireplatform.alarm.AlarmLifecycle;
import com.fireplatform.identity.CoordinationFence;
import com.fireplatform.integration.BusinessFactPublisher;
import com.fireplatform.integration.NormalizedBusinessFact;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LegacyFactApplier implements LegacyEventSink {
    private record Device(String tenantId, String name, String address, OffsetDateTime lastMessageAt) { }
    private record Identity(String productVersionId,String legacyProductId,String productDigest) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CoordinationFence coordination;
    private final AlarmLifecycle alarms;
    private final com.fireplatform.configuration.AlarmBaseConfigurationEventConsumer configuration;
    private final BusinessFactPublisher facts;
    private final long dedupSeconds;

    public LegacyFactApplier(JdbcTemplate jdbc, ObjectMapper json, CoordinationFence coordination, AlarmLifecycle alarms, com.fireplatform.configuration.AlarmBaseConfigurationEventConsumer configuration, BusinessFactPublisher facts) {
        this(jdbc, json, coordination, alarms, configuration, facts, 180);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LegacyFactApplier(JdbcTemplate jdbc, ObjectMapper json, CoordinationFence coordination, AlarmLifecycle alarms,
            com.fireplatform.configuration.AlarmBaseConfigurationEventConsumer configuration, BusinessFactPublisher facts,
            @org.springframework.beans.factory.annotation.Value("${fire.legacy.alarm-dedup-seconds:180}") long dedupSeconds) {
        this.jdbc = jdbc; this.json = json; this.coordination = coordination; this.alarms = alarms; this.configuration = configuration; this.facts = facts; this.dedupSeconds = Math.max(0,dedupSeconds);
    }


    @Override @Transactional
    public void accept(String messageId, LegacyNormalizedEvent event) {
        apply(messageId, event);
    }

    @Transactional
    public void apply(String messageId, LegacyNormalizedEvent event) {
        // Capture, ownership cutover and fact application use the same lock order.
        coordination.lock();
        var rows = jdbc.query("SELECT tenant_id,name,address,last_message_at FROM devices WHERE id=? AND is_simulated=FALSE FOR UPDATE", (r,n) -> new Device(r.getString(1),r.getString(2),r.getString(3),r.getObject(4,OffsetDateTime.class)), event.deviceId());
        if (rows.isEmpty()) return;
        var device = rows.getFirst();
        if(!java.util.Objects.equals(device.tenantId(),event.tenantId()))return;
        if(!"hikvision_firecloud".equalsIgnoreCase(event.platform())) {
            var rejected=com.fireplatform.legacyingest.LegacyOwnershipBoundary.check(jdbc,event.deviceId(),event.observedAt(),event.events()==null?java.util.List.of():event.events());
            if(rejected!=null)return;
        }
        OffsetDateTime latestHikObservation=device.lastMessageAt();
        OffsetDateTime received = jdbc.queryForObject("SELECT received_at FROM legacy_ingest_messages WHERE id=?", OffsetDateTime.class, messageId);
        boolean hikvision="hikvision_firecloud".equalsIgnoreCase(event.platform());
        boolean knownObservation=event.observedAt()!=null || (event.events()!=null && event.events().stream().anyMatch(item->item.observedAt()!=null));
        boolean outputs=!hikvision && facts!=null && jdbc.queryForObject("SELECT COUNT(*) FROM application_outputs WHERE tenant_id=? AND enabled=TRUE",Long.class,device.tenantId())>0;
        Identity identity=outputs?identity(event.deviceId()):null;
        String rawBase64=outputs?rawPayload(messageId):null;
        if(!event.pendingDecoder()&&event.events()!=null&&!event.events().isEmpty()&&(!hikvision||knownObservation))jdbc.update("UPDATE devices SET integration_state='CONNECTED' WHERE id=?",event.deviceId());
        if (!hikvision) {
            OffsetDateTime sourceObservation=event.observedAt();
            if(event.events()!=null)for(var item:event.events())if(item.observedAt()!=null&&(sourceObservation==null||item.observedAt().isAfter(sourceObservation)))sourceObservation=item.observedAt();
            if(sourceObservation!=null&&(device.lastMessageAt()==null||!sourceObservation.isBefore(device.lastMessageAt()))) {
                if(event.battery()==null)jdbc.update("UPDATE devices SET last_message_at=? WHERE id=?",sourceObservation,event.deviceId());
                else jdbc.update("UPDATE devices SET last_message_at=?,battery_percent=? WHERE id=?",sourceObservation,event.battery(),event.deviceId());
            }
        }
        int eventIndex=0;
        if (event.events() != null) for (var e : event.events()) {
            int index=eventIndex++;
            String kind = e.kind() == null ? "OTHER" : e.kind();
            String params = e.paramsJson() == null ? "{}" : e.paramsJson();
            OffsetDateTime observed = e.observedAt() == null ? event.observedAt() : e.observedAt();
            boolean recovery = kind.equals("RECOVERY");
            String storedKind = recovery ? "ALARM" : kind;
            String businessCode = businessCode(e.method(), params, storedKind);
            boolean timedHikReport=hikvision && switch(kind) {
                case "PARAM","ALARM" -> true;
                // Cloud-generated offline/link faults are not evidence of a detector report.
                case "FAULT" -> switch(businessCode) {
                    case "300003","300004","300005","300116" -> true; // Battery, tamper, smoke chamber, smoke sensor.
                    default -> false;
                };
                default -> "HIKVISION_CURRENT_MONITOR".equals(e.method());
            };
            if (timedHikReport && observed != null && (latestHikObservation == null || observed.isAfter(latestHikObservation))) {
                if(event.battery()==null) jdbc.update("UPDATE devices SET last_message_at=? WHERE id=?",observed,event.deviceId());
                else jdbc.update("UPDATE devices SET last_message_at=?,battery_percent=? WHERE id=?",observed,event.battery(),event.deviceId());
                latestHikObservation=observed;
            }
            String storedMethod = recovery ? (observed == null ? "RECOVERY_UNVERIFIED" : "RECOVERY") : e.method();
            OffsetDateTime eventTime = observed == null ? received : observed;
            String sourceIdentity = hikvision ? "\u0000" + sourceEventId(params) : "";
            String businessKey = sha256(event.deviceId() + "\u0000" + eventTime.toInstant().toEpochMilli() + "\u0000" + businessCode + "\u0000" + storedKind + "\u0000" + storedMethod + sourceIdentity);
            Long exact = jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE tenant_id=? AND device_id=? AND business_key=?", Long.class, device.tenantId(), event.deviceId(), businessKey);
            if (exact != null && exact > 0) continue;
            if (!hikvision && observed!=null && !recovery && dedupSeconds > 0 && businessCode != null && (storedKind.equals("ALARM") || storedKind.equals("FAULT"))) {
                Long window = jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events e WHERE e.tenant_id=? AND e.device_id=? AND e.business_code=? AND e.kind IN ('ALARM','FAULT') AND e.method<>'RECOVERY' AND e.observed_at BETWEEN ? AND ? AND NOT EXISTS(SELECT 1 FROM legacy_ingest_events r WHERE r.tenant_id=e.tenant_id AND r.device_id=e.device_id AND r.method='RECOVERY' AND r.observed_at>=e.observed_at)", Long.class, device.tenantId(), event.deviceId(), businessCode, eventTime.minusSeconds(dedupSeconds), eventTime);
                if (window != null && window > 0) {
                    if (storedKind.equals("ALARM") && fireSemantic(e.method(),params)) applyFireOccurrence(device,event.deviceId(),eventTime,observed);
                    continue;
                }
            }
            String eventId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at,business_key,business_code) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?,?)", eventId, device.tenantId(), messageId, event.deviceId(), storedKind, storedMethod, params, observed, businessKey, businessCode);
            // Preserve the report, but missing source chronology cannot clear fires or publish recovery facts.
            if (recovery && observed == null) continue;
            String factAlarm=null;
            if (!recovery && storedKind.equals("FAULT")) configuration.onLegacyFaultEvidence(device.tenantId(), eventId, event.deviceId(), businessCode, eventTime);
            if (recovery) {
                var active = jdbc.query("SELECT id FROM alarms WHERE tenant_id=? AND device_id=? AND is_simulated=FALSE AND physical_state='FIRE' AND (source_time_known=FALSE OR COALESCE(last_event_at,occurred_at)<=?) ORDER BY COALESCE(last_event_at,occurred_at),id FOR UPDATE", (r,n)->r.getString(1), device.tenantId(), event.deviceId(), observed);
                for (String alarmId : active) alarms.recoverAlarm(device.tenantId(),event.deviceId(),alarmId,observed);
                jdbc.update("UPDATE devices SET physical_state=? WHERE id=?",alarms.hasActiveFire(device.tenantId(),event.deviceId())?"FIRE":"NORMAL",event.deviceId());
            } else if (kind.equals("ALARM") && fireSemantic(e.method(),params)) {
                factAlarm=applyFireOccurrence(device,event.deviceId(),eventTime,observed);
            }
            if(outputs) publishFact(messageId,index,event,e,kind,observed,received,factAlarm,identity,rawBase64,params,recovery);
        }
    }
    private Identity identity(String deviceId) {
        var rows=jdbc.query("SELECT d.product_version_id,pv.legacy_product_id FROM devices d LEFT JOIN product_versions pv ON pv.id=d.product_version_id WHERE d.id=?",(r,n)->new String[]{r.getString(1),r.getString(2)},deviceId);
        if(rows.isEmpty())return null;
        String productVersionId=rows.getFirst()[0],legacyProductId=rows.getFirst()[1],digest=null;
        if(legacyProductId!=null) {
            var values=jdbc.queryForList("SELECT source_sha256 FROM legacy_product_decoders WHERE legacy_product_id=? ORDER BY revision DESC LIMIT 1",String.class,legacyProductId);
            if(!values.isEmpty())digest=values.getFirst();
        }
        return new Identity(productVersionId,legacyProductId,digest);
    }
    private String rawPayload(String messageId) {
        var rows=jdbc.queryForList("SELECT payload_raw FROM legacy_ingest_messages WHERE id=?",String.class,messageId);
        if(rows.isEmpty()||rows.getFirst()==null)return null;
        byte[] bytes=rows.getFirst().getBytes(StandardCharsets.UTF_8);
        return bytes.length==0||bytes.length>1_048_576?null:Base64.getEncoder().encodeToString(bytes);
    }
    private void publishFact(String messageId,int index,LegacyNormalizedEvent event,LegacyNormalizedEvent.LegacyEvent item,String kind,OffsetDateTime observed,OffsetDateTime received,String alarmId,Identity identity,String rawBase64,String params,boolean recovery) {
        if(facts==null)return;
        String factType=recovery?"RECOVERY":kind.equals("ALARM")&&fireSemantic(item.method(),params)?"FIRE":kind;
        String legacyProductId=identity==null?null:identity.legacyProductId();
        facts.publish(new NormalizedBusinessFact("LEGACY:"+messageId+":"+index,event.tenantId(),event.deviceId(),
            identity==null?null:identity.productVersionId(),legacyProductId,event.platform(),
            legacyProductId==null?null:NormalizedBusinessFact.LEGACY_PRODUCT,legacyProductId,event.platform(),
            identity==null?null:identity.productDigest(),messageId,factType,observed,received,alarmId,
            params,rawBase64,"application/json",true,true));
    }
    private String applyFireOccurrence(Device device,String deviceId,OffsetDateTime eventTime,OffsetDateTime sourceObservedAt) {
        jdbc.update("UPDATE devices SET physical_state='FIRE' WHERE id=?",deviceId);
        var open=sourceObservedAt==null?java.util.List.<String>of():jdbc.query("SELECT a.id FROM alarms a WHERE a.tenant_id=? AND a.device_id=? AND a.is_simulated=FALSE AND a.response_state='UNHANDLED' AND a.event_type='FIRE' AND a.physical_state='FIRE' AND a.source_time_known IS NOT FALSE AND a.last_event_at>=? ORDER BY a.last_event_at DESC,a.id DESC FOR UPDATE",
            (r,n)->r.getString(1),device.tenantId(),deviceId,eventTime.minusSeconds(dedupSeconds));
        if(open.isEmpty()) {
            String alarmId=alarms.fire(device.tenantId(),deviceId,device.name(),device.address(),sourceObservedAt);
            jdbc.update("UPDATE alarms SET last_event_at=? WHERE id=?",eventTime,alarmId);
            return alarmId;
        }
        jdbc.update("UPDATE alarms SET last_event_at=CASE WHEN last_event_at<? THEN ? ELSE last_event_at END,occurrence_count=occurrence_count+1 WHERE id=?",eventTime,eventTime,open.getFirst());
        return open.getFirst();
    }
    private String businessCode(String method, String params, String kind) {
        try {
            JsonNode node = json.readTree(params);
            for (String key : new String[]{"deviceStateCode", "code", "eventCode"}) {
                JsonNode value = node.get(key);
                if (value != null && !value.isNull() && !value.asText().isBlank()) return value.asText().trim();
            }
        } catch (Exception ignored) { }
        if (method != null && !method.isBlank()) return method;
        return kind;
    }
    private String sourceEventId(String params) {
        try {
            JsonNode value=json.readTree(params).get("source_event_id");
            return value==null||value.isNull()||value.asText().isBlank()?"":value.asText().trim();
        } catch(Exception ignored) { return ""; }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private boolean fireSemantic(String method, String params) {
        try {
            JsonNode node = json.readTree(params);
            if ("FIRE".equals(node.path("eventType").asText())) return true;
            JsonNode code = node.get("deviceStateCode");
            if(code!=null)return "1001001".equals(code.asText().trim());
            return "thing.event.fire".equalsIgnoreCase(method);
        } catch (Exception ignored) { return false; }
    }
}
