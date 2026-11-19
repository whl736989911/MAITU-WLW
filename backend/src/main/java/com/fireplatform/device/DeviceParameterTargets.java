package com.fireplatform.device;

import com.fireplatform.shared.*;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Device-owned minimal directory, independent from DeviceQueryService/policy beans. No writes. */
@Service
public class DeviceParameterTargets implements ParameterTargets {
    private final JdbcTemplate jdbc; private final PageSizePolicy sizes;private final DeviceAccessScope scopes; private final ScopeFilter selections;
    public DeviceParameterTargets(JdbcTemplate jdbc,PageSizePolicy sizes,DeviceAccessScope scopes,ScopeFilter selections) { this.jdbc=jdbc; this.sizes=sizes;this.scopes=scopes;this.selections=selections; }
    @Override public Target require(String actor,String tenant,String device,boolean lock) {
        var scope=scopes.predicate(actor, "devices.tenant_id", "devices.id");var args=new java.util.ArrayList<Object>();args.add(tenant);args.addAll(scope.bindings());args.add(device);
        var rows=jdbc.query("SELECT id,name,tenant_id FROM devices WHERE tenant_id=?"+scope.and()+" AND id=? AND archived_at IS NULL"+(lock?" FOR UPDATE":""),(rs,n)->new Target(rs.getString(1),rs.getString(2),rs.getString(3)),args.toArray()); if(rows.isEmpty()) throw ApiError.missing(); return rows.getFirst();
    }
    @Override public Page<Target> list(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size,String keyword) {
        var p=Pagination.of(page,size,sizes.defaultPageSize()); if(keyword==null) keyword=""; if(keyword.length()>Limits.MAX_KEYWORD_LENGTH) throw ApiError.bad("搜索关键字过长"); String like="%"+keyword.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        var authorized=scopes.predicate(actor, "devices.tenant_id", "devices.id");
        var selected=selections.devices(actor,"devices",tenant,selection);
        var args=new java.util.ArrayList<Object>(selected.bindings());args.addAll(authorized.bindings());args.add(like);args.add(like);
        String from=" FROM devices WHERE "+selected.sql()+authorized.and()+" AND archived_at IS NULL AND (LOWER(id) LIKE ? ESCAPE '!' OR LOWER(name) LIKE ? ESCAPE '!')";
        long count=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray()); args.add(p.size());args.add(p.offset());
        var rows=jdbc.query("SELECT id,name,tenant_id"+from+" ORDER BY tenant_id,id LIMIT ? OFFSET ?",(rs,n)->new Target(rs.getString(1),rs.getString(2),rs.getString(3)),args.toArray()); return new Page<>(rows,count,p.page(),p.size());
    }
}
