package com.fireplatform.deviceservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.deviceservice.IntegrationDriver.DriverOutcome;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.CoordinationFence;
import com.fireplatform.identity.ExecutionAuthorization;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.Digests;
import com.fireplatform.shared.IdempotencyStore;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.PageSizePolicy;
import com.fireplatform.shared.Pagination;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable command center. A command is persisted QUEUED, claimed to IN_FLIGHT before any network call,
 * and only a documented correlated receipt may move it to SUCCEEDED/FAILED. Nothing here ever retries an
 * uncertain send automatically.
 */
@Service
public class DeviceServiceCommandService {
    private static final int STALE_IN_FLIGHT_SECONDS = 300;
    private static final String CLOUD_DELETE = "cloud.delete";

    private record CommandRow(String id, String tenantId, String deviceId, String driverKey, String driverMode,
                              String serviceKey, String serviceDefinitionId, String riskLevel, String resultKind,
                              String state, long statusVersion, String argumentsJson, String payloadSha256,
                              String snapshotJson, String actorId, String reason, String acceptedReference,
                              String vendorCode, String resultJson, String nextAction, OffsetDateTime acceptedAt,
                              OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                              OffsetDateTime updatedAt) { }
    private record Claimed(CommandRow row, int attemptNo) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DriverRegistry drivers;
    private final DeviceServiceQueryService queries;
    private final DeviceServiceSecrets secrets;
    private final DeviceSourceBindingService bindings;
    private final ServiceSchema schema;
    private final ManagementGuard guard;
    private final CoordinationFence coordination;
    private final AuditService audit;
    private final IdempotencyStore idem;
    private final PageSizePolicy sizes;
    private final DeviceAccessScope scopes;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final TenantFence tenantFence;
    private final ExecutionAuthorization executionAuthorization;
    public DeviceServiceCommandService(JdbcTemplate jdbc, ObjectMapper json, DriverRegistry drivers,
                                       DeviceServiceQueryService queries, DeviceServiceSecrets secrets,
                                       DeviceSourceBindingService bindings, ServiceSchema schema,
                                       ManagementGuard guard, CoordinationFence coordination, AuditService audit,
                                       IdempotencyStore idem, PageSizePolicy sizes, DeviceAccessScope scopes,
                                       Clock clock, TransactionTemplate transactions,
                                       ExecutionAuthorization executionAuthorization, TenantFence tenantFence) {
        this.jdbc = jdbc;
        this.json = json;
        this.drivers = drivers;
        this.queries = queries;
        this.secrets = secrets;
        this.bindings = bindings;
        this.schema = schema;
        this.guard = guard;
        this.coordination = coordination;
        this.audit = audit;
        this.idem = idem;
        this.sizes = sizes;
        this.scopes = scopes;
        this.clock = clock;
        this.transactions = transactions;
        this.executionAuthorization = executionAuthorization;
        this.tenantFence = tenantFence;
    }

    public record AutomationEnqueue(String tenantId, String deviceId, String serviceKey, JsonNode arguments,
                                    String reason, String automationSource, String idemKey) { }
    public record AutomationResult(String commandId, String state, String blockedCode, String blockedReason) { }

    /**
     * Narrow system seam for resource-linkage/controller evaluation. Joins the caller's transaction, never
     * performs I/O, and only queues verified NORMAL typed controller services (no engineering downgrade).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AutomationResult enqueueAutomation(AutomationEnqueue request) {
        if (request == null || request.tenantId() == null || request.deviceId() == null || request.serviceKey() == null)
            throw ApiError.bad("自动指令参数不完整");
        String source = request.automationSource() == null ? "" : request.automationSource();
        if (source.length() > 200 || !(source.startsWith("RESOURCE_LINKAGE:") || source.startsWith("CONTROLLER:")))
            throw ApiError.bad("自动指令来源必须为 RESOURCE_LINKAGE: 或 CONTROLLER:");
        if (request.reason() == null || request.reason().isBlank() || request.reason().length() > 1000)
            throw ApiError.bad("自动指令原因无效");
        if (request.idemKey() == null || !request.idemKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw ApiError.bad("自动指令幂等键无效");
        var tenantEnabled = jdbc.query("SELECT enabled FROM tenants WHERE id=?", (rs, n) -> rs.getBoolean(1), request.tenantId());
        if (tenantEnabled.isEmpty() || !tenantEnabled.getFirst())
            return new AutomationResult(null, null, "TENANT_DISABLED", "单位不存在或已停用");
        var device = queries.systemDevice(request.tenantId(), request.deviceId());
        if (device.archivedAt() != null)
            return new AutomationResult(null, null, "DEVICE_ARCHIVED", "设备已归档，不能自动下发");
        DeviceServiceQueryService.Resolution resolution = queries.resolve(device);
        DeviceServiceQueryService.ResolvedService resolved = resolution.services().get(request.serviceKey());
        if (resolved == null)
            return new AutomationResult(null, null, "SERVICE_NOT_AVAILABLE", "当前设备能力中不存在该服务操作");
        var view = resolved.view();
        if (!"PUBLISHED".equals(view.status()))
            return new AutomationResult(null, null, "SERVICE_NOT_AVAILABLE", "服务定义未发布");
        if (!"VERIFIED".equals(view.supportState()))
            return new AutomationResult(null, null, "CAPABILITY_UNVERIFIED", "能力证据未验证");
        if (!"NORMAL".equals(view.riskLevel()))
            return new AutomationResult(null, null, "ENGINEERING_REFUSED", "高风险操作不能由自动联动发起");
        if ("QUERY".equals(view.category()))
            return new AutomationResult(null, null, "OPERATION_NOT_AVAILABLE", "自动联动不能发起只读查询");
        if (!"COMMAND".equals(view.resultKind()))
            return new AutomationResult(null, null, "RESULT_KIND_UNSAFE", "自动联动操作必须为COMMAND结果类型");
        if (resolved.descriptor() == null || !resolved.descriptor().typedNormalAllowed())
            return new AutomationResult(null, null, "ENGINEERING_REFUSED", "仅受信类型化普通操作允许自动发起");
        if (resolved.blockedCode() != null)
            return new AutomationResult(null, null, resolved.blockedCode(), resolved.blockedReason());
        ObjectNode canonical;
        try { canonical = schema.validateArguments(request.arguments() == null ? json.createObjectNode() : request.arguments(), resolved.fields()); }
        catch (ApiError invalid) { return new AutomationResult(null, null, "INVALID_ARGUMENTS", invalid.getMessage()); }
        String hash = Digests.sha256(source + "\n" + request.tenantId() + "\n" + request.deviceId() + "\n"
            + request.serviceKey() + "\n" + canonical);
        var existing = jdbc.query("SELECT command_id,request_hash FROM device_service_automation_intents WHERE tenant_id=? AND idem_key=?",
            (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, request.tenantId(), request.idemKey());
        if (!existing.isEmpty()) {
            if (!existing.getFirst()[1].equals(hash))
                throw ApiError.conflict("AUTOMATION_IDEMPOTENCY_CONFLICT", "自动指令幂等键已用于不同内容");
            return automationState(request.tenantId(), existing.getFirst()[0]);
        }
        String commandId = UUID.randomUUID().toString();
        ObjectNode stored = secrets.protectArguments(commandId, canonical, resolved.fields());
        String payloadHash = Digests.sha256(canonical.toString());
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("tenant_id", request.tenantId()).put("device_id", request.deviceId()).put("device_revision", device.revision());
        snapshot.put("device_eui", device.eui()).put("device_model", device.model());
        snapshot.put("mode", resolution.mode()).put("driver_key", resolution.driverKey());
        snapshot.put("service_key", view.serviceKey()).put("result_kind", view.resultKind()).put("risk_level", "NORMAL");
        snapshot.put("definition_id", view.id()).put("definition_revision", view.revision());
        snapshot.put("product_version_id", device.productVersionId());
        if (resolution.binding() != null) {
            snapshot.put("binding_id", resolution.binding().id()).put("binding_generation", resolution.binding().generation())
                .put("external_device_id", resolution.binding().externalId()).put("external_serial", resolution.binding().serial());
        } else {
            snapshot.putNull("binding_id").putNull("binding_generation").putNull("external_device_id").putNull("external_serial");
        }
        snapshot.put("account_fingerprint", resolution.accountFingerprint());
        snapshot.put("origin", "AUTOMATION").put("automation_source", source).put("payload_sha256", payloadHash)
            .put("created_at", OffsetDateTime.now(clock).toString());
        snapshot.set("input_schema", view.inputSchema());
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            jdbc.update("INSERT INTO device_service_commands(id,tenant_id,device_id,driver_key,driver_mode,service_key,service_definition_id,risk_level,result_kind,state,status_version,arguments_json,payload_sha256,snapshot_json,actor_id,reason,idem_key,request_hash,origin,automation_source,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'NORMAL','COMMAND','QUEUED',0,?,?,?,NULL,?,?,?,'AUTOMATION',?,?,?)",
                commandId, request.tenantId(), request.deviceId(), resolution.driverKey() == null ? "NONE" : resolution.driverKey(),
                resolution.mode(), request.serviceKey(), view.id(), stored.toString(), payloadHash, snapshot.toString(),
                request.reason().trim(), request.idemKey(), hash, source, now, now);
            jdbc.update("INSERT INTO device_service_automation_intents(tenant_id,idem_key,command_id,request_hash,automation_source,created_at) VALUES(?,?,?,?,?,?)",
                request.tenantId(), request.idemKey(), commandId, hash, source, now);
        } catch (DuplicateKeyException race) {
            // A concurrent identical intent aborts this transaction; the caller retries and the pre-check
            // above returns the original command. Do not query an aborted PostgreSQL transaction here.
            throw ApiError.conflict("AUTOMATION_IDEMPOTENCY_RACE", "同一自动指令正在创建，请重试读取原命令");
        }
        audit.record(null, request.tenantId(), "DEVICE_SERVICE_COMMAND_AUTOMATION_QUEUED", commandId, "SUCCESS", commandId);
        return new AutomationResult(commandId, "QUEUED", null, null);
    }

    public AutomationResult automationState(String tenantId, String commandId) {
        var rows = jdbc.query("SELECT state FROM device_service_commands WHERE tenant_id=? AND id=?",
            (rs, n) -> rs.getString(1), tenantId, commandId);
        if (rows.isEmpty()) throw ApiError.missing();
        return new AutomationResult(commandId, rows.getFirst(), null, null);
    }

    // ---------------- create ----------------

    public DeviceServiceModels.CommandDetail create(AuthService.Identity actor, String audience, String tenant, String deviceId,
                                                    String key, DeviceServiceInputs.CreateCommand input, String requestId) {
        var device = queries.device(actor.user().id(), tenant, deviceId);
        var resolution = queries.resolve(device);
        var resolved = resolution.services().get(input.serviceKey());
        if (resolved == null) throw ApiError.bad("当前设备能力中不存在该服务操作，不能借用其他版本或驱动的操作");
        boolean nonQuery = !"QUERY".equals(resolved.view().category());
        if (nonQuery && !"COMMAND".equals(resolved.view().resultKind()))
            throw ApiError.conflict("SERVICE_RESULT_KIND_UNSAFE", "非只读操作必须登记为COMMAND结果类型，不能以STATUS绕过未决写入");
        boolean typedNormal = resolved.descriptor() != null && resolved.descriptor().typedNormalAllowed();
        boolean engineering = "ENGINEERING".equals(resolved.view().riskLevel()) || (nonQuery && !typedNormal);
        if (engineering && !"management".equals(audience))
            throw ApiError.forbidden("工程操作仅限平台管理入口");
        if (engineering && !input.riskConfirmed())
            throw ApiError.conflict("RISK_CONFIRMATION_REQUIRED", "高风险操作必须显式确认风险与证据");
        String permission = engineering ? "device-services:engineering" : "device-services:execute";
        return transactions.execute(status ->
            createLocked(actor, audience, tenant, deviceId, key, input, permission, engineering, requestId));
    }

    private DeviceServiceModels.CommandDetail createLocked(AuthService.Identity actor, String audience, String tenant, String deviceId,
                                                           String key, DeviceServiceInputs.CreateCommand input,
                                                           String permission, boolean engineering, String requestId) {
        guard.begin(actor, permission, tenant, audience);
        idem.validateKey(key);
        var device = lockedDevice(actor.user().id(), tenant, deviceId);
        var resolution = queries.resolve(device);
        var resolved = resolution.services().get(input.serviceKey());
        if (resolved == null) throw ApiError.conflict("SERVICE_NOT_AVAILABLE", "服务能力已变化，请刷新后重试");
        boolean typedNormal = resolved.descriptor() != null && resolved.descriptor().typedNormalAllowed();
        boolean effectiveEngineering = engineering || (!"QUERY".equals(resolved.view().category()) && !typedNormal);
        if (!"QUERY".equals(resolved.view().category()) && !"COMMAND".equals(resolved.view().resultKind()))
            throw ApiError.conflict("SERVICE_RESULT_KIND_UNSAFE", "非只读操作必须登记为COMMAND结果类型，不能以STATUS绕过未决写入");
        if (effectiveEngineering) {
            if (!"management".equals(audience)) throw ApiError.forbidden("工程操作仅限平台管理入口");
            guard.requireCurrent(actor, "device-services:engineering", tenant, audience);
        }
        if (device.revision() != input.expectedDeviceRevision())
            throw ApiError.conflict("REVISION_CONFLICT", "设备已更新，请刷新后重试");
        if (input.capabilityRevision() != null && input.capabilityRevision() != resolved.view().revision())
            throw ApiError.conflict("CAPABILITY_CHANGED", "服务能力已更新，请刷新后重试");
        if (input.sourceBindingId() != null && (resolution.binding() == null || !resolution.binding().id().equals(input.sourceBindingId())))
            throw ApiError.conflict("SOURCE_BINDING_CHANGED", "来源关联已变化，请刷新后重试");
        if (resolved.blockedCode() != null)
            throw new ApiError(409, resolved.blockedCode(), resolved.blockedReason() == null ? "当前不可下发" : resolved.blockedReason());
        if (device.archivedAt() != null) throw ApiError.conflict("DEVICE_ARCHIVED", "设备已归档，不能下发命令");
        ObjectNode canonical = schema.validateArguments(input.arguments(), resolved.fields());
        String commandId = UUID.randomUUID().toString();
        ObjectNode stored = secrets.protectArguments(commandId, canonical, resolved.fields());
        String payloadHash = Digests.sha256(canonical.toString());
        String fingerprint = idem.fingerprint(List.of(tenant, deviceId, input.serviceKey(), input.expectedDeviceRevision(),
            String.valueOf(input.capabilityRevision()), String.valueOf(input.sourceBindingId()), input.reason(), canonical.toString()));
        DeviceServiceModels.IdResult replay = idem.replay(actor.user().id(), "device-service-command", key, fingerprint,
            DeviceServiceModels.IdResult.class);
        if (replay != null) return detail(actor.user().id(), tenant, replay.id());

        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("tenant_id", tenant).put("device_id", deviceId).put("device_revision", device.revision());
        snapshot.put("device_eui", device.eui()).put("device_model", device.model());
        snapshot.put("mode", resolution.mode()).put("driver_key", resolution.driverKey());
        snapshot.put("service_key", resolved.view().serviceKey()).put("result_kind", resolved.view().resultKind());
        snapshot.put("risk_level", effectiveEngineering ? "ENGINEERING" : "NORMAL").put("definition_id", resolved.view().id());
        snapshot.put("definition_revision", resolved.view().revision());
        snapshot.put("product_version_id", device.productVersionId());
        if (resolution.binding() != null) {
            snapshot.put("binding_id", resolution.binding().id())
                .put("binding_generation", resolution.binding().generation())
                .put("external_device_id", resolution.binding().externalId())
                .put("external_serial", resolution.binding().serial());
        } else {
            snapshot.putNull("binding_id").putNull("binding_generation")
                .putNull("external_device_id").putNull("external_serial");
        }
        snapshot.put("account_fingerprint", resolution.accountFingerprint());
        snapshot.put("actor_id", actor.user().id()).put("payload_sha256", payloadHash)
            .put("origin", "OPERATOR").put("audience", audience).put("required_permission", permission)
            .put("actor_revision", currentRevision(actor.user().id()))
            .put("created_at", OffsetDateTime.now(clock).toString());
        snapshot.set("input_schema", resolved.view().inputSchema());
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            jdbc.update("INSERT INTO device_service_commands(id,tenant_id,device_id,driver_key,driver_mode,service_key,service_definition_id,risk_level,result_kind,state,status_version,arguments_json,payload_sha256,snapshot_json,actor_id,reason,idem_key,request_hash,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',0,?,?,?,?,?,?,?,?,?)",
                commandId, tenant, deviceId, resolution.driverKey() == null ? "NONE" : resolution.driverKey(), resolution.mode(),
                input.serviceKey(), resolved.view().id(), effectiveEngineering ? "ENGINEERING" : "NORMAL", resolved.view().resultKind(),
                stored.toString(), payloadHash, snapshot.toString(), actor.user().id(), input.reason().trim(), key,
                fingerprint, now, now);
        } catch (DuplicateKeyException race) {
            throw ApiError.conflict("IDEMPOTENCY_CONFLICT", "该幂等键正在创建命令，请刷新后查看");
        }
        audit.recordReason(actor.user().id(), tenant, "DEVICE_SERVICE_COMMAND_QUEUED", commandId, "SUCCESS", requestId, input.reason());
        idem.save(actor.user().id(), "device-service-command", key, fingerprint, new DeviceServiceModels.IdResult(commandId));
        return detail(actor.user().id(), tenant, commandId);
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

    // ---------------- read ----------------

    public Page<DeviceServiceModels.CommandSummary> page(String actorId, String tenant, String deviceId, int page, Integer size) {
        var device = queries.device(actorId, tenant, deviceId);
        var pagination = Pagination.of(page, size, sizes.defaultPageSize());
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM device_service_commands WHERE tenant_id=? AND device_id=?",
            Long.class, device.tenantId(), device.id());
        var items = jdbc.query("SELECT * FROM device_service_commands WHERE tenant_id=? AND device_id=? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            (rs, n) -> summary(row(rs)), device.tenantId(), device.id(), pagination.size(), pagination.offset());
        return new Page<>(items, total, pagination.page(), pagination.size());
    }

    public DeviceServiceModels.CommandDetail detail(String actorId, String tenant, String commandId) {
        var rows = jdbc.query("SELECT * FROM device_service_commands WHERE tenant_id=? AND id=?",
            (rs, n) -> row(rs), tenant, commandId);
        if (rows.isEmpty()) throw ApiError.missing();
        CommandRow row = rows.getFirst();
        queries.device(actorId, tenant, row.deviceId());
        ObjectNode snapshot = parseObject(row.snapshotJson());
        ObjectNode stored = parseObject(row.argumentsJson());
        List<ServiceSchema.Field> fields;
        try { fields = schema.parse(snapshot.path("input_schema")); }
        catch (ApiError invalid) { fields = List.of(); }
        ObjectNode masked = stored.deepCopy();
        for (ServiceSchema.Field field : fields) if (field.sensitive() && masked.has(field.key())) masked.put(field.key(), "******");
        List<DeviceServiceModels.Attempt> attempts = jdbc.query("SELECT * FROM device_service_command_attempts WHERE command_id=? ORDER BY attempt_no",
            (rs, n) -> new DeviceServiceModels.Attempt(rs.getString("id"), rs.getString("command_id"), rs.getInt("attempt_no"),
                rs.getString("phase"), rs.getString("outcome"), rs.getString("vendor_code"),
                parseNullable(rs.getString("detail_json")),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class)), commandId);
        return new DeviceServiceModels.CommandDetail(summary(row), masked, snapshot,
            parseNullable(row.resultJson()), row.actorId(), row.reason(), row.payloadSha256(),
            row.startedAt(), row.updatedAt(), attempts);
    }

    // ---------------- cancel ----------------

    @Transactional
    public DeviceServiceModels.CommandDetail cancel(AuthService.Identity actor, String audience, String tenant, String commandId,
                                                   DeviceServiceInputs.CancelCommand input, String requestId) {
        guard.begin(actor, "device-services:execute", tenant, audience);
        CommandRow row = requireForUpdate(tenant, commandId);
        queries.device(actor.user().id(), tenant, row.deviceId());
        if ("CANCELLED".equals(row.state())) return detail(actor.user().id(), tenant, commandId);
        if (!"QUEUED".equals(row.state()))
            throw ApiError.conflict("COMMAND_ALREADY_SENT", "命令已进入外部发送或已结束，不能撤销；外部结果必须显式核对");
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (jdbc.update("UPDATE device_service_commands SET state='CANCELLED',status_version=status_version+1,finished_at=?,updated_at=? WHERE id=? AND state='QUEUED' AND status_version=?",
                now, now, commandId, row.statusVersion()) != 1)
            throw ApiError.conflict("COMMAND_STATE_CHANGED", "命令状态已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant, "DEVICE_SERVICE_COMMAND_CANCELLED", commandId, "SUCCESS", requestId, input.reason());
        return detail(actor.user().id(), tenant, commandId);
    }

    // ---------------- cloud delete lifecycle command ----------------

    @Transactional
    public DeviceServiceModels.CommandDetail createCloudDelete(AuthService.Identity actor, String tenant, String deviceId,
                                                               String key, DeviceServiceInputs.CloudDelete input,
                                                               String requestId) {
        guard.begin(actor, "device:write", tenant, "management");
        guard.requireCurrent(actor, "device-services:engineering", tenant, "management");
        if (!input.riskConfirmed()) throw ApiError.conflict("RISK_CONFIRMATION_REQUIRED", "云端删除是高风险操作，必须显式确认");
        idem.validateKey(key);
        var device = lockedDevice(actor.user().id(), tenant, deviceId);
        if (device.revision() != input.expectedDeviceRevision())
            throw ApiError.conflict("REVISION_CONFLICT", "设备已更新，请刷新后重试");
        Long unresolved = jdbc.queryForObject("SELECT COUNT(*) FROM device_service_commands WHERE tenant_id=? AND device_id=? AND result_kind='COMMAND' AND state IN ('QUEUED','IN_FLIGHT','ACCEPTED','UNKNOWN')",
            Long.class, tenant, deviceId);
        if (unresolved != null && unresolved != 0)
            throw ApiError.conflict("UNRESOLVED_DEVICE_INTENT", "设备存在未终态的写/控制指令；必须先取消或完成回执核对，云端删除不能绕过未决写入");
        var history = bindings.historyForDevice(tenant, deviceId);
        if (history.isEmpty()) throw ApiError.conflict("SOURCE_NOT_BOUND", "设备没有可追溯的来源关联，不能发起云端删除");
        var binding = history.getFirst();
        String external = input.externalDeviceId() == null || input.externalDeviceId().isBlank()
            ? binding.externalId() : input.externalDeviceId().trim();
        String serial = input.externalSerial() == null || input.externalSerial().isBlank()
            ? binding.serial() : input.externalSerial().trim();
        if (!binding.externalId().equals(external))
            throw ApiError.conflict("EXTERNAL_ID_MISMATCH", "只允许删除当前来源关联记录的云设备；不能指定其他云记录");
        IntegrationDriver driver = drivers.require(binding.driverKey());
        var descriptor = driver.services().stream().filter(service -> CLOUD_DELETE.equals(service.key())).findFirst()
            .orElseThrow(() -> ApiError.conflict("OPERATION_NOT_IMPLEMENTED", "该驱动没有实现云端删除操作"));
        if (!driver.configuration().configured())
            throw new ApiError(409, driver.configuration().code(), "接入驱动未启用或凭证不可用，未创建命令");
        String account = driver.accountFingerprint();
        if (binding.accountFingerprint() == null || account == null)
            throw ApiError.conflict("SOURCE_ACCOUNT_UNCONFIRMED", "来源关联缺少原账号证据，不能借用当前账号删除历史台账；请先核对原来源");
        if (!binding.accountFingerprint().equals(account))
            throw ApiError.conflict("ACCOUNT_CHANGED", "当前接入账号与该来源关联的原账号不一致，不能向新账号发送历史台账删除");
        ObjectNode canonical = json.createObjectNode().put("externalDeviceId", external);
        if (serial != null && !serial.isBlank()) canonical.put("serial", serial);
        canonical = schema.validateArguments(canonical, schema.parse(descriptor.inputSchema()));
        String commandId = UUID.randomUUID().toString();
        ObjectNode stored = secrets.protectArguments(commandId, canonical, schema.parse(descriptor.inputSchema()));
        String payloadHash = Digests.sha256(canonical.toString());
        String fingerprint = idem.fingerprint(List.of(tenant, deviceId, external, input.expectedDeviceRevision(), input.reason(), canonical.toString()));
        DeviceServiceModels.IdResult replay = idem.replay(actor.user().id(), "device-service-cloud-delete", key, fingerprint,
            DeviceServiceModels.IdResult.class);
        if (replay != null) return detail(actor.user().id(), tenant, replay.id());

        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("tenant_id", tenant).put("device_id", deviceId).put("device_revision", device.revision());
        snapshot.put("device_eui", device.eui()).put("device_model", device.model());
        snapshot.put("mode", "VENDOR_PLATFORM").put("driver_key", driver.key());
        snapshot.put("service_key", CLOUD_DELETE).put("result_kind", "COMMAND").put("risk_level", "ENGINEERING");
        snapshot.putNull("definition_id").put("definition_revision", 0).putNull("product_version_id");
        snapshot.put("binding_id", binding.id()).put("binding_generation", binding.generation())
            .put("external_device_id", external);
        if (serial == null) snapshot.putNull("external_serial"); else snapshot.put("external_serial", serial);
        snapshot.put("account_fingerprint", account);
        snapshot.put("actor_id", actor.user().id()).put("payload_sha256", payloadHash)
            .put("origin", "OPERATOR").put("audience", "management")
            .put("required_permission", "device-services:engineering")
            .put("actor_revision", currentRevision(actor.user().id()))
            .put("created_at", OffsetDateTime.now(clock).toString());
        snapshot.set("input_schema", descriptor.inputSchema());
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            jdbc.update("INSERT INTO device_service_commands(id,tenant_id,device_id,driver_key,driver_mode,service_key,service_definition_id,risk_level,result_kind,state,status_version,arguments_json,payload_sha256,snapshot_json,actor_id,reason,idem_key,request_hash,created_at,updated_at) VALUES(?,?,?,?,'VENDOR_PLATFORM',?,NULL,'ENGINEERING','COMMAND','QUEUED',0,?,?,?,?,?,?,?,?,?)",
                commandId, tenant, deviceId, driver.key(), CLOUD_DELETE, stored.toString(), payloadHash,
                snapshot.toString(), actor.user().id(), input.reason().trim(), key, fingerprint, now, now);
        } catch (DuplicateKeyException race) {
            throw ApiError.conflict("IDEMPOTENCY_CONFLICT", "该幂等键正在创建命令，请刷新后查看");
        }
        audit.recordReason(actor.user().id(), tenant, "DEVICE_CLOUD_DELETE_QUEUED", commandId, "SUCCESS", requestId, input.reason());
        idem.save(actor.user().id(), "device-service-cloud-delete", key, fingerprint, new DeviceServiceModels.IdResult(commandId));
        return detail(actor.user().id(), tenant, commandId);
    }

    // ---------------- reconcile ----------------

    public DeviceServiceModels.CommandDetail reconcile(AuthService.Identity actor, String audience, String tenant, String commandId,
                                                       DeviceServiceInputs.ReconcileCommand input, String requestId) {
        Claimed claimed = transactions.execute(status -> prepareReconcile(actor, audience, tenant, commandId, input));
        if (claimed == null) throw new IllegalStateException("Reconcile preparation failed");
        DriverOutcome outcome = invoke(claimed.row(), claimed.row().acceptedReference(), true);
        transactions.executeWithoutResult(status -> finish(claimed, outcome, "QUERY"));
        audit.recordReason(actor.user().id(), tenant, "DEVICE_SERVICE_COMMAND_RECONCILED", commandId, "SUCCESS", requestId, input.reason());
        return detail(actor.user().id(), tenant, commandId);
    }

    private Claimed prepareReconcile(AuthService.Identity actor, String audience, String tenant, String commandId,
                                     DeviceServiceInputs.ReconcileCommand input) {
        String risk = jdbc.query("SELECT risk_level FROM device_service_commands WHERE tenant_id=? AND id=?",
            (rs, n) -> rs.getString(1), tenant, commandId).stream().findFirst().orElse(null);
        if (risk == null) throw ApiError.missing();
        guard.begin(actor, "device-services:query", tenant, audience);
        if ("ENGINEERING".equals(risk)) {
            if (!"management".equals(audience)) throw ApiError.forbidden("工程操作仅限平台管理入口");
            guard.requireCurrent(actor, "device-services:engineering", tenant, audience);
        }
        CommandRow row = requireForUpdate(tenant, commandId);
        queries.device(actor.user().id(), tenant, row.deviceId());
        OffsetDateTime now = OffsetDateTime.now(clock);
        if ("IN_FLIGHT".equals(row.state())) {
            OffsetDateTime started = row.startedAt();
            if (started != null && started.plusSeconds(STALE_IN_FLIGHT_SECONDS).isAfter(now))
                throw ApiError.conflict("COMMAND_IN_FLIGHT", "命令正在外部发送中，请稍后再核对");
            jdbc.update("UPDATE device_service_commands SET state='UNKNOWN',next_action='RECONCILE',status_version=status_version+1,updated_at=? WHERE id=? AND status_version=?",
                now, commandId, row.statusVersion());
            row = requireForUpdate(tenant, commandId);
            audit.record(actor.user().id(), tenant, "DEVICE_SERVICE_COMMAND_STALE_RECOVERY", commandId, "RECORDED", commandId);
        }
        if (!List.of("ACCEPTED", "UNKNOWN").contains(row.state()))
            throw ApiError.conflict("COMMAND_NOT_RECONCILABLE", "只有ACCEPTED或UNKNOWN的命令可核对；成功/失败/拒绝为终态");
        int attemptNo = nextAttempt(commandId);
        jdbc.update("INSERT INTO device_service_command_attempts(id,command_id,attempt_no,phase,outcome,started_at) VALUES(?,?,?,'QUERY','IN_FLIGHT',?)",
            UUID.randomUUID().toString(), commandId, attemptNo, now);
        return new Claimed(row, attemptNo);
    }

    // ---------------- worker dispatch ----------------

    public boolean dispatchOne() {
        Claimed claimed = transactions.execute(status -> claim());
        if (claimed == null) return false;
        DriverOutcome outcome = invoke(claimed.row(), null, false);
        transactions.executeWithoutResult(status -> finish(claimed, outcome, "SUBMIT"));
        return true;
    }

    private Claimed claim() {
        // The same fence that transfer/unbind take: a command is durably claimed before I/O, and a
        // transfer can never observe QUEUED and release the binding between our check and the send.
        coordination.lock();
        var ids = jdbc.queryForList("SELECT id FROM device_service_commands WHERE state='QUEUED' ORDER BY created_at,id LIMIT 1", String.class);
        if (ids.isEmpty()) return null;
        String id = ids.getFirst();
        CommandRow row = jdbc.query("SELECT * FROM device_service_commands WHERE id=? FOR UPDATE", (rs, n) -> row(rs), id).getFirst();
        String rejection = preflight(row);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (rejection != null) {
            markPreflightFailure(row, rejection, now);
            return null;
        }
        if (jdbc.update("UPDATE device_service_commands SET state='IN_FLIGHT',started_at=?,updated_at=?,status_version=status_version+1 WHERE id=? AND state='QUEUED'",
                now, now, id) != 1) return null;
        row = jdbc.query("SELECT * FROM device_service_commands WHERE id=?", (rs, n) -> row(rs), id).getFirst();
        int attemptNo = nextAttempt(id);
        jdbc.update("INSERT INTO device_service_command_attempts(id,command_id,attempt_no,phase,outcome,started_at) VALUES(?,?,?,'SUBMIT','IN_FLIGHT',?)",
            UUID.randomUUID().toString(), id, attemptNo, now);
        return new Claimed(row, attemptNo);
    }

    /** Runs before the durable IN_FLIGHT claim; any failure is a definite not-sent outcome. */
    private String preflight(CommandRow row) {
        ObjectNode snapshot = parseObject(row.snapshotJson());
        String authorityFailure = operatorRejection(row, snapshot);
        if (authorityFailure != null) return authorityFailure;
        String tenantFailure = tenantRejection(row.tenantId());
        if (tenantFailure != null) return tenantFailure;
        IntegrationDriver driver = drivers.find(row.driverKey());
        if (driver == null) return "DRIVER_UNKNOWN";
        var deviceRows = jdbc.query("SELECT id,tenant_id,name,device_eui,model,revision,product_version_id,archived_at FROM devices WHERE id=?",
            (rs, n) -> new DeviceServiceQueryService.DeviceContext(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class)),
            row.deviceId());
        if (deviceRows.isEmpty()) return "DEVICE_MISSING";
        var device = deviceRows.getFirst();
        if (!row.tenantId().equals(device.tenantId()) || device.archivedAt() != null) return "DEVICE_NOT_ACTIVE";
        if (!CLOUD_DELETE.equals(row.serviceKey())
            && !java.util.Objects.equals(device.model(), snapshot.path("device_model").asText(null)))
            return "DEVICE_MODEL_CHANGED";
        if ("PRODUCT_MODEL".equals(row.driverMode())) {
            String snapshotVersion = snapshot.path("product_version_id").asText(null);
            if (device.productVersionId() == null || !device.productVersionId().equals(snapshotVersion)) return "VERSION_CHANGED";
        } else {
            String bindingFailure = bindingRejection(row, snapshot);
            if (bindingFailure != null) return bindingFailure;
        }
        String account = snapshot.path("account_fingerprint").asText(null);
        String currentAccount = driver.accountFingerprint();
        if (account != null && currentAccount != null && !account.equals(currentAccount)) return "ACCOUNT_CHANGED";
        if (!CLOUD_DELETE.equals(row.serviceKey())) {
            var resolution = queries.resolve(device);
            if (!row.driverKey().equals(resolution.driverKey()) || !row.driverMode().equals(resolution.mode())) return "SOURCE_IDENTITY_CHANGED";
            var service = resolution.services().get(row.serviceKey());
            if (service == null) return "SERVICE_NOT_AVAILABLE";
            if (service.blockedCode() != null) return service.blockedCode();
            if (!row.resultKind().equals(service.view().resultKind())
                || ("NORMAL".equals(row.riskLevel()) && "ENGINEERING".equals(service.view().riskLevel()))
                || !snapshot.path("input_schema").equals(service.view().inputSchema()))
                return "CAPABILITY_CHANGED";
        }
        return null;
    }

    private String tenantRejection(String tenantId) {
        try {
            return tenantFence.state(tenantId, true).enabled() ? null : "TENANT_DISABLED";
        } catch (ApiError unavailable) {
            return "TENANT_DISABLED";
        }
    }

    private String operatorRejection(CommandRow row, ObjectNode snapshot) {
        if ("AUTOMATION".equals(snapshot.path("origin").asText())) return null;
        String actor = snapshot.path("actor_id").asText(row.actorId());
        String audience = snapshot.path("audience").asText(null);
        String permission = snapshot.path("required_permission").asText(null);
        if (actor == null || audience == null || permission == null || !snapshot.path("actor_revision").canConvertToLong())
            return "AUTHORIZATION_SNAPSHOT_MISSING";
        long revision = snapshot.path("actor_revision").asLong();
        if ("management".equals(audience))
            return executionAuthorization.lockExecution(actor, revision, row.tenantId(), List.of(permission), row.deviceId());
        coordination.lock();
        var users = jdbc.query("SELECT revision,enabled,must_change_password,user_type,tenant_id FROM app_users WHERE id=?",
            (rs, n) -> new Object[]{rs.getLong(1), rs.getBoolean(2), rs.getBoolean(3), rs.getString(4), rs.getString(5)}, actor);
        if (users.isEmpty()) return "ACTOR_UNAVAILABLE";
        Object[] user = users.getFirst();
        if (((Number) user[0]).longValue() != revision) return "ACTOR_CHANGED";
        if (!Boolean.TRUE.equals(user[1]) || Boolean.TRUE.equals(user[2]) || !"CUSTOMER".equals(user[3])
            || !row.tenantId().equals(user[4])) return "ACTOR_UNAVAILABLE";
        Long granted = jdbc.queryForObject("SELECT COUNT(*) FROM effective_user_permissions WHERE user_id=? AND permission=?",
            Long.class, actor, permission);
        if (granted == null || granted != 1L || !scopes.contains(actor, row.tenantId(), row.deviceId()))
            return "AUTHORIZATION_REVOKED";
        return null;
    }

    private long currentRevision(String actorId) {
        return jdbc.queryForObject("SELECT revision FROM app_users WHERE id=?", Long.class, actorId);
    }

    private String bindingRejection(CommandRow row, ObjectNode snapshot) {
        String bindingId = snapshot.path("binding_id").asText(null);
        if (bindingId == null) return "BINDING_MISSING";
        return jdbc.query("SELECT device_id,tenant_id,state,generation,driver_key,source_external_id,source_serial,account_fingerprint FROM device_source_bindings WHERE id=?",
            rs -> {
                if (!rs.next()) return "BINDING_MISSING";
                if (!row.deviceId().equals(rs.getString(1)) || !row.tenantId().equals(rs.getString(2))
                    || (!CLOUD_DELETE.equals(row.serviceKey()) && !"ACTIVE".equals(rs.getString(3))))
                    return "BINDING_RELEASED";
                if (rs.getLong(4) != snapshot.path("binding_generation").asLong()) return "BINDING_CHANGED";
                if (!row.driverKey().equals(rs.getString(5))
                    || !java.util.Objects.equals(snapshot.path("external_device_id").asText(null), rs.getString(6))
                    || !java.util.Objects.equals(snapshot.path("external_serial").asText(null), rs.getString(7)))
                    return "SOURCE_IDENTITY_CHANGED";
                String originalAccount = rs.getString(8);
                String requestedAccount = snapshot.path("account_fingerprint").asText(null);
                if (originalAccount == null || requestedAccount == null) return "SOURCE_ACCOUNT_UNCONFIRMED";
                return originalAccount.equals(requestedAccount) ? null : "ACCOUNT_CHANGED";
            }, bindingId);
    }

    private void markPreflightFailure(CommandRow row, String code, OffsetDateTime now) {
        if (jdbc.update("UPDATE device_service_commands SET state='FAILED',status_version=status_version+1,vendor_code=?,next_action=NULL,finished_at=?,updated_at=? WHERE id=? AND status_version=?",
                code, now, now, row.id(), row.statusVersion()) != 1)
            throw ApiError.conflict("COMMAND_STATE_CHANGED", "命令状态已变化，预检结果未写入");
        ObjectNode detail = json.createObjectNode();
        detail.put("note", "发送前预检失败，未发起任何外部请求");
        jdbc.update("INSERT INTO device_service_command_attempts(id,command_id,attempt_no,phase,outcome,vendor_code,detail_json,started_at,finished_at) VALUES(?,?,?,'SUBMIT','ABORTED',?,?,?,?)",
            UUID.randomUUID().toString(), row.id(), nextAttempt(row.id()), code, detail.toString(), now, now);
        audit.record(row.actorId(), row.tenantId(), "DEVICE_SERVICE_COMMAND_PREFLIGHT_FAILED", row.id(), code, row.id());
    }

    private DriverOutcome invoke(CommandRow row, String reference, boolean query) {
        ObjectNode snapshot = parseObject(row.snapshotJson());
        IntegrationDriver driver = drivers.find(row.driverKey());
        if (driver == null)
            return DriverOutcome.blocked("DRIVER_UNKNOWN", "未注册该接入驱动，未发起外部请求");
        IntegrationDriver.DriverServiceDescriptor descriptor = descriptor(row, driver);
        if (descriptor == null)
            return DriverOutcome.blocked("OPERATION_NOT_AVAILABLE", "服务定义不可用，未发起外部请求");
        String account = snapshot.path("account_fingerprint").isNull() ? null : snapshot.path("account_fingerprint").asText(null);
        if (!query) {
            String rejection = transactions.execute(status -> preflight(row));
            if (rejection != null) return DriverOutcome.blocked(rejection, "发送前设备来源、操作者授权或单位状态已变化，未发起外部请求");
        } else {
            String currentAccount = driver.accountFingerprint();
            if (account != null && currentAccount != null && !account.equals(currentAccount))
                return DriverOutcome.blocked("ACCOUNT_CHANGED", "接入账号已轮换，旧命令不自动重发；请重新核对后新建命令");
            if ("VENDOR_PLATFORM".equals(row.driverMode())) {
                String bindingFailure = bindingRejection(row, snapshot);
                if (bindingFailure != null)
                    return DriverOutcome.blocked(bindingFailure, "来源身份或账号与命令快照不一致，未发起结果查询");
            }
            if ("PRODUCT_MODEL".equals(row.driverMode())) {
                String bound = jdbc.queryForObject("SELECT product_version_id FROM devices WHERE id=?", String.class, row.deviceId());
                String snapshotVersion = snapshot.path("product_version_id").asText(null);
                if (bound == null || !bound.equals(snapshotVersion))
                    return DriverOutcome.blocked("VERSION_CHANGED", "设备产品版本已变化，旧命令未查询");
            }
        }
        List<ServiceSchema.Field> fields;
        try { fields = schema.parse(snapshot.path("input_schema")); }
        catch (ApiError invalid) { return DriverOutcome.blocked("SNAPSHOT_SCHEMA_INVALID", "命令快照的输入schema不可用，未发起外部请求"); }
        ObjectNode arguments;
        try { arguments = secrets.revealArguments(row.id(), parseObject(row.argumentsJson()), fields); }
        catch (ApiError unreadable) { return DriverOutcome.blocked("ARGUMENT_UNREADABLE", "命令参数无法解密，未发起外部请求"); }
        var call = new IntegrationDriver.DriverCall(row.id(), row.tenantId(), row.deviceId(),
            snapshot.path("device_eui").asText(null), snapshot.path("external_device_id").asText(null),
            snapshot.path("external_serial").asText(null), snapshot.path("device_model").asText(null),
            snapshot.path("product_version_id").asText(null), reference, arguments, descriptor, account);
        try {
            return query ? driver.query(call) : driver.submit(call);
        } catch (RuntimeException failure) {
            return DriverOutcome.unknown("DRIVER_FAILURE", null, "驱动执行异常，外部结果未确认；禁止自动重发");
        }
    }

    private IntegrationDriver.DriverServiceDescriptor descriptor(CommandRow row, IntegrationDriver driver) {
        if ("VENDOR_PLATFORM".equals(row.driverMode())) {
            return driver.services().stream().filter(service -> service.key().equals(row.serviceKey())).findFirst().orElse(null);
        }
        if (row.serviceDefinitionId() == null) return null;
        var rows = jdbc.query("SELECT operation_key,display_name,category,risk_level,result_kind,input_schema_json,applicability_json,evidence_json,support_state FROM device_service_definitions WHERE id=?",
            (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)}, row.serviceDefinitionId());
        if (rows.isEmpty()) return null;
        String[] value = rows.getFirst();
        return new IntegrationDriver.DriverServiceDescriptor(value[0], value[1], value[2], value[3], value[4],
            parseNullable(value[5]), parseNullable(value[6]), parseNullable(value[7]), value[8], false);
    }

    private void finish(Claimed claimed, DriverOutcome outcome, String phase) {
        CommandRow row = claimed.row();
        boolean submit = "SUBMIT".equals(phase);
        String attemptOutcome = switch (outcome.outcome()) {
            case "ACCEPTED" -> "ACCEPTED";
            case "REJECTED" -> "REJECTED";
            case "SUCCEEDED" -> "SUCCEEDED";
            case "FAILED" -> "FAILED";
            case "BLOCKED" -> "ABORTED";
            default -> "UNKNOWN";
        };
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.update("UPDATE device_service_command_attempts SET outcome=?,vendor_code=?,detail_json=?,finished_at=? WHERE command_id=? AND attempt_no=?",
            attemptOutcome, outcome.vendorCode(), detailJson(outcome).toString(), now, row.id(), claimed.attemptNo());
        String state;
        String nextAction;
        boolean terminal;
        switch (outcome.outcome()) {
            case "ACCEPTED" -> { state = "ACCEPTED"; nextAction = "RECONCILE"; terminal = false; }
            case "SUCCEEDED" -> { state = "SUCCEEDED"; nextAction = null; terminal = true; }
            case "FAILED" -> { state = "FAILED"; nextAction = null; terminal = true; }
            case "REJECTED" -> {
                if (submit) { state = "REJECTED"; nextAction = null; terminal = true; }
                else { state = row.state(); nextAction = "RECONCILE"; terminal = false; }
            }
            case "BLOCKED" -> {
                if (submit) { state = "FAILED"; nextAction = null; terminal = true; }
                else { state = row.state(); nextAction = "RECONCILE"; terminal = false; }
            }
            default -> {
                state = "ACCEPTED".equals(row.state()) ? "ACCEPTED" : "UNKNOWN";
                nextAction = "RECONCILE";
                terminal = false;
            }
        }
        int changed = jdbc.update("UPDATE device_service_commands SET state=?,status_version=status_version+1,vendor_code=?,accepted_reference=COALESCE(?,accepted_reference),result_json=?,next_action=?,finished_at=?,updated_at=? WHERE id=? AND status_version=?",
            state, outcome.vendorCode(), outcome.reference(), resultJson(outcome), nextAction, terminal ? now : null, now,
            row.id(), row.statusVersion());
        if (changed != 1) throw ApiError.conflict("COMMAND_STATE_CHANGED", "命令状态已变化，外部结果未写入；请显式核对");
        if ("SUCCEEDED".equals(state) && "PARAMETERS".equals(row.resultKind())) storeSnapshot(row, outcome, now);
        audit.record(row.actorId(), row.tenantId(), "DEVICE_SERVICE_COMMAND_" + state, row.id(), "RECORDED", row.id());
    }

    private void storeSnapshot(CommandRow row, DriverOutcome outcome, OffsetDateTime now) {
        JsonNode result = outcome.result();
        if (result == null || !result.isObject()) return;
        JsonNode parameters = result.path("parameters");
        if (!parameters.isArray()) return;
        JsonNode schemaNode = result.path("schema");
        Long generation = null;
        try {
            JsonNode value = parseObject(row.snapshotJson()).path("binding_generation");
            if (value.isNumber()) generation = value.asLong();
        } catch (RuntimeException ignored) { generation = null; }
        jdbc.update("INSERT INTO device_parameter_snapshots(id,tenant_id,device_id,driver_key,source_generation,schema_json,values_json,observed_at,command_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), row.tenantId(), row.deviceId(), row.driverKey(), generation,
            (schemaNode.isMissingNode() || schemaNode.isNull() ? json.createObjectNode() : schemaNode).toString(),
            parameters.toString(), now, row.id(), now);
    }

    // ---------------- helpers ----------------

    private CommandRow requireForUpdate(String tenant, String commandId) {
        var rows = jdbc.query("SELECT * FROM device_service_commands WHERE tenant_id=? AND id=? FOR UPDATE",
            (rs, n) -> row(rs), tenant, commandId);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private int nextAttempt(String commandId) {
        Integer value = jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0)+1 FROM device_service_command_attempts WHERE command_id=?",
            Integer.class, commandId);
        return value == null ? 1 : value;
    }

    private ObjectNode parseObject(String raw) {
        if (raw == null || raw.isBlank()) return json.createObjectNode();
        try {
            JsonNode node = json.readTree(raw);
            return node.isObject() ? (ObjectNode) node : json.createObjectNode();
        } catch (Exception invalid) { throw new IllegalStateException("Stored command JSON is invalid"); }
    }

    private JsonNode parseNullable(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return json.readTree(raw); }
        catch (Exception invalid) { return null; }
    }

    private static String resultJson(DriverOutcome outcome) {
        return outcome.result() == null ? null : outcome.result().toString();
    }

    private ObjectNode detailJson(DriverOutcome outcome) {
        ObjectNode node = json.createObjectNode();
        node.put("note", outcome.note());
        if (outcome.reference() != null) node.put("reference", outcome.reference());
        if (outcome.result() != null) node.set("result", outcome.result());
        return node;
    }

    private static CommandRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CommandRow(rs.getString("id"), rs.getString("tenant_id"), rs.getString("device_id"),
            rs.getString("driver_key"), rs.getString("driver_mode"), rs.getString("service_key"),
            rs.getString("service_definition_id"), rs.getString("risk_level"), rs.getString("result_kind"),
            rs.getString("state"), rs.getLong("status_version"), rs.getString("arguments_json"),
            rs.getString("payload_sha256"), rs.getString("snapshot_json"), rs.getString("actor_id"),
            rs.getString("reason"), rs.getString("accepted_reference"), rs.getString("vendor_code"),
            rs.getString("result_json"), rs.getString("next_action"),
            rs.getObject("accepted_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static DeviceServiceModels.CommandSummary summary(CommandRow row) {
        return new DeviceServiceModels.CommandSummary(row.id(), row.tenantId(), row.deviceId(), row.driverKey(),
            row.driverMode(), row.serviceKey(), row.riskLevel(), row.resultKind(), row.state(), row.statusVersion(),
            row.acceptedReference(), row.vendorCode(), row.nextAction(), row.acceptedAt(), row.createdAt(),
            row.finishedAt());
    }
}
