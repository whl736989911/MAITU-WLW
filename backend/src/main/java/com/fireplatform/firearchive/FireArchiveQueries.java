package com.fireplatform.firearchive;

import com.fireplatform.shared.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Scoped read projections for unit fire archives. Attachments return metadata only, never ciphertext. */
@Service
public class FireArchiveQueries {
    static final String COLUMNS = "f.id,f.tenant_id,f.title,f.category,f.archive_number,f.device_id,"
        + "d.name AS device_name,f.place_id,p.name AS place_name,f.issued_on,f.expires_on,f.description,"
        + "f.status,f.revision,f.created_at,f.updated_at,f.archived_by,au.display_name AS archived_by_name,"
        + "f.archived_at,f.archived_reason,(SELECT COUNT(*) FROM fire_archive_attachments a"
        + " WHERE a.tenant_id=f.tenant_id AND a.archive_id=f.id) AS attachment_count";
    static final String FROM = " FROM fire_archives f"
        + " LEFT JOIN devices d ON d.id=f.device_id AND d.tenant_id=f.tenant_id"
        + " LEFT JOIN places p ON p.id=f.place_id AND p.tenant_id=f.tenant_id"
        + " LEFT JOIN app_users au ON au.id=f.archived_by";

    private final JdbcTemplate jdbc;
    private final DeviceAccessScope devices;
    private final ScopeFilter scope;
    private final PageSizePolicy sizes;
    private final TransactionRequirements transactions;

    public FireArchiveQueries(JdbcTemplate jdbc, DeviceAccessScope devices, ScopeFilter scope, PageSizePolicy sizes,
                              TransactionRequirements transactions) {
        this.jdbc = jdbc; this.devices = devices; this.scope = scope; this.sizes = sizes; this.transactions = transactions;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FireArchiveModels.Page page(String actor, String tenant, ScopeFilter.Selection selection, String status,
                                       String keyword, String deviceId, int page, Integer requestedSize) {
        transactions.repeatableReadOnly();
        Pagination pagination = Pagination.of(page, requestedSize, sizes.defaultPageSize());
        String normalizedStatus = status(status);
        String normalizedKeyword = com.fireplatform.maintenance.MaintenanceInputs.keyword(keyword);
        String normalizedDevice = deviceId(deviceId);
        if (selection.empty()) return new FireArchiveModels.Page(List.of(), 0, pagination.page(), pagination.size());
        var filter = visibility(actor, tenant, selection);
        var args = new ArrayList<Object>(filter.bindings());
        var where = new StringBuilder(" WHERE ").append(filter.sql());
        if (normalizedStatus != null) { where.append(" AND f.status=?"); args.add(normalizedStatus); }
        if (normalizedDevice != null) { where.append(" AND f.device_id=?"); args.add(normalizedDevice); }
        if (!normalizedKeyword.isEmpty()) {
            String like = LiteralLike.contains(normalizedKeyword);
            where.append(" AND (LOWER(f.title) LIKE LOWER(?) ESCAPE '!' OR LOWER(f.category) LIKE LOWER(?) ESCAPE '!'"
                + " OR LOWER(COALESCE(f.archive_number,'')) LIKE LOWER(?) ESCAPE '!'"
                + " OR LOWER(COALESCE(f.description,'')) LIKE LOWER(?) ESCAPE '!'"
                + " OR LOWER(COALESCE(d.name,'')) LIKE LOWER(?) ESCAPE '!')");
            args.add(like); args.add(like); args.add(like); args.add(like); args.add(like);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*)" + FROM + where, Long.class, args.toArray());
        var window = new ArrayList<>(args);
        window.add(pagination.size());
        window.add(pagination.offset());
        var items = jdbc.query("SELECT " + COLUMNS + FROM + where
            + " ORDER BY f.updated_at DESC,f.id DESC LIMIT ? OFFSET ?", FireArchiveQueries::summary, window.toArray());
        return new FireArchiveModels.Page(items, total, pagination.page(), pagination.size());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FireArchiveModels.Detail detail(String actor, String tenant, ScopeFilter.Selection selection, String id) {
        transactions.repeatableReadOnly();
        FireArchiveInputs.archiveId(id);
        if (selection.empty()) throw ApiError.missing();
        var filter = visibility(actor, tenant, selection);
        var args = new ArrayList<Object>(filter.bindings());
        args.add(id);
        var rows = jdbc.query("SELECT " + COLUMNS + FROM + " WHERE " + filter.sql() + " AND f.id=?",
            FireArchiveQueries::summary, args.toArray());
        if (rows.isEmpty()) throw ApiError.missing();
        return detailFor(rows.getFirst());
    }

    FireArchiveModels.Detail detailCurrent(String tenant, String id) {
        var rows = jdbc.query("SELECT " + COLUMNS + FROM + " WHERE f.tenant_id=? AND f.id=?",
            FireArchiveQueries::summary, tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        return detailFor(rows.getFirst());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<FireArchiveModels.History> history(String actor, String tenant, ScopeFilter.Selection selection,
                                                   String id) {
        transactions.repeatableReadOnly();
        var detail = detail(actor, tenant, selection, id);
        return jdbc.query("SELECT h.id,h.action,h.revision_before,h.revision_after,h.actor_id,"
                + "u.display_name AS actor_name,h.reason,h.created_at FROM fire_archive_history h"
                + " LEFT JOIN app_users u ON u.id=h.actor_id WHERE h.tenant_id=? AND h.archive_id=?"
                + " ORDER BY h.created_at,h.id", (rs, n) -> new FireArchiveModels.History(rs.getString("id"),
                rs.getString("action"), rs.getObject("revision_before", Long.class),
                rs.getObject("revision_after", Long.class), rs.getString("actor_id"), rs.getString("actor_name"),
                rs.getString("reason"), rs.getObject("created_at", OffsetDateTime.class)),
            detail.archive().tenantId(), id);
    }

    FireArchiveModels.Locked locked(String tenant, String id) {
        FireArchiveInputs.archiveId(id);
        var rows = jdbc.query("SELECT status,revision,device_id,place_id FROM fire_archives WHERE tenant_id=? AND id=? FOR UPDATE",
            (rs, n) -> new FireArchiveModels.Locked(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)), tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        var locked = rows.getFirst();
        if (!"ACTIVE".equals(locked.status()) && !"ARCHIVED".equals(locked.status()))
            throw new IllegalStateException("Stored fire archive status is invalid");
        return locked;
    }

    FireArchiveModels.StoredAttachment storedAttachment(String actor, String tenant, ScopeFilter.Selection selection,
                                                        String archiveId, String attachmentId) {
        FireArchiveInputs.archiveId(archiveId);
        FireArchiveInputs.attachmentId(attachmentId);
        if (selection.empty()) throw ApiError.missing();
        var filter = visibility(actor, tenant, selection);
        var args = new ArrayList<Object>(filter.bindings());
        args.add(archiveId);
        args.add(attachmentId);
        var rows = jdbc.query("SELECT a.id,a.archive_id,a.tenant_id,a.filename,a.mime_type,a.byte_size,"
                + "a.content_sha256,a.ciphertext,a.nonce,f.status FROM fire_archive_attachments a"
                + " JOIN fire_archives f ON f.id=a.archive_id AND f.tenant_id=a.tenant_id"
                + " WHERE " + filter.sql() + " AND a.archive_id=? AND a.id=?",
            (rs, n) -> new FireArchiveModels.StoredAttachment(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10)), args.toArray());
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    public boolean actualDevice(String tenant, String deviceId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id=? AND id=? AND is_simulated=FALSE"
            + " AND archived_at IS NULL", Long.class, tenant, deviceId);
        return count != null && count == 1L;
    }

    public boolean placeInUnit(String tenant, String placeId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE tenant_id=? AND id=? AND archived_at IS NULL",
            Long.class, tenant, placeId);
        return count != null && count == 1L;
    }

    private FireArchiveModels.Detail detailFor(FireArchiveModels.Summary archive) {
        var attachments = jdbc.query("SELECT a.id,a.filename,a.mime_type,a.byte_size,a.content_sha256,a.uploaded_by,"
                + "u.display_name AS uploader_name,a.uploaded_at FROM fire_archive_attachments a"
                + " LEFT JOIN app_users u ON u.id=a.uploaded_by WHERE a.tenant_id=? AND a.archive_id=?"
                + " ORDER BY a.uploaded_at,a.id", (rs, n) -> new FireArchiveModels.Attachment(rs.getString("id"),
                rs.getString("filename"), rs.getString("mime_type"), rs.getInt("byte_size"),
                rs.getString("content_sha256"), rs.getString("uploaded_by"), rs.getString("uploader_name"),
                rs.getObject("uploaded_at", OffsetDateTime.class)), archive.tenantId(), archive.id());
        return new FireArchiveModels.Detail(archive, attachments);
    }

    private Visibility visibility(String actor, String tenant, ScopeFilter.Selection selection) {
        var tree = scope.units(actor, "f", "tenant_id", tenant, selection);
        var access = devices.predicate(actor, "f.tenant_id", "f.device_id");
        var bindings = new ArrayList<Object>(tree.bindings());
        bindings.addAll(access.bindings());
        return new Visibility(tree.sql() + " AND (f.device_id IS NULL OR (" + access.sql() + "))", bindings);
    }

    private record Visibility(String sql, List<Object> bindings) { }

    private static String status(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"ACTIVE".equals(normalized) && !"ARCHIVED".equals(normalized)) throw ApiError.bad("档案状态筛选无效");
        return normalized;
    }

    private static String deviceId(String value) {
        if (value == null || value.isBlank()) return null;
        return com.fireplatform.maintenance.MaintenanceInputs.deviceId(value);
    }

    private static FireArchiveModels.Summary summary(ResultSet rs, int n) throws SQLException {
        return new FireArchiveModels.Summary(rs.getString("id"), rs.getString("tenant_id"), rs.getString("title"),
            rs.getString("category"), rs.getString("archive_number"), rs.getString("device_id"),
            rs.getString("device_name"), rs.getString("place_id"), rs.getString("place_name"),
            rs.getObject("issued_on", LocalDate.class), rs.getObject("expires_on", LocalDate.class),
            rs.getString("description"), rs.getString("status"), rs.getLong("revision"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
            rs.getString("archived_by"), rs.getString("archived_by_name"),
            rs.getObject("archived_at", OffsetDateTime.class), rs.getString("archived_reason"),
            rs.getLong("attachment_count"));
    }
}
