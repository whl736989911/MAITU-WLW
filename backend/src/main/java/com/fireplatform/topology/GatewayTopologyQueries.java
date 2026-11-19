package com.fireplatform.topology;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.LiteralLike;
import com.fireplatform.shared.PageSizePolicy;
import com.fireplatform.shared.Pagination;
import com.fireplatform.shared.ScopeFilter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scoped topology reads. Visibility is decided by the current device scope; a narrowed place/unit scope
 * only ever returns children inside it, and child counts are counts of visible children (never totals).
 */
@Service
public class GatewayTopologyQueries {
    private static final int MAX_TREE_CHILDREN = 200;

    private final JdbcTemplate jdbc;
    private final DeviceAccessScope scopes;
    private final ScopeFilter tree;
    private final PageSizePolicy sizes;

    public GatewayTopologyQueries(JdbcTemplate jdbc, DeviceAccessScope scopes, ScopeFilter tree, PageSizePolicy sizes) {
        this.jdbc = jdbc; this.scopes = scopes; this.tree = tree; this.sizes = sizes;
    }

    @Transactional(readOnly = true)
    public TopologyModels.GatewayPage gateways(String actorId, String tenant, ScopeFilter.Selection selection,
                                               String keyword, int page, Integer size) {
        var pagination = Pagination.of(page, size, sizes.defaultPageSize());
        String term = TopologyInputs.keyword(keyword);
        var visibleCd = visibleDevice(actorId, tenant, selection, "cd");
        var visibleGd = visibleDevice(actorId, tenant, selection, "gd");
        var visibleLd = visibleDevice(actorId, tenant, selection, "ld");
        var visibleBacking = visibleDevice(actorId, tenant, selection, "d");
        boolean restricted = restricted(actorId, tenant, selection);
        String visibility = "";
        var visibilityArgs = new ArrayList<Object>();
        if (restricted) {
            visibility = " AND (EXISTS (SELECT 1 FROM devices gd WHERE gd.id=g.device_id AND gd.tenant_id=g.tenant_id AND "
                + visibleGd.sql() + ")"
                + " OR EXISTS (SELECT 1 FROM gateway_links gl JOIN devices ld ON ld.id=gl.device_id AND ld.tenant_id=gl.tenant_id"
                + " WHERE gl.tenant_id=g.tenant_id AND gl.gateway_id=g.id AND gl.state='ACTIVE' AND " + visibleLd.sql() + "))";
            visibilityArgs.addAll(visibleGd.bindings());
            visibilityArgs.addAll(visibleLd.bindings());
        }
        String filter = "";
        if (!term.isEmpty()) {
            String like = LiteralLike.contains(term);
            filter = " AND (LOWER(g.address) LIKE LOWER(?) ESCAPE '!' OR LOWER(g.name) LIKE LOWER(?) ESCAPE '!')";
        }
        var countArgs = new ArrayList<Object>();
        countArgs.add(tenant);
        countArgs.addAll(visibilityArgs);
        if (!term.isEmpty()) { countArgs.add(LiteralLike.contains(term)); countArgs.add(LiteralLike.contains(term)); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM gateway_nodes g WHERE g.tenant_id=? AND g.state='ACTIVE'"
            + visibility + filter, Long.class, countArgs.toArray());

        var pageArgs = new ArrayList<Object>(visibleCd.bindings());
        pageArgs.addAll(visibleBacking.bindings());
        pageArgs.add(tenant);
        pageArgs.addAll(visibilityArgs);
        if (!term.isEmpty()) { pageArgs.add(LiteralLike.contains(term)); pageArgs.add(LiteralLike.contains(term)); }
        pageArgs.add(pagination.size());
        pageArgs.add(pagination.offset());
        var items = jdbc.query("SELECT g.id,g.tenant_id,g.address,g.name,g.protocol,d.id AS device_id,d.name AS device_name,"
                + "g.state,g.revision,g.created_at,g.updated_at,"
                + "(SELECT COUNT(*) FROM gateway_links lc JOIN devices cd ON cd.id=lc.device_id AND cd.tenant_id=lc.tenant_id"
                + " WHERE lc.tenant_id=g.tenant_id AND lc.gateway_id=g.id AND lc.state='ACTIVE' AND " + visibleCd.sql()
                + ") AS visible_children"
                + " FROM gateway_nodes g LEFT JOIN devices d ON d.id=g.device_id AND d.tenant_id=g.tenant_id AND " + visibleBacking.sql()
                + " WHERE g.tenant_id=? AND g.state='ACTIVE'" + visibility + filter
                + " ORDER BY g.address,g.id LIMIT ? OFFSET ?",
            (rs, n) -> new TopologyModels.Gateway(rs.getString("id"), rs.getString("tenant_id"), rs.getString("address"),
                rs.getString("name"), rs.getString("protocol"), rs.getString("device_id"), rs.getString("device_name"),
                rs.getString("state"), rs.getLong("revision"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("visible_children")),
            pageArgs.toArray());
        return new TopologyModels.GatewayPage(items, total, pagination.page(), pagination.size(), restricted);
    }

    @Transactional(readOnly = true)
    public TopologyModels.LinkPage links(String actorId, String tenant, ScopeFilter.Selection selection,
                                         String gatewayId, String keyword, String childKind, String state,
                                         int page, Integer size) {
        var pagination = Pagination.of(page, size, sizes.defaultPageSize());
        var visible = visibleDevice(actorId, tenant, selection, "d");
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.addAll(visible.bindings());
        var where = new StringBuilder(" WHERE l.tenant_id=? AND ").append(visible.sql());
        String gateway = blank(gatewayId) ? null : TopologyInputs.id(gatewayId, "网关ID");
        if (gateway != null) { where.append(" AND l.gateway_id=?"); args.add(gateway); }
        String kindFilter = blank(childKind) ? null : childKind.trim().toUpperCase(java.util.Locale.ROOT);
        if (kindFilter != null) {
            if (!TopologyInputs.CHILD_KINDS.contains(kindFilter)) throw ApiError.bad("关系类型筛选无效");
            where.append(" AND l.child_kind=?"); args.add(kindFilter);
        }
        String stateFilter = blank(state) ? "ACTIVE" : state.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("ACTIVE", "REVOKED", "ALL").contains(stateFilter)) throw ApiError.bad("关系状态筛选无效");
        if (!"ALL".equals(stateFilter)) { where.append(" AND l.state=?"); args.add(stateFilter); }
        String term = TopologyInputs.keyword(keyword);
        if (!term.isEmpty()) {
            String like = LiteralLike.contains(term);
            where.append(" AND (LOWER(COALESCE(d.name,'')) LIKE LOWER(?) ESCAPE '!' OR LOWER(COALESCE(d.device_eui,'')) LIKE LOWER(?) ESCAPE '!'"
                + " OR LOWER(g.address) LIKE LOWER(?) ESCAPE '!' OR LOWER(COALESCE(c.name,'')) LIKE LOWER(?) ESCAPE '!')");
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM gateway_links l"
            + " JOIN gateway_nodes g ON g.id=l.gateway_id AND g.tenant_id=l.tenant_id"
            + " JOIN devices d ON d.id=l.device_id AND d.tenant_id=l.tenant_id"
            + " LEFT JOIN device_channels c ON c.id=l.channel_id AND c.tenant_id=l.tenant_id AND c.device_id=l.device_id"
            + where, Long.class, args.toArray());
        var pageArgs = new ArrayList<Object>(args);
        pageArgs.add(pagination.size());
        pageArgs.add(pagination.offset());
        var items = jdbc.query(selectLinks() + where + " ORDER BY l.updated_at DESC,l.id DESC LIMIT ? OFFSET ?",
            (rs, n) -> link(rs), pageArgs.toArray());
        return new TopologyModels.LinkPage(items, total, pagination.page(), pagination.size(),
            restricted(actorId, tenant, selection));
    }

    @Transactional(readOnly = true)
    public TopologyModels.GatewayTree gatewayTree(String actorId, String tenant, ScopeFilter.Selection selection,
                                                  String gatewayId) {
        String gateway = TopologyInputs.id(gatewayId, "网关ID");
        var visible = visibleDevice(actorId, tenant, selection, "d");
        var gatewayArgs = new ArrayList<Object>(visible.bindings());
        gatewayArgs.add(tenant);
        gatewayArgs.add(gateway);
        var gatewayRows = jdbc.query("SELECT g.id,g.tenant_id,g.address,g.name,g.protocol,d.id AS device_id,d.name AS device_name,"
                + "g.state,g.revision,g.created_at,g.updated_at"
                + " FROM gateway_nodes g LEFT JOIN devices d ON d.id=g.device_id AND d.tenant_id=g.tenant_id AND " + visible.sql()
                + " WHERE g.tenant_id=? AND g.id=? AND g.state='ACTIVE'",
            (rs, n) -> new TopologyModels.Gateway(rs.getString("id"), rs.getString("tenant_id"), rs.getString("address"),
                rs.getString("name"), rs.getString("protocol"), rs.getString("device_id"), rs.getString("device_name"),
                rs.getString("state"), rs.getLong("revision"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), 0L), gatewayArgs.toArray());
        if (gatewayRows.isEmpty()) throw ApiError.missing();
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.add(gateway);
        args.addAll(visible.bindings());
        args.add(MAX_TREE_CHILDREN + 1);
        var links = jdbc.query(selectLinks() + " WHERE l.tenant_id=? AND l.gateway_id=? AND l.state='ACTIVE' AND " + visible.sql()
                + " ORDER BY d.name,l.id LIMIT ?",
            (rs, n) -> link(rs), args.toArray());
        boolean truncated = links.size() > MAX_TREE_CHILDREN;
        var bounded = truncated ? List.copyOf(links.subList(0, MAX_TREE_CHILDREN)) : List.copyOf(links);
        return new TopologyModels.GatewayTree(gatewayRows.getFirst(), bounded,
            restricted(actorId, tenant, selection), truncated,
            truncated ? "仅显示前 " + MAX_TREE_CHILDREN + " 条可见子节点；请使用关系表按设备筛选查看其余记录"
                : bounded.isEmpty() ? "该网关当前没有可见的设备或测点关系；不会推断未登记关系" : null);
    }

    private String selectLinks() {
        return "SELECT l.id,l.tenant_id,l.gateway_id,g.address AS gateway_address,g.name AS gateway_name,"
            + "l.child_kind,l.device_id,d.name AS device_name,d.device_eui,d.place_id,p.name AS place_name,"
            + "l.channel_id,c.name AS channel_name,c.channel_type,c.enabled AS channel_enabled,d.archived_at AS device_archived_at,"
            + "l.provenance,l.observation_source,l.observation_event_id,l.observed_at,l.verified,l.verified_by,"
            + "l.verified_at,l.verification_note,l.state,l.revoked_by,l.revoked_at,l.revoke_reason,l.revision,"
            + "l.created_by,l.created_at,l.updated_at"
            + " FROM gateway_links l"
            + " JOIN gateway_nodes g ON g.id=l.gateway_id AND g.tenant_id=l.tenant_id"
            + " JOIN devices d ON d.id=l.device_id AND d.tenant_id=l.tenant_id"
            + " LEFT JOIN places p ON p.id=d.place_id AND p.tenant_id=d.tenant_id"
            + " LEFT JOIN device_channels c ON c.id=l.channel_id AND c.tenant_id=l.tenant_id AND c.device_id=l.device_id";
    }

    private static TopologyModels.Link link(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TopologyModels.Link(rs.getString("id"), rs.getString("tenant_id"), rs.getString("gateway_id"),
            rs.getString("gateway_address"), rs.getString("gateway_name"), rs.getString("child_kind"),
            rs.getString("device_id"), rs.getString("device_name"), rs.getString("device_eui"), rs.getString("place_id"),
            rs.getString("place_name"), rs.getString("channel_id"), rs.getString("channel_name"),
            rs.getString("channel_type"), rs.getBoolean("channel_enabled"),
            rs.getObject("device_archived_at", OffsetDateTime.class), rs.getString("provenance"),
            rs.getString("observation_source"), rs.getString("observation_event_id"),
            rs.getObject("observed_at", OffsetDateTime.class), rs.getBoolean("verified"), rs.getString("verified_by"),
            rs.getObject("verified_at", OffsetDateTime.class), rs.getString("verification_note"), rs.getString("state"),
            rs.getString("revoked_by"), rs.getObject("revoked_at", OffsetDateTime.class), rs.getString("revoke_reason"),
            rs.getLong("revision"), rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));
    }

    /** A device is visible only when the current device scope and the selected unit/place tree both allow it. */
    public ScopeFilter.Clause visibleDevice(String actorId, String tenant, ScopeFilter.Selection selection, String alias) {
        var access = scopes.predicate(actorId, alias + ".tenant_id", alias + ".id");
        var treeClause = tree.devices(actorId, alias, tenant, selection);
        var args = new ArrayList<Object>();
        args.addAll(access.bindings());
        args.addAll(treeClause.bindings());
        return new ScopeFilter.Clause("(" + access.sql() + ") AND " + treeClause.sql(), List.copyOf(args));
    }

    private boolean restricted(String actorId, String tenant, ScopeFilter.Selection selection) {
        return !selection.tenantIds().isEmpty() || !selection.placeIds().isEmpty() || selection.empty()
            || !scopes.all(actorId, tenant);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
