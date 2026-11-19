package com.fireplatform.device;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.*;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceTransferService {
    public record Result(DeviceQueryService.DeviceRow source, DeviceQueryService.DeviceRow target, String transferId) { }
    public record BatchItem(String deviceId,String targetPlaceId,long expectedRevision) { }
    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final com.fireplatform.identity.CoordinationFence coordination;
    private final DeviceQueryService devices;
    private final DeviceAccessScope scopes;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final Clock clock;
    private final com.fireplatform.deviceservice.DeviceSourceBindingService sourceBindings;

    public DeviceTransferService(JdbcTemplate jdbc, ManagementGuard guard, com.fireplatform.identity.CoordinationFence coordination, DeviceQueryService devices, DeviceAccessScope scopes, IdempotencyStore idem, AuditService audit, Clock clock, com.fireplatform.deviceservice.DeviceSourceBindingService sourceBindings) {
        this.jdbc=jdbc; this.guard=guard; this.coordination=coordination; this.devices=devices; this.scopes=scopes; this.idem=idem; this.audit=audit; this.clock=clock;this.sourceBindings=sourceBindings;
    }

    @Transactional
    public Result transfer(AuthService.Identity actor, String sourceTenant, String sourceId, String key, DeviceTransferInput input, String requestId) {
        coordination.lock();
        guard.begin(actor,"device:write",sourceTenant,"management");
        guard.begin(actor,"device:write",input.targetTenantId(),"management");
        idem.validateKey(key);
        String fingerprint=idem.fingerprint(List.of(sourceTenant,sourceId,input.expectedRevision(),input.targetTenantId(),input.targetPlaceId(),input.reason()));
        Result replay=idem.replay(actor.user().id(),"device-controlled-transfer",key,fingerprint,Result.class);
        if(replay!=null) return replay;
        String targetId=move(actor,sourceTenant,sourceId,input.targetTenantId(),input.targetPlaceId(),input.expectedRevision(),input.reason(),requestId);
        String transferId=jdbc.queryForObject("SELECT id FROM device_transfers WHERE target_device_id=?",String.class,targetId);
        Result result=new Result(devices.device(actor.user().id(),sourceTenant,sourceId,false),
            devices.device(actor.user().id(),input.targetTenantId(),targetId,false),transferId);
        idem.save(actor.user().id(),"device-controlled-transfer",key,fingerprint,result);
        return result;
    }

    /** One authorization check for the atomic batch; never one expensive check per asset. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void transferBatch(AuthService.Identity actor,String sourceTenant,String targetTenant,List<BatchItem> items,
        String reason,String requestId) {
        coordination.lock();
        guard.begin(actor,"device:write",sourceTenant,"management");
        guard.requireCurrent(actor,"device:write",targetTenant,"management");
        scopes.requireAll(actor.user().id(),sourceTenant);
        scopes.requireAll(actor.user().id(),targetTenant);
        for(var item:items)move(actor,sourceTenant,item.deviceId(),targetTenant,item.targetPlaceId(),item.expectedRevision(),reason,requestId);
    }

    private String move(AuthService.Identity actor,String sourceTenant,String sourceId,String targetTenant,String targetPlace,
        long expectedRevision,String reason,String requestId) {
        if(sourceTenant.equals(targetTenant))throw ApiError.bad("跨单位移交必须选择不同的目标单位");
        var source=devices.device(actor.user().id(),sourceTenant,sourceId,true);
        if(source.revision()!=expectedRevision)throw ApiError.conflict("REVISION_CONFLICT","设备已更新，请刷新");
        if(source.isSimulated())throw ApiError.conflict("SIMULATED_DEVICE_TRANSFER_FORBIDDEN","模拟设备不能移交");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tenants WHERE id=? AND enabled=TRUE)",Boolean.class,targetTenant)))throw ApiError.bad("目标单位不存在或已停用");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL",Long.class,targetPlace,targetTenant)==0)throw ApiError.bad("目标位置不存在、已归档或不属于目标单位");
        scopes.requireAll(actor.user().id(),targetTenant);
        var locked=jdbc.query("SELECT revision FROM devices WHERE id=? AND tenant_id=? FOR UPDATE",(r,n)->r.getLong(1),sourceId,sourceTenant);
        if(locked.isEmpty())throw ApiError.missing();
        if(locked.getFirst()!=expectedRevision)throw ApiError.conflict("REVISION_CONFLICT","设备已更新，请刷新");
        requireNoPendingMutation(sourceTenant,sourceId);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM device_source_bindings WHERE tenant_id=? AND device_id=? AND state='ACTIVE'",Long.class,sourceTenant,sourceId)>1)throw ApiError.conflict("SOURCE_BINDING_CONFLICT","设备存在多条活动来源关联，不能猜选后移交");
        var binding=sourceBindings.activeForDevice(sourceTenant,sourceId);
        long activeBindings=jdbc.queryForObject("SELECT COUNT(*) FROM active_source_bindings WHERE device_id=?",Long.class,sourceId);
        if(activeBindings>0)throw ApiError.conflict("TRANSFER_SOURCE_BOUND","固定单位归属的通用来源仍绑定设备。请先明确解绑并准备目标单位的新来源；不会静默更改旧来源归属");
        var hikvision=jdbc.query("SELECT external_id FROM hikvision_discovered_devices WHERE local_device_id=? ORDER BY external_id FOR UPDATE",(r,n)->r.getString(1),sourceId);
        for(String externalId:hikvision) {
            long pending=jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_raw_records WHERE external_device_id=? AND stream_kind IN ('FAULT','ALARM','MONITOR','HISTORY_MONITOR','DEVICE_EVENT') AND applied_at IS NULL AND (apply_error IS NULL OR apply_error NOT IN ('PRE_OWNERSHIP_HISTORY','OWNERSHIP_TIME_UNKNOWN'))",Long.class,externalId);
            if(pending>0)throw ApiError.conflict("TRANSFER_HIKVISION_PENDING","海康资源仍有未归属的历史原始记录，请先处理或隔离后移交");
        }
        long aliasConflicts=jdbc.queryForObject("SELECT COUNT(*) FROM device_aliases a JOIN device_aliases b ON b.kind=a.kind AND b.alias_value=a.alias_value AND b.tenant_id=? WHERE a.device_id=? AND a.tenant_id=?",Long.class,targetTenant,sourceId,sourceTenant);
        if(aliasConflicts>0)throw ApiError.conflict("TRANSFER_ALIAS_CONFLICT","目标单位已有相同类型的厂商标识，须先核实重复映射；整批移交不会覆盖既有映射");
        String id=UUID.randomUUID().toString();
        OffsetDateTime transferredAt=OffsetDateTime.now(clock);
        int archived=jdbc.update("UPDATE devices SET archived_at=?,archived_reason=?,archived_by=?,identity_slot=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=? AND archived_at IS NULL",
            transferredAt,"跨单位移交；目标资产="+id+"；"+reason.trim(),actor.user().id(),"HISTORY:"+sourceId,sourceId,sourceTenant,expectedRevision);
        if(archived!=1)throw ApiError.conflict("REVISION_CONFLICT","设备已更新或已归档，请刷新");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,place_id,physical_state,is_simulated,product_id,product_version_id,product_name,version_number,revision,integration_state,system_type,device_category,ownership_from) VALUES (?,?,?,?,?,?,?,'UNKNOWN',FALSE,?,?,?,?,0,'PENDING_INTEGRATION',?,?,?)",
            id,targetTenant,source.name(),source.deviceEui(),source.model(),source.address(),targetPlace,source.productId(),source.productVersionId(),source.productName(),source.versionNumber(),source.systemType(),source.deviceCategory(),transferredAt);
        jdbc.update("INSERT INTO device_place_history(id,device_id,tenant_id,action,from_place_id,from_place_name,to_place_id,to_place_name,revision_before,revision_after,actor_id,occurred_at) VALUES (?,?,?,'REGISTER',NULL,NULL,?,?,NULL,0,?,?)",
            UUID.randomUUID().toString(),id,targetTenant,targetPlace,jdbc.queryForObject("SELECT name FROM places WHERE id=? AND tenant_id=?",String.class,targetPlace,targetTenant),actor.user().id(),transferredAt);
        if(!hikvision.isEmpty())jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=?,event_history_from=?,revision=revision+1 WHERE local_device_id=?",id,transferredAt,sourceId);
        String transferId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO device_transfers(id,source_device_id,source_tenant_id,target_device_id,target_tenant_id,actor_id,reason,occurred_at) VALUES (?,?,?,?,?,?,?,?)",
            transferId,sourceId,sourceTenant,id,targetTenant,actor.user().id(),reason.trim(),transferredAt);
        var aliases=jdbc.queryForList("SELECT id,kind,alias_value FROM device_aliases WHERE device_id=? AND tenant_id=? ORDER BY id FOR UPDATE",sourceId,sourceTenant);
        for(var alias:aliases) {
            jdbc.update("INSERT INTO device_transfer_alias_history(id,transfer_id,alias_id,kind,alias_value,source_tenant_id,source_device_id,target_tenant_id,target_device_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),transferId,alias.get("id"),alias.get("kind"),alias.get("alias_value"),sourceTenant,sourceId,targetTenant,id,transferredAt);
        }
        jdbc.update("UPDATE device_aliases SET tenant_id=?,device_id=? WHERE tenant_id=? AND device_id=?",targetTenant,id,sourceTenant,sourceId);
        if(binding.isPresent()) {
            var old=binding.get();
            sourceBindings.release(old.id(),actor.user().id(),"跨单位移交："+reason.trim(),old.pendingRisk());
            sourceBindings.bind(targetTenant,id,old.driverKey(),old.externalId(),old.serial(),old.accountFingerprint(),actor.user().id());
        }
        audit.record(actor.user().id(),sourceTenant,"DEVICE_TRANSFER_OUT",transferId,"SUCCESS",requestId);
        audit.record(actor.user().id(),targetTenant,"DEVICE_TRANSFER_IN",transferId,"SUCCESS",requestId);
        return id;
    }
    @Transactional
    public DeviceQueryService.DeviceRow archive(AuthService.Identity actor,String tenant,String deviceId,String key,DeviceArchiveInput input,String requestId) {
        coordination.lock();
        guard.begin(actor,"device:write",tenant,"management");
        idem.validateKey(key);
        String fingerprint=idem.fingerprint(List.of(tenant,deviceId,input.expectedRevision(),input.reason()));
        var replay=idem.replay(actor.user().id(),"device-archive",key,fingerprint,DeviceQueryService.DeviceRow.class);
        if(replay!=null) return replay;
        var device=devices.device(actor.user().id(),tenant,deviceId,true);
        if(device.revision()!=input.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT","设备已更新，请刷新");
        if(device.isSimulated()) throw ApiError.conflict("SIMULATED_DEVICE_ARCHIVE_FORBIDDEN","模拟设备不能作为真实资产归档");
        requireNoPendingMutation(tenant,deviceId);
        if(sourceBindings.activeForDevice(tenant,deviceId).isPresent())throw ApiError.conflict("ARCHIVE_SOURCE_BOUND","设备仍有关联来源，须先显式解除关联，保留历史代次");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM active_source_bindings WHERE device_id=?",Long.class,deviceId)>0) throw ApiError.conflict("ARCHIVE_SOURCE_BOUND","设备仍绑定通用接入来源，必须先明确解绑");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices WHERE local_device_id=?",Long.class,deviceId)>0) throw ApiError.conflict("ARCHIVE_HIKVISION_BOUND","设备仍绑定海康来源资源，必须先明确解除关联");
        int changed=jdbc.update("UPDATE devices SET archived_at=?,archived_reason=?,archived_by=?,identity_slot=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=? AND archived_at IS NULL",
            OffsetDateTime.now(clock),input.reason().trim(),actor.user().id(),"HISTORY:"+deviceId,deviceId,tenant,input.expectedRevision());
        if(changed!=1) throw ApiError.conflict("REVISION_CONFLICT","设备已更新或已归档，请刷新");
        var result=devices.device(actor.user().id(),tenant,deviceId,false);
        audit.record(actor.user().id(),tenant,"DEVICE_ARCHIVE",deviceId,"SUCCESS",requestId);
        idem.save(actor.user().id(),"device-archive",key,fingerprint,result);
        return result;
    }
    private void requireNoPendingMutation(String tenant,String device) {
        long pending=jdbc.queryForObject("SELECT COUNT(*) FROM device_service_commands WHERE tenant_id=? AND device_id=? AND result_kind='COMMAND' AND state IN ('QUEUED','IN_FLIGHT','ACCEPTED','UNKNOWN')",Long.class,tenant,device);
        if(pending>0)throw ApiError.conflict("DEVICE_COMMAND_PENDING","设备 "+device+" 有尚未证实终态的远程操作，请先在设备详情查询结果或撤销尚未下发的排队请求；不能在硬件仍可能执行旧指令时移交或归档");
    }
}
