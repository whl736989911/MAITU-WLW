package com.fireplatform.place;

import com.fireplatform.shared.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only place trees across the actor's authorized units, narrowed by explicit tree selection. */
@Service
public class PlaceQueries {
    public record PlaceNode(String id, String tenantId, String tenantName, String name, String parentId, String legacyPlaceId, long deviceCount, int level, long revision) { }
    private final JdbcTemplate jdbc;
    private final ScopeFilter scopes;
    private final DeviceAccessScope devices;
    public PlaceQueries(JdbcTemplate jdbc, ScopeFilter scopes, DeviceAccessScope devices) { this.jdbc = jdbc; this.scopes = scopes; this.devices = devices; }

    @Transactional(readOnly = true)
    public List<PlaceNode> places(String actorId, String tenant, ScopeFilter.Selection selection) {
        var clause = scopes.units(actorId, "p", "tenant_id", tenant, selection);
        var deviceClause = devices.predicate(actorId, "d.tenant_id", "d.id");
        var args = new ArrayList<Object>(deviceClause.bindings());
        args.addAll(clause.bindings());
        var rows = jdbc.query(
            "SELECT p.id,p.tenant_id,t.name AS tenant_name,p.name,p.parent_place_id,p.legacy_place_id,p.revision,COUNT(d.id) AS device_count " +
            "FROM places p JOIN tenants t ON t.id=p.tenant_id LEFT JOIN devices d ON d.place_id=p.id AND d.tenant_id=p.tenant_id AND d.archived_at IS NULL " + deviceClause.and() +
            " WHERE p.archived_at IS NULL AND " + clause.sql() + " GROUP BY p.id,p.tenant_id,t.name,p.name,p.parent_place_id,p.legacy_place_id,p.revision ORDER BY t.name,t.id,p.name,p.id",
            (rs, n) -> row(rs), args.toArray());
        if (rows.size() > 5000) throw ApiError.bad("位置数量超过单次读取上限");
        Map<String, PlaceNode> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.id(), row));
        rows = selectedPlaces(rows, selection);
        List<PlaceNode> result = new ArrayList<>(rows.size());
        for (var row : rows) result.add(new PlaceNode(row.id(), row.tenantId(), row.tenantName(), row.name(), row.parentId(), row.legacyPlaceId(), row.deviceCount(), level(row, byId), row.revision()));
        return result;
    }
    private static List<PlaceNode> selectedPlaces(List<PlaceNode> rows, ScopeFilter.Selection selection) {
        if (selection.placeIds().isEmpty()) return rows;
        var children = new HashMap<String, List<String>>();
        for (var row : rows) if (row.parentId() != null) children.computeIfAbsent(row.parentId(), ignored -> new ArrayList<>()).add(row.id());
        var included = new HashSet<String>();
        var pending = new ArrayDeque<String>(selection.placeIds());
        while (!pending.isEmpty()) {
            var id = pending.removeFirst();
            if (included.add(id)) pending.addAll(children.getOrDefault(id, List.of()));
        }
        var tenants = new HashSet<>(selection.tenantIds());
        return rows.stream().filter(row -> tenants.contains(row.tenantId()) || included.contains(row.id())).toList();
    }

    @Transactional(readOnly = true)
    public List<PlaceNode> placesForTenant(String actorId, String tenant) {
        var deviceClause = devices.predicate(actorId, "d.tenant_id", "d.id");
        var args = new ArrayList<Object>(deviceClause.bindings());
        args.add(tenant);
        var rows = jdbc.query("SELECT p.id,p.tenant_id,t.name AS tenant_name,p.name,p.parent_place_id,p.legacy_place_id,p.revision,COUNT(d.id) AS device_count FROM places p JOIN tenants t ON t.id=p.tenant_id LEFT JOIN devices d ON d.place_id=p.id AND d.tenant_id=p.tenant_id AND d.archived_at IS NULL " + deviceClause.and() + " WHERE p.tenant_id=? AND p.archived_at IS NULL GROUP BY p.id,p.tenant_id,t.name,p.name,p.parent_place_id,p.legacy_place_id,p.revision ORDER BY p.name,p.id", (rs,n) -> row(rs), args.toArray());
        Map<String, PlaceNode> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.id(), row));
        return rows.stream().map(row -> new PlaceNode(row.id(), row.tenantId(), row.tenantName(), row.name(), row.parentId(), row.legacyPlaceId(), row.deviceCount(), level(row, byId), row.revision())).toList();
    }


    private static int level(PlaceNode row, Map<String, PlaceNode> byId) {
        int level = 1;
        Set<String> seen = new HashSet<>();
        String parent = row.parentId();
        while (parent != null && !parent.isBlank()) {
            if (!seen.add(parent)) return 0;
            var next = byId.get(parent);
            if (next == null || level >= 64) return level;
            level++;
            parent = next.parentId();
        }
        return level;
    }

    private static PlaceNode row(ResultSet rs) throws SQLException {
        return new PlaceNode(rs.getString("id"), rs.getString("tenant_id"), rs.getString("tenant_name"), rs.getString("name"), rs.getString("parent_place_id"),
            rs.getString("legacy_place_id"), rs.getLong("device_count"), 0, rs.getLong("revision"));
    }
}
