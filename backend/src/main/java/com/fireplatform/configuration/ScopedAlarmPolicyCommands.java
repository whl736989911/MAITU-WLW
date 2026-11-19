package com.fireplatform.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.AlarmBaseConfigurationQueries.RuleValue;
import com.fireplatform.configuration.AlarmBaseConfigurationQueries.ScopedPolicyView;
import com.fireplatform.configuration.AlarmBaseConfigurationQueries.Target;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ExecutionAuthorization;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.IdempotencyStore;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ScopedAlarmPolicyCommands {
    private static final Set<String> FIELDS=Set.of("subscriptions","receive_alarm","push_message","push_period_hours",
        "voice_enabled","voice_types","voice_fields","voice_continuous","silence_minutes");
    private static final Set<String> VOICE_FIELDS=Set.of("zone","position","eventSource","time","eventType");

    private record Current(long revision,boolean configured) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AlarmBaseConfigurationQueries queries;
    private final DeviceAccessScope scopes;
    private final ExecutionAuthorization authorization;
    private final TenantFence tenants;
    private final IdempotencyStore idem;
    private final DatabaseTime time;
    private final AuditService audit;

    ScopedAlarmPolicyCommands(JdbcTemplate jdbc,ObjectMapper json,AlarmBaseConfigurationQueries queries,DeviceAccessScope scopes,
            ExecutionAuthorization authorization,TenantFence tenants,IdempotencyStore idem,DatabaseTime time,AuditService audit) {
        this.jdbc=jdbc; this.json=json; this.queries=queries; this.scopes=scopes; this.authorization=authorization;
        this.tenants=tenants; this.idem=idem; this.time=time; this.audit=audit;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ScopedPolicyView view(String actor,Target target) {
        validateTarget(actor,target,false);
        return queries.scopeView(target);
    }

    @Transactional
    public ScopedPolicyView save(AuthService.Identity actor,Target target,String key,long expected,String reason,Map<String,Object> input,String requestId) {
        return mutate(actor,target,key,expected,reason,normalize(input),requestId);
    }

    @Transactional
    public ScopedPolicyView clear(AuthService.Identity actor,Target target,String key,long expected,String reason,String requestId) {
        return mutate(actor,target,key,expected,reason,null,requestId);
    }

    private ScopedPolicyView mutate(AuthService.Identity actor,Target target,String key,long expected,String reason,RuleValue value,String requestId) {
        if (target.scope().equals("UNIT")) throw ApiError.bad("单位规则请使用现有单位报警配置");
        boolean platform=target.scope().equals("PLATFORM");
        var permissions=platform ? List.of("alarm-base:write","alarm-base:read","settings:write","settings:read")
            : List.of("alarm-base:write","alarm-base:read");
        authorization.lockRequest(actor,target.tenantId(),permissions);
        if (!platform) tenants.lockEnabled(target.tenantId());
        String actorId=actor.user().id();
        validateTarget(actorId,target,true);
        idem.validateKey(key);
        String trimmed=reason.trim();
        if (trimmed.isEmpty() || trimmed.indexOf(0)>=0) throw ApiError.bad("必须填写有效的配置原因");
        String operation=value==null ? "alarm-scoped-policy-clear" : "alarm-scoped-policy-set";
        String hash=idem.fingerprint(Arrays.asList(target,expected,trimmed,value));
        var replay=idem.replay(actorId,operation,key,hash,ScopedPolicyView.class);
        if (replay!=null) return replay;
        var rows=jdbc.query("SELECT revision,config_json IS NOT NULL FROM alarm_scoped_policies WHERE event_kind=? AND scope_kind=? AND scope_id=? FOR UPDATE",
            (rs,n)->new Current(rs.getLong(1),rs.getBoolean(2)),target.eventKind(),target.scope(),target.id());
        var current=rows.isEmpty() ? new Current(0,false) : rows.getFirst();
        if (current.revision()!=expected) throw ApiError.conflict("REVISION_CONFLICT","报警规则已更新，请刷新后重试");
        if (value==null && !current.configured()) throw ApiError.conflict("NO_OVERRIDE","当前没有可清除的报警规则");
        String content=value==null ? null : encode(value);
        var now=time.now();
        if (expected==0) {
            jdbc.update("""
                INSERT INTO alarm_scoped_policies(event_kind,scope_kind,scope_id,tenant_id,place_id,device_id,config_json,revision,updated_by,updated_at)
                VALUES (?,?,?,?,?,?,?,1,?,?)
                """,target.eventKind(),target.scope(),target.id(),target.tenantId(),target.scope().equals("PLACE")?target.id():null,
                target.scope().equals("DEVICE")?target.id():null,content,actorId,now);
        } else if (jdbc.update("""
            UPDATE alarm_scoped_policies SET config_json=?,revision=revision+1,updated_by=?,updated_at=?
            WHERE event_kind=? AND scope_kind=? AND scope_id=? AND revision=?
            """,content,actorId,now,target.eventKind(),target.scope(),target.id(),expected)!=1) {
            throw ApiError.conflict("REVISION_CONFLICT","报警规则已更新，请刷新后重试");
        }
        String action="ALARM_"+target.eventKind()+"_POLICY_"+(value==null?"CLEAR":"SET");
        audit.recordReason(actorId,target.tenantId(),action,target.id(),"SUCCESS",requestId,trimmed);
        var result=queries.scopeView(target);
        idem.save(actorId,operation,key,hash,result);
        return result;
    }

    private void validateTarget(String actor,Target target,boolean lock) {
        String tenant=target.tenantId(), id=target.id();
        switch (target.scope()) {
            case "PLATFORM" -> { }
            case "UNIT" -> scopes.requireAll(actor,tenant);
            case "PLACE" -> {
                requireLive("places",tenant,id,lock);
                scopes.requirePlace(actor,tenant,id);
            }
            case "DEVICE" -> {
                requireLive("devices",tenant,id,lock);
                scopes.requireDevice(actor,tenant,id);
            }
            default -> throw ApiError.bad("报警规则作用域无效");
        }
    }

    private void requireLive(String table,String tenant,String id,boolean lock) {
        if (jdbc.queryForList("SELECT id FROM "+table+" WHERE id=? AND tenant_id=? AND archived_at IS NULL"+(lock?" FOR UPDATE":""),
            String.class,id,tenant).isEmpty()) throw ApiError.missing();
    }

    private String encode(RuleValue value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Could not serialize validated alarm policy",failure); }
    }

    private static RuleValue normalize(Map<String,Object> value) {
        if (value==null || !value.keySet().equals(FIELDS)) throw ApiError.bad("报警规则字段无效");
        if (!(value.get("subscriptions") instanceof Map<?,?> raw) || raw.size()>128) throw ApiError.bad("报警订阅无效");
        var subscriptions=new LinkedHashMap<String,List<String>>();
        for (var entry:raw.entrySet()) {
            if (!(entry.getKey() instanceof String type) || type.isBlank() || type.length()>64 || type.indexOf(0)>=0) throw ApiError.bad("报警订阅系统类型无效");
            subscriptions.put(type,strings(entry.getValue(),128));
        }
        boolean receive=bool(value,"receive_alarm"), push=bool(value,"push_message");
        var voiceFields=strings(value.get("voice_fields"),32);
        if (!VOICE_FIELDS.containsAll(voiceFields)) throw ApiError.bad("语音播报内容字段无效");
        return new RuleValue(subscriptions,receive,receive && push,integer(value,"push_period_hours",720),
            bool(value,"voice_enabled"),strings(value.get("voice_types"),128),voiceFields,
            bool(value,"voice_continuous"),integer(value,"silence_minutes",1440));
    }

    private static boolean bool(Map<String,Object> value,String key) {
        if (!(value.get(key) instanceof Boolean flag)) throw ApiError.bad("报警规则开关无效："+key);
        return flag;
    }

    private static int integer(Map<String,Object> value,String key,int max) {
        // The lossless JSON mapper represents untyped integers as BigInteger.
        long number = switch (value.get(key)) {
            case Integer n -> n;
            case Long n -> n;
            case BigInteger n when n.bitLength() < Integer.SIZE -> n.intValue();
            case null, default -> -1;
        };
        if (number < 0 || number > max) throw ApiError.bad("报警规则数值无效："+key);
        return (int)number;
    }

    private static List<String> strings(Object value,int limit) {
        if (!(value instanceof List<?> items) || items.size()>128) throw ApiError.bad("报警规则列表无效");
        var validated=new java.util.ArrayList<String>(items.size());
        for (Object item:items) {
            if (!(item instanceof String text) || text.isBlank() || text.length()>limit || text.indexOf(0)>=0) throw ApiError.bad("报警规则列表内容无效");
            validated.add(text);
        }
        return List.copyOf(validated);
    }
}
