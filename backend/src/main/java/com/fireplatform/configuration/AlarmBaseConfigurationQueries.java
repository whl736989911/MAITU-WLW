package com.fireplatform.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.ApiError;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AlarmBaseConfigurationQueries {
    public record Configuration(String tenantId, long revision, Map<String,Object> value, OffsetDateTime updatedAt) { }
    public record DeliveryPolicy(boolean enabled, boolean receiveAlarm, boolean pushMessage, int pushPeriodHours) { }
    public record RuleValue(Map<String,List<String>> subscriptions,boolean receiveAlarm,boolean pushMessage,
        int pushPeriodHours,boolean voiceEnabled,List<String> voiceTypes,List<String> voiceFields,
        boolean voiceContinuous,int silenceMinutes) { }
    public record EffectivePolicy(String sourceScope,String sourceId,long sourceRevision,RuleValue value,
        boolean unitReceiveAlarm,boolean unitPushMessage) {
        public boolean subscribed(String systemType,String eventCode) {
            if (systemType==null || eventCode==null) return true;
            var excluded=value.subscriptions().get(systemType);
            return excluded==null || !excluded.contains(eventCode);
        }
        public DeliveryPolicy deliveryPolicy(String channel) {
            boolean receive=unitReceiveAlarm && value.receiveAlarm();
            boolean push=unitPushMessage && value.pushMessage();
            return new DeliveryPolicy(receive && (!"SMS".equals(channel) || push),receive,push,value.pushPeriodHours());
        }
    }
    record Target(String tenantId,String scope,String id,String eventKind) { }
    record ScopedPolicyView(String eventKind,String scope,String scopeId,String tenantId,long revision,RuleValue value,EffectivePolicy effective) { }
    private record Stored(long revision,String content) { }
    private static final RuleValue DEFAULT_RULE=new RuleValue(Map.of(),true,true,0,false,List.of(),
        List.of("zone","position","eventSource","time","eventType"),false,0);
    private static final String MATCH = """
        WITH RECURSIVE target(tenant_id,scope_kind,scope_id,event_kind) AS (
            SELECT CAST(? AS VARCHAR(64)),CAST(? AS VARCHAR(8)),CAST(? AS VARCHAR(64)),CAST(? AS VARCHAR(8))
        ), ancestors(id,tenant_id,parent_place_id,distance) AS (
            SELECT p.id,p.tenant_id,p.parent_place_id,0
            FROM target t
            LEFT JOIN devices d ON t.scope_kind='DEVICE' AND d.id=t.scope_id AND d.tenant_id=t.tenant_id
            JOIN places p ON p.tenant_id=t.tenant_id AND p.archived_at IS NULL
                AND p.id=CASE WHEN t.scope_kind='PLACE' THEN t.scope_id ELSE d.place_id END
            UNION ALL
            SELECT p.id,p.tenant_id,p.parent_place_id,a.distance+1
            FROM ancestors a JOIN places p ON p.tenant_id=a.tenant_id AND p.id=a.parent_place_id
            WHERE p.archived_at IS NULL AND a.distance<63
        ), candidates(scope_kind,scope_id,revision,config_json,precedence) AS (
            SELECT r.scope_kind,r.scope_id,r.revision,r.config_json,0
            FROM alarm_scoped_policies r JOIN target t ON r.scope_kind='DEVICE' AND t.scope_kind='DEVICE'
                AND r.device_id=t.scope_id AND r.tenant_id=t.tenant_id AND r.event_kind=t.event_kind
            WHERE r.config_json IS NOT NULL
            UNION ALL
            SELECT r.scope_kind,r.scope_id,r.revision,r.config_json,a.distance+1
            FROM ancestors a JOIN alarm_scoped_policies r ON r.place_id=a.id AND r.tenant_id=a.tenant_id
            JOIN target t ON r.event_kind=t.event_kind
            WHERE r.config_json IS NOT NULL
            UNION ALL
            SELECT r.scope_kind,r.scope_id,r.revision,r.config_json,101
            FROM alarm_scoped_policies r JOIN target t ON r.event_kind=t.event_kind
            WHERE r.scope_kind='PLATFORM' AND r.scope_id='platform' AND r.config_json IS NOT NULL
        )
        SELECT c.scope_kind,c.scope_id,c.revision,c.config_json,u.config_json AS unit_config,u.revision AS unit_revision,
            CASE WHEN t.scope_kind='DEVICE' AND NOT EXISTS (
                SELECT 1 FROM devices d WHERE d.tenant_id=t.tenant_id AND d.id=t.scope_id
            ) THEN FALSE ELSE TRUE END AS target_exists
        FROM target t LEFT JOIN alarm_base_configurations u ON u.tenant_id=t.tenant_id
        LEFT JOIN candidates c ON TRUE
        ORDER BY c.precedence LIMIT 1
        """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public AlarmBaseConfigurationQueries(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public Configuration configuration(String tenant) {
        var rows=jdbc.query("SELECT config_json,revision,updated_at FROM alarm_base_configurations WHERE tenant_id=?",(rs,n)->new Configuration(tenant,rs.getLong("revision"),decode(rs.getString("config_json")),rs.getObject("updated_at",OffsetDateTime.class)),tenant);
        if(rows.isEmpty()) return new Configuration(tenant,0,defaults(),null);
        return rows.getFirst();
    }
    /** Resolve a whole FIRE or FAULT rule for the device's actual unit and current place. */
    public EffectivePolicy effectiveForDevice(String tenantId,String deviceId,String eventKind) {
        if (!"FIRE".equals(eventKind) && !"FAULT".equals(eventKind)) throw new IllegalArgumentException("Unsupported alarm event kind");
        return effective(new Target(tenantId,"DEVICE",deviceId,eventKind));
    }

    ScopedPolicyView scopeView(Target target) {
        boolean unit=target.scope().equals("UNIT");
        var own=unit
            ? jdbc.query("SELECT revision,config_json FROM alarm_base_configurations WHERE tenant_id=?",
                (rs,n)->new Stored(rs.getLong(1),rs.getString(2)),target.tenantId())
            : jdbc.query("SELECT revision,config_json FROM alarm_scoped_policies WHERE event_kind=? AND scope_kind=? AND scope_id=?",
                (rs,n)->new Stored(rs.getLong(1),rs.getString(2)),target.eventKind(),target.scope(),target.id());
        var stored=own.isEmpty() ? null : own.getFirst();
        RuleValue value=null;
        if (stored!=null && stored.content()!=null) {
            value=unit ? unitRule(parse(stored.content()),target.eventKind()) : parseScoped(stored.content());
        }
        return new ScopedPolicyView(target.eventKind(),target.scope(),target.id(),target.tenantId(),
            stored==null?0:stored.revision(),value,effective(target));
    }

    private EffectivePolicy effective(Target target) {
        return jdbc.queryForObject(MATCH,(rs,n)->{
            if (!rs.getBoolean("target_exists")) throw ApiError.missing();
            String unitConfig=rs.getString("unit_config");
            JsonNode unit=unitConfig==null ? null : parse(unitConfig);
            JsonNode gate=unit==null ? null : unit.path("unitService");
            boolean unitReceive=gate==null || gate.path("receiveAlarm").asBoolean(true);
            boolean unitPush=gate==null || gate.path("pushMessage").asBoolean(true);
            String scope=rs.getString("scope_kind");
            if (unit!=null && (scope==null || scope.equals("PLATFORM"))) {
                return new EffectivePolicy("UNIT",target.tenantId(),rs.getLong("unit_revision"),
                    unitRule(unit,target.eventKind()),unitReceive,unitPush);
            }
            if (scope==null) return new EffectivePolicy("PLATFORM",null,0,DEFAULT_RULE,unitReceive,unitPush);
            return new EffectivePolicy(scope,rs.getString("scope_id"),rs.getLong("revision"),
                parseScoped(rs.getString("config_json")),unitReceive,unitPush);
        },target.tenantId(),target.scope(),target.id(),target.eventKind());
    }

    private RuleValue parseScoped(String content) {
        try { return json.readValue(content,RuleValue.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Persisted scoped alarm policy is unreadable",failure);
        }
    }

    private RuleValue unitRule(JsonNode unit,String eventKind) {
        var exclusions=new LinkedHashMap<String,List<String>>();
        unit.path("subscriptions").path(eventKind).fields().forEachRemaining(e->exclusions.put(e.getKey(),strings(e.getValue())));
        String prefix=eventKind.equals("FAULT") ? "fault" : "alarm";
        var voice=unit.path("voiceBroadcast");
        var gate=unit.path("unitService");
        return new RuleValue(exclusions,gate.path("receiveAlarm").asBoolean(true),gate.path("pushMessage").asBoolean(true),
            unit.path("pushRules").path("periodHours").asInt(24),voice.path(prefix+"Enabled").asBoolean(false),
            strings(voice.path(prefix+"Types")),strings(voice.path(prefix+"Fields")),voice.path("continuous").asBoolean(false),
            unit.path("silenceIntervals").path(prefix+"Minutes").asInt(0));
    }

    private static List<String> strings(JsonNode array) {
        var strings=new java.util.ArrayList<String>();
        for (JsonNode item:array) strings.add(item.asText());
        return strings;
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
