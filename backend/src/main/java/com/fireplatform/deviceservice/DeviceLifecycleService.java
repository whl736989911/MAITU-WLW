package com.fireplatform.deviceservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.CoordinationFence;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.IdempotencyStore;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit local lifecycle operations. Unbinding releases the active vendor-source slot, keeps the raw
 * source history and records the unresolved risk; it never archives or hard-deletes a device. Cloud
 * deletion remains a separate durable command and is not terminal-effect proof.
 */
@Service
public class DeviceLifecycleService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DeviceSourceBindingService bindings;
    private final DeviceServiceQueryService queries;
    private final DeviceAccessScope scopes;
    private final ManagementGuard guard;
    private final CoordinationFence coordination;
    private final AuditService audit;
    private final IdempotencyStore idem;
    private final java.time.Clock clock;

    public DeviceLifecycleService(JdbcTemplate jdbc, ObjectMapper json, DeviceSourceBindingService bindings,
                                  DeviceServiceQueryService queries, DeviceAccessScope scopes, ManagementGuard guard,
                                  CoordinationFence coordination, AuditService audit, IdempotencyStore idem,
                                  java.time.Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.bindings = bindings;
        this.queries = queries;
        this.scopes = scopes;
        this.guard = guard;
        this.coordination = coordination;
        this.audit = audit;
        this.idem = idem;
        this.clock = clock;
    }

    @Transactional
    public DeviceServiceModels.SourceBinding unbind(AuthService.Identity actor, String tenant, String deviceId,
                                                    String key, DeviceServiceInputs.UnbindSource input, String requestId) {
        coordination.lock();
        guard.begin(actor, "device:write", tenant, "management");
        idem.validateKey(key);
        var device = lockedDevice(actor.user().id(), tenant, deviceId);
        if (device.revision() != input.expectedDeviceRevision())
            throw ApiError.conflict("REVISION_CONFLICT", "设备已更新，请刷新后重试");
        Long unresolved = jdbc.queryForObject("SELECT COUNT(*) FROM device_service_commands WHERE tenant_id=? AND device_id=? AND result_kind='COMMAND' AND state IN ('QUEUED','IN_FLIGHT','ACCEPTED','UNKNOWN')",
            Long.class, tenant, deviceId);
        if (unresolved != null && unresolved != 0)
            throw ApiError.conflict("UNRESOLVED_DEVICE_INTENT", "设备存在未终态的写/控制指令；必须先取消（未外发）或完成关联回执核对，不能解绑或转移");
        var active = bindings.activeForDevice(tenant, deviceId);
        if (active.isEmpty()) throw ApiError.conflict("SOURCE_NOT_BOUND", "设备当前没有生效的来源关联，不能重复解绑");
        var binding = active.get();
        if (input.bindingId() != null && !binding.id().equals(input.bindingId()))
            throw ApiError.conflict("SOURCE_BINDING_CHANGED", "来源关联已变化，请刷新后重试");
        String fingerprint = idem.fingerprint(List.of(tenant, deviceId, binding.id(), input.expectedDeviceRevision(), input.reason()));
        DeviceServiceModels.SourceBinding replay = idem.replay(actor.user().id(), "device-source-unbind", key,
            fingerprint, DeviceServiceModels.SourceBinding.class);
        if (replay != null) return replay;
        long pending = bindings.pendingRawRecords(binding.externalId());
        ObjectNode risk = json.createObjectNode();
        risk.put("pending_raw_records", pending);
        risk.put("note", "解绑只释放当前生效关联；历史原始记录与未应用记录保持不变，重新绑定前不会归入其他设备");
        var released = bindings.release(binding.id(), actor.user().id(), input.reason().trim(), risk);
        jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=NULL,revision=revision+1 WHERE external_id=? AND local_device_id=?",
            binding.externalId(), deviceId);
        DeviceServiceModels.SourceBinding view = queries.bindingView(released);
        audit.recordReason(actor.user().id(), tenant, "DEVICE_SOURCE_UNBIND", binding.id(), "SUCCESS", requestId,
            input.reason() + "; pending_raw_records=" + pending);
        idem.save(actor.user().id(), "device-source-unbind", key, fingerprint, view);
        return view;
    }

    private DeviceServiceQueryService.DeviceContext lockedDevice(String actorId, String tenant, String deviceId) {
        var scope = scopes.predicate(actorId, "devices.tenant_id", "devices.id");
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.addAll(scope.bindings());
        args.add(deviceId);
        var rows = jdbc.query("SELECT id,tenant_id,name,device_eui,model,revision,product_version_id,archived_at FROM devices WHERE tenant_id=?" + scope.and() + " AND id=? FOR UPDATE",
            (rs, n) -> new DeviceServiceQueryService.DeviceContext(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class)),
            args.toArray());
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }
}
