package com.fireplatform.shared;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Builds a parameterized tenant/place-subtree filter for a trusted SQL alias. */
@Service
public class ScopeFilter {
    public record Clause(String sql, List<Object> bindings) {
        public String and() { return sql == null ? "" : " AND (" + sql + ")"; }
    }
    public record Selection(List<String> tenantIds, List<String> placeIds, boolean empty) {
        public Selection {
            tenantIds = tenantIds == null ? List.of() : List.copyOf(tenantIds);
            placeIds = placeIds == null ? List.of() : List.copyOf(placeIds);
            if (tenantIds.size() > 100 || placeIds.size() > 200) throw ApiError.bad("范围筛选超过上限");
        }
        public Selection(List<String> tenantIds, List<String> placeIds) { this(tenantIds, placeIds, false); }
        private static final Selection ALL = new Selection(List.of(), List.of(), false);
        private static final Selection NONE = new Selection(List.of(), List.of(), true);
        public static Selection none() { return NONE; }
        public static Selection all() { return ALL; }
    }
    public record PlaceEdge(String id, String parent) { }

    private final JdbcTemplate jdbc;
    public ScopeFilter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Unit-owned records follow selected units or the owners of selected places, never device attribution. */
    public Clause units(String actorId, String alias, String tenantColumn, String tenant, Selection selection) {
        if (selection.empty()) return new Clause("1=0", List.of());
        String column = tenantColumn.contains(".") ? tenantColumn : alias + "." + tenantColumn;
        var sql = new StringBuilder("EXISTS (SELECT 1 FROM tenants __read_unit WHERE __read_unit.enabled=TRUE AND __read_unit.id=" + column
            + " AND (EXISTS (SELECT 1 FROM effective_user_tenant_grants __read_grant WHERE __read_grant.user_id=? AND __read_grant.tenant_id=__read_unit.id)"
            + " OR EXISTS (SELECT 1 FROM app_users __read_customer WHERE __read_customer.id=? AND __read_customer.user_type='CUSTOMER' AND __read_customer.enabled=TRUE AND __read_customer.tenant_id=__read_unit.id)))");
        var args = new ArrayList<Object>();
        args.add(actorId);
        args.add(actorId);
        if (tenant != null) { sql.append(" AND ").append(column).append("=?"); args.add(tenant); }
        if (!selection.tenantIds().isEmpty() || !selection.placeIds().isEmpty()) {
            sql.append(" AND (");
            if (!selection.tenantIds().isEmpty()) {
                sql.append(column).append(" IN (").append(String.join(",", Collections.nCopies(selection.tenantIds().size(), "?"))).append(")");
                args.addAll(selection.tenantIds());
            }
            if (!selection.placeIds().isEmpty()) {
                if (!selection.tenantIds().isEmpty()) sql.append(" OR ");
                sql.append("EXISTS (SELECT 1 FROM places __read_place WHERE __read_place.tenant_id=").append(column)
                    .append(" AND __read_place.id IN (").append(String.join(",", Collections.nCopies(selection.placeIds().size(), "?"))).append("))");
                args.addAll(selection.placeIds());
            }
            sql.append(")");
        }
        return new Clause("(" + sql + ")", List.copyOf(args));
    }

    /** Tree filtering only; callers also apply their existing DeviceAccessScope policy. */
    public Clause devices(String actorId, String alias, String tenant, Selection selection) {
        if (selection.empty()) return new Clause("1=0", List.of());
        var units = units(actorId, alias, "tenant_id", tenant, Selection.all());
        var tree = clause(alias, selection.tenantIds(), selection.placeIds());
        if (tree.sql() == null) return units;
        var args = new ArrayList<Object>(units.bindings());
        args.addAll(tree.bindings());
        return new Clause("(" + units.sql() + ") AND (" + tree.sql() + ")", List.copyOf(args));
    }

    /** Empty tenant and place selections mean no additional filter. */
    public Clause clause(String alias, List<String> tenantIds, List<String> placeIds) {
        var tenants = tenantIds == null ? List.<String>of() : tenantIds;
        var places = placeIds == null ? List.<String>of() : placeIds;
        if (tenants.isEmpty() && places.isEmpty()) return new Clause(null, List.of());
        if (tenants.size() > 100 || places.size() > 200) throw ApiError.bad("范围筛选超过上限");
        var sql = new StringBuilder();
        var args = new ArrayList<Object>();
        if (!tenants.isEmpty()) {
            sql.append(alias).append(".tenant_id IN (").append(String.join(",", Collections.nCopies(tenants.size(), "?"))).append(')');
            args.addAll(tenants);
        }
        if (!places.isEmpty()) {
            var expanded = expandPlaces(places);
            if (sql.length() > 0) sql.append(" OR ");
            if (expanded.isEmpty()) sql.append("1=0");
            else {
                sql.append(alias).append(".place_id IN (").append(String.join(",", Collections.nCopies(expanded.size(), "?"))).append(')');
                args.addAll(expanded);
            }
        }
        return new Clause("(" + sql + ")", args);
    }

    /** Correlated filter for alarm/event rows whose device is referenced by another alias. */
    public Clause alarmDevices(String alarmAlias, List<String> tenantIds, List<String> placeIds) {
        var inner = clause("__scope_device", tenantIds, placeIds);
        if (inner.sql() == null) return inner;
        return new Clause("EXISTS (SELECT 1 FROM devices __scope_device WHERE __scope_device.tenant_id=" + alarmAlias
            + ".tenant_id AND __scope_device.id=" + alarmAlias + ".device_id AND " + inner.sql() + ")", inner.bindings());
    }

    private List<String> expandPlaces(List<String> roots) {
        var args = new ArrayList<Object>(roots);
        String where = "SELECT id,parent_place_id FROM places WHERE id IN (" + String.join(",", Collections.nCopies(roots.size(), "?")) + ")";
        var selected = jdbc.query(where, (rs, n) -> new PlaceEdge(rs.getString(1), rs.getString(2)), args.toArray());
        var rows = jdbc.query("SELECT id,parent_place_id FROM places", (rs, n) -> new PlaceEdge(rs.getString(1), rs.getString(2)));
        if (rows.size() > 10000) throw ApiError.bad("位置数量超过筛选上限");
        var children = new HashMap<String, List<String>>();
        for (var row : rows) if (row.parent() != null) children.computeIfAbsent(row.parent(), k -> new ArrayList<>()).add(row.id());
        var result = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>();
        for (var root : selected) queue.add(root.id());
        while (!queue.isEmpty()) {
            var id = queue.removeFirst();
            if (result.add(id)) for (var child : children.getOrDefault(id, List.of())) queue.add(child);
        }
        return List.copyOf(result);
    }
}
