package com.fireplatform.place;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authorized unit/place tree for the global workbench scope selector. */
@Service
public class ScopeTreeQueries {
    public record ParentContext(String id, String name) { }
    public record ScopePlaceNode(String id, String tenantId, String tenantName, String name, String parentId,
            String legacyPlaceId, long deviceCount, int level, long revision, ParentContext parentContext) { }
    public record UnitNode(String id, String name, List<ScopePlaceNode> places) { }
    private record Unit(String id, String name) { }
    private record ContextEdge(String placeId, String parentPlaceId, String parentCompanyId) { }
    private record AuthorizedPlace(String id, String tenantId, String legacyPlaceId, String name) { }

    private final JdbcTemplate jdbc;
    private final PlaceQueries places;
    public ScopeTreeQueries(JdbcTemplate jdbc, PlaceQueries places) { this.jdbc = jdbc; this.places = places; }

    @Transactional(readOnly = true)
    public List<UnitNode> tree(String userId) {
        var units = jdbc.query(
            "SELECT t.id,t.name FROM tenants t JOIN user_tenant_grants g ON g.tenant_id=t.id " +
            "WHERE g.user_id=? AND t.enabled=TRUE ORDER BY t.name,t.id",
            (rs, n) -> new Unit(rs.getString(1), rs.getString(2)), userId);
        var authorizedTenantIds=units.stream().map(Unit::id).toList();
        var visiblePlaces=authorizedPlaces(authorizedTenantIds);
        return units.stream().map(unit -> new UnitNode(unit.id(), unit.name(),
            withParentContexts(unit.id(), places.placesForTenant(userId, unit.id()), visiblePlaces))).toList();
    }

    private Map<String,AuthorizedPlace> authorizedPlaces(List<String> tenantIds) {
        if(tenantIds.isEmpty()) return Map.of();
        String placeholders=String.join(",",Collections.nCopies(tenantIds.size(),"?"));
        var rows=jdbc.query("SELECT id,tenant_id,legacy_place_id,name FROM places WHERE archived_at IS NULL AND tenant_id IN ("+placeholders+")",
            (rs,n)->new AuthorizedPlace(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),tenantIds.toArray());
        var byLegacyKey=new HashMap<String,AuthorizedPlace>();
        for(var row:rows) if(row.legacyPlaceId()!=null) byLegacyKey.put(row.tenantId()+"\u0000"+row.legacyPlaceId(),row);
        return byLegacyKey;
    }

    private List<ScopePlaceNode> withParentContexts(String tenantId,List<PlaceQueries.PlaceNode> nodes,
            Map<String,AuthorizedPlace> authorizedPlaces) {
        var edges=jdbc.query("SELECT id,legacy_parent_place_id,legacy_parent_company_id FROM places " +
                "WHERE tenant_id=? AND archived_at IS NULL AND parent_place_id IS NULL AND revision=0 AND legacy_parent_place_id IS NOT NULL",
            (rs,n)->new ContextEdge(rs.getString(1),rs.getString(2),rs.getString(3)),tenantId);
        var parentByChild=new HashMap<String,ParentContext>();
        for(var edge:edges) {
            AuthorizedPlace parent=authorizedPlaces.get("LEGACY-"+edge.parentCompanyId()+"\u0000"+edge.parentPlaceId());
            parentByChild.put(edge.placeId(),parent==null
                ?new ParentContext(null,"上级位置（暂不可见）")
                :new ParentContext(parent.id(),parent.name()));
        }
        return nodes.stream().map(node->new ScopePlaceNode(node.id(),node.tenantId(),node.tenantName(),node.name(),node.parentId(),
            node.legacyPlaceId(),node.deviceCount(),node.level(),node.revision(),parentByChild.get(node.id()))).toList();
    }
}
