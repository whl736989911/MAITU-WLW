package com.fireplatform.application;

import com.fireplatform.bulkio.DataTransferDomain;
import com.fireplatform.bulkio.DataTransferFilters;
import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.LiteralLike;
import com.fireplatform.shared.ScopeFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Metadata import/export for open applications. Only configuration metadata crosses the boundary: no endpoint URL,
 * credential, payload or delivery row is ever exported or imported. An imported row creates a new application, one
 * authorization of the declared identity kind and a DISABLED draft output with no destination, so import can never
 * activate an external effect. Existing applications or outputs are never modified by an import.
 */
@Component
public class ApplicationOutputDataTransferDomain implements DataTransferDomain {
    private static final List<String> IMPORT_COLUMNS = List.of("application_name", "application_description",
        "reference_kind", "product_manufacturer", "product_model", "version_number", "source_platform",
        "legacy_product_id", "vendor_model_id", "driver_key", "scope_mode", "device_euis", "output_name",
        "output_mode", "output_protocol", "note");
    private static final List<String> EXPORT_COLUMNS = List.of("application_id", "tenant_id", "application_name",
        "application_description", "application_status", "reference_kind", "reference_key", "product_manufacturer",
        "product_model", "version_number", "source_platform", "legacy_product_id", "legacy_decoder_revision",
        "vendor_model_id", "driver_key", "adapter_digest", "scope_mode", "authorization_status", "device_count",
        "output_id", "output_name", "output_mode", "output_protocol", "output_enabled", "endpoint_origin",
        "endpoint_digest", "note");

    private final JdbcTemplate jdbc;
    private final DeviceAccessScope devices;
    private final ScopeFilter scope;
    private final ApplicationCommands commands;

    public ApplicationOutputDataTransferDomain(JdbcTemplate jdbc, DeviceAccessScope devices, ScopeFilter scope,
                                               ApplicationCommands commands) {
        this.jdbc = jdbc;
        this.devices = devices;
        this.scope = scope;
        this.commands = commands;
    }

    @Override
    public List<Kind> kinds() {
        return List.of(new Kind("APPLICATION_OUTPUTS", IMPORT_COLUMNS, "applications:read", "applications:write",
            true, true, true, Set.of("management"), "DISABLED_DRAFT"));
    }

    @Override
    public List<String> validate(String kind, Map<String, String> cells, AuthService.Identity actor, String tenant) {
        requireKind(kind);
        var errors = new ArrayList<String>();
        Prepared prepared;
        try {
            prepared = prepare(cells, actor, tenant);
        } catch (ApiError failure) {
            errors.add(failure.getMessage());
            return List.copyOf(errors);
        }
        if (exists("SELECT COUNT(*) FROM output_applications WHERE tenant_id=? AND name=?", tenant,
            prepared.applicationName())) errors.add("同一单位已存在同名应用；导入不会修改现有应用");
        return List.copyOf(errors);
    }

    @Override
    public String operation(String kind, Map<String, String> cells) {
        requireKind(kind);
        return "CREATE";
    }

    @Override
    public String apply(String kind, Map<String, String> cells, AuthService.Identity actor, String tenant,
                        String reason, String jobId, int rowNumber) {
        requireKind(kind);
        var prepared = prepare(cells, actor, tenant);
        String rowReason = prepared.note() == null ? reason : prepared.note();
        String prefix = "data-transfer:" + jobId + ":" + rowNumber;
        var created = commands.create(actor, tenant, prefix + ":app",
            new ApplicationInputs.Create(prepared.applicationName(), prepared.description(), rowReason),
            "data-transfer");
        String applicationId = created.application().id();
        var grant = prepared.grant(rowReason);
        var granted = commands.grant(actor, tenant, applicationId, prefix + ":auth", grant, "data-transfer");
        String referenceKey = referenceKey(grant);
        String authorizationId = granted.authorizations().stream()
            .filter(item -> item.referenceKey().equals(referenceKey))
            .findFirst().orElseThrow(() -> new IllegalStateException("Imported authorization is missing")).id();
        var withOutput = commands.createOutput(actor, tenant, applicationId, prefix + ":output",
            new ApplicationInputs.CreateOutput(new ApplicationInputs.OutputSpec(prepared.outputName(),
                prepared.outputMode(), prepared.outputProtocol(), null, "NONE", null, null, null, null, null,
                5000, false), rowReason), "data-transfer");
        return applicationId + "/" + authorizationId + "/" + withOutput.outputs().stream()
            .filter(item -> item.name().equals(prepared.outputName()))
            .findFirst().orElseThrow(() -> new IllegalStateException("Imported draft output is missing")).id();
    }

    @Override
    public ExportData export(String kind, AuthService.Identity actor, String tenant, Map<String, String> filters,
                             List<String> selectedIds) {
        requireKind(kind);
        if (filters == null) filters = Map.of();
        var selection = DataTransferFilters.selection(filters, Set.of("keyword", "status"));
        if (selection.empty()) return new ExportData(EXPORT_COLUMNS, List.of());
        var tree = scope.units(actor.user().id(), "a", "tenant_id", tenant, selection);
        var args = new ArrayList<Object>(tree.bindings());
        var where = new StringBuilder(" WHERE ").append(tree.sql());
        String status = text(filters.get("status"));
        if (status != null) {
            String normalized = status.toUpperCase(Locale.ROOT);
            if (!"ACTIVE".equals(normalized) && !"ARCHIVED".equals(normalized)) throw ApiError.bad("应用状态筛选无效");
            where.append(" AND a.status=?"); args.add(normalized);
        }
        String keyword = text(filters.get("keyword"));
        if (keyword != null) {
            String like = LiteralLike.contains(keyword);
            where.append(" AND (LOWER(a.name) LIKE LOWER(?) ESCAPE '!' OR LOWER(a.description) LIKE LOWER(?) ESCAPE '!')");
            args.add(like); args.add(like);
        }
        var rows = jdbc.query("SELECT a.id,a.tenant_id,a.name,a.description,a.status,z.reference_kind,z.reference_key,"
                + "p.manufacturer,p.model,v.version_number,z.source_platform,z.legacy_product_id,"
                + "z.legacy_decoder_revision,z.vendor_model_id,z.driver_key,z.adapter_digest,z.scope_mode,"
                + "z.status AS authorization_status,"
                + "(SELECT COUNT(*) FROM application_authorization_devices s WHERE s.authorization_id=z.id"
                + " AND s.tenant_id=z.tenant_id),o.id AS output_id,o.name AS output_name,o.mode,o.protocol,o.enabled,"
                + "o.endpoint_origin,o.endpoint_digest,a.archived_reason FROM output_applications a"
                + " LEFT JOIN application_product_authorizations z ON z.application_id=a.id AND z.tenant_id=a.tenant_id"
                + " LEFT JOIN products p ON p.id=z.product_id"
                + " LEFT JOIN product_versions v ON v.id=z.product_version_id"
                + " LEFT JOIN application_outputs o ON o.application_id=a.id AND o.tenant_id=a.tenant_id"
                + where + " ORDER BY a.updated_at DESC,a.id DESC,o.name LIMIT 10001",
            (rs, n) -> Arrays.asList(nullable(rs.getString(1)), nullable(rs.getString(2)), nullable(rs.getString(3)),
                nullable(rs.getString(4)), nullable(rs.getString(5)), nullable(rs.getString(6)),
                nullable(rs.getString(7)), nullable(rs.getString(8)), nullable(rs.getString(9)),
                nullable(rs.getObject(10, Long.class)), nullable(rs.getString(11)), nullable(rs.getString(12)),
                nullable(rs.getObject(13, Long.class)), nullable(rs.getString(14)), nullable(rs.getString(15)),
                nullable(rs.getString(16)), nullable(rs.getString(17)), nullable(rs.getString(18)),
                Long.toString(rs.getLong(19)), nullable(rs.getString(20)), nullable(rs.getString(21)),
                nullable(rs.getString(22)), nullable(rs.getString(23)), nullable(rs.getObject(24, Boolean.class)),
                nullable(rs.getString(25)), nullable(rs.getString(26)), nullable(rs.getString(27))),
            args.toArray());
        var filtered = new ArrayList<List<String>>(rows.size());
        var seen = new LinkedHashSet<String>();
        for (var row : rows) {
            // A unit may own several authorizations and outputs; export each triple once.
            if (seen.add(row.get(0) + "|" + row.get(6) + "|" + row.get(19))) filtered.add(row);
        }
        if (selectedIds != null && !selectedIds.isEmpty())
            throw ApiError.bad("应用输出导出为配置清单，不支持按行勾选；请使用关键字或单位/位置范围");
        return new ExportData(EXPORT_COLUMNS, filtered);
    }

    private record Prepared(String applicationName, String description, String sourcePlatform,
                            String legacyProductId, String vendorModelId, String driverKey, String referenceKind,
                            String productId, String productVersionId, String scopeMode, List<String> deviceIds,
                            String outputName, String outputMode, String outputProtocol, String note) {
        ApplicationInputs.Grant grant(String reason) {
            return new ApplicationInputs.Grant(referenceKind, productId, productVersionId, sourcePlatform,
                legacyProductId, vendorModelId, driverKey, scopeMode, deviceIds, reason);
        }
    }

    private String referenceKey(ApplicationInputs.Grant grant) {
        return switch (grant.referenceKind()) {
            case "FORMAL_VERSION" -> "FORMAL:" + grant.productVersionId();
            case "LEGACY_PRODUCT" -> "LEGACY:" + grant.sourcePlatform() + ":" + grant.legacyProductId();
            case "VENDOR_MODEL" -> "VENDOR:" + grant.sourcePlatform() + ":" + grant.vendorModelId();
            default -> throw ApiError.bad("授权身份类型无效");
        };
    }

    private Prepared prepare(Map<String, String> cells, AuthService.Identity actor, String tenant) {
        String applicationName = required(cells.get("application_name"), 200, "应用名称");
        String description = optional(cells.get("application_description"), 2000, "应用说明");
        String referenceKind = ApplicationInputs.referenceKind(cells.get("reference_kind"));
        String scopeMode = required(cells.get("scope_mode"), 16, "授权范围").toUpperCase(Locale.ROOT);
        if (!ApplicationInputs.SCOPE_MODES.contains(scopeMode))
            throw ApiError.bad("授权范围必须为ALL_TENANT或DEVICE_SUBSET");
        String platform = optional(cells.get("source_platform"), 64, "来源平台");
        if (platform != null) platform = platform.toLowerCase(Locale.ROOT);
        String legacyProductId = optional(cells.get("legacy_product_id"), 64, "旧产品ID");
        String vendorModelId = optional(cells.get("vendor_model_id"), 200, "厂商型号ID");
        if (!"FORMAL_VERSION".equals(referenceKind)) {
            if (platform == null) throw ApiError.bad("该授权身份必须提供来源平台");
            if ("LEGACY_PRODUCT".equals(referenceKind) && legacyProductId == null)
                throw ApiError.bad("旧产品授权必须提供旧产品ID");
            if ("VENDOR_MODEL".equals(referenceKind) && vendorModelId == null)
                throw ApiError.bad("厂商型号授权必须提供厂商型号ID");
        }
        String driverKey = optional(cells.get("driver_key"), 64, "适配器标识");
        String productId = null;
        String productVersionId = null;
        if ("FORMAL_VERSION".equals(referenceKind)) {
            String manufacturer = required(cells.get("product_manufacturer"), 100, "产品制造商");
            String model = required(cells.get("product_model"), 100, "产品型号");
            String version = required(cells.get("version_number"), 19, "版本号");
            long versionNumber;
            try {
                versionNumber = Long.parseLong(version);
                if (versionNumber < 1) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                throw ApiError.bad("版本号必须为正整数");
            }
            var versions = jdbc.query("SELECT p.id,pv.id FROM products p JOIN product_versions pv ON pv.product_id=p.id"
                    + " WHERE p.manufacturer=? AND p.model=? AND pv.version_number=? AND pv.status='PUBLISHED'",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, manufacturer, model, versionNumber);
            if (versions.size() != 1)
                throw ApiError.bad("未找到唯一的已发布产品版本：" + manufacturer + " " + model + " v" + versionNumber);
            productId = versions.getFirst()[0];
            productVersionId = versions.getFirst()[1];
        }
        var euis = new LinkedHashSet<String>();
        String euiList = optional(cells.get("device_euis"), 8000, "设备EUI列表");
        if (euiList != null) {
            for (String part : euiList.split("[,;\\s]+")) {
                String eui = part.trim();
                if (!eui.isEmpty()) euis.add(eui);
            }
        }
        if ("ALL_TENANT".equals(scopeMode) && !euis.isEmpty())
            throw ApiError.bad("全单位授权不接受设备清单");
        if ("DEVICE_SUBSET".equals(scopeMode) && euis.isEmpty())
            throw ApiError.bad("部分设备授权必须提供设备EUI清单");
        if (euis.size() > com.fireplatform.shared.Limits.MAX_SCOPE_DEVICES) throw ApiError.bad("设备清单超过上限");
        if ("ALL_TENANT".equals(scopeMode) && !devices.all(actor.user().id(), tenant))
            throw ApiError.forbidden("全单位授权需要当前全单位设备范围");
        var deviceIds = new ArrayList<String>();
        for (String eui : euis) {
            var rows = jdbc.query("SELECT id,product_version_id FROM devices WHERE tenant_id=? AND device_eui=?"
                    + " AND archived_at IS NULL AND is_simulated=FALSE",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, tenant, eui);
            if (rows.isEmpty()) throw ApiError.bad("设备不存在、已归档或为仿真设备：" + eui);
            String deviceId = rows.getFirst()[0], version = rows.getFirst()[1];
            if ("FORMAL_VERSION".equals(referenceKind) && !productVersionId.equals(version))
                throw ApiError.bad("设备当前产品版本与授权版本不一致：" + eui);
            if (!devices.contains(actor.user().id(), tenant, deviceId))
                throw ApiError.bad("设备不在当前授权范围：" + eui);
            if ("LEGACY_PRODUCT".equals(referenceKind) && version != null) {
                String deviceLegacy = jdbc.queryForObject("SELECT legacy_product_id FROM product_versions WHERE id=?",
                    String.class, version);
                if (deviceLegacy != null && !deviceLegacy.equals(legacyProductId))
                    throw ApiError.bad("设备的旧产品标识与授权不一致：" + eui);
            }
            if ("VENDOR_MODEL".equals(referenceKind)) {
                var models = jdbc.queryForList("SELECT model FROM hikvision_discovered_devices"
                    + " WHERE local_device_id=? AND model IS NOT NULL", String.class, deviceId);
                if (!models.isEmpty() && models.stream().noneMatch(vendorModelId::equals))
                    throw ApiError.bad("设备的发现型号与授权型号不一致：" + eui);
            }
            deviceIds.add(deviceId);
        }
        String outputName = required(cells.get("output_name"), 200, "输出名称");
        String outputMode = required(cells.get("output_mode"), 24, "输出模式").toUpperCase(Locale.ROOT);
        if (!ApplicationInputs.MODES.contains(outputMode))
            throw ApiError.bad("输出模式必须为NORMALIZED或SINGLE_DEVICE_RAW");
        String outputProtocol = ApplicationInputs.protocol(cells.get("output_protocol"), "HTTPS");
        if ("SINGLE_DEVICE_RAW".equals(outputMode)) {
            boolean permitted = count("SELECT COUNT(*) FROM effective_user_permissions WHERE user_id=?"
                + " AND permission IN ('applications:forward-raw','messages:raw')", actor.user().id()) == 2L;
            if (!permitted) throw ApiError.forbidden("原始报文转发需要应用原始转发权限与消息原文权限");
        }
        String note = optional(cells.get("note"), 1000, "备注");
        var prepared = new Prepared(applicationName, description == null ? "" : description, platform,
            legacyProductId, vendorModelId, driverKey, referenceKind, productId, productVersionId, scopeMode,
            List.copyOf(deviceIds), outputName, outputMode, outputProtocol, note);
        commands.validateReference(tenant, prepared.grant("数据导入"));
        return prepared;
    }

    private boolean exists(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count != null && count > 0;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static String required(String value, int max, String field) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > max || controls(normalized))
            throw ApiError.bad(field + "无效或超过长度限制");
        return normalized;
    }

    private static String optional(String value, int max, String field) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty()) return null;
        if (normalized.length() > max || controls(normalized)) throw ApiError.bad(field + "无效或超过长度限制");
        return normalized;
    }

    private static boolean controls(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) return true;
        }
        return false;
    }

    private static void requireKind(String kind) {
        if (!"APPLICATION_OUTPUTS".equals(kind)) throw ApiError.bad("该类型不支持应用输出导入导出");
    }

    private static String text(String value) {
        String normalized = value == null ? null : value.trim();
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }

    private static String nullable(Object value) { return value == null ? "" : value.toString(); }
}
