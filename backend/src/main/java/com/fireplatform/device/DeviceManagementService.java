package com.fireplatform.device;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.product.PublishedVersionCatalog;
import com.fireplatform.shared.*;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceManagementService {
    public record History(String id,String fromVersionId,String toVersionId,String reason,String actorId,OffsetDateTime occurredAt) { }
    private record Classification(String deviceId,String systemType,String deviceCategory) { }
    private final JdbcTemplate jdbc; private final ManagementGuard guard; private final PublishedVersionCatalog catalog; private final DeviceQueryService queries; private final IdempotencyStore idem; private final AuditService audit; private final Clock clock;private final DeviceAccessScope scopes;
    public DeviceManagementService(JdbcTemplate jdbc,ManagementGuard guard,PublishedVersionCatalog catalog,DeviceQueryService queries,IdempotencyStore idem,AuditService audit,Clock clock,DeviceAccessScope scopes) { this.jdbc=jdbc; this.guard=guard; this.catalog=catalog; this.queries=queries; this.idem=idem; this.audit=audit; this.clock=clock;this.scopes=scopes; }
    private void begin(AuthService.Identity actor,String tenant,String permission,String audience,String key) { guard.begin(actor,permission,tenant,audience); idem.validateKey(key); }
    private void revision(DeviceQueryService.DeviceRow device,long expected) { if(device.revision()!=expected) throw ApiError.conflict("REVISION_CONFLICT","设备已更新，请刷新"); }
    private void save(AuthService.Identity actor,String tenant,String op,String key,String hash,Object result,String target,String request) { audit.record(actor.user().id(),tenant,op,target,"SUCCESS",request); idem.save(actor.user().id(),op,key,hash,result); }
    private static String classification(String value,String label) {
        if(value==null || value.isBlank()) return null;
        String normalized=value.trim();
        if(normalized.length()>80 || normalized.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE)) throw ApiError.bad(label+"长度或字符无效");
        return normalized;
    }
    private Classification currentClassification(String tenant,String id) {
        var rows=jdbc.query("SELECT system_type,device_category FROM devices WHERE tenant_id=? AND id=?",
            (rs,n)->new Classification(id,rs.getString(1),rs.getString(2)),tenant,id);
        if(rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }
    private void history(AuthService.Identity actor,String tenant,String device,String from,String to,String reason) { var scope=scopes.predicate(actor.user().id(),"d.tenant_id","d.id");var args=new java.util.ArrayList<Object>();args.add(UUID.randomUUID().toString());args.add(device);args.add(tenant);args.add(from);args.add(to);args.add(reason);args.add(actor.user().id());args.add(OffsetDateTime.now(clock));args.add(tenant);args.add(device);args.addAll(scope.bindings());if(jdbc.update("INSERT INTO device_version_history(id,device_id,tenant_id,from_version_id,to_version_id,reason,actor_id,occurred_at) SELECT ?,?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM devices d WHERE d.tenant_id=? AND d.id=?"+scope.and()+")",args.toArray())!=1)throw ApiError.missing(); }
    private String placeName(String tenant,String placeId) {
        return placeId==null ? null : jdbc.queryForObject("SELECT name FROM places WHERE tenant_id=? AND id=?",String.class,tenant,placeId);
    }
    private void placeHistory(AuthService.Identity actor,String tenant,String device,String action,String from,String to,Long before,long after) {
        jdbc.update("INSERT INTO device_place_history(id,device_id,tenant_id,action,from_place_id,from_place_name,to_place_id,to_place_name,revision_before,revision_after,actor_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),device,tenant,action,from,placeName(tenant,from),to,placeName(tenant,to),before,after,actor.user().id(),OffsetDateTime.now(clock));
    }
    @Transactional public DeviceQueryService.DeviceRow register(AuthService.Identity actor,String tenant,String key,DeviceInputs.Register in,String request,String audience) {
        begin(actor,tenant,"device:write",audience,key);
        String systemType=classification(in.systemType(),"所属系统"),deviceCategory=classification(in.deviceCategory(),"设备类别");
        String placeId=in.placeId();
        if(placeId==null || placeId.isBlank()) throw ApiError.bad("必须选择安装位置");
        if(placeId.length()>64 || jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL",Long.class,placeId,tenant)==0) throw ApiError.bad("安装位置不存在、不属于当前单位或已归档");
        scopes.requirePlace(actor.user().id(),tenant,placeId);
        String hash=idem.fingerprint(List.of(tenant,in.name(),in.deviceEui(),in.address(),placeId,in.productVersionId(),systemType==null?"":systemType,deviceCategory==null?"":deviceCategory)); var replay=idem.replay(actor.user().id(),"device-register",key,hash,DeviceQueryService.DeviceRow.class); if(replay!=null) return replay;
        var version=catalog.requireAvailableForNewUse(in.productVersionId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=? AND identity_slot='ACTIVE'",Long.class,in.deviceEui())!=0) throw ApiError.conflict("DEVICE_EUI_CONFLICT","设备标识已被使用");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,place_id,physical_state,is_simulated,product_id,product_version_id,product_name,version_number,revision,integration_state,system_type,device_category,ownership_from) VALUES (?,?,?,?,?,?,?,'UNKNOWN',FALSE,?,?,?,?,0,'PENDING_INTEGRATION',?,?,?)",id,tenant,in.name(),in.deviceEui(),version.model(),in.address(),placeId,version.productId(),version.id(),version.productName(),version.versionNumber(),systemType,deviceCategory,OffsetDateTime.now(clock));
        placeHistory(actor,tenant,id,"REGISTER",null,placeId,null,0);
        history(actor,tenant,id,null,version.id(),"REGISTER"); var result=queries.device(actor.user().id(),tenant,id,false); save(actor,tenant,"device-register",key,hash,result,id,request); return result;
    }
    @Transactional public DeviceQueryService.DeviceRow edit(AuthService.Identity actor,String tenant,String id,String key,DeviceInputs.Edit in,String request,String audience) {
        begin(actor,tenant,"device:write",audience,key); var current=queries.device(actor.user().id(),tenant,id,true);
        String placeId=in.placeId()==null||in.placeId().isBlank()?current.placeId():in.placeId();
        if(placeId==null || placeId.isBlank()) throw ApiError.bad("设备必须归属一个实际安装位置");
        boolean moving = !java.util.Objects.equals(current.placeId(), placeId);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE id=? AND tenant_id=?"+(moving?" AND archived_at IS NULL":""),Long.class,placeId,tenant)==0) throw ApiError.bad(moving ? "目标位置不存在、不属于当前单位或已归档" : "安装位置不存在或不属于当前单位");
        if(moving) scopes.requirePlace(actor.user().id(),tenant,placeId);
        var prior=currentClassification(tenant,id);
        String systemType=in.systemType()==null?prior.systemType():classification(in.systemType(),"所属系统");
        String deviceCategory=in.deviceCategory()==null?prior.deviceCategory():classification(in.deviceCategory(),"设备类别");
        String hash=idem.fingerprint(List.of(tenant,id,in.name(),in.address(),placeId,in.expectedRevision(),systemType==null?"":systemType,deviceCategory==null?"":deviceCategory)); var replay=idem.replay(actor.user().id(),"device-edit",key,hash,DeviceQueryService.DeviceRow.class); if(replay!=null) return replay;
        revision(current,in.expectedRevision());var access=scopes.predicate(actor.user().id(), "devices.tenant_id", "devices.id");var editArgs=new java.util.ArrayList<Object>();editArgs.add(in.name());editArgs.add(in.address());editArgs.add(placeId);editArgs.add(systemType);editArgs.add(deviceCategory);editArgs.add(id);editArgs.add(tenant);editArgs.add(in.expectedRevision());editArgs.addAll(access.bindings());int changed=jdbc.update("UPDATE devices SET name=?,address=?,place_id=?,system_type=?,device_category=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=?"+access.and(),editArgs.toArray());
        if(changed!=1) throw ApiError.conflict("REVISION_CONFLICT","设备已更新，请刷新");
        if(!java.util.Objects.equals(current.placeId(),placeId)) placeHistory(actor,tenant,id,"MOVE",current.placeId(),placeId,current.revision(),current.revision()+1);
        var result=queries.device(actor.user().id(),tenant,id,false); save(actor,tenant,"device-edit",key,hash,result,id,request); return result;
    }
    @Transactional public DeviceQueryService.DeviceRow migrate(AuthService.Identity actor,String tenant,String id,String key,DeviceInputs.Migrate in,String request) {
        begin(actor,tenant,"device:migrate","management",key); var current=queries.device(actor.user().id(),tenant,id,true);
        if(current.placeId()==null || current.placeId().isBlank()) throw ApiError.conflict("DEVICE_PLACE_REQUIRED","设备必须先归属实际安装位置，才能迁移产品版本");
        String hash=idem.fingerprint(List.of(tenant,id,in.productVersionId(),in.expectedRevision(),in.reason())); var replay=idem.replay(actor.user().id(),"device-migrate",key,hash,DeviceQueryService.DeviceRow.class); if(replay!=null) return replay;
        revision(current,in.expectedRevision());
        if(in.productVersionId().equals(current.productVersionId())) throw ApiError.conflict("VERSION_UNCHANGED","设备已绑定该版本");
        var version=catalog.requireAvailableForNewUse(in.productVersionId());
        if(current.productId()!=null && !current.productId().equals(version.productId())) throw ApiError.conflict("PRODUCT_MISMATCH","只能显式迁移同产品版本");
        if(!"SUPPORTED".equals(version.batteryCapability()) && jdbc.queryForObject("SELECT COUNT(*) FROM device_battery_threshold_overrides WHERE device_id=? AND threshold IS NOT NULL",Long.class,id)>0) throw ApiError.conflict("PARAMETER_INCOMPATIBLE","请先显式清除设备电量阈值后再迁移到不支持电量的版本");
        var access=scopes.predicate(actor.user().id(), "devices.tenant_id", "devices.id");var migrationArgs=new java.util.ArrayList<Object>();migrationArgs.add(version.productId());migrationArgs.add(version.id());migrationArgs.add(version.productName());migrationArgs.add(version.versionNumber());migrationArgs.add(version.model());migrationArgs.add(id);migrationArgs.add(tenant);migrationArgs.add(in.expectedRevision());migrationArgs.addAll(access.bindings());int changed=jdbc.update("UPDATE devices SET product_id=?,product_version_id=?,product_name=?,version_number=?,model=?,integration_state='PENDING_INTEGRATION',revision=revision+1 WHERE id=? AND tenant_id=? AND revision=?"+access.and(),migrationArgs.toArray());
        if(changed!=1) throw ApiError.conflict("REVISION_CONFLICT","设备已更新，请刷新");
        history(actor,tenant,id,current.productVersionId(),version.id(),in.reason()); var result=queries.device(actor.user().id(),tenant,id,false); save(actor,tenant,"device-migrate",key,hash,result,id,request); return result;
    }
    @Transactional(readOnly=true) public List<History> history(AuthService.Identity identity,String tenant,String id,String audience) { String actor=identity.user().id();queries.device(actor,tenant,id,false);var scope=scopes.predicate(actor,"d.tenant_id","d.id");var args=new java.util.ArrayList<Object>();args.add(tenant);args.add(id);args.addAll(scope.bindings());return jdbc.query("SELECT h.* FROM device_version_history h JOIN devices d ON d.id=h.device_id AND d.tenant_id=h.tenant_id WHERE h.tenant_id=? AND h.device_id=?"+scope.and()+" ORDER BY h.occurred_at,h.id",(rs,n)->new History(rs.getString("id"),rs.getString("from_version_id"),rs.getString("to_version_id"),rs.getString("reason"),rs.getString("actor_id"),rs.getObject("occurred_at",OffsetDateTime.class)),args.toArray()); }
    public record BatchResult(List<DeviceQueryService.DeviceRow> items) { }
    private List<DeviceQueryService.DeviceRow> lockBatch(AuthService.Identity actor,String tenant,List<DeviceInputs.BatchTarget> targets) {
        var byId=new java.util.TreeMap<String,Long>();
        for(var target:targets) if(byId.putIfAbsent(target.deviceId(),target.expectedRevision())!=null) throw ApiError.bad("批量设备ID重复");
        var result=new java.util.ArrayList<DeviceQueryService.DeviceRow>();
        for(var entry:byId.entrySet()) {
            var device=queries.device(actor.user().id(),tenant,entry.getKey(),true);
            revision(device,entry.getValue());
            if(device.placeId()==null || device.placeId().isBlank()) throw ApiError.conflict("DEVICE_PLACE_REQUIRED","所选设备包含未归属实际位置的记录");
            result.add(device);
        }
        return result;
    }
    private String batchFingerprint(String tenant,List<DeviceInputs.BatchTarget> targets,String value,String reason) {
        var canonical=targets.stream().map(t->t.deviceId()+":"+t.expectedRevision()).sorted().toList();
        return idem.fingerprint(List.of(tenant,String.join("|",canonical),value,reason==null?"":reason));
    }
    @Transactional public BatchResult batchMigrate(AuthService.Identity actor,String tenant,String key,DeviceInputs.BatchMigrate in,String request) {
        begin(actor,tenant,"device:migrate","management",key);
        var hash=batchFingerprint(tenant,in.devices(),in.productVersionId(),in.reason());
        var replay=idem.replay(actor.user().id(),"device-batch-migrate",key,hash,BatchResult.class); if(replay!=null) return replay;
        var devicesToMigrate=lockBatch(actor,tenant,in.devices());
        var version=catalog.requireAvailableForNewUse(in.productVersionId());
        for(var device:devicesToMigrate) {
            if(java.util.Objects.equals(version.id(),device.productVersionId())) throw ApiError.conflict("VERSION_UNCHANGED","所选设备已绑定目标版本");
            if(device.productId()!=null && !device.productId().equals(version.productId())) throw ApiError.conflict("PRODUCT_MISMATCH","所选设备必须属于目标产品");
            if(!"SUPPORTED".equals(version.batteryCapability()) && jdbc.queryForObject("SELECT COUNT(*) FROM device_battery_threshold_overrides WHERE device_id=? AND threshold IS NOT NULL",Long.class,device.id())>0) throw ApiError.conflict("PARAMETER_INCOMPATIBLE","目标版本不支持已配置的设备电量阈值");
        }
        var changed=new java.util.ArrayList<DeviceQueryService.DeviceRow>();
        for(var device:devicesToMigrate) {
            var access=scopes.predicate(actor.user().id(),"devices.tenant_id","devices.id");var args=new java.util.ArrayList<Object>();
            args.add(version.productId());args.add(version.id());args.add(version.productName());args.add(version.versionNumber());args.add(version.model());args.add(device.id());args.add(tenant);args.add(device.revision());args.addAll(access.bindings());
            if(jdbc.update("UPDATE devices SET product_id=?,product_version_id=?,product_name=?,version_number=?,model=?,integration_state='PENDING_INTEGRATION',revision=revision+1 WHERE id=? AND tenant_id=? AND revision=?"+access.and(),args.toArray())!=1) throw ApiError.conflict("REVISION_CONFLICT","所选设备已更新，请刷新");
            history(actor,tenant,device.id(),device.productVersionId(),version.id(),in.reason());
            changed.add(queries.device(actor.user().id(),tenant,device.id(),false));
        }
        var result=new BatchResult(List.copyOf(changed)); save(actor,tenant,"device-batch-migrate",key,hash,result,String.join(",",changed.stream().map(DeviceQueryService.DeviceRow::id).toList()),request); return result;
    }
    @Transactional public BatchResult batchMove(AuthService.Identity actor,String tenant,String key,DeviceInputs.BatchMove in,String request,String audience) {
        begin(actor,tenant,"device:write",audience,key);
        String placeId=in.placeId();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL",Long.class,placeId,tenant)==0) throw ApiError.bad("目标位置不存在、不属于当前单位或已归档");
        scopes.requirePlace(actor.user().id(),tenant,placeId);
        for(var target:in.devices()) scopes.requireDevice(actor.user().id(),tenant,target.deviceId());
        var hash=batchFingerprint(tenant,in.devices(),placeId,null);
        var replay=idem.replay(actor.user().id(),"device-batch-move",key,hash,BatchResult.class); if(replay!=null) return replay;
        var selected=lockBatch(actor,tenant,in.devices());
        var changed=new java.util.ArrayList<DeviceQueryService.DeviceRow>();
        for(var device:selected) {
            if(!java.util.Objects.equals(placeId,device.placeId())) {
                var access=scopes.predicate(actor.user().id(),"devices.tenant_id","devices.id");var args=new java.util.ArrayList<Object>();
                args.add(placeId);args.add(device.id());args.add(tenant);args.add(device.revision());args.addAll(access.bindings());
                if(jdbc.update("UPDATE devices SET place_id=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=?"+access.and(),args.toArray())!=1) throw ApiError.conflict("REVISION_CONFLICT","所选设备已更新，请刷新");
                placeHistory(actor,tenant,device.id(),"MOVE",device.placeId(),placeId,device.revision(),device.revision()+1);
                changed.add(queries.device(actor.user().id(),tenant,device.id(),false));
            } else changed.add(device);
        }
        var result=new BatchResult(List.copyOf(changed)); save(actor,tenant,"device-batch-move",key,hash,result,String.join(",",changed.stream().map(DeviceQueryService.DeviceRow::id).toList()),request); return result;
    }
}
