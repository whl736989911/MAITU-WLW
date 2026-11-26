package com.fireplatform.maintenance;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.device.DeviceArchiveInput;
import com.fireplatform.device.DeviceInputs;
import com.fireplatform.device.DeviceManagementService;
import com.fireplatform.device.DeviceQueryService;
import com.fireplatform.device.DeviceTransferService;
import com.fireplatform.deviceservice.DeviceLifecycleService;
import com.fireplatform.deviceservice.DeviceServiceCommandService;
import com.fireplatform.deviceservice.DeviceServiceInputs;
import com.fireplatform.deviceservice.DeviceSourceBindingService;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.CoordinationFence;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.Pagination;
import com.fireplatform.shared.ScopeFilter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FieldMaintenance {
    private static final String HIK = "HIKVISION_FIRECLOUD";
    private static final String SOURCE = "App 现场更换";
    private static final Set<String> REASONS = Set.of("LOW_BATTERY", "TAMPER", "SMOKE_CHAMBER", "SENSOR", "OFFLINE", "OTHER");
    private static final String FROM = " FROM field_maintenance_records r JOIN devices d ON d.id=r.device_id AND d.tenant_id=r.tenant_id"
        + " LEFT JOIN devices n ON n.id=r.replacement_device_id AND n.tenant_id=r.tenant_id"
        + " LEFT JOIN device_service_commands c ON c.id=r.cloud_command_id AND c.tenant_id=r.tenant_id AND c.device_id=r.device_id AND c.service_key='cloud.delete'";
    private static final String PROJECTION = "SELECT r.*,CASE WHEN r.completed_at IS NOT NULL THEN 'COMPLETE'"
        + " WHEN r.cloud_command_id IS NOT NULL AND c.state='SUCCEEDED' AND c.vendor_code='CLOUD_ABSENCE_VERIFIED' THEN 'READY_TO_RETIRE'"
        + " WHEN r.cloud_command_id IS NOT NULL AND c.state IN ('QUEUED','IN_FLIGHT') THEN 'CLOUD_PENDING'"
        + " WHEN r.cloud_command_id IS NOT NULL AND c.state IN ('FAILED','CANCELLED') THEN 'CLOUD_REJECTED'"
        + " WHEN r.cloud_command_id IS NOT NULL THEN 'CLOUD_UNKNOWN'"
        + " WHEN n.integration_state='CONNECTED' AND n.archived_at IS NULL THEN 'READY_TO_RETIRE' ELSE 'NEW_DEVICE_PENDING' END AS state";

    public record Draft(@NotBlank @Size(max=64) String deviceId, @NotNull @Min(0) Long expectedDeviceRevision,
                        @NotBlank @Pattern(regexp="HANDLE|REPLACE") String action, @NotBlank @Size(max=32) String reasonCode,
                        @Size(max=1000) String note, @Size(max=200) String replacementSerial,
                        @Size(max=64) String replacementDeviceId, boolean riskConfirmed) { }
    public record Entry(String id, String tenantId, String tenantName, String deviceId, String deviceName, String deviceSerial,
                        String placeId, String placeName, String address, String actorName, String action, String reasonCode,
                        String note, OffsetDateTime createdAt, OffsetDateTime completedAt, String status,
                        String replacementDeviceId, String replacementSerial, String cloudCommandId) { }
    private record Stored(Entry entry, long deviceRevision, String sourceBindingId, String replacementBindingId) { }
    private record Receipt(String id) { }

    private final JdbcTemplate jdbc;
    private final DeviceQueryService devices;
    private final DeviceAccessScope deviceScope;
    private final DeviceManagementService assets;
    private final DeviceTransferService transfers;
    private final DeviceLifecycleService lifecycle;
    private final DeviceSourceBindingService bindings;
    private final DeviceServiceCommandService commands;
    private final ManagementGuard guard;
    private final CoordinationFence coordination;
    private final ScopeFilter scopes;
    private final AuditService audit;
    private final IdempotencyStore idem;
    private final DatabaseTime time;

    public FieldMaintenance(JdbcTemplate jdbc, DeviceQueryService devices, DeviceManagementService assets,
                            DeviceTransferService transfers, DeviceLifecycleService lifecycle, DeviceSourceBindingService bindings,
                            DeviceServiceCommandService commands, ManagementGuard guard, CoordinationFence coordination,
                            ScopeFilter scopes, DeviceAccessScope deviceScope, AuditService audit, IdempotencyStore idem, DatabaseTime time) {
        this.jdbc=jdbc; this.devices=devices; this.assets=assets; this.transfers=transfers; this.lifecycle=lifecycle;
        this.bindings=bindings; this.commands=commands; this.guard=guard; this.coordination=coordination;
        this.scopes=scopes; this.deviceScope=deviceScope; this.audit=audit; this.idem=idem; this.time=time;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Page<Entry> list(String actor, String tenant, ScopeFilter.Selection selection, String device, int page, Integer size) {
        var window=Pagination.of(page,size,20);
        if(selection.empty()) return new Page<>(List.of(),0,window.page(),window.size());
        var tree=scopes.devices(actor,"d",tenant,selection);
        var own=deviceScope.predicate(actor,"d.tenant_id","d.id");
        var next=deviceScope.predicate(actor,"n.tenant_id","n.id");
        String where=" WHERE ("+tree.sql()+") AND ("+own.sql()+") AND (r.replacement_device_id IS NULL OR ("+next.sql()+"))"+(device==null?"":" AND r.device_id=?");
        var args=new ArrayList<>(tree.bindings());
        args.addAll(own.bindings()); args.addAll(next.bindings());
        if(device!=null) args.add(device);
        long total=jdbc.queryForObject("SELECT COUNT(*)"+FROM+where,Long.class,args.toArray());
        args.add(window.size()); args.add(window.offset());
        var items=jdbc.query(PROJECTION+FROM+where+" ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",
            (rs,index)->map(rs).entry(),args.toArray());
        return new Page<>(items,total,window.page(),window.size());
    }

    @Transactional
    public Entry create(AuthService.Identity actor, String tenant, String key, Draft draft, String request) {
        boolean replacing=draft.action().equals("REPLACE");
        String serial=trim(draft.replacementSerial()), target=trim(draft.replacementDeviceId()), note=trim(draft.note());
        if(replacing ? !"REPLACED".equals(draft.reasonCode()) || serial.isEmpty() :
            !REASONS.contains(draft.reasonCode()) || !serial.isEmpty() || !target.isEmpty()) throw ApiError.bad("维护原因或新设备编号无效");
        if(replacing && !draft.riskConfirmed()) throw conflict("RISK_CONFIRMATION_REQUIRED","更换会删除旧设备，必须明确确认");
        begin(actor,tenant,replacing);
        idem.validateKey(key);
        String user=actor.user().id();
        String fingerprint=idem.fingerprint(List.of(tenant,draft.deviceId(),draft.expectedDeviceRevision(),draft.action(),draft.reasonCode(),note,serial,target));
        var replay=idem.replay(user,"field-maintenance-create",key,fingerprint,Receipt.class);
        if(replay!=null) {
            devices.device(user,tenant,draft.deviceId(),false);
            var entry=stored(tenant,replay.id()).entry();
            if(entry.replacementDeviceId()!=null) devices.device(user,tenant,entry.replacementDeviceId(),false);
            return entry;
        }
        var old=devices.device(user,tenant,draft.deviceId(),true);
        if(old.isSimulated()) throw ApiError.missing();
        if(old.revision()!=draft.expectedDeviceRevision()) throw conflict("REVISION_CONFLICT","设备资料已变化，请刷新再确认");
        String id=UUID.randomUUID().toString();
        String replacement=null, oldBindingId=null, replacementBindingId=null, command=null;
        if(replacing) {
            if(old.placeId()==null || serial.equals(old.deviceEui())) throw ApiError.bad("换机须有真实位置且新旧设备编号不同");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM field_maintenance_records WHERE tenant_id=? AND active_device_id=?",Long.class,tenant,old.id())>0)
                throw conflict("REPLACEMENT_ACTIVE","已有进行中的换机，请打开维护清单继续原记录");
            requireRetirable(tenant,old.id());
            var oldBinding=bindings.activeForDevice(tenant,old.id()).orElse(null);
            if(oldBinding!=null) oldBindingId=oldBinding.id();
            boolean hik=oldBinding!=null && HIK.equals(oldBinding.driverKey());
            if(hik) {
                guard.requireCurrent(actor,"device-services:engineering",tenant,"management");
                guard.requireCurrent(actor,"device-services:read",tenant,"management");
            }
            if(target.isEmpty()) {
                var matches=jdbc.queryForList("SELECT id FROM devices WHERE tenant_id=? AND device_eui=? AND archived_at IS NULL AND is_simulated=FALSE",String.class,tenant,serial);
                if(!matches.isEmpty()) target=matches.getFirst();
            }
            DeviceQueryService.DeviceRow next;
            if(target.isEmpty()) {
                if(hik) throw conflict("NEW_REGISTRATION_REQUIRED","请先在原单位和位置登记新海康设备并确认开始上报");
                if(old.productVersionId()==null || old.address()==null || old.address().isBlank() || !serial.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"))
                    throw conflict("NEW_REGISTRATION_REQUIRED","原设备资料不足以登记新资产，请先在原位置完成新设备登记");
                next=assets.register(actor,tenant,id+":register",new DeviceInputs.Register(old.name(),serial,old.address(),old.placeId(),
                    old.productVersionId(),old.systemType(),old.deviceCategory()),request,"management");
            } else next=devices.device(user,tenant,target,true);
            requireSameLocation(old,next,serial);
            replacement=next.id();
            if(hik) {
                var nextBinding=bindings.activeForDevice(tenant,next.id()).orElseThrow(()->conflict("NEW_REGISTRATION_REQUIRED","新设备尚未绑定海康来源"));
                if(!HIK.equals(nextBinding.driverKey()) || oldBinding.accountFingerprint()==null ||
                    !oldBinding.accountFingerprint().equals(nextBinding.accountFingerprint()) || oldBinding.externalId().equals(nextBinding.externalId()))
                    throw conflict("SOURCE_MISMATCH","新旧设备必须属于同一海康账号且为不同云设备");
                requireConnected(next);
                replacementBindingId=nextBinding.id();
                command=commands.createCloudDelete(actor,tenant,old.id(),id+":cloud-delete",
                    new DeviceServiceInputs.CloudDelete(old.revision(),oldBinding.externalId(),oldBinding.serial(),SOURCE,true),request).command().id();
            }
        }
        var now=time.now();
        jdbc.update("INSERT INTO field_maintenance_records(id,tenant_id,tenant_name,device_id,device_name,device_serial,device_revision,place_id,place_name,address,actor_id,actor_name,action,reason_code,note,created_at,completed_at,replacement_device_id,replacement_serial,source_binding_id,replacement_binding_id,cloud_command_id,active_device_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,tenant,old.tenantName(),old.id(),old.name(),old.deviceEui(),old.revision(),old.placeId(),old.placeName(),old.address(),user,actor.user().name(),draft.action(),draft.reasonCode(),note,now,
            replacing?null:now,replacement,replacing?serial:null,oldBindingId,replacementBindingId,command,replacing?old.id():null);
        audit.record(actor.user().id(),tenant,replacing?"FIELD_REPLACEMENT_STARTED":"FIELD_MAINTENANCE_RECORDED",id,"SUCCESS",request);
        idem.save(user,"field-maintenance-create",key,fingerprint,new Receipt(id));
        return stored(tenant,id).entry();
    }

    @Transactional
    public Entry finish(AuthService.Identity actor, String tenant, String id, String key, String request) {
        begin(actor,tenant,true);
        idem.validateKey(key);
        var stored=stored(tenant,id);
        Entry entry=stored.entry();
        if(!entry.action().equals("REPLACE")) throw ApiError.missing();
        String user=actor.user().id();
        devices.device(user,tenant,entry.deviceId(),false);
        devices.device(user,tenant,entry.replacementDeviceId(),false);
        if(entry.cloudCommandId()!=null) guard.requireCurrent(actor,"device-services:engineering",tenant,"management");
        if(entry.status().equals("COMPLETE")) return entry;
        if(!entry.status().equals("READY_TO_RETIRE")) throw conflict("REPLACEMENT_NOT_VERIFIED","接入或云端删除结果未确认；旧设备不会归档");
        var old=devices.device(user,tenant,entry.deviceId(),true);
        var next=devices.device(user,tenant,entry.replacementDeviceId(),true);
        if(old.revision()!=stored.deviceRevision() || !Objects.equals(old.placeId(),entry.placeId()) || !Objects.equals(old.address(),entry.address()))
            throw conflict("REVISION_CONFLICT","旧设备资料已变化，需管理人员核对原换机记录");
        requireSameLocation(old,next,entry.replacementSerial());
        requireConnected(next);
        requireRetirable(tenant,old.id());
        var binding=bindings.activeForDevice(tenant,old.id()).orElse(null);
        if(!Objects.equals(stored.sourceBindingId(),binding==null?null:binding.id())) throw conflict("SOURCE_BINDING_CHANGED","旧设备来源已变化，不能归档");
        if(entry.cloudCommandId()!=null && !bindings.activeForDevice(tenant,next.id()).map(DeviceSourceBindingService.Binding::id).filter(stored.replacementBindingId()::equals).isPresent())
            throw conflict("SOURCE_BINDING_CHANGED","新设备来源已变化，不能归档旧设备");
        if(binding!=null) lifecycle.unbind(actor,tenant,old.id(),id+":unbind",new DeviceServiceInputs.UnbindSource(old.revision(),binding.id(),SOURCE),request);
        transfers.archive(actor,tenant,old.id(),id+":archive",new DeviceArchiveInput(old.revision(),SOURCE),request);
        jdbc.update("UPDATE field_maintenance_records SET completed_at=?,active_device_id=NULL WHERE id=? AND tenant_id=? AND completed_at IS NULL",time.now(),id,tenant);
        audit.record(user,tenant,"FIELD_REPLACEMENT_COMPLETED",id,"SUCCESS",request);
        return stored(tenant,id).entry();
    }

    private void begin(AuthService.Identity actor,String tenant,boolean replacing) {
        coordination.lock();
        guard.begin(actor,"maintenance:write",tenant,"management");
        guard.requireCurrent(actor,"device:read",tenant,"management");
        if(replacing) {
            guard.requireCurrent(actor,"device:write",tenant,"management");
            guard.requireCurrent(actor,"maintenance:read",tenant,"management");
        }
    }

    private void requireRetirable(String tenant,String device) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM active_source_bindings WHERE device_id=?",Long.class,device)>0)
            throw conflict("ARCHIVE_SOURCE_BOUND","旧设备仍绑定通用接入源；须由管理员先处理来源关系");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM device_service_commands WHERE tenant_id=? AND device_id=? AND result_kind='COMMAND' AND state IN ('QUEUED','IN_FLIGHT','ACCEPTED','UNKNOWN')",Long.class,tenant,device)>0)
            throw conflict("DEVICE_COMMAND_PENDING","旧设备有未核实的远程命令；不能归档或再次提交换机");
    }

    private static void requireConnected(DeviceQueryService.DeviceRow next) {
        if(!"CONNECTED".equals(next.integrationState())) throw conflict("NEW_DEVICE_NOT_CONNECTED","没有新设备已接入证据；旧设备继续在用");
    }

    private static void requireSameLocation(DeviceQueryService.DeviceRow old,DeviceQueryService.DeviceRow next,String serial) {
        if(next.isSimulated() || next.id().equals(old.id()) || !serial.equals(next.deviceEui()) || !old.placeId().equals(next.placeId()) || !Objects.equals(old.address(),next.address()))
            throw conflict("REPLACEMENT_TARGET_MISMATCH","新设备编号、单位、位置或安装地址不匹配");
    }

    private Stored stored(String tenant,String id) {
        var result=jdbc.query(PROJECTION+FROM+" WHERE r.tenant_id=? AND r.id=?",(rs,index)->map(rs),tenant,id);
        if(result.isEmpty()) throw ApiError.missing();
        return result.getFirst();
    }

    private static Stored map(ResultSet r) throws SQLException {
        var entry=new Entry(r.getString("id"),r.getString("tenant_id"),r.getString("tenant_name"),r.getString("device_id"),
            r.getString("device_name"),r.getString("device_serial"),r.getString("place_id"),r.getString("place_name"),r.getString("address"),r.getString("actor_name"),
            r.getString("action"),r.getString("reason_code"),r.getString("note"),r.getObject("created_at",OffsetDateTime.class),r.getObject("completed_at",OffsetDateTime.class),
            r.getString("state"),r.getString("replacement_device_id"),r.getString("replacement_serial"),r.getString("cloud_command_id"));
        return new Stored(entry,r.getLong("device_revision"),r.getString("source_binding_id"),r.getString("replacement_binding_id"));
    }

    private static String trim(String value) { return value==null?"":value.trim(); }
    private static ApiError conflict(String code,String message) { return ApiError.conflict(code,message); }
}
