package com.fireplatform.maintenance;

import com.fireplatform.shared.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only work-order projections. Fault payloads and device/source state are never exposed here. */
@Service
public class MaintenanceQueries {
    record Locked(String status, long revision, String assigneeUserId, String faultEventId,
                  String activeFaultEventId, String deviceId) { }

    static final String ORDER_COLUMNS = "w.id,w.tenant_id,w.device_id,d.name AS device_name,d.address AS device_address,"
        + "w.fault_event_id,w.title,w.description,w.priority,w.due_at,w.status,w.assignee_user_id,a.display_name AS assignee_name,"
        + "w.revision,w.created_at,w.updated_at,w.closed_at,w.cancelled_at";
    static final String ORDER_FROM = " FROM maintenance_work_orders w JOIN devices d ON d.id=w.device_id AND d.tenant_id=w.tenant_id"
        + " LEFT JOIN app_users a ON a.id=w.assignee_user_id";

    private final JdbcTemplate jdbc;
    private final DeviceAccessScope devices;
    private final ScopeFilter scope;
    private final PageSizePolicy sizes;
    private final TransactionRequirements transactions;

    public MaintenanceQueries(JdbcTemplate jdbc, DeviceAccessScope devices, ScopeFilter scope, PageSizePolicy sizes,
                              TransactionRequirements transactions) {
        this.jdbc = jdbc; this.devices = devices; this.scope = scope; this.sizes = sizes; this.transactions = transactions;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MaintenanceModels.OrderPage page(String actor, String tenant, ScopeFilter.Selection selection,
                                            String status, String keyword, String deviceId, int page, Integer requestedSize) {
        transactions.repeatableReadOnly();
        Pagination pagination = Pagination.of(page, requestedSize, sizes.defaultPageSize());
        String normalizedStatus = status(status);
        String normalizedKeyword = MaintenanceInputs.keyword(keyword);
        String normalizedDevice = MaintenanceInputs.blank(deviceId) ? null : MaintenanceInputs.deviceId(deviceId);
        return pageInTransaction(actor, tenant, selection, normalizedStatus, normalizedKeyword, normalizedDevice, pagination);
    }

    MaintenanceModels.OrderPage pageInTransaction(String actor, String tenant, ScopeFilter.Selection selection,
                                                  String status, String keyword, String deviceId, Pagination pagination) {
        if (selection.empty()) return new MaintenanceModels.OrderPage(List.of(), 0, pagination.page(), pagination.size());
        var access = devices.predicate(actor, "w.tenant_id", "w.device_id");
        var tree = scope.devices(actor, "d", tenant, selection);
        var args = new ArrayList<Object>();
        args.addAll(access.bindings());
        args.addAll(tree.bindings());
        var where = new StringBuilder(" WHERE d.is_simulated=FALSE AND (").append(access.sql()).append(") AND ").append(tree.sql());
        if (status != null) { where.append(" AND w.status=?"); args.add(status); }
        if (deviceId != null) { where.append(" AND w.device_id=?"); args.add(deviceId); }
        if (!keyword.isEmpty()) {
            String like = LiteralLike.contains(keyword);
            where.append(" AND (LOWER(w.title) LIKE LOWER(?) ESCAPE '!' OR LOWER(w.description) LIKE LOWER(?) ESCAPE '!' OR LOWER(COALESCE(d.name,'')) LIKE LOWER(?) ESCAPE '!')");
            args.add(like); args.add(like); args.add(like);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*)" + ORDER_FROM + where, Long.class, args.toArray());
        var window = new ArrayList<>(args);
        window.add(pagination.size());
        window.add(pagination.offset());
        var items = jdbc.query("SELECT " + ORDER_COLUMNS + ORDER_FROM + where
            + " ORDER BY w.updated_at DESC,w.id DESC LIMIT ? OFFSET ?", MaintenanceQueries::order, window.toArray());
        return new MaintenanceModels.OrderPage(items, total, pagination.page(), pagination.size());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MaintenanceModels.OrderDetail detail(String actor, String tenant, ScopeFilter.Selection selection, String id) {
        transactions.repeatableReadOnly();
        MaintenanceInputs.orderId(id);
        if (selection.empty()) throw ApiError.missing();
        var access = devices.predicate(actor, "w.tenant_id", "w.device_id");
        var tree = scope.devices(actor, "d", tenant, selection);
        var args = new ArrayList<Object>();
        args.addAll(access.bindings());
        args.addAll(tree.bindings());
        args.add(id);
        var rows = jdbc.query("SELECT " + ORDER_COLUMNS + ORDER_FROM
            + " WHERE d.is_simulated=FALSE AND (" + access.sql() + ") AND " + tree.sql() + " AND w.id=?",
            MaintenanceQueries::order, args.toArray());
        if (rows.isEmpty()) throw ApiError.missing();
        return detailFor(rows.getFirst());
    }

    /** Post-write projection inside the caller's transaction; visibility was already enforced by the command. */
    MaintenanceModels.OrderDetail detailCurrent(String tenant, String id) {
        var rows = jdbc.query("SELECT " + ORDER_COLUMNS + ORDER_FROM + " WHERE w.tenant_id=? AND w.id=?",
            MaintenanceQueries::order, tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        return detailFor(rows.getFirst());
    }

    private MaintenanceModels.OrderDetail detailFor(MaintenanceModels.OrderSummary order) {
        var timeline = jdbc.query("SELECT t.id,t.action,t.from_status,t.to_status,t.actor_id,u.display_name AS actor_name,"
                + "t.reason,t.assignee_user_id,t.revision_before,t.revision_after,t.work_log_id,t.created_at"
                + " FROM maintenance_work_order_transitions t LEFT JOIN app_users u ON u.id=t.actor_id"
                + " WHERE t.tenant_id=? AND t.work_order_id=? ORDER BY t.revision_after,t.created_at,t.id",
            (rs, n) -> new MaintenanceModels.Transition(rs.getString("id"), rs.getString("action"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getString("actor_id"),
                rs.getString("actor_name"), rs.getString("reason"), rs.getString("assignee_user_id"),
                rs.getObject("revision_before", Long.class), rs.getObject("revision_after", Long.class),
                rs.getString("work_log_id"), rs.getObject("created_at", OffsetDateTime.class)),
            order.tenantId(), order.id());
        var logs = jdbc.query("SELECT l.id,l.actor_id,u.display_name AS actor_name,l.note,l.result,"
                + "r.replacement_device_id,rd.name AS replacement_device_name,l.created_at"
                + " FROM maintenance_work_logs l LEFT JOIN app_users u ON u.id=l.actor_id"
                + " LEFT JOIN maintenance_work_order_replacements r ON r.work_log_id=l.id"
                + " LEFT JOIN devices rd ON rd.id=r.replacement_device_id AND rd.tenant_id=r.tenant_id"
                + " WHERE l.tenant_id=? AND l.work_order_id=? ORDER BY l.revision_after,l.created_at,l.id",
            (rs, n) -> new MaintenanceModels.WorkLog(rs.getString("id"), rs.getString("actor_id"),
                rs.getString("actor_name"), rs.getString("note"), rs.getString("result"),
                rs.getString("replacement_device_id"), rs.getString("replacement_device_name"),
                rs.getObject("created_at", OffsetDateTime.class)),
            order.tenantId(), order.id());
        var replacements = jdbc.query("SELECT r.id,r.work_log_id,r.source_device_id,r.replacement_device_id,"
                + "rd.name AS replacement_device_name,r.created_by,r.created_at"
                + " FROM maintenance_work_order_replacements r"
                + " LEFT JOIN devices rd ON rd.id=r.replacement_device_id AND rd.tenant_id=r.tenant_id"
                + " WHERE r.tenant_id=? AND r.work_order_id=? ORDER BY r.created_at,r.id",
            (rs, n) -> new MaintenanceModels.Replacement(rs.getString("id"), rs.getString("work_log_id"),
                rs.getString("source_device_id"), rs.getString("replacement_device_id"),
                rs.getString("replacement_device_name"), rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class)),
            order.tenantId(), order.id());
        return new MaintenanceModels.OrderDetail(order, timeline, logs, replacements);
    }

    Locked locked(String tenant, String id) {
        MaintenanceInputs.orderId(id);
        var rows = jdbc.query("SELECT status,revision,assignee_user_id,fault_event_id,active_fault_event_id,device_id"
                + " FROM maintenance_work_orders WHERE tenant_id=? AND id=? FOR UPDATE",
            (rs, n) -> new Locked(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6)),
            tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        var locked = rows.getFirst();
        if (!MaintenanceLifecycle.STATUSES.contains(locked.status())) throw new IllegalStateException("Stored work-order status is invalid");
        return locked;
    }

    List<MaintenanceModels.Assignee> assigneeCandidates(String tenant, String deviceId, String keyword) {
        var args = new ArrayList<Object>();
        args.add(tenant); args.add(tenant);
        var where = new StringBuilder(" WHERE u.enabled=TRUE AND u.must_change_password=FALSE"
            + " AND EXISTS(SELECT 1 FROM effective_user_permissions p WHERE p.user_id=u.id AND p.permission='maintenance:write')"
            + " AND ((u.user_type='CUSTOMER' AND u.tenant_id=?)"
            + " OR (u.user_type='PLATFORM' AND EXISTS(SELECT 1 FROM user_tenant_grants g WHERE g.user_id=u.id AND g.tenant_id=?)))");
        if (!MaintenanceInputs.blank(keyword)) {
            String like = LiteralLike.contains(MaintenanceInputs.keyword(keyword));
            where.append(" AND (LOWER(u.username) LIKE LOWER(?) ESCAPE '!' OR LOWER(u.display_name) LIKE LOWER(?) ESCAPE '!')");
            args.add(like); args.add(like);
        }
        var candidates = jdbc.query("SELECT u.id,u.username,u.display_name FROM app_users u" + where
            + " ORDER BY u.display_name,u.id LIMIT 1000",
            (rs, n) -> new MaintenanceModels.Assignee(rs.getString(1), rs.getString(2), rs.getString(3)), args.toArray());
        var allowed = devices.usersWithDevice(candidates.stream().map(MaintenanceModels.Assignee::userId).toList(), tenant, deviceId);
        return candidates.stream().filter(candidate -> allowed.contains(candidate.userId())).limit(100).toList();
    }

    boolean assigneeAllowed(String tenant, String deviceId, String userId) {
        if (userId == null || !devices.contains(userId, tenant, deviceId)) return false;
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_users u"
            + " WHERE u.id=? AND u.enabled=TRUE AND u.must_change_password=FALSE"
            + " AND EXISTS(SELECT 1 FROM effective_user_permissions p WHERE p.user_id=u.id AND p.permission='maintenance:write')"
            + " AND ((u.user_type='CUSTOMER' AND u.tenant_id=?)"
            + " OR (u.user_type='PLATFORM' AND EXISTS(SELECT 1 FROM user_tenant_grants g WHERE g.user_id=u.id AND g.tenant_id=?)))",
            Long.class, userId, tenant, tenant) == 1L;
    }

    private static String status(String value) {
        if (MaintenanceInputs.blank(value)) return null;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!MaintenanceLifecycle.STATUSES.contains(normalized)) throw ApiError.bad("工单状态筛选无效");
        return normalized;
    }

    private static MaintenanceModels.OrderSummary order(ResultSet rs, int n) throws SQLException {
        return new MaintenanceModels.OrderSummary(rs.getString("id"), rs.getString("tenant_id"),
            rs.getString("device_id"), rs.getString("device_name"), rs.getString("device_address"),
            rs.getString("fault_event_id"), rs.getString("title"), rs.getString("description"),
            rs.getString("priority"), rs.getObject("due_at", OffsetDateTime.class), rs.getString("status"),
            rs.getString("assignee_user_id"), rs.getString("assignee_name"), rs.getLong("revision"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
            rs.getObject("closed_at", OffsetDateTime.class), rs.getObject("cancelled_at", OffsetDateTime.class));
    }
}
