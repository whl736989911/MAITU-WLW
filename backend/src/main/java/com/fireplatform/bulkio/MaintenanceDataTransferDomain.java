package com.fireplatform.bulkio;

import com.fireplatform.duty.DutyQueries;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.DeviceScopes;
import com.fireplatform.maintenance.MaintenanceCommands;
import com.fireplatform.maintenance.MaintenanceInputs;
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

/** Data-transfer adapter: work-order imports create local drafts only; exports are scope-preserving reads. */
@Component
public class MaintenanceDataTransferDomain implements DataTransferDomain {
    private static final List<String> IMPORT_COLUMNS = List.of("device_id", "title", "description", "priority",
        "due_at", "fault_event_id");
    private static final List<String> EXPORT_COLUMNS = List.of("id", "tenant_id", "device_id", "device_name",
        "fault_event_id", "title", "description", "priority", "due_at", "status", "assignee_user_id",
        "assignee_name", "revision", "created_at", "updated_at");
    private static final Set<String> AUDIENCES = Set.of("management", "customer");

    private final JdbcTemplate jdbc;
    private final DeviceScopes devices;
    private final ScopeFilter scope;
    private final DutyQueries duty;
    private final MaintenanceCommands commands;

    public MaintenanceDataTransferDomain(JdbcTemplate jdbc, DeviceScopes devices, ScopeFilter scope, DutyQueries duty,
                                         MaintenanceCommands commands) {
        this.jdbc = jdbc; this.devices = devices; this.scope = scope; this.duty = duty; this.commands = commands;
    }

    @Override
    public List<Kind> kinds() {
        return List.of(new Kind("WORK_ORDERS", IMPORT_COLUMNS, "maintenance:read", "maintenance:write",
            true, true, true, AUDIENCES, "DRAFT_ONLY"));
    }

    @Override
    public List<String> validate(String kind, Map<String, String> cells, AuthService.Identity actor, String tenant) {
        requireKind(kind);
        var errors = new ArrayList<String>();
        MaintenanceInputs.Create input;
        try {
            input = normalize(cells, "数据导入草稿");
        } catch (ApiError failure) {
            errors.add(failure.getMessage());
            return List.copyOf(errors);
        }
        if (!devices.contains(actor.user().id(), tenant, input.deviceId())) {
            errors.add("设备不存在或不在当前授权范围");
            return List.copyOf(errors);
        }
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id=? AND id=? AND is_simulated=FALSE"
            + " AND archived_at IS NULL", Long.class, tenant, input.deviceId());
        if (exists == null || exists != 1L) {
            errors.add("设备不存在或已归档");
            return List.copyOf(errors);
        }
        if (input.faultEventId() != null) {
            try {
                var event = duty.fault(actor.user().id(), tenant, ScopeFilter.Selection.all(), input.faultEventId());
                if (!input.deviceId().equals(event.deviceId())) errors.add("故障事件必须属于同一设备");
            } catch (ApiError failure) {
                errors.add("故障事件不存在或不在当前授权范围");
            }
        }
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
        var detail = commands.create(actor, tenant, "data-transfer:" + jobId + ":" + rowNumber,
            normalize(cells, reason), "data-transfer", true);
        return detail.order().id();
    }

    @Override
    public ExportData export(String kind, AuthService.Identity actor, String tenant, Map<String, String> filters,
                             List<String> selectedIds) {
        requireKind(kind);
        if (filters == null) filters = Map.of();
        var selection = DataTransferFilters.selection(filters, Set.of("keyword", "status", "device_id"));
        if (selection.empty()) return new ExportData(EXPORT_COLUMNS, List.of());
        var access = devices.predicate(actor.user().id(), "w.tenant_id", "w.device_id");
        var tree = scope.devices(actor.user().id(), "d", tenant, selection);
        var args = new ArrayList<Object>();
        args.addAll(access.bindings());
        args.addAll(tree.bindings());
        var where = new StringBuilder(" WHERE d.is_simulated=FALSE AND (").append(access.sql()).append(") AND ").append(tree.sql());
        String status = text(filters.get("status"));
        if (status != null) {
            String normalized = status.toUpperCase(java.util.Locale.ROOT);
            if (!com.fireplatform.maintenance.MaintenanceLifecycle.STATUSES.contains(normalized))
                throw ApiError.bad("工单状态筛选无效");
            where.append(" AND w.status=?"); args.add(normalized);
        }
        String deviceId = text(filters.get("device_id"));
        if (deviceId != null) { where.append(" AND w.device_id=?"); args.add(MaintenanceInputs.deviceId(deviceId)); }
        String keyword = MaintenanceInputs.keyword(filters.get("keyword"));
        if (!keyword.isEmpty()) {
            String like = LiteralLike.contains(keyword);
            where.append(" AND (LOWER(w.title) LIKE LOWER(?) ESCAPE '!' OR LOWER(w.description) LIKE LOWER(?) ESCAPE '!'"
                + " OR LOWER(COALESCE(d.name,'')) LIKE LOWER(?) ESCAPE '!')");
            args.add(like); args.add(like); args.add(like);
        }
        var rows = jdbc.query("SELECT w.id,w.tenant_id,w.device_id,d.name AS device_name,w.fault_event_id,w.title,"
                + "w.description,w.priority,w.due_at,w.status,w.assignee_user_id,a.display_name AS assignee_name,"
                + "w.revision,w.created_at,w.updated_at FROM maintenance_work_orders w"
                + " JOIN devices d ON d.id=w.device_id AND d.tenant_id=w.tenant_id"
                + " LEFT JOIN app_users a ON a.id=w.assignee_user_id" + where
                + " ORDER BY w.updated_at DESC,w.id DESC LIMIT 10001",
            (rs, n) -> Arrays.asList(nullable(rs.getString(1)), nullable(rs.getString(2)), nullable(rs.getString(3)),
                nullable(rs.getString(4)), nullable(rs.getString(5)), nullable(rs.getString(6)),
                nullable(rs.getString(7)), nullable(rs.getString(8)), nullable(rs.getObject(9, OffsetDateTime.class)),
                nullable(rs.getString(10)), nullable(rs.getString(11)), nullable(rs.getString(12)),
                Long.toString(rs.getLong(13)), nullable(rs.getObject(14, OffsetDateTime.class)),
                nullable(rs.getObject(15, OffsetDateTime.class))),
            args.toArray());
        return DataTransferFilters.selected(new ExportData(EXPORT_COLUMNS, rows), selectedIds);
    }

    private static MaintenanceInputs.Create normalize(Map<String, String> cells, String reason) {
        return MaintenanceInputs.create(new MaintenanceInputs.CreateBody(cells.get("fault_event_id"),
            cells.get("device_id"), cells.get("title"), cells.get("description"), cells.get("priority"),
            cells.get("due_at"), reason));
    }

    private static void requireKind(String kind) {
        if (!"WORK_ORDERS".equals(kind)) throw ApiError.bad("该类型不支持维护工单导入导出");
    }

    private static String text(String value) {
        String normalized = value == null ? null : value.trim();
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }

    private static String nullable(Object value) { return value == null ? "" : value.toString(); }
}
