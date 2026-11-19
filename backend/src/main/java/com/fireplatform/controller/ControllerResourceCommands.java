package com.fireplatform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.deviceservice.DeviceServiceQueryService;
import com.fireplatform.deviceservice.ServiceSchema;
import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Controller associations bind one action to one real device capability. They never accept a free-form relay
 * identifier, never synthesise a command, and only enable automatic use of a VERIFIED NORMAL typed COMMAND
 * service. The device-service worker remains the only sender.
 */
@Service
public class ControllerResourceCommands {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ManagementGuard guard;
    private final TenantFence tenants;
    private final DeviceAccessScope scopes;
    private final DeviceServiceQueryService services;
    private final ServiceSchema schema;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final DatabaseTime time;
    private final ControllerResourceQueries queries;

    public ControllerResourceCommands(JdbcTemplate jdbc, ObjectMapper json, ManagementGuard guard, TenantFence tenants,
                                      DeviceAccessScope scopes, DeviceServiceQueryService services, ServiceSchema schema,
                                      IdempotencyStore idem, AuditService audit, DatabaseTime time,
                                      ControllerResourceQueries queries) {
        this.jdbc = jdbc; this.json = json; this.guard = guard; this.tenants = tenants; this.scopes = scopes;
        this.services = services; this.schema = schema; this.idem = idem; this.audit = audit; this.time = time;
        this.queries = queries;
    }

    @Transactional
    public ControllerModels.Resource create(AuthService.Identity actor, String tenant, String key,
                                            ControllerInputs.Create input, String requestId) {
        guard.begin(actor, "controller:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, input.name(), input.deviceId(), input.action(),
            input.serviceKey(), input.argumentsJson(), input.reason()));
        var replay = idem.replay(actor.user().id(), "controller-resource-create", key, hash,
            ControllerModels.Resource.class);
        if (replay != null) return replay;
        assertDevice(actor.user().id(), tenant, input.deviceId());
        var resolved = resolve(actor.user().id(), tenant, input.deviceId(), input.serviceKey(), input.argumentsJson());
        String id = UUID.randomUUID().toString();
        var now = time.now();
        try {
            jdbc.update("INSERT INTO controller_resources(id,tenant_id,name,device_id,action,service_key,driver_key,"
                    + "driver_mode,service_definition_id,capability_revision,support_state,risk_level,result_kind,"
                    + "arguments_json,argument_keys_json,capability_evidence_json,enabled,state,active_key,revision,"
                    + "created_by,created_at,updated_by,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,FALSE,'ACTIVE',?,0,?,?,?,?)",
                id, tenant, input.name(), input.deviceId(), input.action(), input.serviceKey(), resolved.driverKey(),
                resolved.driverMode(), resolved.definitionId(), resolved.capabilityRevision(), resolved.supportState(),
                resolved.riskLevel(), resolved.resultKind(), resolved.arguments().toString(), resolved.argumentKeys(),
                resolved.evidence(), input.deviceId() + ":" + input.action(), actor.user().id(), now,
                actor.user().id(), now);
        } catch (DuplicateKeyException race) {
            throw ApiError.conflict("CONTROLLER_ACTION_ALREADY_BOUND",
                "该设备动作已存在有效控制器关联；不会自动替换");
        }
        audit.recordReason(actor.user().id(), tenant, "CONTROLLER_RESOURCE_CREATED", id, "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "controller-resource-create", key, hash, result);
        return result;
    }

    @Transactional
    public ControllerModels.Resource update(AuthService.Identity actor, String tenant, String id, String key,
                                            ControllerInputs.Update input, String requestId) {
        guard.begin(actor, "controller:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.name(),
            input.serviceKey(), input.argumentsJson(), input.reason()));
        var replay = idem.replay(actor.user().id(), "controller-resource-update", key, hash,
            ControllerModels.Resource.class);
        if (replay != null) return replay;
        var current = locked(tenant, id);
        revision(input.expectedRevision(), current.revision());
        if (!"ACTIVE".equals(current.state())) throw ApiError.conflict("CONTROLLER_RETIRED", "已停用的控制器不能修改");
        var resolved = resolve(actor.user().id(), tenant, current.deviceId(), input.serviceKey(), input.argumentsJson());
        if (current.enabled() && resolved.blockedCode() != null)
            throw new ApiError(409, resolved.blockedCode(), resolved.blockedReason());
        if (jdbc.update("UPDATE controller_resources SET name=?,service_key=?,driver_key=?,driver_mode=?,"
                + "service_definition_id=?,capability_revision=?,support_state=?,risk_level=?,result_kind=?,"
                + "arguments_json=?,argument_keys_json=?,capability_evidence_json=?,revision=revision+1,"
                + "updated_by=?,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",
            input.name(), input.serviceKey(), resolved.driverKey(), resolved.driverMode(), resolved.definitionId(),
            resolved.capabilityRevision(), resolved.supportState(), resolved.riskLevel(), resolved.resultKind(),
            resolved.arguments().toString(), resolved.argumentKeys(), resolved.evidence(), actor.user().id(),
            time.now(), id, tenant, input.expectedRevision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "控制器已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant, "CONTROLLER_RESOURCE_UPDATED", id, "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "controller-resource-update", key, hash, result);
        return result;
    }

    @Transactional
    public ControllerModels.Resource setEnabled(AuthService.Identity actor, String tenant, String id, String key,
                                                ControllerInputs.Enabled input, String requestId) {
        guard.begin(actor, "controller:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.enabled(), input.reason()));
        var current = locked(tenant, id);
        if (!scopes.contains(actor.user().id(), tenant, current.deviceId())) throw ApiError.missing();
        var replay = idem.replay(actor.user().id(), "controller-resource-enabled", key, hash,
            ControllerModels.Resource.class);
        if (replay != null) return replay;
        revision(input.expectedRevision(), current.revision());
        if (input.enabled()) {
            var resolved = resolve(actor.user().id(), tenant, current.deviceId(), current.serviceKey(), storedArguments(tenant, id));
            if (resolved.blockedCode() != null)
                throw new ApiError(409, resolved.blockedCode(), resolved.blockedReason());
        }
        if (jdbc.update("UPDATE controller_resources SET enabled=?,revision=revision+1,updated_by=?,updated_at=?"
                + " WHERE id=? AND tenant_id=? AND revision=?",
            input.enabled(), actor.user().id(), time.now(), id, tenant, input.expectedRevision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "控制器已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant,
            input.enabled() ? "CONTROLLER_ENABLED" : "CONTROLLER_DISABLED", id, "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "controller-resource-enabled", key, hash, result);
        return result;
    }

    @Transactional
    public ControllerModels.Resource retire(AuthService.Identity actor, String tenant, String id, String key,
                                            ControllerInputs.Versioned input, String requestId) {
        guard.begin(actor, "controller:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.reason()));
        var current = locked(tenant, id);
        if (!scopes.contains(actor.user().id(), tenant, current.deviceId())) throw ApiError.missing();
        var replay = idem.replay(actor.user().id(), "controller-resource-retire", key, hash,
            ControllerModels.Resource.class);
        if (replay != null) return replay;
        revision(input.expectedRevision(), current.revision());
        if (jdbc.update("UPDATE controller_resources SET state='RETIRED',active_key=NULL,revision=revision+1,"
                + "updated_by=?,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",
            actor.user().id(), time.now(), id, tenant, input.expectedRevision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "控制器已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant, "CONTROLLER_RESOURCE_RETIRED", id, "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "controller-resource-retire", key, hash, result);
        return result;
    }

    /** Import-only: disabled draft; no automatic enabling and no external effect. */
    @Transactional
    public ControllerModels.Resource importController(AuthService.Identity actor, String tenant,
                                                      ControllerInputs.Create input, String reason, String requestId) {
        assertDevice(actor.user().id(), tenant, input.deviceId());
        var resolved = resolve(actor.user().id(), tenant, input.deviceId(), input.serviceKey(), input.argumentsJson());
        var existing = jdbc.queryForList("SELECT id FROM controller_resources WHERE tenant_id=? AND device_id=?"
            + " AND action=? AND state='ACTIVE'", String.class, tenant, input.deviceId(), input.action());
        if (!existing.isEmpty())
            throw ApiError.conflict("CONTROLLER_ACTION_ALREADY_BOUND", "该设备动作已有控制器关联；导入不会覆盖");
        String id = UUID.randomUUID().toString();
        var now = time.now();
        jdbc.update("INSERT INTO controller_resources(id,tenant_id,name,device_id,action,service_key,driver_key,"
                + "driver_mode,service_definition_id,capability_revision,support_state,risk_level,result_kind,"
                + "arguments_json,argument_keys_json,capability_evidence_json,enabled,state,active_key,revision,"
                + "created_by,created_at,updated_by,updated_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,FALSE,'ACTIVE',?,0,?,?,?,?)",
            id, tenant, input.name(), input.deviceId(), input.action(), input.serviceKey(), resolved.driverKey(),
            resolved.driverMode(), resolved.definitionId(), resolved.capabilityRevision(), resolved.supportState(),
            resolved.riskLevel(), resolved.resultKind(), resolved.arguments().toString(), resolved.argumentKeys(),
            resolved.evidence(), input.deviceId() + ":" + input.action(), actor.user().id(), now,
            actor.user().id(), now);
        audit.recordReason(actor.user().id(), tenant, "CONTROLLER_RESOURCE_IMPORTED", id, "SUCCESS", requestId, reason);
        return queries.byId(tenant, id);
    }

    // ---------------- capability resolution ----------------

    public record Resolved(String driverKey, String driverMode, String definitionId, long capabilityRevision,
                           String supportState, String riskLevel, String resultKind, ObjectNode arguments,
                           String argumentKeys, String evidence, String blockedCode, String blockedReason) { }

    /** Resolves the live capability and validates declarative arguments. Never invents an operation. */
    public Resolved resolve(String actorId, String tenant, String deviceId, String serviceKey, String argumentsJson) {
        var device = services.device(actorId, tenant, deviceId);
        var resolution = services.resolve(device);
        var resolved = resolution.services().get(serviceKey);
        if (resolved == null)
            throw ApiError.conflict("SERVICE_NOT_AVAILABLE", "当前设备能力中不存在该服务操作；不能借用其他版本或驱动");
        var view = resolved.view();
        ObjectNode arguments;
        try {
            JsonNode raw = json.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
            arguments = schema.validateArguments(raw, resolved.fields());
        } catch (ApiError invalid) {
            throw invalid;
        } catch (Exception unreadable) {
            throw ApiError.bad("控制器参数必须是有效JSON对象");
        }
        return new Resolved(resolution.driverKey(), resolution.mode(), view.id(), view.revision(),
            view.supportState(), view.riskLevel(), view.resultKind(), arguments,
            argumentKeys(arguments), evidence(view.evidence()),
            blockedCode(resolved, device.archivedAt() != null, arguments),
            blockedReason(blockedCode(resolved, device.archivedAt() != null, arguments)));
    }

    private static String blockedReason(String code) {
        if (code == null) return null;
        return switch (code) {
            case "DEVICE_ARCHIVED" -> "设备已归档";
            case "SERVICE_NOT_AVAILABLE" -> "服务定义未发布或不可用";
            case "CAPABILITY_UNVERIFIED" -> "能力证据未验证";
            case "ENGINEERING_REFUSED" -> "工程/高风险操作不能用于自动联动";
            case "OPERATION_NOT_AVAILABLE" -> "只读查询不能作为控制器动作";
            case "RESULT_KIND_UNSAFE" -> "非只读操作必须登记为COMMAND结果类型";
            case "SENSITIVE_ARGUMENTS_NOT_ALLOWED" -> "敏感参数不能保存在联动配置中";
            default -> "当前能力不能用于自动联动";
        };
    }

    private String storedArguments(String tenant, String id) {
        var rows = jdbc.queryForList("SELECT arguments_json FROM controller_resources WHERE tenant_id=? AND id=?",
            String.class, tenant, id);
        return rows.isEmpty() ? "{}" : rows.getFirst();
    }

    private String blockedCode(DeviceServiceQueryService.ResolvedService resolved, boolean archived, ObjectNode arguments) {
        var view = resolved.view();
        if (archived) return "DEVICE_ARCHIVED";
        if (!"PUBLISHED".equals(view.status())) return "SERVICE_NOT_AVAILABLE";
        if (!"VERIFIED".equals(view.supportState())) return "CAPABILITY_UNVERIFIED";
        if (!"NORMAL".equals(view.riskLevel())) return "ENGINEERING_REFUSED";
        if ("QUERY".equals(view.category())) return "OPERATION_NOT_AVAILABLE";
        if (!"COMMAND".equals(view.resultKind())) return "RESULT_KIND_UNSAFE";
        if (resolved.descriptor() == null || !resolved.descriptor().typedNormalAllowed())
            return "ENGINEERING_REFUSED";
        if (resolved.blockedCode() != null) return resolved.blockedCode();
        boolean sensitive = false;
        try {
            for (var field : resolved.fields())
                if (field.sensitive() && arguments.has(field.key())) sensitive = true;
        } catch (RuntimeException ignored) {
            return "INVALID_ARGUMENTS";
        }
        if (sensitive) return "SENSITIVE_ARGUMENTS_NOT_ALLOWED";
        return null;
    }

    private void assertDevice(String actorId, String tenant, String deviceId) {
        var rows = jdbc.query("SELECT archived_at FROM devices WHERE id=? AND tenant_id=?",
            (rs, n) -> rs.getObject(1, OffsetDateTime.class), deviceId, tenant);
        if (rows.isEmpty()) throw ApiError.conflict("DEVICE_NOT_IN_TENANT", "设备不存在或不属于当前单位");
        if (rows.getFirst() != null) throw ApiError.conflict("DEVICE_ARCHIVED", "已归档设备不能建立控制器关联");
        if (!scopes.contains(actorId, tenant, deviceId)) throw ApiError.forbidden("设备不在当前操作者的设备范围内");
    }

    private ControllerModels.Resource locked(String tenant, String id) {
        var rows = jdbc.query("SELECT * FROM controller_resources WHERE id=? AND tenant_id=? FOR UPDATE",
            (rs, n) -> new ControllerModels.Resource(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("name"), rs.getString("device_id"), null, null, rs.getString("action"),
                rs.getString("service_key"), rs.getString("driver_key"), rs.getString("driver_mode"),
                rs.getString("service_definition_id"), rs.getLong("capability_revision"),
                rs.getString("support_state"), rs.getString("risk_level"), rs.getString("result_kind"),
                rs.getString("arguments_json"), rs.getBoolean("enabled"), rs.getString("state"), rs.getLong("revision"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)),
            id, tenant);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private static String argumentKeys(ObjectNode arguments) {
        var keys = new ArrayList<String>();
        arguments.fieldNames().forEachRemaining(keys::add);
        return String.join(",", keys);
    }

    private static String evidence(JsonNode evidence) {
        return evidence == null || evidence.isNull() ? "{}" : evidence.toString();
    }

    private static void revision(long expected, long actual) {
        if (expected != actual) throw ApiError.conflict("REVISION_CONFLICT", "数据已变化，请刷新后重试");
    }
}
