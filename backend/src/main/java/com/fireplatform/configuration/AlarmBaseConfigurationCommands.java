package com.fireplatform.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ExecutionAuthorization;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import com.fireplatform.shared.ReminderScheduling;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlarmBaseConfigurationCommands {
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final AlarmBaseConfigurationQueries queries; private final ReminderScheduling reminders; private final IdempotencyStore idem;
    private final ExecutionAuthorization authorization; private final TenantFence tenants; private final AuditService audit; private final DatabaseTime time;
    public AlarmBaseConfigurationCommands(JdbcTemplate jdbc,ObjectMapper json,AlarmBaseConfigurationQueries queries,ExecutionAuthorization authorization,TenantFence tenants,AuditService audit,DatabaseTime time,ReminderScheduling reminders,IdempotencyStore idem) {
        this.jdbc=jdbc;this.json=json;this.queries=queries;this.authorization=authorization;this.tenants=tenants;this.audit=audit;this.time=time;this.reminders=reminders;this.idem=idem;
    }
    @Transactional public AlarmBaseConfigurationQueries.Configuration save(AuthService.Identity actor,String tenant,String key,long expectedRevision,String reason,Map<String,Object> value,String requestId) {
        authorization.lockRequest(actor,tenant,List.of("alarm-base:write")); tenants.lockEnabled(tenant); idem.validateKey(key);
        if(reason==null||reason.trim().isEmpty()||reason.length()>1000)throw ApiError.bad("必须填写配置原因");
        Map<String,Object> normalized=normalize(value);
        String hash=idem.fingerprint(List.of(tenant,expectedRevision,reason.trim(),normalized));
        var replay=idem.replay(actor.user().id(),"alarm-base-configuration",key,hash,AlarmBaseConfigurationQueries.Configuration.class);
        if(replay!=null)return replay;
        var current=jdbc.query("SELECT revision FROM alarm_base_configurations WHERE tenant_id=? FOR UPDATE",(rs,n)->rs.getLong(1),tenant);
        long revision=current.isEmpty()?0:current.getFirst();
        if(revision!=expectedRevision)throw ApiError.conflict("REVISION_CONFLICT","报警基础配置已变化，请刷新");
        String content;
        try { content=json.writeValueAsString(normalized); } catch(Exception failure) { throw ApiError.bad("报警基础配置无法序列化"); }
        var now=time.now();
        if(revision==0)jdbc.update("INSERT INTO alarm_base_configurations(tenant_id,config_json,revision,updated_by,updated_at) VALUES(?,?,1,?,?)",tenant,content,actor.user().id(),now);
        else if(jdbc.update("UPDATE alarm_base_configurations SET config_json=?,revision=revision+1,updated_by=?,updated_at=? WHERE tenant_id=? AND revision=?",content,actor.user().id(),now,tenant,revision)!=1)throw ApiError.conflict("REVISION_CONFLICT","报警基础配置已变化，请刷新");
        reminders.reconcileTenant(tenant);
        audit.recordReason(actor.user().id(),tenant,"ALARM_BASE_CONFIG_UPDATED",tenant,"SUCCESS",requestId,reason.trim());
        var result=queries.configuration(tenant);idem.save(actor.user().id(),"alarm-base-configuration",key,hash,result);return result;
    }
    private Map<String,Object> normalize(Map<String,Object> input) {
        if(input==null||!input.keySet().equals(Set.of("subscriptions","pushRules","voiceBroadcast","unitService","silenceIntervals")))throw ApiError.bad("报警基础配置结构无效");
        Map<String,Object> subscriptions=map(input,"subscriptions"),push=map(input,"pushRules"),voice=map(input,"voiceBroadcast"),service=map(input,"unitService"),silence=map(input,"silenceIntervals");
        for(String event:List.of("FIRE","FAULT")) { Map<String,Object> eventRules=map(subscriptions,event); for(var entry:eventRules.entrySet()) { text(entry.getKey(),64); strings(entry.getValue(),128); } }
        if(!push.keySet().equals(Set.of("periodHours")))throw ApiError.bad("周期推送规则字段无效");
        integer(push.get("periodHours"),1,720,"未恢复报警提醒周期");
        bool(voice,"alarmEnabled"); bool(voice,"faultEnabled"); bool(voice,"continuous"); strings(voice.get("alarmTypes"),64); strings(voice.get("faultTypes"),64);
        for(String key:List.of("alarmFields","faultFields")) { strings(voice.get(key),32); for(Object field:(List<?>)voice.get(key))if(!Set.of("zone","position","eventSource","time","eventType").contains(field))throw ApiError.bad("语音播报内容字段无效"); }
        if(!service.keySet().equals(Set.of("receiveAlarm","pushMessage")))throw ApiError.bad("单位通知服务字段无效");
        boolean receive=bool(service,"receiveAlarm"); boolean message=bool(service,"pushMessage");
        if(!receive)message=false;
        if(!silence.keySet().equals(Set.of("alarmMinutes","faultMinutes")))throw ApiError.bad("本地静音策略字段无效");
        int alarm=integer(silence.get("alarmMinutes"),0,1440,"报警静音间隔"),fault=integer(silence.get("faultMinutes"),0,1440,"故障静音间隔");
        Map<String,Object> normalizedSilence=Map.of("alarmMinutes",alarm,"faultMinutes",fault);
        Map<String,Object> normalizedService=Map.of("receiveAlarm",receive,"pushMessage",message);
        Map<String,Object> result=new LinkedHashMap<>();result.put("subscriptions",subscriptions);result.put("pushRules",push);result.put("voiceBroadcast",voice);result.put("unitService",normalizedService);result.put("silenceIntervals",normalizedSilence);return result;
    }
    private static Map<String,Object> map(Map<String,Object> parent,String key){Object value=parent.get(key);if(!(value instanceof Map<?,?> raw))throw ApiError.bad("报警基础配置字段无效："+key);Map<String,Object> result=new LinkedHashMap<>();for(var e:raw.entrySet()){if(!(e.getKey() instanceof String k))throw ApiError.bad("配置键无效");result.put(k,e.getValue());}return result;}
    private static int integer(Object value,int min,int max,String label){if(!(value instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<min||n.intValue()>max)throw ApiError.bad(label+"无效");return n.intValue();}
    private static boolean bool(Map<String,Object> map,String key){Object value=map.get(key);if(!(value instanceof Boolean b))throw ApiError.bad("配置开关无效："+key);return b;}
    private static void strings(Object value,int max){if(!(value instanceof List<?> list)||list.size()>max)throw ApiError.bad("事件/字段列表无效");for(Object item:list)text(item,128);}
    private static void text(Object value,int max){if(!(value instanceof String s)||s.isBlank()||s.length()>max)throw ApiError.bad("配置文本无效");}
}
