package com.fireplatform.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ExecutionAuthorization;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ResourceLinkageCommands {
    private static final List<String> ACTIONS=List.of("start","openGate","closeGate","output","releaser","reset","io-start","io-stop");
    private static final List<String> SOUND_START=List.of("200002","200001","200014","200070","200013","200017","200087","200109");
    public record Rule(String id,String tenantId,String name,String kind,boolean enabled,Map<String,Object> config,long revision,OffsetDateTime updatedAt) { }
    public record Evaluation(String id,String ruleId,String ruleName,String eventType,String outcome,Map<String,Object> detail,OffsetDateTime occurredAt) { }
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final ExecutionAuthorization auth;private final TenantFence tenants;private final AuditService audit;private final DatabaseTime time;private final IdempotencyStore idem;
    private final LinkageControllerPort controllers;private final LinkageVideoPort videos;
    public ResourceLinkageCommands(JdbcTemplate jdbc,ObjectMapper json,ExecutionAuthorization auth,TenantFence tenants,AuditService audit,DatabaseTime time,IdempotencyStore idem,LinkageControllerPort controllers,LinkageVideoPort videos){this.jdbc=jdbc;this.json=json;this.auth=auth;this.tenants=tenants;this.audit=audit;this.time=time;this.idem=idem;this.controllers=controllers;this.videos=videos;}
    public List<Rule> list(String tenant){var rows=jdbc.query("SELECT * FROM resource_linkage_rules WHERE tenant_id=? AND deleted=FALSE ORDER BY rule_kind,name,id",(rs,n)->row(rs),tenant);Map<String,Rule> result=new LinkedHashMap<>();for(Rule rule:rows)result.put(rule.kind(),rule);result.putIfAbsent("DEFAULT_VIDEO",defaultRule(tenant,"DEFAULT_VIDEO"));result.putIfAbsent("DEFAULT_SOUND_LIGHT",defaultRule(tenant,"DEFAULT_SOUND_LIGHT"));var ordered=new ArrayList<Rule>();ordered.add(result.remove("DEFAULT_VIDEO"));ordered.add(result.remove("DEFAULT_SOUND_LIGHT"));ordered.addAll(rows.stream().filter(r->"CUSTOM".equals(r.kind())).toList());return ordered;}
    @Transactional public Rule saveDefault(AuthService.Identity actor,String tenant,String kind,boolean enabled,long expectedRevision,Map<String,Object> config,String reason,String requestId,String key){auth.lockRequest(actor,tenant,List.of("resource-linkage:write"));tenants.lockEnabled(tenant);idem.validateKey(key);String hash=idem.fingerprint(Arrays.asList(tenant,kind,enabled,expectedRevision,config,reason));var replay=idem.replay(actor.user().id(),"resource-linkage-default",key,hash,Rule.class);if(replay!=null){return replay;}if(!List.of("DEFAULT_VIDEO","DEFAULT_SOUND_LIGHT").contains(kind))throw ApiError.bad("默认联动类型无效");validateDefault(kind,config);String name="DEFAULT_VIDEO".equals(kind)?"默认视频资源联动":"声光默认联动";var old=jdbc.query("SELECT revision FROM resource_linkage_rules WHERE tenant_id=? AND name=? FOR UPDATE",(rs,n)->rs.getLong(1),tenant,name);long rev=old.isEmpty()?0:old.getFirst();revision(expectedRevision,rev);String content=encode(config);var now=time.now();Rule saved;if(rev==0){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO resource_linkage_rules(id,tenant_id,name,rule_kind,enabled,rule_json,revision,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,1,?,?,?)",id,tenant,name,kind,enabled,content,actor.user().id(),now,now);saved=read(tenant,id);}else{jdbc.update("UPDATE resource_linkage_rules SET enabled=?,rule_json=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND name=? AND revision=?",enabled,content,now,tenant,name,rev);saved=readByName(tenant,name);}audit.recordReason(actor.user().id(),tenant,"RESOURCE_LINKAGE_DEFAULT_UPDATED",saved.id(),"SUCCESS",requestId,reason);idem.save(actor.user().id(),"resource-linkage-default",key,hash,saved);return saved;}
    @Transactional public Rule create(AuthService.Identity actor,String tenant,String name,Map<String,Object> config,String requestId,String reason,String key){auth.lockRequest(actor,tenant,List.of("resource-linkage:write"));tenants.lockEnabled(tenant);idem.validateKey(key);String hash=idem.fingerprint(Arrays.asList(tenant,name,config,reason));var replay=idem.replay(actor.user().id(),"resource-linkage-create",key,hash,Rule.class);if(replay!=null){return replay;}validateName(name);validateCustom(tenant,config);String id=UUID.randomUUID().toString();var now=time.now();jdbc.update("INSERT INTO resource_linkage_rules(id,tenant_id,name,rule_kind,enabled,rule_json,revision,created_by,created_at,updated_at) VALUES(?,?,?,'CUSTOM',FALSE,?,1,?,?,?)",id,tenant,name.trim(),encode(config),actor.user().id(),now,now);audit.recordReason(actor.user().id(),tenant,"RESOURCE_LINKAGE_CREATED",id,"SUCCESS",requestId,reason);var result=read(tenant,id);idem.save(actor.user().id(),"resource-linkage-create",key,hash,result);return result;}
    @Transactional public Rule update(AuthService.Identity actor,String tenant,String id,long expectedRevision,String name,Map<String,Object> config,String reason,String requestId,String key){auth.lockRequest(actor,tenant,List.of("resource-linkage:write"));tenants.lockEnabled(tenant);idem.validateKey(key);String hash=idem.fingerprint(Arrays.asList(tenant,id,expectedRevision,name,config,reason));var replay=idem.replay(actor.user().id(),"resource-linkage-update",key,hash,Rule.class);if(replay!=null){return replay;}Rule old=read(tenant,id);if(!"CUSTOM".equals(old.kind()))throw ApiError.bad("默认规则需使用对应配置接口");revision(expectedRevision,old.revision());validateName(name);validateCustom(tenant,config);int changed=jdbc.update("UPDATE resource_linkage_rules SET name=?,rule_json=?,revision=revision+1,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",name.trim(),encode(config),time.now(),id,tenant,expectedRevision);if(changed!=1)throw ApiError.conflict("REVISION_CONFLICT","规则已变化，请刷新");audit.recordReason(actor.user().id(),tenant,"RESOURCE_LINKAGE_UPDATED",id,"SUCCESS",requestId,reason);var result=read(tenant,id);idem.save(actor.user().id(),"resource-linkage-update",key,hash,result);return result;}
    @Transactional public Rule setEnabled(AuthService.Identity actor,String tenant,String id,long expectedRevision,boolean enabled,String reason,String requestId,String key){auth.lockRequest(actor,tenant,List.of("resource-linkage:write"));tenants.lockEnabled(tenant);idem.validateKey(key);String hash=idem.fingerprint(Arrays.asList(tenant,id,expectedRevision,enabled,reason));var replay=idem.replay(actor.user().id(),"resource-linkage-enable",key,hash,Rule.class);if(replay!=null){return replay;}Rule old=read(tenant,id);revision(expectedRevision,old.revision());int changed=jdbc.update("UPDATE resource_linkage_rules SET enabled=?,revision=revision+1,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",enabled,time.now(),id,tenant,expectedRevision);if(changed!=1)throw ApiError.conflict("REVISION_CONFLICT","规则已变化，请刷新");audit.recordReason(actor.user().id(),tenant,enabled?"RESOURCE_LINKAGE_ENABLED":"RESOURCE_LINKAGE_DISABLED",id,"SUCCESS",requestId,reason);var result=read(tenant,id);idem.save(actor.user().id(),"resource-linkage-enable",key,hash,result);return result;}
    @Transactional public void delete(AuthService.Identity actor,String tenant,String id,long expectedRevision,String reason,String requestId,String key){auth.lockRequest(actor,tenant,List.of("resource-linkage:write"));tenants.lockEnabled(tenant);idem.validateKey(key);String hash=idem.fingerprint(Arrays.asList(tenant,id,expectedRevision,reason));var replay=idem.replay(actor.user().id(),"resource-linkage-delete",key,hash,Boolean.class);if(replay!=null){return;}Rule old=read(tenant,id);if(!"CUSTOM".equals(old.kind()))throw ApiError.bad("默认规则不可删除");revision(expectedRevision,old.revision());jdbc.update("UPDATE resource_linkage_rules SET deleted=TRUE,enabled=FALSE,revision=revision+1,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",time.now(),id,tenant,expectedRevision);audit.recordReason(actor.user().id(),tenant,"RESOURCE_LINKAGE_DELETED",id,"SUCCESS",requestId,reason);idem.save(actor.user().id(),"resource-linkage-delete",key,hash,true);}
    @Transactional public List<Evaluation> evaluate(AuthService.Identity actor,String tenant,String alarmId,String reason,String requestId,String key){auth.lockRequest(actor,tenant,List.of("resource-linkage:write"));tenants.lockEnabled(tenant);idem.validateKey(key);String hash=idem.fingerprint(Arrays.asList(tenant,alarmId,reason));var replay=idem.replay(actor.user().id(),"resource-linkage-evaluate",key,hash,Evaluation[].class);if(replay!=null){return Arrays.asList(replay);}var alarm=jdbc.queryForList("SELECT a.event_type,a.device_id,a.occurred_at,a.physical_state,d.place_id,d.model FROM alarms a JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id WHERE a.id=? AND a.tenant_id=?",alarmId,tenant);if(alarm.isEmpty())throw ApiError.missing();String event=String.valueOf(alarm.getFirst().get("event_type")),device=String.valueOf(alarm.getFirst().get("device_id"));var output=evaluateForAlarm(tenant,alarmId,device,event,(OffsetDateTime)alarm.getFirst().get("occurred_at"));audit.recordReason(actor.user().id(),tenant,"RESOURCE_LINKAGE_EVALUATED",alarmId,"SUCCESS",requestId,reason);idem.save(actor.user().id(),"resource-linkage-evaluate",key,hash,output);return output;}
    @Transactional(propagation=Propagation.MANDATORY) public List<Evaluation> evaluateForAlarm(String tenant,String alarmId,String deviceId,String eventType,OffsetDateTime at){return evaluateSource(tenant,alarmId,alarmId,deviceId,eventType,at,"ALARM");}
    @Transactional(propagation=Propagation.MANDATORY) public List<Evaluation> evaluateForEvidence(String tenant,String sourceId,String deviceId,String eventType,OffsetDateTime at,String sourceKind){if(!List.of("RAW_FAULT","LEGACY_FAULT","LEGACY_RECOVERY").contains(sourceKind))throw ApiError.bad("联动事实来源无效");if(jdbc.queryForList("SELECT id FROM devices WHERE id=? AND tenant_id=?",String.class,deviceId,tenant).isEmpty())throw ApiError.missing();return evaluateSource(tenant,null,sourceId,deviceId,eventType,at,sourceKind);}
    @Transactional(propagation=Propagation.MANDATORY) public List<Evaluation> evaluateForRecovery(String tenant,String alarmId,String deviceId,OffsetDateTime at){return evaluateForAlarm(tenant,alarmId,deviceId,"RECOVERY",at);}
    private List<Evaluation> evaluateSource(String tenant,String alarmId,String sourceId,String deviceId,String eventType,OffsetDateTime at,String sourceKind){
        var rules=list(tenant).stream().filter(Rule::enabled).toList();
        var output=new ArrayList<Evaluation>();
        for(Rule rule:rules){
            if("CUSTOM".equals(rule.kind())){
                boolean match=matches(rule.config(),tenant,deviceId,eventType);
                if(!match){output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"NOT_MATCHED",Map.of("effect","none","reason","condition did not match"),at));continue;}
                Map<String,Object> targets=map(rule.config().get("targets"));
                List<?> devices=list(targets.get("deviceIds"));
                output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"MATCHED_SOFTWARE_ACTION",Map.of("effect","rule condition matched; no physical effect","target_device_ids",devices),at));
                var controllerIds=optionalControllerIds(targets.get("controllerIds"));
                for(Object actionObject:list(rule.config().get("actions"))){
                    String action=String.valueOf(actionObject);
                    if(controllerIds.isEmpty()){
                        var detail=new LinkedHashMap<String,Object>();
                        detail.put("action",action);detail.put("target_device_ids",devices);detail.put("execution","not attempted");
                        detail.put("blocked_code","NO_CONTROLLER_SELECTED");
                        detail.put("blocked_reason","规则未选择控制器资源；设备目标ID不是继电器标识，不会仅凭设备ID下发动作");
                        output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"UNSUPPORTED_EXTERNAL_ADAPTER",detail,at));
                        continue;
                    }
                    var matchedControllers=controllers.controllersFor(tenant,action,controllerIds);
                    var found=new LinkedHashSet<String>();
                    for(LinkageControllerPort.Target controller:matchedControllers)found.add(controller.id());
                    for(String id:controllerIds)if(!found.contains(id)){
                        var detail=new LinkedHashMap<String,Object>();
                        detail.put("controller_id",id);detail.put("action",action);detail.put("execution","not attempted");
                        detail.put("blocked_code","CONTROLLER_NOT_FOUND_FOR_ACTION");
                        detail.put("blocked_reason","该控制器不存在、已停用，或其关联动作与本次联动动作不一致");
                        output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"UNSUPPORTED_EXTERNAL_ADAPTER",detail,at));
                    }
                    for(LinkageControllerPort.Target controller:matchedControllers)
                        recordEnqueue(output,tenant,alarmId,sourceKind,sourceId,rule,eventType,at,controller,action);
                }
            } else if(defaultRuleMatches(rule,eventType,tenant)){
                if("DEFAULT_VIDEO".equals(rule.kind())){
                    var detail=new LinkedHashMap<String,Object>();
                    detail.put("effect","default video policy matched; no media was fetched");
                    detail.put("configured_actions",rule.config().getOrDefault("actions",List.of()));
                    output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"MATCHED_SOFTWARE_ACTION",detail,at));
                    var associated=videos.associatedForDevice(tenant,deviceId);
                    if(associated.isEmpty()){
                        output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"UNSUPPORTED_EXTERNAL_ADAPTER",Map.of("execution","not attempted","blocked_code","NO_VIDEO_ASSOCIATION","blocked_reason","事件设备没有有效视频资源关联；不会生成预览或播放"),at));
                    } else for(LinkageVideoPort.Video video:associated){
                        var videoDetail=new LinkedHashMap<String,Object>();
                        videoDetail.put("video_resource_id",video.id());videoDetail.put("name",video.name());
                        videoDetail.put("protocol",video.protocol());videoDetail.put("playback",video.playback());
                        videoDetail.put("snapshot",video.snapshot());videoDetail.put("media_ready",video.mediaReady());
                        videoDetail.put("note",video.note());
                        videoDetail.put("effect","media requires an authenticated scoped session; background evaluation does not fetch streams");
                        output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"MATCHED_SOFTWARE_ACTION",videoDetail,at));
                        for(Object actionObject:list(rule.config().get("actions"))){
                            String action=String.valueOf(actionObject);
                            boolean snapshot="SNAPSHOT".equals(action);
                            boolean preview="PREVIEW".equals(action);
                            boolean playback="PLAYBACK".equals(action);
                            if(!snapshot&&!preview&&!playback)continue;
                            if(snapshot){
                                if(!List.of("JPEG","JPEG_VIA_FFMPEG","SNAPSHOT_VIA_GATEWAY").contains(video.snapshot())){
                                    var blocked=new LinkedHashMap<String,Object>();
                                    blocked.put("video_resource_id",video.id());blocked.put("action",action);blocked.put("execution","not attempted");
                                    blocked.put("blocked_code",List.of("FFMPEG_NOT_CONFIGURED","NOT_CONFIGURED").contains(video.snapshot())?"FFMPEG_NOT_CONFIGURED":"CAPTURE_NOT_CONFIGURED");
                                    blocked.put("blocked_reason",video.note());
                                    output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"UNSUPPORTED_EXTERNAL_ADAPTER",blocked,at));
                                    continue;
                                }
                                var job=videos.queueCapture(tenant,video.id(),deviceId,sourceKind,sourceId,rule.id());
                                var queued=new LinkedHashMap<String,Object>();
                                queued.put("video_resource_id",video.id());queued.put("action",action);queued.put("capture_job_id",job.id());
                                queued.put("state",job.state());
                                queued.put("effect","durable snapshot job queued before any media I/O; image is captured by the worker");
                                output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"LOCAL_COMMAND_INTENT",queued,at));
                                continue;
                            }
                            var intent=videos.queueIntent(tenant,video.id(),deviceId,preview?"PREVIEW":"PLAYBACK",sourceKind,sourceId,rule.id());
                            var reference=new LinkedHashMap<String,Object>();
                            reference.put("video_resource_id",video.id());reference.put("action",action);reference.put("view_intent_id",intent.id());
                            reference.put("purpose",intent.purpose());reference.put("state",intent.state());
                            reference.put("effect","viewer must open the authenticated verified player; server did not play media");
                            output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"LOCAL_COMMAND_INTENT",reference,at));
                        }
                    }
                } else {
                    var detail=new LinkedHashMap<String,Object>();
                    detail.put("effect","default sound/light policy matched");
                    detail.put("configured_actions",rule.config().getOrDefault("start",Boolean.FALSE));
                    output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"MATCHED_SOFTWARE_ACTION",detail,at));
                    var actions=defaultSoundActions(rule.config(),eventType);
                    if(actions.isEmpty())
                        output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"UNSUPPORTED_EXTERNAL_ADAPTER",Map.of("execution","not attempted","blocked_code","NO_MATCHED_ACTION","blocked_reason","已匹配的默认策略没有可下发动作"),at));
                    for(String action:actions){
                        var candidates=controllers.enabledControllersOn(tenant,deviceId).stream().filter(item->item.action().equals(action)).toList();
                        if(candidates.isEmpty()){
                            var missing=new LinkedHashMap<String,Object>();
                            missing.put("action",action);missing.put("device_id",deviceId);missing.put("execution","not attempted");
                            missing.put("blocked_code","NO_CONTROLLER_ASSOCIATION");
                            missing.put("blocked_reason","事件设备没有已启用的该动作控制器关联；未下发任何声光或继电器动作");
                            output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"UNSUPPORTED_EXTERNAL_ADAPTER",missing,at));
                        }
                        for(LinkageControllerPort.Target controller:candidates)
                            recordEnqueue(output,tenant,alarmId,sourceKind,sourceId,rule,eventType,at,controller,action);
                    }
                }
            }
        }
        return List.copyOf(output);
    }
    private void recordEnqueue(List<Evaluation> output,String tenant,String alarmId,String sourceKind,String sourceId,Rule rule,String eventType,OffsetDateTime at,LinkageControllerPort.Target controller,String action){
        var result=controllers.enqueue(tenant,controller,action,"联动规则《"+rule.name()+"》匹配 "+eventType,rule.id()+"|"+sourceKind+"|"+sourceId+"|"+eventType);
        if(result.commandId()!=null){
            var detail=new LinkedHashMap<String,Object>();
            detail.put("controller_id",controller.id());detail.put("action",action);detail.put("device_id",result.deviceId());
            detail.put("service_key",result.serviceKey());detail.put("command_id",result.commandId());detail.put("state",result.state());
            detail.put("execution","queued in the durable device-service queue; physical effect is not confirmed");
            output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"LOCAL_COMMAND_INTENT",detail,at));
        } else {
            var detail=new LinkedHashMap<String,Object>();
            detail.put("controller_id",controller.id());detail.put("action",action);detail.put("device_id",result.deviceId());
            detail.put("service_key",result.serviceKey());detail.put("execution","not attempted");
            detail.put("blocked_code",result.blockedCode());detail.put("blocked_reason",result.blockedReason());
            output.add(record(tenant,alarmId,sourceKind,sourceId,rule,eventType,"UNSUPPORTED_EXTERNAL_ADAPTER",detail,at));
        }
    }
    private static List<String> optionalControllerIds(Object value){
        if(value==null)return List.of();
        if(!(value instanceof List<?> items))throw ApiError.bad("控制器目标列表无效");
        var result=new ArrayList<String>();
        for(Object item:items){
            if(item==null)throw ApiError.bad("控制器目标ID无效");
            String id=String.valueOf(item);
            if(id.isBlank()||id.length()>64||!id.equals(id.trim()))throw ApiError.bad("控制器目标ID无效");
            if(!result.contains(id))result.add(id);
        }
        if(result.size()>8)throw ApiError.bad("控制器目标数量超过上限");
        return List.copyOf(result);
    }
    private static List<String> defaultSoundActions(Map<String,Object> config,String event){
        var actions=new ArrayList<String>();
        if(Boolean.TRUE.equals(config.get("start"))&&list(config.get("startEvents")).contains(event)&&!actions.contains("start"))actions.add("start");
        if(Boolean.TRUE.equals(config.get("multiStart"))&&list(config.get("multiStartEvents")).contains(event)&&!actions.contains("start"))actions.add("start");
        boolean reset=Boolean.TRUE.equals(config.get("reset"))&&("RECOVERY".equals(event)?list(config.get("resetEvents")).contains("200040"):list(config.get("resetEvents")).contains(event));
        if(reset&&!actions.contains("reset"))actions.add("reset");
        return List.copyOf(actions);
    }
    public List<Evaluation> outcomes(String tenant,String sourceId){return jdbc.query("SELECT o.id,o.rule_id,r.name,o.event_type,o.outcome,o.detail_json,o.occurred_at FROM resource_linkage_outcomes o JOIN resource_linkage_rules r ON r.id=o.rule_id WHERE o.tenant_id=? AND o.source_id=? ORDER BY o.occurred_at,o.id",(rs,n)->new Evaluation(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),decodeMap(rs.getString(6)),rs.getObject(7,OffsetDateTime.class)),tenant,sourceId);}
    private Evaluation record(String tenant,String alarm,String sourceKind,String sourceId,Rule rule,String event,String outcome,Map<String,Object> detail,OffsetDateTime at){String id=UUID.randomUUID().toString(),content=encode(detail);var when=at==null?time.now():at;jdbc.update("INSERT INTO resource_linkage_outcomes(id,tenant_id,rule_id,alarm_id,source_kind,source_id,event_type,outcome,detail_json,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,tenant,rule.id(),alarm,sourceKind,sourceId,event,outcome,content,when);return new Evaluation(id,rule.id(),rule.name(),event,outcome,detail,when);}
    private boolean matches(Map<String,Object> c,String tenant,String device,String event){if(!list(c.get("eventCodes")).contains(event))return false;Map<String,Object> sources=map(c.get("eventSources"));List<?> ids=list(sources.get("deviceIds"));if(!ids.contains(device))return false;if("TWO".equals(c.get("eventPointMode"))){for(Object id:ids)if(jdbc.queryForList("SELECT id FROM alarms WHERE tenant_id=? AND device_id=? AND event_type='FIRE' AND physical_state='FIRE'",String.class,tenant,id).isEmpty())return false;}Map<String,Object> scope=map(c.get("scope"));List<?> places=list(scope.get("placeIds"));String mode=String.valueOf(scope.get("areaMode"));if(!"ALL".equals(mode)){if(places.isEmpty())return false;String operator="SELECT place_id FROM devices WHERE id=? AND tenant_id=?";Object place=jdbc.queryForObject(operator,Object.class,device,tenant);if(place==null)return false;boolean inScope=false;for(Object selected:places){if("SELECTED".equals(mode))inScope|=selected.equals(place);else inScope|=jdbc.queryForObject("WITH RECURSIVE branch(id) AS (SELECT id FROM places WHERE id=? AND tenant_id=? UNION ALL SELECT p.id FROM places p JOIN branch b ON p.parent_place_id=b.id WHERE p.tenant_id=?) SELECT COUNT(*) FROM branch WHERE id=?",Long.class,selected,tenant,tenant,place)>0;}if(!inScope)return false;}return true;}
    private boolean defaultRuleMatches(Rule rule,String event,String tenant){if("DEFAULT_VIDEO".equals(rule.kind()))return "FIRE".equals(event)&&(Boolean.TRUE.equals(rule.config().get("unit"))||Boolean.TRUE.equals(rule.config().get("nineSmall")))&&!list(rule.config().get("actions")).isEmpty();boolean start=Boolean.TRUE.equals(rule.config().get("start"))&&list(rule.config().get("startEvents")).contains(event);boolean multiple=Boolean.TRUE.equals(rule.config().get("multiStart"))&&list(rule.config().get("multiStartEvents")).contains(event)&&jdbc.queryForObject("SELECT COUNT(DISTINCT device_id) FROM alarms WHERE tenant_id=? AND physical_state='FIRE'",Long.class,tenant)>=2;boolean reset=Boolean.TRUE.equals(rule.config().get("reset"))&&("RECOVERY".equals(event)?list(rule.config().get("resetEvents")).contains("200040"):list(rule.config().get("resetEvents")).contains(event));return start||multiple||reset;}
    private void validateDefault(String kind,Map<String,Object> c){if(c==null)throw ApiError.bad("默认规则参数无效");if("DEFAULT_VIDEO".equals(kind)){for(String key:List.of("unit","nineSmall","samePlace"))if(!(c.get(key) instanceof Boolean))throw ApiError.bad("视频联动选项无效");List<?> actions=list(c.get("actions"));if(actions.isEmpty()||!List.of("SNAPSHOT","PREVIEW","PLAYBACK").containsAll(actions))throw ApiError.bad("视频动作无效");}else{for(String key:List.of("start","reset","multiStart"))if(!(c.get(key) instanceof Boolean))throw ApiError.bad("声光联动开关无效");strings(c.get("startEvents"),List.of("FIRE","200002","200001","200014","200070","200013","200017","200087","200109"));strings(c.get("resetEvents"),List.of("RECOVERY","200040"));strings(c.get("multiStartEvents"),List.of("FIRE","200002","200001","200014","200070","200013","200017","200087","200109"));}}
    private void validateCustom(String tenant,Map<String,Object> c){
        if(c==null)throw ApiError.bad("自定义联动规则结构无效");
        if(!c.keySet().equals(Set.of("scope","eventPointMode","eventSources","eventCodes","targets","actions")))
            throw ApiError.bad("自定义联动规则结构无效");
        Map<String,Object> scope=map(c.get("scope")),sources=map(c.get("eventSources")),targets=map(c.get("targets"));
        if(!Set.of("CURRENT","ANY").contains(scope.get("unitMode")))throw ApiError.bad("本地只允许当前单位或可授权范围规则");
        if(!Set.of("ALL","SELECTED","SUBTREE").contains(scope.get("areaMode")))throw ApiError.bad("区域范围无效");
        List<?> places=list(scope.get("placeIds"));
        for(Object place:places)if(jdbc.queryForList("SELECT id FROM places WHERE id=? AND tenant_id=?",String.class,String.valueOf(place),tenant).isEmpty())throw ApiError.conflict("PLACE_NOT_IN_TENANT","所选区域不属于当前单位");
        if(!Set.of("ONE","TWO").contains(c.get("eventPointMode")))throw ApiError.bad("事件源数量模式无效");
        List<?> sourceIds=list(sources.get("deviceIds"));
        int expected="TWO".equals(c.get("eventPointMode"))?2:1;
        if(sourceIds.size()!=expected)throw ApiError.bad("事件源设备数量与规则模式不一致");
        for(Object id:sourceIds)assertDevice(tenant,String.valueOf(id));
        strings(c.get("eventCodes"),List.of("FIRE","200002","200001","200014","200070","200013","200017","200087","200109","200040"));
        List<?> targetsList=list(targets.get("deviceIds"));
        if(!List.of("ANY_DEVICE","SELECTED_DEVICE").contains(targets.get("resourceScope"))||Objects.equals("SELECTED_DEVICE",targets.get("resourceScope"))&&targetsList.isEmpty())throw ApiError.bad("设备目标范围无效；本地没有独立通道/传感器资源");
        for(Object id:targetsList)assertDevice(tenant,String.valueOf(id));
        List<?> actions=list(c.get("actions"));
        if(actions.isEmpty()||actions.size()>8||!ACTIONS.containsAll(actions))throw ApiError.bad("联动动作无效");
        for(String controllerId:optionalControllerIds(targets.get("controllerIds"))){
            var rows=jdbc.query("SELECT action,state FROM controller_resources WHERE id=? AND tenant_id=?",
                (rs,n)->new String[]{rs.getString(1),rs.getString(2)},controllerId,tenant);
            if(rows.isEmpty())throw ApiError.conflict("CONTROLLER_NOT_IN_TENANT","所选控制器不属于当前单位");
            String[] row=rows.getFirst();
            if(!"ACTIVE".equals(row[1]))throw ApiError.conflict("CONTROLLER_RETIRED","所选控制器已停用");
            if(!actions.contains(row[0]))throw ApiError.conflict("CONTROLLER_ACTION_MISMATCH","所选控制器的关联动作不在规则动作中");
        }
    }
    private void assertDevice(String tenant,String id){if(jdbc.queryForList("SELECT id FROM devices WHERE id=? AND tenant_id=?",String.class,id,tenant).isEmpty())throw ApiError.conflict("DEVICE_NOT_IN_TENANT","设备不属于当前单位");}
    private static void strings(Object value,List<String> allowed){List<?> items=list(value);if(items.size()>128||!allowed.containsAll(items))throw ApiError.bad("事件/动作列表包含不支持的选项");}
    private Rule defaultRule(String tenant,String kind){Map<String,Object> config="DEFAULT_VIDEO".equals(kind)?Map.of("unit",false,"nineSmall",false,"samePlace",true,"actions",List.of("SNAPSHOT","PREVIEW","PLAYBACK")):Map.of("start",false,"reset",false,"multiStart",false,"startEvents",SOUND_START,"resetEvents",List.of("200040"),"multiStartEvents",SOUND_START);return new Rule("default-"+kind.toLowerCase(Locale.ROOT),tenant,"DEFAULT_VIDEO".equals(kind)?"默认视频资源联动":"声光默认联动",kind,false,config,0,null);}
    private Rule row(java.sql.ResultSet rs)throws java.sql.SQLException{return new Rule(rs.getString("id"),rs.getString("tenant_id"),rs.getString("name"),rs.getString("rule_kind"),rs.getBoolean("enabled"),decodeMap(rs.getString("rule_json")),rs.getLong("revision"),rs.getObject("updated_at",OffsetDateTime.class));}
    private Rule read(String tenant,String id){var rows=jdbc.query("SELECT * FROM resource_linkage_rules WHERE id=? AND tenant_id=? AND deleted=FALSE",(rs,n)->row(rs),id,tenant);if(rows.isEmpty())throw ApiError.missing();return rows.getFirst();}
    private Rule readByName(String tenant,String name){var rows=jdbc.query("SELECT * FROM resource_linkage_rules WHERE tenant_id=? AND name=? AND deleted=FALSE",(rs,n)->row(rs),tenant,name);if(rows.isEmpty())throw ApiError.missing();return rows.getFirst();}
    private Map<String,Object> decodeMap(String content){try{return json.readValue(content,json.getTypeFactory().constructMapType(Map.class,String.class,Object.class));}catch(Exception failure){throw new IllegalStateException("Persisted linkage rule is unreadable",failure);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw ApiError.bad("联动规则无法序列化");}}
    private static Map<String,Object> map(Object value){if(!(value instanceof Map<?,?> raw))throw ApiError.bad("联动规则对象无效");Map<String,Object> result=new LinkedHashMap<>();raw.forEach((k,v)->{if(k instanceof String key)result.put(key,v);});return result;}
    private static List<?> list(Object value){if(!(value instanceof List<?> items))throw ApiError.bad("联动规则列表无效");return items;}
    private static void validateName(String name){if(name==null||name.trim().isEmpty()||name.length()>120)throw ApiError.bad("规则名称无效");}
    private static void revision(long expected,long actual){if(expected!=actual)throw ApiError.conflict("REVISION_CONFLICT","规则版本已变化，请刷新");}
}
