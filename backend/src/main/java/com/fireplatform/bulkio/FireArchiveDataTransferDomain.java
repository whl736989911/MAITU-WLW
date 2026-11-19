package com.fireplatform.bulkio;

import com.fireplatform.firearchive.FireArchiveCommands;
import com.fireplatform.firearchive.FireArchiveInputs;
import com.fireplatform.firearchive.FireArchiveQueries;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.DeviceScopes;
import com.fireplatform.maintenance.MaintenanceInputs;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.LiteralLike;
import com.fireplatform.shared.ScopeFilter;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Metadata import/export for unit fire archives. Attachments are never part of a bulk transfer. */
@Component
public class FireArchiveDataTransferDomain implements DataTransferDomain {
    private static final List<String> IMPORT_COLUMNS = List.of("title", "category", "archive_number", "device_id",
        "place_id", "issued_on", "expires_on", "description");
    private static final List<String> EXPORT_COLUMNS = List.of("id", "tenant_id", "title", "category", "archive_number",
        "device_id", "device_name", "place_id", "place_name", "issued_on", "expires_on", "description",
        "status", "revision", "created_at", "updated_at", "archived_at");
    private static final Set<String> AUDIENCES = Set.of("management", "customer");

    private final JdbcTemplate jdbc;
    private final DeviceScopes devices;
    private final ScopeFilter scope;
    private final FireArchiveQueries queries;
    private final FireArchiveCommands commands;

    public FireArchiveDataTransferDomain(JdbcTemplate jdbc, DeviceScopes devices, ScopeFilter scope,
                                         FireArchiveQueries queries, FireArchiveCommands commands) {
        this.jdbc = jdbc; this.devices = devices; this.scope = scope; this.queries = queries; this.commands = commands;
    }

    @Override
    public List<Kind> kinds() {
        return List.of(new Kind("FIRE_ARCHIVES", IMPORT_COLUMNS, "fire-archives:read", "fire-archives:write",
            true, true, true, AUDIENCES, "MASTER_DATA"));
    }

    @Override
    public List<String> validate(String kind, Map<String, String> cells, AuthService.Identity actor, String tenant) {
        requireKind(kind);
        var errors = new ArrayList<String>();
        FireArchiveInputs.Create input;
        try {
            input = normalize(cells);
        } catch (ApiError failure) {
            errors.add(failure.getMessage());
            return List.copyOf(errors);
        }
        var metadata = input.metadata();
        if (metadata.deviceId() != null) {
            if (!queries.actualDevice(tenant, metadata.deviceId())) errors.add("设备不存在或已归档");
            else if (!devices.contains(actor.user().id(), tenant, metadata.deviceId()))
                errors.add("设备不在当前授权范围");
        }
        if (metadata.placeId() != null && !queries.placeInUnit(tenant, metadata.placeId()))
            errors.add("位置不存在或已归档");
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
        String idemKey = "data-transfer:" + jobId + ":" + rowNumber;
        var metadata = normalize(cells).metadata();
        var detail = commands.create(actor, tenant, idemKey,
            new FireArchiveInputs.Create(metadata, reason), "data-transfer");
        return detail.archive().id();
    }

    @Override
    public ExportData export(String kind, AuthService.Identity actor, String tenant, Map<String, String> filters,
                             List<String> selectedIds) {
        requireKind(kind);
        if (filters == null) filters = Map.of();
        var selection = DataTransferFilters.selection(filters, Set.of("keyword", "status", "device_id"));
        if (selection.empty()) return new ExportData(EXPORT_COLUMNS, List.of());
        var access = devices.predicate(actor.user().id(), "f.tenant_id", "f.device_id");
        var tree = scope.units(actor.user().id(), "f", "tenant_id", tenant, selection);
        var args = new ArrayList<Object>();
        args.addAll(tree.bindings());
        args.addAll(access.bindings());
        var where = new StringBuilder(" WHERE ").append(tree.sql())
            .append(" AND (f.device_id IS NULL OR (").append(access.sql()).append("))");
        String status = text(filters.get("status"));
        if (status != null) {
            String normalized = status.toUpperCase(java.util.Locale.ROOT);
            if (!"ACTIVE".equals(normalized) && !"ARCHIVED".equals(normalized)) throw ApiError.bad("档案状态筛选无效");
            where.append(" AND f.status=?"); args.add(normalized);
        }
        String deviceId = text(filters.get("device_id"));
        if (deviceId != null) { where.append(" AND f.device_id=?"); args.add(MaintenanceInputs.deviceId(deviceId)); }
        String keyword = MaintenanceInputs.keyword(filters.get("keyword"));
        if (!keyword.isEmpty()) {
            String like = LiteralLike.contains(keyword);
            where.append(" AND (LOWER(f.title) LIKE LOWER(?) ESCAPE '!' OR LOWER(f.category) LIKE LOWER(?) ESCAPE '!'"
                + " OR LOWER(COALESCE(f.archive_number,'')) LIKE LOWER(?) ESCAPE '!'"
                + " OR LOWER(COALESCE(f.description,'')) LIKE LOWER(?) ESCAPE '!'"
                + " OR LOWER(COALESCE(d.name,'')) LIKE LOWER(?) ESCAPE '!')");
            args.add(like); args.add(like); args.add(like); args.add(like); args.add(like);
        }
        var rows = jdbc.query("SELECT f.id,f.tenant_id,f.title,f.category,f.archive_number,f.device_id,"
                + "d.name AS device_name,f.place_id,p.name AS place_name,f.issued_on,f.expires_on,f.description,"
                + "f.status,f.revision,f.created_at,f.updated_at,f.archived_at FROM fire_archives f"
                + " LEFT JOIN devices d ON d.id=f.device_id AND d.tenant_id=f.tenant_id"
                + " LEFT JOIN places p ON p.id=f.place_id AND p.tenant_id=f.tenant_id" + where
                + " ORDER BY f.updated_at DESC,f.id DESC LIMIT 10001",
            (rs, n) -> Arrays.asList(nullable(rs.getString(1)), nullable(rs.getString(2)), nullable(rs.getString(3)),
                nullable(rs.getString(4)), nullable(rs.getString(5)), nullable(rs.getString(6)),
                nullable(rs.getString(7)), nullable(rs.getString(8)), nullable(rs.getString(9)),
                nullable(rs.getObject(10, LocalDate.class)), nullable(rs.getObject(11, LocalDate.class)),
                nullable(rs.getString(12)), nullable(rs.getString(13)), Long.toString(rs.getLong(14)),
                nullable(rs.getObject(15, OffsetDateTime.class)), nullable(rs.getObject(16, OffsetDateTime.class)),
                nullable(rs.getObject(17, OffsetDateTime.class))),
            args.toArray());
        return DataTransferFilters.selected(new ExportData(EXPORT_COLUMNS, rows), selectedIds);
    }

    private static FireArchiveInputs.Create normalize(Map<String, String> cells) {
        return FireArchiveInputs.create(new FireArchiveInputs.CreateBody(cells.get("title"), cells.get("category"),
            cells.get("archive_number"), cells.get("device_id"), cells.get("place_id"), cells.get("issued_on"),
            cells.get("expires_on"), cells.get("description"), "数据导入"));
    }

    private static void requireKind(String kind) {
        if (!"FIRE_ARCHIVES".equals(kind)) throw ApiError.bad("该类型不支持消防档案导入导出");
    }

    private static String text(String value) {
        String normalized = value == null ? null : value.trim();
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }

    private static String nullable(Object value) { return value == null ? "" : value.toString(); }
}
