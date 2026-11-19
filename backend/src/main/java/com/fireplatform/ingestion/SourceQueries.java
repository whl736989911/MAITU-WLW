package com.fireplatform.ingestion;

import com.fireplatform.shared.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceQueries {
    private final JdbcTemplate jdbc; private final PageSizePolicy sizes; private final DeviceAccessScope scope; private final ScopeFilter scopes;
    public SourceQueries(JdbcTemplate jdbc,PageSizePolicy sizes,DeviceAccessScope scope,ScopeFilter scopes) { this.jdbc=jdbc; this.sizes=sizes; this.scope=scope; this.scopes=scopes; }
    private IngestionModels.Source row(ResultSet rs) throws SQLException { return new IngestionModels.Source(rs.getString("id"),rs.getString("tenant_id"),rs.getString("name"),rs.getString("protocol"),rs.getBoolean("is_simulated"),rs.getBoolean("enabled"),rs.getLong("revision"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("last_received_at",OffsetDateTime.class),rs.getLong("message_count"),rs.getLong("duplicate_count")); }
    public IngestionModels.Source source(String actor,String tenant,String id,boolean lock) { var f=scope.predicate(actor,"r.tenant_id","r.device_id");String includeUnbound=scope.all(actor,tenant)?"TRUE":"FALSE";String count="(SELECT COUNT(*) FROM raw_messages r WHERE r.tenant_id=s.tenant_id AND r.source_id=s.id AND (("+f.sql()+") OR (r.device_id IS NULL AND "+includeUnbound+")))";String duplicates="COALESCE((SELECT SUM(r.duplicate_count) FROM raw_messages r WHERE r.tenant_id=s.tenant_id AND r.source_id=s.id AND (("+f.sql()+") OR (r.device_id IS NULL AND "+includeUnbound+"))),0)";var args=new ArrayList<Object>();args.addAll(f.bindings());args.addAll(f.bindings());args.add(tenant);args.add(id);var rows=jdbc.query("SELECT s.*, "+count+" AS message_count, "+duplicates+" AS duplicate_count FROM ingestion_sources s WHERE s.is_simulated=FALSE AND s.tenant_id=? AND s.id=?"+(lock ? " FOR UPDATE" : ""),(rs,n)->row(rs),args.toArray()); if(rows.isEmpty()) throw ApiError.missing(); return rows.getFirst(); }
    IngestionModels.Source sourceSystem(String tenant,String id,boolean lock) { var rows=jdbc.query("SELECT * FROM ingestion_sources WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),(rs,n)->row(rs),tenant,id);if(rows.isEmpty())throw ApiError.missing();return rows.getFirst(); }
    @Transactional(readOnly=true) public Page<IngestionModels.Source> sources(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size,String keyword) {
        var p=Pagination.of(page,size,sizes.defaultPageSize()); String like=like(keyword);
        var visibleScope=scope.predicate(actor,"r.tenant_id","r.device_id");
        var allDevices=scope.allPredicate(actor,"r.tenant_id");
        var unitScope=scopes.units(actor,"s","tenant_id",tenant,selection);
        String where=" FROM ingestion_sources s WHERE s.is_simulated=FALSE AND "+unitScope.sql()+" AND (LOWER(s.name) LIKE ? ESCAPE '!' OR LOWER(s.id) LIKE ? ESCAPE '!')";
        var baseArgs=new ArrayList<Object>(unitScope.bindings()); baseArgs.add(like); baseArgs.add(like);
        String selectedRows="TRUE"; var selectedArgs=new ArrayList<Object>();
        if(!selection.tenantIds().isEmpty()||!selection.placeIds().isEmpty()) {
            var selectedDevices=scopes.devices(actor,"d",tenant,selection);
            String unboundSelected="1=0";
            if(!selection.tenantIds().isEmpty()) {
                unboundSelected="(r.device_id IS NULL AND r.tenant_id IN ("+String.join(",",Collections.nCopies(selection.tenantIds().size(),"?"))+") AND "+allDevices.sql()+")";
                selectedArgs.addAll(selection.tenantIds()); selectedArgs.addAll(allDevices.bindings());
            }
            selectedRows="("+unboundSelected+" OR EXISTS (SELECT 1 FROM devices d WHERE d.tenant_id=r.tenant_id AND d.id=r.device_id AND "+selectedDevices.sql()+"))";
            selectedArgs.addAll(selectedDevices.bindings());
        }
        String visibleMessageScope="(("+visibleScope.sql()+") OR (r.device_id IS NULL AND "+allDevices.sql()+")) AND "+selectedRows;
        String visibleCount="(SELECT COUNT(*) FROM raw_messages r WHERE r.tenant_id=s.tenant_id AND r.source_id=s.id AND "+visibleMessageScope+")";
        String visibleDuplicates="COALESCE((SELECT SUM(r.duplicate_count) FROM raw_messages r WHERE r.tenant_id=s.tenant_id AND r.source_id=s.id AND "+visibleMessageScope+"),0)";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,baseArgs.toArray());
        var args=new ArrayList<Object>();args.addAll(visibleScope.bindings());args.addAll(allDevices.bindings());args.addAll(selectedArgs);args.addAll(visibleScope.bindings());args.addAll(allDevices.bindings());args.addAll(selectedArgs);args.addAll(baseArgs);args.add(p.size());args.add(p.offset());
        var rows=jdbc.query("SELECT s.*, "+visibleCount+" AS message_count, "+visibleDuplicates+" AS duplicate_count"+where+" ORDER BY s.created_at,s.id LIMIT ? OFFSET ?",(rs,n)->row(rs),args.toArray()); return new Page<>(rows,total,p.page(),p.size());
    }
    static String like(String keyword) { if(keyword==null) keyword=""; if(keyword.length()>Limits.MAX_KEYWORD_LENGTH) throw ApiError.bad("搜索关键字过长"); return "%"+keyword.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }
    private IngestionModels.Binding bindingRow(ResultSet rs) throws SQLException { return new IngestionModels.Binding(rs.getString("id"),rs.getString("source_id"),rs.getString("device_id"),rs.getString("vendor_device_id"),rs.getObject("ended_at",OffsetDateTime.class)==null,rs.getObject("created_at",OffsetDateTime.class),rs.getObject("ended_at",OffsetDateTime.class)); }
    public IngestionModels.Binding binding(String actor,String tenant,String source,String id,boolean lock) { var f=scope.predicate(actor, "source_bindings.tenant_id", "source_bindings.device_id");var args=new ArrayList<Object>(List.of(tenant,source,id));args.addAll(f.bindings());var rows=jdbc.query("SELECT * FROM source_bindings WHERE tenant_id=? AND source_id=? AND id=?"+f.and()+(lock ? " FOR UPDATE" : ""),(rs,n)->bindingRow(rs),args.toArray()); if(rows.isEmpty()) throw ApiError.missing(); return rows.getFirst(); }
    @Transactional(readOnly=true) public List<IngestionModels.Binding> bindings(String actor,String tenant,String source) { source(actor,tenant,source,false); var f=scope.predicate(actor, "source_bindings.tenant_id", "source_bindings.device_id"); var args=new ArrayList<Object>(List.of(tenant,source));args.addAll(f.bindings());return jdbc.query("SELECT * FROM source_bindings WHERE tenant_id=? AND source_id=?"+f.and()+" ORDER BY created_at,id",(rs,n)->bindingRow(rs),args.toArray()); }
}
