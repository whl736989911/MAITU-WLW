package com.fireplatform.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.configuration.AlarmBaseConfigurationEventConsumer;
import com.fireplatform.product.ProductLimits;
import com.fireplatform.product.ProductModels;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Bounded evidence bridge for authenticated generic-product facts. It writes the same normalized event/history
 * rows the existing legacy projection already reads (fault pages, handling, maintenance conversion, telemetry
 * history/current/trends, device history, export), with the captured raw message and source platform retained.
 *
 * <p>It never creates a legacy ingest receipt, never fires or recovers a device, never forwards the raw frame and
 * never writes a second alert truth. Fire/recovery lifecycle stays with {@link MessageApplyCommands}; this bridge
 * only records the already-applied source fact. Fault subscription decisions are delegated to the existing
 * configuration consumer so a generic fault becomes exactly one notification path, never a parallel one.
 */
@Component
public class GenericFactBridge {
    private static final List<String> APPLICABLE=List.of("FIRE","FAULT","RECOVERY","HEARTBEAT","PARAM","OTHER");
    public record Evidence(String eventId,String measurementEventId) { }
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final AlarmBaseConfigurationEventConsumer configuration; private final Clock clock; private final long dedupSeconds;
    public GenericFactBridge(JdbcTemplate jdbc,ObjectMapper json,AlarmBaseConfigurationEventConsumer configuration,Clock clock,
            @Value("${fire.legacy.alarm-dedup-seconds:180}") long dedupSeconds) {
        this.jdbc=jdbc; this.json=json; this.configuration=configuration; this.clock=clock; this.dedupSeconds=Math.max(0,dedupSeconds);
    }
    /** Record one applied source fact plus its declared readings; {@code notify=false} keeps history without effects. */
    public Evidence record(String tenant,String deviceId,String rawMessageId,String sourcePlatform,OffsetDateTime observedAt,
            String eventType,String sourceEventValue,List<ProductModels.MeasurementReading> readings,boolean notify) {
        if(tenant==null || deviceId==null || rawMessageId==null || eventType==null || !APPLICABLE.contains(eventType)) return new Evidence(null,null);
        String eventId=null,measurementId=null;
        if("PARAM".equals(eventType)) {
            measurementId=insert(tenant,deviceId,rawMessageId,sourcePlatform,observedAt,"PARAM","GENERIC_MEASUREMENT",measurementParams(readings),"PARAM", "GENERIC_MEASUREMENT",notify);
            return new Evidence(measurementId,measurementId);
        }
        String kind=switch(eventType) { case "FIRE" -> "ALARM"; case "RECOVERY" -> "ALARM"; case "FAULT" -> "FAULT"; case "HEARTBEAT" -> "HEARTBEAT"; default -> "OTHER"; };
        String method=switch(eventType) { case "FIRE" -> "GENERIC_FIRE"; case "RECOVERY" -> "RECOVERY"; case "FAULT" -> "GENERIC_FAULT"; case "HEARTBEAT" -> "GENERIC_HEARTBEAT"; default -> "GENERIC_EVENT"; };
        String businessCode=businessCode(eventType,sourceEventValue);
        if("FAULT".equals(eventType) && notify && !recentFault(tenant,deviceId,businessCode,observedAt))
            eventId=insert(tenant,deviceId,rawMessageId,sourcePlatform,observedAt,kind,method,eventParams(eventType,sourceEventValue,businessCode),businessCode,"EVENT",notify);
        else if(!"FAULT".equals(eventType) || !notify)
            eventId=insert(tenant,deviceId,rawMessageId,sourcePlatform,observedAt,kind,method,eventParams(eventType,sourceEventValue,businessCode),businessCode,"EVENT",notify);
        if(readings!=null && !readings.isEmpty())
            measurementId=insert(tenant,deviceId,rawMessageId,sourcePlatform,observedAt,"PARAM","GENERIC_MEASUREMENT",measurementParams(readings),businessCode,"PARAM",notify);
        return new Evidence(eventId,measurementId);
    }
    private boolean recentFault(String tenant,String deviceId,String businessCode,OffsetDateTime observedAt) {
        if(observedAt==null || dedupSeconds==0) return false;
        Long duplicates=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events e WHERE e.tenant_id=? AND e.device_id=? AND e.kind='FAULT' AND e.business_code=? AND e.observed_at BETWEEN ? AND ? AND NOT EXISTS(SELECT 1 FROM legacy_ingest_events r WHERE r.tenant_id=e.tenant_id AND r.device_id=e.device_id AND r.method='RECOVERY' AND r.observed_at>=e.observed_at)",
            Long.class,tenant,deviceId,businessCode,observedAt.minusSeconds(dedupSeconds),observedAt);
        return duplicates!=null && duplicates>0;
    }
    private String insert(String tenant,String deviceId,String rawMessageId,String sourcePlatform,OffsetDateTime observedAt,
            String kind,String method,String paramsJson,String businessCode,String discriminator,boolean notify) {
        String businessKey=sha256("GENERIC:"+rawMessageId+":"+kind+":"+method+":"+discriminator);
        var existing=recent(businessKey,tenant,deviceId);
        if(existing!=null) return existing;
        String id=UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at,business_key,business_code,raw_message_id,source_platform) VALUES (?,?,NULL,?,?,?,?,?,?,?,?,?,?)",
                id,tenant,deviceId,kind,method,paramsJson,observedAt,OffsetDateTime.now(clock),businessKey,bounded(businessCode,64),rawMessageId,bounded(sourcePlatform,64));
        } catch(DuplicateKeyException concurrent) { return recent(businessKey,tenant,deviceId); }
        if(notify && "FAULT".equals(kind)) configuration.onLegacyFaultEvidence(tenant,id,deviceId,businessCode,observedAt);
        return id;
    }
    private String recent(String businessKey,String tenant,String deviceId) {
        var rows=jdbc.queryForList("SELECT id FROM legacy_ingest_events WHERE tenant_id=? AND device_id=? AND business_key=?",String.class,tenant,deviceId,businessKey);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private String eventParams(String eventType,String sourceEventValue,String businessCode) {
        ObjectNode params=json.createObjectNode();
        params.put("source","GENERIC_PRODUCT");
        params.put("event_type",eventType);
        if(sourceEventValue!=null && sourceEventValue.length()<=ProductLimits.MAX_RAW_STRING) params.put("source_event_value",sourceEventValue);
        if("FAULT".equals(eventType)) params.put("code",businessCode);
        return params.toString();
    }
    private String measurementParams(List<ProductModels.MeasurementReading> readings) {
        ObjectNode params=json.createObjectNode();
        if(readings!=null) for(var reading:readings) if(reading!=null && reading.key()!=null && reading.value()!=null) params.set(reading.key(),reading.value());
        return params.toString();
    }
    private static String businessCode(String eventType,String sourceEventValue) {
        String fallback=switch(eventType) { case "FAULT" -> "GENERIC_FAULT"; default -> eventType; };
        if(sourceEventValue==null || sourceEventValue.isBlank()) return fallback;
        return sourceEventValue.length()<=64?sourceEventValue:sha256(sourceEventValue);
    }
    private static String bounded(String value,int maximum) { return value==null?null:value.length()<=maximum?value:sha256(value); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception unavailable) { throw new IllegalStateException(unavailable); }
    }
}
