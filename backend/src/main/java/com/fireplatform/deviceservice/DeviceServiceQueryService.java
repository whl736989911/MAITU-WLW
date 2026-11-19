package com.fireplatform.deviceservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.DeviceAccessScope;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only capability/parameter/history projection. Opening a tab never wakes a device: capabilities are
 * cached metadata and the parameter snapshot is the last explicit read result.
 */
@Service
public class DeviceServiceQueryService {
    public record DeviceContext(String id, String tenantId, String name, String eui, String model, long revision,
                                String productVersionId, OffsetDateTime archivedAt) { }
    public record ResolvedService(DeviceServiceModels.ServiceDefinition view,
                                  IntegrationDriver.DriverServiceDescriptor descriptor,
                                  DeviceServiceDefinitionService.Row row,
                                  List<ServiceSchema.Field> fields,
                                  String blockedCode, String blockedReason) { }
    public record Resolution(String mode, String driverKey, IntegrationDriver driver, boolean configured,
                             String configurationCode, String accountFingerprint,
                             DeviceSourceBindingService.Binding binding,
                             Map<String, ResolvedService> services, List<String> notes) { }
    public record ParameterRead(String deviceId, DeviceServiceModels.ParameterSnapshot snapshot, String note) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DriverRegistry registry;
    private final DeviceServiceDefinitionService definitions;
    private final DeviceCapabilityEvidenceService evidence;
    private final DeviceSourceBindingService bindings;
    private final ServiceSchema schema;
    private final DeviceAccessScope scopes;

    public DeviceServiceQueryService(JdbcTemplate jdbc, ObjectMapper json, DriverRegistry registry,
                                     DeviceServiceDefinitionService definitions,
                                     DeviceCapabilityEvidenceService evidence,
                                     DeviceSourceBindingService bindings, ServiceSchema schema,
                                     DeviceAccessScope scopes) {
        this.jdbc = jdbc;
        this.json = json;
        this.registry = registry;
        this.definitions = definitions;
        this.evidence = evidence;
        this.bindings = bindings;
        this.schema = schema;
        this.scopes = scopes;
    }

    public DeviceContext device(String actorId, String tenantId, String deviceId) {
        scopes.requireDevice(actorId, tenantId, deviceId);
        var rows = jdbc.query("SELECT id,tenant_id,name,device_eui,model,revision,product_version_id,archived_at FROM devices WHERE tenant_id=? AND id=?",
            (rs, n) -> new DeviceContext(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getLong(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class)),
            tenantId, deviceId);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    /** System-internal resolve for automation; caller has already established tenant/device ownership. */
    public DeviceContext systemDevice(String tenantId, String deviceId) {
        var rows = jdbc.query("SELECT id,tenant_id,name,device_eui,model,revision,product_version_id,archived_at FROM devices WHERE tenant_id=? AND id=?",
            (rs, n) -> new DeviceContext(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getLong(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class)),
            tenantId, deviceId);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    public Resolution resolve(DeviceContext device) {
        var binding = bindings.activeForDevice(device.tenantId(), device.id());
        if (binding.isPresent()) return vendorResolution(device, binding.get());
        if (device.productVersionId() != null) return productResolution(device);
        IntegrationDriver configured = registry.all().stream()
            .filter(driver -> "PRODUCT_MODEL".equals(driver.mode())).findFirst().orElse(null);
        return new Resolution("NONE", configured == null ? null : configured.key(), configured,
            configured != null && configured.configuration().configured(),
            configured == null ? "DRIVER_NOT_REGISTERED" : configured.configuration().code(), null, null,
            Map.of(), List.of("设备未绑定厂商来源，也未绑定已发布产品版本；无法读取真实服务能力，不能下发命令"));
    }

    public DeviceServiceModels.Capabilities capabilities(String actorId, String tenantId, String deviceId) {
        DeviceContext device = device(actorId, tenantId, deviceId);
        Resolution resolution = resolve(device);
        var snapshot = latestSnapshot(device.tenantId(), device.id());
        List<DeviceServiceModels.ServiceDefinition> services = resolution.services().values().stream()
            .map(ResolvedService::view).toList();
        DeviceServiceModels.SourceBinding bindingView = resolution.binding() == null ? null : bindingView(resolution.binding());
        return new DeviceServiceModels.Capabilities(device.id(), device.tenantId(), device.revision(), resolution.mode(),
            resolution.driverKey(), resolution.configured(), resolution.configurationCode(),
            resolution.binding() != null, bindingView,
            snapshot != null, snapshot == null ? null : snapshot.observedAt(), services,
            List.copyOf(resolution.notes()));
    }

    public ParameterRead parameters(String actorId, String tenantId, String deviceId) {
        DeviceContext device = device(actorId, tenantId, deviceId);
        var snapshot = latestSnapshot(device.tenantId(), device.id());
        return new ParameterRead(device.id(), snapshot, snapshot == null
            ? "尚无已保存参数快照；请显式发起一次参数读取指令，打开页面不会唤醒设备"
            : "参数快照来自最近一次已完成的读取指令；不是当前实时状态，也不代表下发配置已生效");
    }

    public List<DeviceServiceModels.SourceBinding> sourceBindings(String actorId, String tenantId, String deviceId) {
        device(actorId, tenantId, deviceId);
        var rows = bindings.historyForDevice(tenantId, deviceId);
        List<DeviceServiceModels.SourceBinding> result = new ArrayList<>();
        for (var row : rows) result.add(bindingView(row));
        return result;
    }

    public List<DeviceServiceModels.Driver> drivers() {
        List<DeviceServiceModels.Driver> result = new ArrayList<>();
        for (IntegrationDriver driver : registry.all()) {
            var configuration = driver.configuration();
            List<DeviceServiceModels.ServiceDefinition> services = new ArrayList<>();
            for (var descriptor : driver.services()) {
                services.add(driverDefinition(descriptor, driver, null));
            }
            result.add(new DeviceServiceModels.Driver(driver.key(), driver.mode(), driver.displayName(),
                configuration.configured(), configuration.enabled(), configuration.code(), configuration.endpoint(),
                configuration.tokenMasked(), configuration.accountFingerprint(), configuration.revision(), null,
                driver.configurableOriginAccount(), List.copyOf(services)));
        }
        return result;
    }

    public DeviceServiceModels.SourceBinding bindingView(DeviceSourceBindingService.Binding binding) {
        long pending = bindings.pendingRawRecords(binding.externalId());
        return new DeviceServiceModels.SourceBinding(binding.id(), binding.driverKey(), binding.externalId(),
            binding.serial(), binding.accountFingerprint(), binding.generation(), binding.state(), binding.boundAt(),
            binding.boundBy(), binding.releasedAt(), binding.releasedBy(), binding.releaseReason(),
            binding.pendingRisk(), pending);
    }

    public DeviceServiceModels.ParameterSnapshot latestSnapshot(String tenantId, String deviceId) {
        var rows = jdbc.query("SELECT * FROM device_parameter_snapshots WHERE tenant_id=? AND device_id=? ORDER BY observed_at DESC, id DESC LIMIT 1",
            (rs, n) -> new DeviceServiceModels.ParameterSnapshot(rs.getString("id"), rs.getString("device_id"),
                rs.getString("driver_key"), rs.getObject("source_generation", Long.class),
                parse(rs.getString("schema_json")), parse(rs.getString("values_json")),
                rs.getObject("observed_at", OffsetDateTime.class), rs.getString("command_id")),
            tenantId, deviceId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public DeviceServiceModels.ServiceDefinition productDefinition(DeviceServiceDefinitionService.Row row) {
        IntegrationDriver driver = registry.all().stream()
            .filter(candidate -> "PRODUCT_MODEL".equals(candidate.mode())).findFirst().orElse(null);
        boolean configured = driver != null && driver.configuration().configured();
        String blockedCode = driver == null ? "DRIVER_NOT_REGISTERED"
            : !configured ? driver.configuration().code()
            : !"VERIFIED".equals(row.supportState()) ? "CAPABILITY_UNVERIFIED"
            : !"QUERY".equals(row.category()) && !"ENGINEERING".equals(row.riskLevel()) ? "RISK_FLOOR"
            : !"QUERY".equals(row.category()) && !"COMMAND".equals(row.resultKind()) ? "RESULT_KIND_UNSAFE" : null;
        return new DeviceServiceModels.ServiceDefinition(row.id(), "PRODUCT_VERSION", row.productVersionId(),
            driver == null ? null : driver.key(), row.operationKey(), row.displayName(), row.category(),
            row.riskLevel(), row.resultKind(), row.supportState(), blockedCode == null, blockedCode,
            reason(blockedCode), parse(row.inputSchemaJson()), parse(row.applicabilityJson()),
            parse(row.evidenceJson()), row.status(), row.revision(), row.publishedAt());
    }

    public List<DeviceServiceModels.ServiceDefinition> productDefinitions(String versionId) {
        List<DeviceServiceModels.ServiceDefinition> result = new ArrayList<>();
        for (var row : definitions.forVersion(versionId)) result.add(productDefinition(row));
        return result;
    }

    // ---------------- resolution ----------------

    private Resolution vendorResolution(DeviceContext device, DeviceSourceBindingService.Binding binding) {
        IntegrationDriver driver = registry.require(binding.driverKey());
        var configuration = driver.configuration();
        Map<String, ResolvedService> services = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        if (!configuration.configured())
            notes.add("接入驱动未启用或凭证不可用（" + configuration.code() + "）；能力仅可查看，不能下发");
        if (device.archivedAt() != null) notes.add("设备已归档，只能查看历史，不能下发命令");
        for (var descriptor : driver.services()) {
            var evidenceRow = evidence.find(driver.key(), device.model(), descriptor.key());
            String support = descriptor.supportState();
            JsonNode evidenceNode = descriptor.evidence();
            if (evidenceRow.isPresent()) {
                support = "VERIFIED";
                var merged = json.createObjectNode();
                merged.set("built_in", descriptor.evidence());
                var operator = merged.putObject("operator");
                operator.put("evidence", evidenceRow.get().evidence());
                operator.put("verified_by", evidenceRow.get().verifiedBy());
                operator.put("verified_at", evidenceRow.get().verifiedAt() == null ? null : evidenceRow.get().verifiedAt().toString());
                evidenceNode = merged;
            }
            String blockedCode = null, blockedReason = null;
            if (device.archivedAt() != null) {
                blockedCode = "DEVICE_ARCHIVED"; blockedReason = "设备已归档";
            } else if (!configuration.configured()) {
                blockedCode = configuration.code(); blockedReason = "接入驱动未启用或凭证不可用";
            } else if (!applicability(descriptor.applicability(), device.model())) {
                blockedCode = "MODEL_OUT_OF_SCOPE"; blockedReason = "该操作未覆盖当前设备型号";
            } else if (!"VERIFIED".equals(support)) {
                blockedCode = "CAPABILITY_UNVERIFIED";
                blockedReason = "官方未发布该型号能力证据；需操作者登记已验证证据后才能下发";
            } else if (!"QUERY".equals(descriptor.category()) && !"ENGINEERING".equals(descriptor.riskLevel())
                && !descriptor.typedNormalAllowed()) {
                blockedCode = "RISK_FLOOR"; blockedReason = "非只读操作必须为ENGINEERING风险（仅受信类型化普通操作例外）";
            } else if (!"QUERY".equals(descriptor.category()) && !"COMMAND".equals(descriptor.resultKind())) {
                blockedCode = "RESULT_KIND_UNSAFE"; blockedReason = "非只读操作必须为COMMAND结果类型";
            }
            var view = new DeviceServiceModels.ServiceDefinition(null, "DRIVER", null, driver.key(), descriptor.key(),
                descriptor.displayName(), descriptor.category(), descriptor.riskLevel(), descriptor.resultKind(),
                support, blockedCode == null, blockedCode, blockedReason, descriptor.inputSchema(),
                descriptor.applicability(), evidenceNode, "PUBLISHED", 0, null);
            services.put(descriptor.key(), new ResolvedService(view, descriptor, null,
                schema.parse(descriptor.inputSchema()), blockedCode, blockedReason));
        }
        return new Resolution("VENDOR_PLATFORM", driver.key(), driver, configuration.configured(),
            configuration.code(), configuration.accountFingerprint(), binding, services, notes);
    }

    private Resolution productResolution(DeviceContext device) {
        IntegrationDriver driver = registry.all().stream()
            .filter(candidate -> "PRODUCT_MODEL".equals(candidate.mode())).findFirst()
            .orElseThrow(() -> new ApiError(503, "DRIVER_NOT_REGISTERED", "未注册标准产品网关驱动"));
        var configuration = driver.configuration();
        var version = jdbc.query("SELECT status FROM product_versions WHERE id=?",
            (rs, n) -> rs.getString(1), device.productVersionId());
        String versionStatus = version.isEmpty() ? "MISSING" : version.getFirst();
        var rows = definitions.publishedForVersion(device.productVersionId());
        Map<String, ResolvedService> services = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        if (!configuration.configured())
            notes.add("标准产品网关未启用或令牌不可用（" + configuration.code() + "）；能力仅可查看，不能下发");
        if (device.archivedAt() != null) notes.add("设备已归档，只能查看历史，不能下发命令");
        if (!"PUBLISHED".equals(versionStatus))
            notes.add("设备绑定的产品版本不是已发布状态；未冻结的服务定义不可执行");
        for (var row : rows) {
            JsonNode inputSchema = parse(row.inputSchemaJson());
            JsonNode applicability = parse(row.applicabilityJson());
            JsonNode evidenceNode = parse(row.evidenceJson());
            String blockedCode = null, blockedReason = null;
            if (device.archivedAt() != null) {
                blockedCode = "DEVICE_ARCHIVED"; blockedReason = "设备已归档";
            } else if (!configuration.configured()) {
                blockedCode = configuration.code(); blockedReason = "标准产品网关未启用或令牌不可用";
            } else if (!"PUBLISHED".equals(versionStatus)) {
                blockedCode = "VERSION_NOT_PUBLISHED"; blockedReason = "产品版本未发布";
            } else if (!applicability(applicability, device.model())) {
                blockedCode = "MODEL_OUT_OF_SCOPE"; blockedReason = "该操作未覆盖当前设备型号";
            } else if (!"VERIFIED".equals(row.supportState())) {
                blockedCode = "CAPABILITY_UNVERIFIED"; blockedReason = "该版本服务定义未提供已验证能力证据";
            } else if (!"QUERY".equals(row.category()) && !"ENGINEERING".equals(row.riskLevel())) {
                blockedCode = "RISK_FLOOR"; blockedReason = "非只读操作必须为ENGINEERING风险";
            } else if (!"QUERY".equals(row.category()) && !"COMMAND".equals(row.resultKind())) {
                blockedCode = "RESULT_KIND_UNSAFE"; blockedReason = "非只读操作必须为COMMAND结果类型";
            }
            var view = new DeviceServiceModels.ServiceDefinition(row.id(), "PRODUCT_VERSION", row.productVersionId(),
                driver.key(), row.operationKey(), row.displayName(), row.category(), row.riskLevel(), row.resultKind(),
                row.supportState(), blockedCode == null, blockedCode, blockedReason, inputSchema, applicability,
                evidenceNode, row.status(), row.revision(), row.publishedAt());
            services.put(row.operationKey(), new ResolvedService(view, null, row, schema.parse(inputSchema),
                blockedCode, blockedReason));
        }
        if (rows.isEmpty() && "PUBLISHED".equals(versionStatus))
            notes.add("该已发布产品版本尚未登记任何服务操作；不能借用其他版本或驱动操作");
        return new Resolution("PRODUCT_MODEL", driver.key(), driver, configuration.configured(),
            configuration.code(), configuration.accountFingerprint(), null, services, notes);
    }

    private DeviceServiceModels.ServiceDefinition driverDefinition(IntegrationDriver.DriverServiceDescriptor descriptor,
                                                                   IntegrationDriver driver, String model) {
        var configuration = driver.configuration();
        String blockedCode = !configuration.configured() ? configuration.code()
            : !"VERIFIED".equals(descriptor.supportState()) ? "CAPABILITY_UNVERIFIED"
            : !"QUERY".equals(descriptor.category()) && !"ENGINEERING".equals(descriptor.riskLevel()) && !descriptor.typedNormalAllowed() ? "RISK_FLOOR"
            : !"QUERY".equals(descriptor.category()) && !"COMMAND".equals(descriptor.resultKind()) ? "RESULT_KIND_UNSAFE" : null;
        return new DeviceServiceModels.ServiceDefinition(null, "DRIVER", null, driver.key(), descriptor.key(),
            descriptor.displayName(), descriptor.category(), descriptor.riskLevel(), descriptor.resultKind(),
            descriptor.supportState(), blockedCode == null, blockedCode, reason(blockedCode),
            descriptor.inputSchema(), descriptor.applicability(), descriptor.evidence(), "PUBLISHED", 0, null);
    }

    private static String reason(String blockedCode) {
        if (blockedCode == null) return null;
        return switch (blockedCode) {
            case "RISK_FLOOR" -> "非只读操作必须为ENGINEERING风险";
            case "RESULT_KIND_UNSAFE" -> "非只读操作必须为COMMAND结果类型";
            default -> "接入驱动未启用/凭证不可用，或该定义未提供已验证证据";
        };
    }

    private static boolean applicability(JsonNode applicability, String model) {
        if (applicability == null || !applicability.isObject()) return false;
        JsonNode models = applicability.path("models");
        if (!models.isArray() || models.isEmpty()) return true;
        for (JsonNode candidate : models) if (candidate.isTextual() && candidate.asText().equals(model)) return true;
        return false;
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) return json.createObjectNode();
        try { return json.readTree(raw); }
        catch (Exception error) { throw new IllegalStateException("Stored JSON is invalid"); }
    }
}
