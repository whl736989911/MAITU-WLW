package com.fireplatform.controller;

import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.LiteralLike;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.PageSizePolicy;
import com.fireplatform.shared.Pagination;
import com.fireplatform.shared.ScopeFilter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Controller associations are visible only through the real device they reference. */
@Service
public class ControllerResourceQueries {
    private final JdbcTemplate jdbc;
    private final DeviceAccessScope scopes;
    private final ScopeFilter tree;
    private final PageSizePolicy sizes;

    public ControllerResourceQueries(JdbcTemplate jdbc, DeviceAccessScope scopes, ScopeFilter tree, PageSizePolicy sizes) {
        this.jdbc = jdbc; this.scopes = scopes; this.tree = tree; this.sizes = sizes;
    }

    @Transactional(readOnly = true)
    public Page<ControllerModels.Resource> page(String actorId, String tenant, ScopeFilter.Selection selection,
                                                String keyword, boolean includeRetired, int page, Integer size) {
        var pagination = Pagination.of(page, size, sizes.defaultPageSize());
        var visible = visibleClause(actorId, tenant, selection, "d");
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.addAll(visible.bindings());
        var where = new StringBuilder(" WHERE c.tenant_id=? AND ").append(visible.sql());
        if (!includeRetired) where.append(" AND c.state='ACTIVE'");
        String term = ControllerInputs.keyword(keyword);
        if (!term.isEmpty()) {
            String like = LiteralLike.contains(term);
            where.append(" AND (LOWER(c.name) LIKE LOWER(?) ESCAPE '!' OR LOWER(c.service_key) LIKE LOWER(?) ESCAPE '!'"
                + " OR LOWER(COALESCE(d.name,'')) LIKE LOWER(?) ESCAPE '!')");
            args.add(like); args.add(like); args.add(like);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM controller_resources c"
            + " JOIN devices d ON d.id=c.device_id AND d.tenant_id=c.tenant_id" + where,
            Long.class, args.toArray());
        var pageArgs = new ArrayList<Object>(args);
        pageArgs.add(pagination.size());
        pageArgs.add(pagination.offset());
        var items = jdbc.query(select() + where + " ORDER BY c.name,c.id LIMIT ? OFFSET ?",
            (rs, n) -> resource(rs), pageArgs.toArray());
        return new Page<>(items, total, pagination.page(), pagination.size());
    }

    @Transactional(readOnly = true)
    public ControllerModels.Resource visible(String actorId, String tenant, ScopeFilter.Selection selection, String id) {
        var visible = visibleClause(actorId, tenant, selection, "d");
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.add(id);
        args.addAll(visible.bindings());
        var rows = jdbc.query(select() + " WHERE c.tenant_id=? AND c.id=? AND " + visible.sql(),
            (rs, n) -> resource(rs), args.toArray());
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    public ControllerModels.Resource byId(String tenant, String id) {
        var rows = jdbc.query(select() + " WHERE c.tenant_id=? AND c.id=?", (rs, n) -> resource(rs), tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    /** Linkage-time lookup; the caller must already be tenant-scoped by the triggering alarm. */
    public List<ControllerModels.Resource> activeForAction(String tenant, String action, List<String> controllerIds) {
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.add(action);
        var sql = new StringBuilder(select() + " WHERE c.tenant_id=? AND c.action=? AND c.state='ACTIVE'");
        if (controllerIds != null && !controllerIds.isEmpty()) {
            sql.append(" AND c.id IN (")
                .append(String.join(",", java.util.Collections.nCopies(controllerIds.size(), "?"))).append(")");
            args.addAll(controllerIds);
        }
        sql.append(" ORDER BY c.name,c.id");
        return jdbc.query(sql.toString(), (rs, n) -> resource(rs), args.toArray());
    }

    public List<ControllerModels.Resource> enabledControllers(String tenant, String deviceId) {
        return jdbc.query(select() + " WHERE c.tenant_id=? AND c.device_id=? AND c.state='ACTIVE' AND c.enabled=TRUE"
                + " ORDER BY c.action,c.name,c.id",
            (rs, n) -> resource(rs), tenant, deviceId);
    }

    private String select() {
        return "SELECT c.*,d.name AS device_name,d.device_eui FROM controller_resources c"
            + " JOIN devices d ON d.id=c.device_id AND d.tenant_id=c.tenant_id";
    }

    private static ControllerModels.Resource resource(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ControllerModels.Resource(rs.getString("id"), rs.getString("tenant_id"), rs.getString("name"),
            rs.getString("device_id"), rs.getString("device_name"), rs.getString("device_eui"),
            rs.getString("action"), rs.getString("service_key"), rs.getString("driver_key"),
            rs.getString("driver_mode"), rs.getString("service_definition_id"), rs.getLong("capability_revision"),
            rs.getString("support_state"), rs.getString("risk_level"), rs.getString("result_kind"),
            rs.getString("arguments_json"), rs.getBoolean("enabled"), rs.getString("state"), rs.getLong("revision"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private ScopeFilter.Clause visibleClause(String actorId, String tenant, ScopeFilter.Selection selection, String alias) {
        var access = scopes.predicate(actorId, alias + ".tenant_id", alias + ".id");
        var treeClause = tree.devices(actorId, alias, tenant, selection);
        var args = new ArrayList<Object>();
        args.addAll(access.bindings());
        args.addAll(treeClause.bindings());
        return new ScopeFilter.Clause("(" + access.sql() + ") AND " + treeClause.sql(), List.copyOf(args));
    }
}
