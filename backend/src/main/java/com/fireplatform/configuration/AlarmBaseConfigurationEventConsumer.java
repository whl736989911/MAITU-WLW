package com.fireplatform.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.ReminderScheduling;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Local software-policy evaluator. It records decisions and composed content; it never calls a physical adapter. */
@Service
public class AlarmBaseConfigurationEventConsumer {
    public record Outcome(String id,String tenantId,String alarmId,String eventType,long configurationRevision,String outcomeKind,String outcome,String detailJson,OffsetDateTime occurredAt) { }
    private final JdbcTemplate jdbc; private final AlarmBaseConfigurationQueries queries; private final ObjectMapper json; private final DatabaseTime time; private final ReminderScheduling reminders; private final ResourceLinkageCommands linkage;
    public AlarmBaseConfigurationEventConsumer(JdbcTemplate jdbc,AlarmBaseConfigurationQueries queries,ObjectMapper json,DatabaseTime time,ReminderScheduling reminders,ResourceLinkageCommands linkage){this.jdbc=jdbc;this.queries=queries;this.json=json;this.time=time;this.reminders=reminders;this.linkage=linkage;}
    @Transactional(propagation=Propagation.MANDATORY)
    public List<Outcome> onAlarmCreated(String tenantId,String alarmId,String deviceId,String eventType,OffsetDateTime occurredAt) {
        if(!"FIRE".equals(eventType))return List.of();
        var config=queries.configuration(tenantId);var value=config.value();var policy=queries.deliveryPolicy(tenantId,eventType,"SMS");var voice=map(value.get("voiceBroadcast"));
        var device=jdbc.queryForList("SELECT d.name,d.model,d.address,p.name AS place_name,a.source_time_known FROM devices d LEFT JOIN places p ON p.id=d.place_id AND p.tenant_id=d.tenant_id LEFT JOIN alarms a ON a.id=? AND a.tenant_id=d.tenant_id AND a.device_id=d.id WHERE d.id=? AND d.tenant_id=?",alarmId,deviceId,tenantId);
        if(device.isEmpty())throw new IllegalStateException("Alarm configuration consumer received a device outside its owner tenant");
        String model=String.valueOf(device.getFirst().get("model"));
        boolean subscribed=queries.subscribed(tenantId,eventType,model,eventType);
        List<Outcome> result=new ArrayList<>();
        result.add(record(tenantId,alarmId,eventType,config.revision(),"SUBSCRIPTION",subscribed?"SUBSCRIBED":"EXCLUDED_BY_SUBSCRIPTION",Map.of("system_type",model,"event_code",eventType),occurredAt));
        result.add(record(tenantId,alarmId,eventType,config.revision(),"NOTIFICATION_GATE",policy.enabled()?"ALLOWED":"SUPPRESSED_BY_UNIT_SERVICE",Map.of("receive_alarm",policy.receiveAlarm(),"push_message",policy.pushMessage()),occurredAt));
        int period=policy.pushPeriodHours();
        result.add(record(tenantId,alarmId,eventType,config.revision(),"PUSH_REMINDER_POLICY",policy.enabled()&&period>0?"SCHEDULED":"NOT_SCHEDULED",Map.of("period_hours",period),occurredAt));
        boolean broadcast=Boolean.TRUE.equals(voice.get("alarmEnabled"))&&list(voice.get("alarmTypes")).stream().map(String::valueOf).anyMatch(type->type.equals(eventType));
        if(broadcast){
            var row=device.getFirst();Map<String,Object> content=new LinkedHashMap<>();content.put("zone",row.get("place_name"));content.put("position",row.get("address"));content.put("eventSource",row.get("name"));content.put("time",Boolean.FALSE.equals(row.get("source_time_known"))?"来源时间未知":occurredAt.toString()+(row.get("source_time_known")==null?"（历史时间来源未核验）":""));content.put("eventType",eventType);content.put("fields",list(voice.get("alarmFields")));
            result.add(record(tenantId,alarmId,eventType,config.revision(),"VOICE_BROADCAST","LOCAL_CONTENT_COMPOSED_NOT_SENT",Map.of("content",content),occurredAt));
        } else result.add(record(tenantId,alarmId,eventType,config.revision(),"VOICE_BROADCAST","NOT_CONFIGURED_FOR_EVENT",Map.of(),occurredAt));
        int silenceMinutes=queries.silenceIntervalMinutes(tenantId,eventType);
        result.add(record(tenantId,alarmId,eventType,config.revision(),"SILENCE_INTERVAL",silenceMinutes==0?"NO_INTERVAL":"BROWSER_AUDIO_MUTE_INTERVAL",Map.of("minutes",silenceMinutes,"scope","local browser audio only"),occurredAt));
        reminders.schedule(tenantId,alarmId,occurredAt);
        linkage.evaluateForAlarm(tenantId,alarmId,deviceId,eventType,occurredAt);
        return List.copyOf(result);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public List<Outcome> onFaultEvidence(String tenantId,String rawMessageId,String deviceId,String eventCode,OffsetDateTime occurredAt) {
        if (jdbc.queryForList("SELECT id FROM raw_messages WHERE id=? AND tenant_id=? AND device_id=?",rawMessageId,tenantId,deviceId).isEmpty()) throw new IllegalStateException("Fault evidence must reference a stored tenant-owned raw message");
        return evaluateFault(tenantId,rawMessageId,deviceId,eventCode,occurredAt,"FAULT_MESSAGE");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public List<Outcome> onLegacyFaultEvidence(String tenantId,String eventId,String deviceId,String eventCode,OffsetDateTime eventTime) {
        if (jdbc.queryForList("SELECT id FROM legacy_ingest_events WHERE id=? AND tenant_id=? AND device_id=? AND kind='FAULT'",eventId,tenantId,deviceId).isEmpty()) throw new IllegalStateException("Fault evidence must reference a stored tenant-owned legacy FAULT event");
        return evaluateFault(tenantId,eventId,deviceId,eventCode,eventTime,"LEGACY_FAULT");
    }
    private List<Outcome> evaluateFault(String tenantId,String sourceId,String deviceId,String eventCode,OffsetDateTime occurredAt,String sourceKind) {
        var config=queries.configuration(tenantId);var value=config.value();var voice=map(value.get("voiceBroadcast"));var policy=queries.deliveryPolicy(tenantId,"FAULT","SMS");
        var device=jdbc.queryForList("SELECT d.name,d.model,d.address,p.name AS place_name FROM devices d LEFT JOIN places p ON p.id=d.place_id AND p.tenant_id=d.tenant_id WHERE d.id=? AND d.tenant_id=?",deviceId,tenantId);
        if(device.isEmpty())throw new IllegalStateException("Fault configuration consumer received a device outside its owner tenant");
        String model=String.valueOf(device.getFirst().get("model"));boolean subscribed=queries.subscribed(tenantId,"FAULT",model,eventCode);List<Outcome> result=new ArrayList<>();
        result.add(record(tenantId,null,sourceId,sourceKind,"FAULT",config.revision(),"SUBSCRIPTION",subscribed?"SUBSCRIBED":"EXCLUDED_BY_SUBSCRIPTION",Map.of("system_type",model,"event_code",eventCode),occurredAt));
        result.add(record(tenantId,null,sourceId,sourceKind,"FAULT",config.revision(),"NOTIFICATION_GATE",policy.enabled()?"ALLOWED":"SUPPRESSED_BY_UNIT_SERVICE",Map.of("receive_alarm",policy.receiveAlarm(),"push_message",policy.pushMessage()),occurredAt));
        boolean broadcast=Boolean.TRUE.equals(voice.get("faultEnabled"))&&list(voice.get("faultTypes")).stream().map(String::valueOf).anyMatch(type->type.equals(eventCode));
        OffsetDateTime sourceTime=broadcast?("LEGACY_FAULT".equals(sourceKind)?jdbc.queryForObject("SELECT observed_at FROM legacy_ingest_events WHERE id=? AND tenant_id=? AND device_id=?",OffsetDateTime.class,sourceId,tenantId,deviceId):occurredAt):null;
        if(broadcast){var row=device.getFirst();Map<String,Object> content=new LinkedHashMap<>();content.put("zone",row.get("place_name"));content.put("position",row.get("address"));content.put("eventSource",row.get("name"));content.put("time",sourceTime==null?"来源时间未知":sourceTime.toString());content.put("eventType",eventCode);content.put("fields",list(voice.get("faultFields")));result.add(record(tenantId,null,sourceId,sourceKind,"FAULT",config.revision(),"VOICE_BROADCAST","LOCAL_CONTENT_COMPOSED_NOT_SENT",Map.of("content",content),occurredAt));}
        else result.add(record(tenantId,null,sourceId,sourceKind,"FAULT",config.revision(),"VOICE_BROADCAST","NOT_CONFIGURED_FOR_EVENT",Map.of(),occurredAt));
        int silenceMinutes=queries.silenceIntervalMinutes(tenantId,"FAULT");result.add(record(tenantId,null,sourceId,sourceKind,"FAULT",config.revision(),"SILENCE_INTERVAL",silenceMinutes==0?"NO_INTERVAL":"BROWSER_AUDIO_MUTE_INTERVAL",Map.of("minutes",silenceMinutes,"scope","local browser audio only"),occurredAt));
        linkage.evaluateForEvidence(tenantId,sourceId,deviceId,eventCode,occurredAt,"FAULT_MESSAGE".equals(sourceKind)?"RAW_FAULT":"LEGACY_FAULT");
        return List.copyOf(result);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public List<ResourceLinkageCommands.Evaluation> onLegacyRecoveryEvidence(String tenantId,String eventId,String deviceId,String eventCode,OffsetDateTime eventTime) {
        if (jdbc.queryForList("SELECT id FROM legacy_ingest_events WHERE id=? AND tenant_id=? AND device_id=? AND method='RECOVERY'",eventId,tenantId,deviceId).isEmpty()) throw new IllegalStateException("Recovery evidence must reference a stored tenant-owned legacy RECOVERY event");
        return linkage.evaluateForEvidence(tenantId,eventId,deviceId,eventCode,eventTime,"LEGACY_RECOVERY");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public List<ResourceLinkageCommands.Evaluation> onAlarmRecovered(String tenantId,String alarmId,String deviceId,OffsetDateTime eventTime) {
        return linkage.evaluateForRecovery(tenantId,alarmId,deviceId,eventTime);
    }
    private Outcome record(String tenant,String alarm,String event,long revision,String kind,String outcome,Map<String,Object> detail,OffsetDateTime at) {
        return record(tenant,alarm,alarm,"ALARM",event,revision,kind,outcome,detail,at);
    }
    private Outcome record(String tenant,String alarm,String sourceId,String sourceKind,String event,long revision,String kind,String outcome,Map<String,Object> detail,OffsetDateTime at) {
        String id=UUID.randomUUID().toString(),payload;
        try{payload=json.writeValueAsString(detail);}catch(Exception failure){throw new IllegalStateException("Could not serialize configuration outcome",failure);}
        String rawMessage="FAULT_MESSAGE".equals(sourceKind)?sourceId:null;
        jdbc.update("INSERT INTO alarm_configuration_outcomes(id,tenant_id,alarm_id,raw_message_id,source_kind,source_id,event_type,configuration_revision,outcome_kind,outcome,detail_json,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",id,tenant,alarm,rawMessage,sourceKind,sourceId,event,revision,kind,outcome,payload,at==null?time.now():at);
        return new Outcome(id,tenant,alarm,event,revision,kind,outcome,payload,at==null?time.now():at);
    }
    private static Map<String,Object> map(Object value){if(!(value instanceof Map<?,?> raw))return Map.of();Map<String,Object> result=new LinkedHashMap<>();raw.forEach((k,v)->{if(k instanceof String key)result.put(key,v);});return result;}
    private static List<?> list(Object value){return value instanceof List<?> items?items:List.of();}
}
