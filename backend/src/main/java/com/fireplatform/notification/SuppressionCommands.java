package com.fireplatform.notification;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ExecutionAuthorization;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Notification exits only; never rewrites physical, connectivity or handling facts. */
@Service
public class SuppressionCommands {
    public static final int MAX_DAYS = 90;
    /** Preserve the original timestamp offsets when re-reading saved mutation receipts. */
    public record Suppression(String id,String tenantId,String kind,String scope,boolean fireConfirmed,String reason,
                              @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime startsAt,
                              @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime endsAt,
                              String status,
                              @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime createdAt,
                              @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime cancelledAt,
                              String cancelReason,long revision,boolean enabled,boolean deleted,String effectiveState) { }
    public record ChannelTarget(String channelId,String deviceId,String channelKey,String channelName,String channelType,String deviceName) { }
    public record HistoryEvent(String id,String action,String actorId,String outcome,String requestId,OffsetDateTime occurredAt,String reason,String snapshotJson) { }
    public record Create(String scope,List<String> deviceIds,List<String> channelIds,boolean fireConfirmed,String reason,OffsetDateTime startsAt,OffsetDateTime endsAt) { }
    private final JdbcTemplate jdbc;
    private final DatabaseTime time;
    private final ExecutionAuthorization auth;
    private final TenantFence tenants;
    private final ScopeFilter tree;
    private final DeviceAccessScope deviceScope;
    private final AuditService audit;
    private final IdempotencyStore idem;
    public SuppressionCommands(JdbcTemplate jdbc,DatabaseTime time,ExecutionAuthorization auth,TenantFence tenants,ScopeFilter tree,DeviceAccessScope deviceScope,AuditService audit,IdempotencyStore idem) {
        this.jdbc=jdbc;this.time=time;this.auth=auth;this.tenants=tenants;this.tree=tree;this.deviceScope=deviceScope;this.audit=audit;this.idem=idem;
    }
    private String stateSql(String prefix) {
        return "CASE WHEN "+prefix+"deleted=TRUE THEN 'DELETED' WHEN "+prefix+"status='CANCELLED' THEN 'CANCELLED' WHEN "+prefix+"ends_at<="+time.expression()+" THEN 'EXPIRED' WHEN "+prefix+"enabled=FALSE THEN 'DISABLED' WHEN "+prefix+"starts_at>"+time.expression()+" THEN 'SCHEDULED' ELSE 'ACTIVE' END";
    }
    private String columns() {
        return "id,tenant_id,kind,CASE WHEN resource_kind='SENSOR' THEN 'SENSORS' ELSE scope END AS resource_scope,fire_confirmed,reason,starts_at,ends_at,status,created_at,cancelled_at,cancel_reason,revision,enabled,deleted,"+stateSql("")+" AS effective_state";
    }
    private Suppression row(java.sql.ResultSet rs,int n)throws java.sql.SQLException {
        return new Suppression(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBoolean(5),rs.getString(6),rs.getObject(7,OffsetDateTime.class),rs.getObject(8,OffsetDateTime.class),rs.getString(9),rs.getObject(10,OffsetDateTime.class),rs.getObject(11,OffsetDateTime.class),rs.getString(12),rs.getLong(13),rs.getBoolean(14),rs.getBoolean(15),rs.getString(16));
    }
    private void authorize(AuthService.Identity actor,String tenant) {
        auth.lockRequest(actor,tenant,List.of("suppressions:write"));tenants.lockEnabled(tenant);
    }
    private static String reason(String value) {
        if(value==null||value.isBlank()||value.length()>1000)throw ApiError.bad("必须说明操作原因（≤1000字）");
        return value.trim();
    }
    private void dates(OffsetDateTime starts,OffsetDateTime ends) {
        if(starts==null||ends==null||!ends.isAfter(starts))throw ApiError.bad("必须给出晚于开始时间的期限");
        if(ends.isAfter(starts.plusDays(MAX_DAYS)))throw ApiError.bad("屏蔽期限不得超过"+MAX_DAYS+"天");
    }
    private void visibleDevice(String actor,String tenant,String id) {
        var visible=deviceScope.predicate(actor,"d.tenant_id","d.id");
        var args=new ArrayList<Object>(List.of(id,tenant));args.addAll(visible.bindings());
        if(jdbc.queryForList("SELECT d.id FROM devices d WHERE d.id=? AND d.tenant_id=?"+visible.and(),String.class,args.toArray()).isEmpty())throw ApiError.conflict("DEVICE_NOT_IN_TENANT","设备不属于当前可见单位范围");
    }
    private Suppression visible(String actor,String tenant,String id) {
        Suppression result=read(tenant,id);
        var access=deviceScope.predicate(actor,"d.tenant_id","d.id");
        var args=new ArrayList<Object>(List.of(id));args.addAll(access.bindings());
        if(!result.scope().equals("TENANT")&&!jdbc.queryForList("SELECT d.id FROM devices d JOIN device_suppression_members m ON m.device_id=d.id AND m.tenant_id=d.tenant_id WHERE m.suppression_id=? AND NOT ("+access.sql()+")",String.class,args.toArray()).isEmpty())throw ApiError.missing();
        return result;
    }
    private void active(Suppression old,long revision) {
        if(old.revision()!=revision)throw ApiError.conflict("REVISION_CONFLICT","屏蔽版本已变化，请刷新");
        if(old.deleted()||!old.status().equals("ACTIVE")||!old.endsAt().isAfter(time.now()))throw ApiError.conflict("SUPPRESSION_NOT_ACTIVE","屏蔽已取消、到期或删除");
    }
    private void change(AuthService.Identity actor,String tenant,String id,String action,String why,String request) {
        var now=time.now();
        audit.recordReason(actor.user().id(),tenant,action,id,"SUCCESS",request,why);
        String snapshot=idem.json(Map.of("record",read(tenant,id),"channels",targets(tenant,id)));
        jdbc.update("INSERT INTO suppression_changes(id,suppression_id,tenant_id,action,actor_id,request_id,reason,snapshot_json,occurred_at) VALUES (?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,tenant,action,actor.user().id(),request,why,snapshot,now);
    }
    @Transactional public Suppression create(AuthService.Identity actor,String tenant,String key,Create input,String requestId) {
        authorize(actor,tenant);idem.validateKey(key);
        String why=reason(input.reason()),hash=idem.fingerprint(Arrays.asList(tenant,input));
        var devices=input.deviceIds()==null?List.<String>of():input.deviceIds().stream().distinct().toList();
        var channels=input.channelIds()==null?List.<String>of():input.channelIds().stream().distinct().toList();
        var channelTargets=new ArrayList<ChannelTarget>();
        if(!List.of("TENANT","DEVICES","SENSORS").contains(input.scope()))throw ApiError.bad("屏蔽范围无效");
        if(input.scope().equals("TENANT")&&(!devices.isEmpty()||!channels.isEmpty())||input.scope().equals("DEVICES")&&(devices.isEmpty()||!channels.isEmpty())||input.scope().equals("SENSORS")&&(channels.isEmpty()||!devices.isEmpty()))throw ApiError.bad("屏蔽目标与资源范围不一致");
        if(devices.size()+channels.size()>Limits.MAX_SCOPE_DEVICES)throw ApiError.bad("屏蔽目标数量超限");
        if(input.scope().equals("TENANT")&&!deviceScope.all(actor.user().id(),tenant))throw ApiError.missing();
        for(String device:devices)visibleDevice(actor.user().id(),tenant,device);
        for(String channel:channels) {
            var found=jdbc.query("SELECT c.id,c.device_id,c.channel_key,c.name,c.channel_type,d.name FROM device_channels c JOIN devices d ON d.id=c.device_id AND d.tenant_id=c.tenant_id WHERE c.id=? AND c.tenant_id=? AND c.enabled=TRUE",(rs,n)->new ChannelTarget(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)),channel,tenant);
            if(found.isEmpty())throw ApiError.missing();
            visibleDevice(actor.user().id(),tenant,found.getFirst().deviceId());channelTargets.add(found.getFirst());
        }
        var replay=idem.replay(actor.user().id(),"suppression-create",key,hash,Suppression.class);if(replay!=null)return replay;
        var now=time.now();var starts=input.startsAt()==null?now:input.startsAt();dates(starts,input.endsAt());
        if(!input.endsAt().isAfter(now))throw ApiError.bad("新屏蔽的结束时间必须晚于当前时间");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO device_suppressions(id,tenant_id,kind,scope,fire_confirmed,reason,starts_at,ends_at,status,created_by,created_at,resource_kind) VALUES(?,?,'NOTIFICATION',?,?,?,?,?,'ACTIVE',?,?,?)",id,tenant,input.scope().equals("SENSORS")?"DEVICES":input.scope(),input.fireConfirmed(),why,starts,input.endsAt(),actor.user().id(),now,input.scope().equals("SENSORS")?"SENSOR":"DEVICE");
        var memberDevices=new LinkedHashSet<>(devices);
        for(var target:channelTargets) {
            memberDevices.add(target.deviceId());
            jdbc.update("INSERT INTO suppression_channels(suppression_id,channel_id,tenant_id,device_id,channel_key,channel_name,channel_type,device_name) VALUES (?,?,?,?,?,?,?,?)",id,target.channelId(),tenant,target.deviceId(),target.channelKey(),target.channelName(),target.channelType(),target.deviceName());
        }
        for(String device:memberDevices)jdbc.update("INSERT INTO device_suppression_members(suppression_id,device_id,tenant_id) VALUES (?,?,?)",id,device,tenant);
        change(actor,tenant,id,"SUPPRESSION_CREATED",why,requestId);
        var result=read(tenant,id);idem.save(actor.user().id(),"suppression-create",key,hash,result);return result;
    }
    @Transactional public Suppression cancel(AuthService.Identity actor,String tenant,String key,String id,long expectedRevision,String why,String requestId) {
        return mutate(actor,tenant,key,id,expectedRevision,"CANCEL",why,null,null,null,requestId);
    }
    @Transactional public Suppression edit(AuthService.Identity actor,String tenant,String key,String id,long expectedRevision,OffsetDateTime starts,OffsetDateTime ends,String why,String requestId) {
        return mutate(actor,tenant,key,id,expectedRevision,"EDIT",why,starts,ends,null,requestId);
    }
    @Transactional public Suppression enable(AuthService.Identity actor,String tenant,String key,String id,long expectedRevision,boolean enabled,String why,String requestId) {
        return mutate(actor,tenant,key,id,expectedRevision,enabled?"ENABLE":"DISABLE",why,null,null,enabled,requestId);
    }
    @Transactional public Suppression delete(AuthService.Identity actor,String tenant,String key,String id,long expectedRevision,String why,String requestId) {
        return mutate(actor,tenant,key,id,expectedRevision,"DELETE",why,null,null,null,requestId);
    }
    private Suppression mutate(AuthService.Identity actor,String tenant,String key,String id,long revision,String operation,String note,OffsetDateTime starts,OffsetDateTime ends,Boolean enabled,String request) {
        authorize(actor,tenant);idem.validateKey(key);String why=reason(note);
        if(jdbc.queryForList("SELECT id FROM device_suppressions WHERE id=? AND tenant_id=? FOR UPDATE",String.class,id,tenant).isEmpty())throw ApiError.missing();
        var old=visible(actor.user().id(),tenant,id);
        if(old.scope().equals("TENANT")&&!deviceScope.all(actor.user().id(),tenant))throw ApiError.missing();
        String hash=idem.fingerprint(Arrays.asList(tenant,id,revision,operation,starts,ends,enabled,why)),op="suppression-"+operation.toLowerCase(Locale.ROOT);
        var replay=idem.replay(actor.user().id(),op,key,hash,Suppression.class);if(replay!=null)return replay;
        if(operation.equals("DELETE")) {
            if(old.revision()!=revision)throw ApiError.conflict("REVISION_CONFLICT","屏蔽版本已变化，请刷新");
            if(old.deleted()||old.status().equals("ACTIVE")&&old.endsAt().isAfter(time.now()))throw ApiError.conflict("SUPPRESSION_NOT_EXPIRED","仅失效或已取消的屏蔽允许删除");
            jdbc.update("UPDATE device_suppressions SET deleted=TRUE,enabled=FALSE,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=?",id,tenant,revision);
        }else{
            active(old,revision);
            switch(operation) {
                case "EDIT" -> {dates(starts,ends);if(!ends.isAfter(time.now()))throw ApiError.bad("结束时间必须晚于当前时间");jdbc.update("UPDATE device_suppressions SET starts_at=?,ends_at=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=?",starts,ends,id,tenant,revision);}
                case "ENABLE","DISABLE" -> jdbc.update("UPDATE device_suppressions SET enabled=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=?",enabled,id,tenant,revision);
                case "CANCEL" -> jdbc.update("UPDATE device_suppressions SET status='CANCELLED',cancelled_at=?,cancelled_by=?,cancel_reason=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=?",time.now(),actor.user().id(),why,id,tenant,revision);
                default -> throw new IllegalStateException("Unknown suppression operation");
            }
        }
        String action=switch(operation){case "EDIT"->"SUPPRESSION_UPDATED";case "ENABLE"->"SUPPRESSION_ENABLED";case "DISABLE"->"SUPPRESSION_DISABLED";case "DELETE"->"SUPPRESSION_DELETED";default->"SUPPRESSION_CANCELLED";};
        change(actor,tenant,id,action,why,request);var result=read(tenant,id);idem.save(actor.user().id(),op,key,hash,result);return result;
    }
    public Suppression read(String tenant,String id) {
        var rows=jdbc.query("SELECT "+columns()+" FROM device_suppressions WHERE id=? AND tenant_id=?",this::row,id,tenant);
        if(rows.isEmpty())throw ApiError.missing();return rows.getFirst();
    }
    public List<ChannelTarget> targets(String tenant,String id) {
        read(tenant,id);
        return jdbc.query("SELECT channel_id,device_id,channel_key,channel_name,channel_type,device_name FROM suppression_channels WHERE suppression_id=? AND tenant_id=? ORDER BY device_id,channel_key",(rs,n)->new ChannelTarget(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)),id,tenant);
    }
    public List<HistoryEvent> history(String tenant,String id) {
        read(tenant,id);
        String sql="SELECT id,action,actor_id,'SUCCESS' AS outcome,request_id,occurred_at,reason,snapshot_json FROM suppression_changes WHERE tenant_id=? AND suppression_id=? UNION ALL SELECT a.id,a.action,a.actor_id,a.outcome,a.request_id,a.occurred_at,a.reason,NULL AS snapshot_json FROM audit_log a WHERE a.tenant_id=? AND a.target_id=? AND a.action IN ('SUPPRESSION_CREATED','SUPPRESSION_CANCELLED') AND NOT EXISTS(SELECT 1 FROM suppression_changes c WHERE c.tenant_id=a.tenant_id AND c.suppression_id=a.target_id AND c.request_id=a.request_id AND c.action=a.action) ORDER BY occurred_at,id";
        return jdbc.query(sql,(rs,n)->new HistoryEvent(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,OffsetDateTime.class),rs.getString(7),rs.getString(8)),tenant,id,tenant,id);
    }
    public Suppression view(String actor,String tenant,String id) { return visible(actor,tenant,id); }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page<Suppression> list(String actor,String tenant,ScopeFilter.Selection selection,int page,int size,String status,String scope) {
        var pagination=Pagination.of(page,size,20);
        var unitFilter=tree.units(actor,"s","tenant_id",tenant,selection);
        var access=deviceScope.predicate(actor,"d.tenant_id","d.id");
        var selected=tree.devices(actor,"d",tenant,selection);
        String where="s.kind='NOTIFICATION'"+unitFilter.and()+" AND (s.scope='TENANT' OR EXISTS (SELECT 1 FROM device_suppression_members sm JOIN devices d ON d.id=sm.device_id AND d.tenant_id=sm.tenant_id WHERE sm.suppression_id=s.id"+access.and()+selected.and()+"))";
        var args=new ArrayList<Object>();args.addAll(unitFilter.bindings());args.addAll(access.bindings());args.addAll(selected.bindings());
        if(status!=null&&!status.isBlank()) {
            if(!List.of("ACTIVE","SCHEDULED","DISABLED","EXPIRED","CANCELLED","DELETED").contains(status))throw ApiError.bad("屏蔽状态无效");
            where+=" AND ("+stateSql("s.")+")=?";args.add(status);
        }else where+=" AND s.deleted=FALSE";
        if(scope!=null&&!scope.isBlank()) {
            if(!List.of("TENANT","DEVICES","SENSORS").contains(scope))throw ApiError.bad("屏蔽范围无效");
            where+=scope.equals("SENSORS")?" AND s.resource_kind='SENSOR'":" AND s.resource_kind='DEVICE' AND s.scope=?";
            if(!scope.equals("SENSORS"))args.add(scope);
        }
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM device_suppressions s WHERE "+where,Long.class,args.toArray());
        args.add(pagination.size());args.add(pagination.offset());
        var items=jdbc.query("SELECT "+columns()+" FROM device_suppressions s WHERE "+where+" ORDER BY s.created_at DESC,s.id LIMIT ? OFFSET ?",this::row,args.toArray());
        return new Page<>(items,total,pagination.page(),pagination.size());
    }
    /** Unknown channel provenance never lets a sensor-only rule suppress an entire device. */
    public boolean blocked(String tenant,String deviceId,boolean fireEvent,String alarmId) {
        var hits=jdbc.queryForList("SELECT 1 FROM device_suppressions s WHERE s.tenant_id=? AND "+NotificationSuppressionSql.activeAt("s",time.expression())+" AND (?=FALSE OR s.fire_confirmed=TRUE) AND (s.scope='TENANT' OR (s.resource_kind='DEVICE' AND EXISTS(SELECT 1 FROM device_suppression_members m WHERE m.suppression_id=s.id AND m.device_id=?)) OR (s.resource_kind='SENSOR' AND EXISTS(SELECT 1 FROM suppression_channels c JOIN alarm_device_channels a ON a.channel_id=c.channel_id AND a.tenant_id=c.tenant_id AND a.device_id=c.device_id WHERE c.suppression_id=s.id AND c.tenant_id=s.tenant_id AND c.device_id=? AND a.alarm_id=?)))",Integer.class,tenant,fireEvent,deviceId,deviceId,alarmId);
        return !hits.isEmpty();
    }
}
