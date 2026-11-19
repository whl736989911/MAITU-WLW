package com.fireplatform.bulkio;

import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.LiteralLike;
import com.fireplatform.shared.ScopeFilter;
import com.fireplatform.video.VideoInputs;
import com.fireplatform.video.VideoResourceCommands;
import com.fireplatform.video.VideoResourceQueries;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Video metadata import/export. Spreadsheets never carry endpoints, signed URLs or credentials; imported
 * resources are created disabled with no endpoint and must be configured explicitly afterwards.
 */
@Component
public class VideoDataTransferDomain implements DataTransferDomain {
    private static final List<String> IMPORT_COLUMNS = List.of("name", "resource_kind", "protocol");
    private static final List<String> EXPORT_COLUMNS = List.of("id", "tenant_id", "name", "resource_kind", "protocol",
        "enabled", "endpoint_host", "endpoint_configured", "credentials_configured", "archived", "revision",
        "created_at", "updated_at");

    private final JdbcTemplate jdbc;
    private final VideoResourceQueries queries;
    private final VideoResourceCommands commands;

    public VideoDataTransferDomain(JdbcTemplate jdbc, VideoResourceQueries queries, VideoResourceCommands commands) {
        this.jdbc = jdbc; this.queries = queries; this.commands = commands;
    }

    @Override
    public List<Kind> kinds() {
        return List.of(new Kind("VIDEO_RESOURCES", IMPORT_COLUMNS, "video:read", "video:write",
            true, true, true, Set.of("management"), "DISABLED_DRAFT"));
    }

    @Override
    public List<String> validate(String kind, Map<String, String> cells, AuthService.Identity actor, String tenant) {
        requireKind(kind);
        var errors = new ArrayList<String>();
        VideoInputs.CreateResource input;
        try {
            input = VideoInputs.createResource(new VideoInputs.CreateResourceBody(cells.get("name"),
                cells.get("resource_kind"), cells.get("protocol"), "数据导入草稿"));
        } catch (ApiError failure) {
            errors.add(failure.getMessage());
            return List.copyOf(errors);
        }
        Long duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM video_resources WHERE tenant_id=? AND name=?",
            Long.class, tenant, input.name());
        if (duplicate != null && duplicate != 0) errors.add("该单位已存在同名视频资源");
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
        var input = VideoInputs.createResource(new VideoInputs.CreateResourceBody(cells.get("name"),
            cells.get("resource_kind"), cells.get("protocol"), reason));
        return commands.create(actor, tenant, "import." + jobId + "." + rowNumber, input,
            "data-transfer:" + jobId).id();
    }

    @Override
    public ExportData export(String kind, AuthService.Identity actor, String tenant, Map<String, String> filters,
                             List<String> selectedIds) {
        requireKind(kind);
        if (filters == null) filters = Map.of();
        var selection = DataTransferFilters.selection(filters, Set.of("keyword", "include_archived"));
        if (selection.empty()) return new ExportData(EXPORT_COLUMNS, List.of());
        var visible = queries.visibleDevice(actor.user().id(), tenant, selection, "vd");
        boolean restricted = queries.restricted(actor.user().id(), tenant, selection);
        var args = new ArrayList<Object>();
        args.add(tenant);
        String visibility = "";
        if (restricted) {
            visibility = " AND EXISTS (SELECT 1 FROM video_associations va JOIN devices vd ON vd.id=va.device_id AND vd.tenant_id=va.tenant_id"
                + " WHERE va.tenant_id=r.tenant_id AND va.video_resource_id=r.id AND va.state='ACTIVE' AND " + visible.sql() + ")";
            args.addAll(visible.bindings());
        }
        String includeArchived = text(filters.get("include_archived"));
        if (includeArchived != null && !Set.of("true", "false").contains(includeArchived))
            throw ApiError.bad("归档筛选参数无效");
        String archive = "true".equals(includeArchived) ? "" : " AND r.archived_at IS NULL";
        String keyword = text(filters.get("keyword"));
        String filter = "";
        if (keyword != null) {
            filter = " AND (LOWER(r.name) LIKE LOWER(?) ESCAPE '!' OR LOWER(r.endpoint_host) LIKE LOWER(?) ESCAPE '!')";
        }
        if (keyword != null) { args.add(LiteralLike.contains(keyword)); args.add(LiteralLike.contains(keyword)); }
        var rows = jdbc.query("SELECT r.id,r.tenant_id,r.name,r.resource_kind,r.protocol,r.enabled,r.endpoint_host,"
                + "CASE WHEN r.endpoint_cipher IS NULL THEN 'false' ELSE 'true' END,"
                + "CASE WHEN r.credentials_cipher IS NULL THEN 'false' ELSE 'true' END,"
                + "CASE WHEN r.archived_at IS NULL THEN 'false' ELSE 'true' END,r.revision,r.created_at,r.updated_at"
                + " FROM video_resources r WHERE r.tenant_id=?" + archive + visibility + filter
                + " ORDER BY r.name,r.id LIMIT 10001",
            (rs, n) -> Arrays.asList(nullable(rs.getString(1)), nullable(rs.getString(2)), nullable(rs.getString(3)),
                nullable(rs.getString(4)), nullable(rs.getString(5)), Boolean.toString(rs.getBoolean(6)),
                nullable(rs.getString(7)), rs.getString(8), rs.getString(9), rs.getString(10),
                Long.toString(rs.getLong(11)), nullable(rs.getObject(12, OffsetDateTime.class)),
                nullable(rs.getObject(13, OffsetDateTime.class))),
            args.toArray());
        return DataTransferFilters.selected(new ExportData(EXPORT_COLUMNS, rows), selectedIds);
    }

    private static void requireKind(String kind) {
        if (!"VIDEO_RESOURCES".equals(kind)) throw ApiError.bad("该类型不支持视频资源导入导出");
    }

    private static String text(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String nullable(Object value) { return value == null ? "" : value.toString(); }
}
