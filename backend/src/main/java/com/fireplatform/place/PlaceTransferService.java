package com.fireplatform.place;

import com.fireplatform.device.DeviceTransferService;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.CoordinationFence;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.*;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A local ownership cutover, not an upstream account transfer or historical-data rewrite. */
@Service
public class PlaceTransferService {
    public record Input(@NotBlank @Size(max=64) String targetTenantId, @Size(max=64) String targetParentId,
        @NotNull @Min(0) Long expectedRevision, @NotBlank @Size(min=64,max=64) String snapshot,
        @NotBlank @Size(max=1000) String reason,
        boolean confirmActiveRisks) { }
    public record Preview(String sourcePlaceId, String sourceTenantId, String targetTenantId, String targetParentId,
        long revision, long placesCount, long devicesCount, long activeFireCount, long boundSourceCount,
        String snapshot, List<String> warnings) { }
    public record Result(String transferId,String sourcePlaceId,String targetPlaceId,String targetTenantId,
        long placesCount,long devicesCount) { }
    private record Place(String id,String parent,String name,long revision) { }
    private record Device(String id,String place,long revision,long fires,long bindings) { }
    private record Tree(List<Place> places,List<Device> devices,String snapshot,long fires,long bindings) { }
    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final CoordinationFence fence;
    private final DeviceAccessScope scopes;
    private final DeviceTransferService devices;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final Clock clock;
    public PlaceTransferService(JdbcTemplate jdbc,ManagementGuard guard,CoordinationFence fence,DeviceAccessScope scopes,
        DeviceTransferService devices,IdempotencyStore idem,AuditService audit,Clock clock) {
        this.jdbc=jdbc;this.guard=guard;this.fence=fence;this.scopes=scopes;this.devices=devices;
        this.idem=idem;this.audit=audit;this.clock=clock;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Preview preview(AuthService.Identity actor,String tenant,String id,String targetTenant,String targetParent) {
        authorize(actor,tenant,targetTenant);
        String parent=parent(targetTenant,targetParent);
        Tree tree=tree(tenant,id,targetTenant,parent);
        var root=tree.places().getFirst();
        var warnings=new ArrayList<String>();
        warnings.add("移交全部下级位置及在用设备，不限当前页面；原单位的位置、资产及历史记录保留归档。");
        warnings.add("不迁移原报警、通知、工单、用户权限或厂商云账号；目标设备等待新的有效来源事实。");
        warnings.add("移交前及来源时间未知的旧平台报文将隔离，不记入目标单位。");
        if(tree.bindings()>0) warnings.add("有固定单位的通用来源绑定：须先明确解绑，并准备目标单位的新来源后重新预览。");
        if(tree.fires()>0) warnings.add("存在未恢复火警；移交不会消除火警或替代现场处置。");
        return new Preview(id,tenant,targetTenant,parent,root.revision(),tree.places().size(),tree.devices().size(),
            tree.fires(),tree.bindings(),tree.snapshot(),List.copyOf(warnings));
    }
    @Transactional
    public Result transfer(AuthService.Identity actor,String tenant,String id,Input input,String key,String requestId) {
        fence.lock();
        authorize(actor,tenant,input.targetTenantId());
        guard.begin(actor,"units:write",tenant,"management");
        guard.begin(actor,"units:write",input.targetTenantId(),"management");
        idem.validateKey(key);
        String fingerprint=idem.fingerprint(Arrays.asList(tenant,id,input.targetTenantId(),input.targetParentId(),
            input.expectedRevision(),input.snapshot(),input.reason(),input.confirmActiveRisks()));
        var replay=idem.replay(actor.user().id(),"place-subtree-transfer",key,fingerprint,Result.class);
        if(replay!=null)return replay;
        String parent=parent(input.targetTenantId(),input.targetParentId());
        Tree tree=tree(tenant,id,input.targetTenantId(),parent);
        if(tree.places().getFirst().revision()!=input.expectedRevision() || !tree.snapshot().equals(input.snapshot()))
            throw ApiError.conflict("PLACE_TRANSFER_STALE","位置、设备或目标已变更，请重新预览完整移交范围");
        if(tree.bindings()>0)throw ApiError.conflict("TRANSFER_SOURCE_BOUND","请先明确解绑固定单位来源并配置目标来源，再重新预览");
        if(tree.fires()>0&&!input.confirmActiveRisks())throw ApiError.conflict("ACTIVE_FIRE_CONFIRMATION_REQUIRED","须明确确认未恢复火警仍由原单位处置");
        String transferId=UUID.randomUUID().toString();
        var mapping=new LinkedHashMap<String,String>();
        for(var place:tree.places()) {
            String target=UUID.randomUUID().toString();
            mapping.put(place.id(),target);
            String targetParent=place.id().equals(id)?parent:mapping.get(place.parent());
            jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id,revision) VALUES (?,?,?,?,0)",
                target,input.targetTenantId(),place.name(),targetParent);
        }
        var batch=tree.devices().stream().map(device->new DeviceTransferService.BatchItem(device.id(),mapping.get(device.place()),device.revision())).toList();
        devices.transferBatch(actor,tenant,input.targetTenantId(),batch,input.reason(),requestId);
        OffsetDateTime now=OffsetDateTime.now(clock);
        jdbc.update("INSERT INTO place_transfers(id,source_tenant_id,target_tenant_id,source_place_id,target_place_id,actor_id,reason,snapshot_hash,place_count,device_count,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            transferId,tenant,input.targetTenantId(),id,mapping.get(id),actor.user().id(),input.reason().trim(),tree.snapshot(),tree.places().size(),tree.devices().size(),now);
        for(var place:tree.places()) {
            int changed=jdbc.update("UPDATE places SET archived_at=?,archived_reason=?,archived_by=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=? AND archived_at IS NULL",
                now,input.reason().trim(),actor.user().id(),place.id(),tenant,place.revision());
            if(changed!=1)throw ApiError.conflict("PLACE_TRANSFER_STALE","位置已变更，整批移交已回滚，请重新预览");
            jdbc.update("INSERT INTO place_transfer_nodes(transfer_id,source_place_id,target_place_id) VALUES (?,?,?)",transferId,place.id(),mapping.get(place.id()));
        }
        var result=new Result(transferId,id,mapping.get(id),input.targetTenantId(),tree.places().size(),tree.devices().size());
        audit.record(actor.user().id(),tenant,"PLACE_SUBTREE_TRANSFER_OUT",transferId,"SUCCESS",requestId);
        audit.record(actor.user().id(),input.targetTenantId(),"PLACE_SUBTREE_TRANSFER_IN",transferId,"SUCCESS",requestId);
        idem.save(actor.user().id(),"place-subtree-transfer",key,fingerprint,result);
        return result;
    }
    private void authorize(AuthService.Identity actor,String tenant,String target) {
        guard.requireCurrent(actor,"units:read",tenant,"management");
        guard.requireCurrent(actor,"units:write",tenant,"management");
        guard.requireCurrent(actor,"device:write",tenant,"management");
        if(target==null||target.isBlank()||target.length()>64||tenant.equals(target))throw ApiError.bad("请选择不同的实际目标单位");
        guard.requireCurrent(actor,"units:write",target,"management");
        guard.requireCurrent(actor,"device:write",target,"management");
        scopes.requireAll(actor.user().id(),tenant);
        scopes.requireAll(actor.user().id(),target);
    }
    private String parent(String tenant,String requested) {
        if(requested==null||requested.isBlank())return null;
        if(requested.length()>64 || jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL",Long.class,requested,tenant)!=1)
            throw ApiError.bad("目标上级位置不存在、已归档或不属于目标单位");
        return requested;
    }
    private Tree tree(String tenant,String root,String targetTenant,String targetParent) {
        var rows=jdbc.query("SELECT id,parent_place_id,name,revision FROM places WHERE tenant_id=? AND archived_at IS NULL ORDER BY id",
            (r,n)->new Place(r.getString(1),r.getString(2),r.getString(3),r.getLong(4)),tenant);
        var byId=new HashMap<String,Place>();var children=new HashMap<String,List<Place>>();
        for(var p:rows){byId.put(p.id(),p);if(p.parent()!=null)children.computeIfAbsent(p.parent(),x->new ArrayList<>()).add(p);}
        var first=byId.get(root);if(first==null)throw ApiError.missing();
        var selected=new ArrayList<Place>();var seen=new HashSet<String>();var pending=new ArrayDeque<Place>();pending.add(first);
        var depths=new HashMap<String,Integer>();depths.put(root,1);
        while(!pending.isEmpty()) {
            var p=pending.removeFirst();if(!seen.add(p.id())||depths.get(p.id())>64)throw ApiError.conflict("PLACE_CYCLE","来源位置层级异常，不能移交");
            selected.add(p);
            for(var child:children.getOrDefault(p.id(),List.of())){depths.put(child.id(),depths.get(p.id())+1);pending.addLast(child);}
        }
        if(selected.size()>5000)throw ApiError.bad("单次移交位置超过安全上限");
        var allDevices=jdbc.query("SELECT d.id,d.place_id,d.revision,COALESCE(a.total,0),COALESCE(b.total,0) FROM devices d " +
            "LEFT JOIN (SELECT device_id,COUNT(*) AS total FROM alarms WHERE tenant_id=? AND physical_state='FIRE' AND is_simulated=FALSE GROUP BY device_id) a ON a.device_id=d.id " +
            "LEFT JOIN (SELECT ab.device_id,COUNT(*) AS total FROM active_source_bindings ab JOIN devices bd ON bd.id=ab.device_id WHERE bd.tenant_id=? GROUP BY ab.device_id) b ON b.device_id=d.id " +
            "WHERE d.tenant_id=? AND d.archived_at IS NULL ORDER BY d.id",
            (r,n)->new Device(r.getString(1),r.getString(2),r.getLong(3),r.getLong(4),r.getLong(5)),tenant,tenant,tenant);
        var selectedDevices=allDevices.stream().filter(d->seen.contains(d.place())).toList();
        long fires=0,bindings=0;
        for(var device:selectedDevices){fires+=device.fires();bindings+=device.bindings();}
        var targetRows=jdbc.queryForList("SELECT id,parent_place_id,revision FROM places WHERE tenant_id=? AND archived_at IS NULL ORDER BY id",targetTenant);
        var targetParents=new HashMap<String,String>();
        for(var row:targetRows)targetParents.put((String)row.get("id"),(String)row.get("parent_place_id"));
        int targetDepth=0;String ancestor=targetParent;var visited=new HashSet<String>();
        while(ancestor!=null) {
            if(!targetParents.containsKey(ancestor)||!visited.add(ancestor)||++targetDepth>64)throw ApiError.conflict("PLACE_TREE_INVALID","目标位置树存在缺失上级、循环或层级越界");
            ancestor=targetParents.get(ancestor);
        }
        int sourceDepth=depths.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        if(sourceDepth+targetDepth>64)throw ApiError.bad("移交后的完整位置层级超过安全上限");
        String snapshot=idem.fingerprint(Arrays.asList(tenant,root,targetTenant,targetParent,selected,selectedDevices,targetRows,fires,bindings));
        return new Tree(List.copyOf(selected),selectedDevices,snapshot,fires,bindings);
    }
}
