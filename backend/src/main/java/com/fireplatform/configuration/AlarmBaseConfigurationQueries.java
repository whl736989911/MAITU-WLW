package com.fireplatform.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AlarmBaseConfigurationQueries {
    public record Configuration(String tenantId, long revision, Map<String,Object> value, OffsetDateTime updatedAt) { }
    public record DeliveryPolicy(boolean enabled, boolean receiveAlarm, boolean pushMessage, int pushPeriodHours) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public AlarmBaseConfigurationQueries(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public Configuration configuration(String tenant) {
        var rows=jdbc.query("SELECT config_json,revision,updated_at FROM alarm_base_configurations WHERE tenant_id=?",(rs,n)->new Configuration(tenant,rs.getLong("revision"),decode(rs.getString("config_json")),rs.getObject("updated_at",OffsetDateTime.class)),tenant);
        if(rows.isEmpty()) return new Configuration(tenant,0,defaults(),null);
        return rows.getFirst();
    }
    public boolean subscribed(String tenantId,String eventType,String systemType,String eventCode) {
        if(!"FIRE".equals(eventType)&&!"FAULT".equals(eventType))return false;
        var rows=jdbc.queryForList("SELECT config_json FROM alarm_base_configurations WHERE tenant_id=?",tenantId);
        if(rows.isEmpty())return true;
        JsonNode excluded=parse(String.valueOf(rows.getFirst().get("config_json"))).path("subscriptions").path(eventType).path(systemType);
        for(JsonNode item:excluded)if(item.asText().equals(eventCode))return false;
        return true;
    }
    public DeliveryPolicy deliveryPolicy(String tenantId,String eventType,String channel) {
        var rows=jdbc.queryForList("SELECT config_json FROM alarm_base_configurations WHERE tenant_id=?",tenantId);
        if(rows.isEmpty()) return new DeliveryPolicy(true,true,true,0);
        JsonNode root=parse(String.valueOf(rows.getFirst().get("config_json")));
        JsonNode service=root.path("unitService");
        boolean receive=service.path("receiveAlarm").asBoolean(true);
        boolean push=service.path("pushMessage").asBoolean(true);
        int period=root.path("pushRules").path("periodHours").asInt(24);
        boolean channelEnabled=!"SMS".equals(channel) || push;
        return new DeliveryPolicy(receive&&channelEnabled,receive,push,period);
    }
    private Map<String,Object> decode(String value) {
        try { return json.readValue(value,json.getTypeFactory().constructMapType(Map.class,String.class,Object.class)); }
        catch(Exception failure) { throw new IllegalStateException("Persisted alarm configuration is unreadable",failure); }
    }
    public int silenceIntervalMinutes(String tenantId,String eventType) {
        var rows=jdbc.queryForList("SELECT config_json FROM alarm_base_configurations WHERE tenant_id=?",tenantId);
        if(rows.isEmpty()) return 0;
        JsonNode root=parse(String.valueOf(rows.getFirst().get("config_json")));
        return root.path("silenceIntervals").path("FAULT".equals(eventType)?"faultMinutes":"alarmMinutes").asInt(0);
    }
    private JsonNode parse(String value) {
        try { return json.readTree(value); } catch(Exception failure) { throw new IllegalStateException("Persisted alarm configuration is unreadable",failure); }
    }
    public List<AlarmBaseConfigurationEventConsumer.Outcome> outcomes(String tenantId,String sourceId) {
        return jdbc.query("SELECT id,tenant_id,alarm_id,event_type,configuration_revision,outcome_kind,outcome,detail_json,occurred_at FROM alarm_configuration_outcomes WHERE tenant_id=? AND source_id=? ORDER BY occurred_at,id",(rs,n)->new AlarmBaseConfigurationEventConsumer.Outcome(rs.getString("id"),rs.getString("tenant_id"),rs.getString("alarm_id"),rs.getString("event_type"),rs.getLong("configuration_revision"),rs.getString("outcome_kind"),rs.getString("outcome"),parse(rs.getString("detail_json")).toString(),rs.getObject("occurred_at",OffsetDateTime.class)),tenantId,sourceId);
    }
    public static Map<String,Object> defaults() {
        return Map.of("subscriptions",Map.of("FIRE",Map.of(),"FAULT",Map.of()),
            "pushRules",Map.of("periodHours",24),
            "voiceBroadcast",Map.of("alarmEnabled",false,"alarmTypes",java.util.List.of(),"alarmFields",java.util.List.of("zone","position","eventSource","time","eventType"),"faultEnabled",false,"faultTypes",java.util.List.of(),"faultFields",java.util.List.of("zone","position","eventSource","time","eventType"),"continuous",false),
            "unitService",Map.of("receiveAlarm",true,"pushMessage",true),
            "silenceIntervals",Map.of("alarmMinutes",0,"faultMinutes",0));
    }
}
