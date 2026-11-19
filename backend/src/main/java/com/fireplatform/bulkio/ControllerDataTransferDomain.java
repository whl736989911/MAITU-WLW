package com.fireplatform.bulkio;

import com.fireplatform.controller.ControllerInputs;
import com.fireplatform.controller.ControllerResourceCommands;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.DeviceScopes;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.LiteralLike;
import com.fireplatform.shared.ScopeFilter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Controller association import/export. Imports create disabled drafts and validate the live capability;
 * no import enables automation or dispatches a command.
 */
@Component
public class ControllerDataTransferDomain implements DataTransferDomain {
    private static final List<String> IMPORT_COLUMNS = List.of("name", "device_id", "action", "service_key",
        "arguments_json");
    private static final List<String> EXPORT_COLUMNS = List.of("id", "tenant_id", "name", "device_id", "device_eui",
        "action", "service_key", "driver_key", "driver_mode", "support_state", "risk_level", "result_kind",
        "enabled", "state", "revision", "created_at", "updated_at");

    private final JdbcTemplate jdbc;
    private final DeviceScopes devices;
    private final ScopeFilter tree;
    private final ControllerResourceCommands commands;

    public ControllerDataTransferDomain(JdbcTemplate jdbc, DeviceScopes devices, ScopeFilter tree,
                                        ControllerResourceCommands commands) {
        this.jdbc = jdbc; this.devices = devices; this.tree = tree; this.commands = commands;
    }

    @Override
    public List<Kind> kinds() {
        return List.of(new Kind("CONTROLLER_RESOURCES", IMPORT_COLUMNS, "controller:read", "controller:write",
            true, true, true, Set.of("management"), "DISABLED_DRAFT"));
    }

    @Override
    public List<String> validate(String kind, Map<String, String> cells, AuthService.Identity actor, String tenant) {
        requireKind(kind);
        var errors = new ArrayList<String>();
        ControllerInputs.Create input;
        try {
            input = ControllerInputs.create(new ControllerInputs.CreateBody(cells.get("name"), cells.get("device_id"),
                cells.get("action"), cells.get("service_key"), cells.get("arguments_json"),
                "数据导入草稿"));
        } catch (ApiError failure) {
            errors.add(failure.getMessage());
            return List.copyOf(errors);
        }
        if (!devices.contains(actor.user().id(), tenant, input.deviceId()))
            return List.of("设备不存在或不在当前授权范围");
        try {
            commands.resolve(actor.user().id(), tenant, input.deviceId(), input.serviceKey(), input.argumentsJson());
        } catch (ApiError failure) {
            errors.add(failure.getMessage());
            return List.copyOf(errors);
        }
        Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM controller_resources WHERE tenant_id=?"
            + " AND device_id=? AND action=? AND state='ACTIVE'", Long.class, tenant, input.deviceId(), input.action());
        if (existing != null && existing != 0) errors.add("该设备动作已有控制器关联；导入不会覆盖");
        return List.copyOf(errors);
    }

    @Override
    public String operation(String kind, Map<String, String> cells) {
        requireKind(kind);
        return "CREATE_DISABLED";
    }

    @Override
    public String apply(String kind, Map<String, String> cells, AuthService.Identity actor, String tenant,
                        String reason, String jobId, int rowNumber) {
        requireKind(kind);
        var input = ControllerInputs.create(new ControllerInputs.CreateBody(cells.get("name"), cells.get("device_id"),
            cells.get("action"), cells.get("service_key"), cells.get("arguments_json"), reason));
        return commands.importController(actor, tenant, input, reason, "data-transfer:" + jobId).id();
    }

    @Override
    public ExportData export(String kind, AuthService.Identity actor, String tenant, Map<String, String> filters,
                             List<String> selectedIds) {
        requireKind(kind);
        if (filters == null) filters = Map.of();
        var selection = DataTransferFilters.selection(filters, Set.of("keyword", "include_retired"));
        if (selection.empty()) return new ExportData(EXPORT_COLUMNS, List.of());
        var access = devices.predicate(actor.user().id(), "d.tenant_id", "d.id");
        var treeClause = tree.devices(actor.user().id(), "d", tenant, selection);
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.addAll(access.bindings());
        args.addAll(treeClause.bindings());
        var where = new StringBuilder(" WHERE c.tenant_id=? AND (").append(access.sql()).append(") AND ")
            .append(treeClause.sql());
        String includeRetired = text(filters.get("include_retired"));
        if (includeRetired != null && !Set.of("true", "false").contains(includeRetired))
            throw ApiError.bad("停用筛选参数无效");
        if (!"true".equals(includeRetired)) where.append(" AND c.state='ACTIVE'");
        String keyword = text(filters.get("keyword"));
        if (keyword != null) {
            String like = LiteralLike.contains(keyword);
            where.append(" AND (LOWER(c.name) LIKE LOWER(?) ESCAPE '!' OR LOWER(c.service_key) LIKE LOWER(?) ESCAPE '!'"
                + " OR LOWER(COALESCE(d.name,'')) LIKE LOWER(?) ESCAPE '!')");
            args.add(like); args.add(like); args.add(like);
        }
        var rows = jdbc.query("SELECT c.id,c.tenant_id,c.name,c.device_id,d.device_eui,c.action,c.service_key,"
                + "c.driver_key,c.driver_mode,c.support_state,c.risk_level,c.result_kind,c.enabled,c.state,c.revision,"
                + "c.created_at,c.updated_at FROM controller_resources c"
                + " JOIN devices d ON d.id=c.device_id AND d.tenant_id=c.tenant_id" + where
                + " ORDER BY c.name,c.id LIMIT 10001",
            (rs, n) -> Arrays.asList(nullable(rs.getString(1)), nullable(rs.getString(2)), nullable(rs.getString(3)),
                nullable(rs.getString(4)), nullable(rs.getString(5)), nullable(rs.getString(6)), nullable(rs.getString(7)),
                nullable(rs.getString(8)), nullable(rs.getString(9)), nullable(rs.getString(10)), nullable(rs.getString(11)),
                nullable(rs.getString(12)), Boolean.toString(rs.getBoolean(13)), nullable(rs.getString(14)),
                Long.toString(rs.getLong(15)), nullable(rs.getObject(16, OffsetDateTime.class)),
                nullable(rs.getObject(17, OffsetDateTime.class))),
            args.toArray());
        return DataTransferFilters.selected(new ExportData(EXPORT_COLUMNS, rows), selectedIds);
    }

    private static void requireKind(String kind) {
        if (!"CONTROLLER_RESOURCES".equals(kind)) throw ApiError.bad("该类型不支持控制器关联导入导出");
    }

    private static String text(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String nullable(Object value) { return value == null ? "" : value.toString(); }
}
