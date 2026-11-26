package com.fireplatform.configuration;

import com.fireplatform.shared.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParameterQueries {
    private final JdbcTemplate jdbc; private final ParameterTargets targets; private final OfflinePolicySql sql; private final Clock clock; private final PageSizePolicy sizes;private final DeviceAccessScope scopes;
    public ParameterQueries(JdbcTemplate jdbc,ParameterTargets targets,OfflinePolicySql sql,Clock clock,PageSizePolicy sizes,DeviceAccessScope scopes) { this.jdbc=jdbc; this.targets=targets; this.sql=sql; this.clock=clock; this.sizes=sizes;this.scopes=scopes; }
    public List<ParameterModels.Definition> definitions() {
        return jdbc.query("SELECT setting_key,setting_value,revision FROM numeric_settings WHERE setting_key IN ('offline_threshold_minutes','default_page_size') ORDER BY setting_key",(rs,n)->{ String key=rs.getString(1); var def=Limits.SETTINGS.get(key); boolean offline=key.equals(OfflinePolicySql.KEY); return new ParameterModels.Definition(key,offline?"设备上报超时阈值":"默认分页大小",def.unit(),def.min(),def.max(),"INTEGER",offline?List.of("GLOBAL","TENANT","PLACE","GROUP","DEVICE"):List.of("GLOBAL"),offline?OfflinePolicySql.CONSUMER:"QUERY_PAGINATION_ONLY",offline?"LOCAL_DERIVATION":"QUERY_ONLY",rs.getLong(2),rs.getLong(3)); });
    }
    public record GroupTarget(String id, String name) { }

    @Transactional(readOnly=true)
    public Page<GroupTarget> groups(String actor, String tenant, int page, Integer size) {
        scopes.requireAll(actor,tenant);
        var pagination=Pagination.of(page,size,sizes.defaultPageSize());
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM device_groups WHERE tenant_id=?",Long.class,tenant);
        var items=jdbc.query("SELECT id,name FROM device_groups WHERE tenant_id=? ORDER BY name,id LIMIT ? OFFSET ?",
            (rs,n)->new GroupTarget(rs.getString(1),rs.getString(2)),tenant,pagination.size(),pagination.offset());
        return new Page<>(items,total,pagination.page(),pagination.size());
    }
    public void validate(String actor,String tenant,String scope,String id,String key,boolean lock) {
        if(!Set.of("TENANT","PLACE","GROUP","DEVICE").contains(scope)) throw ApiError.bad("作用域仅支持单位、位置、分组或设备");
        if(!Limits.SETTINGS.containsKey(key)) throw ApiError.missing();
        if(!key.equals(OfflinePolicySql.KEY)) throw ApiError.conflict("PARAMETER_SCOPE_UNSUPPORTED","该参数仅支持全局设置");
        switch(scope) {
            case "TENANT" -> { if(!tenant.equals(id)) throw ApiError.missing(); }
            case "PLACE" -> {
                scopes.requirePlace(actor,tenant,id);
                var places=jdbc.queryForList("SELECT id FROM places WHERE tenant_id=? AND id=? AND archived_at IS NULL"+(lock?" FOR UPDATE":""),String.class,tenant,id);
                if(places.isEmpty())throw ApiError.missing();
            }
            case "GROUP" -> {
                scopes.requireAll(actor,tenant);
                var groups=jdbc.queryForList("SELECT id FROM device_groups WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),String.class,tenant,id);
                if(groups.isEmpty())throw ApiError.missing();
            }
            default -> targets.require(actor,tenant,id,lock);
        }
    }
    @Transactional(readOnly=true) public ParameterModels.PolicyView view(String actor,String tenant,String scope,String id,String key) {
        validate(actor,tenant,scope,id,key,false);
        boolean placeScope=scope.equals("PLACE"), deviceScope=scope.equals("DEVICE"), groupScope=scope.equals("GROUP");
        String device=deviceScope?id:null;
        var evaluated=OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        String target=deviceScope?"SELECT tenant_id,id,place_id FROM devices WHERE tenant_id=? AND id=? AND archived_at IS NULL"
            :"SELECT CAST(? AS VARCHAR(64)) AS tenant_id,CAST(NULL AS VARCHAR(64)) AS id,CAST(? AS VARCHAR(64)) AS place_id";
        String columns=sql.columns(), join="";
        var args=new ArrayList<Object>();args.add(tenant);args.add(deviceScope||placeScope?id:null);args.add(evaluated);
        if(placeScope) {
            columns+=", own_place.override_value AS policy_own_place_value, own_place.revision AS policy_own_place_revision";
            join=" LEFT JOIN parameter_overrides own_place ON own_place.tenant_id=d.tenant_id AND own_place.scope='PLACE'"
                +" AND own_place.scope_id=d.place_id AND own_place.parameter_key='offline_threshold_minutes'";
        }
        if(groupScope) {
            columns+=", own_group.override_value AS policy_own_group_value, own_group.revision AS policy_own_group_revision";
            join=" LEFT JOIN parameter_overrides own_group ON own_group.tenant_id=d.tenant_id AND own_group.scope='GROUP'"
                +" AND own_group.scope_id=? AND own_group.parameter_key='offline_threshold_minutes'";
            args.add(id);
        }
        if(deviceScope){var access=scopes.predicate(actor,"d.tenant_id","d.id");join+=" WHERE "+access.sql();args.addAll(access.bindings());}
        var rows=jdbc.query("SELECT "+columns+" FROM ("+target+") d "+sql.joins()+join,(rs,n)->{
            var effective=sql.effective(rs,tenant,device);
            String prefix=deviceScope?"policy_device_":placeScope?"policy_own_place_":groupScope?"policy_own_group_":"policy_tenant_";
            Long value=rs.getObject(prefix+"value",Long.class); long revision=rs.getLong(prefix+"revision");
            var chain=sql.chain(rs,tenant,device,effective);
            if(placeScope && value==null) {
                var inherited=new ArrayList<>(chain);inherited.add(new ParameterModels.ChainEntry("PLACE",id,false,null,revision,false));
                chain=List.copyOf(inherited);
            }
            if(groupScope) {
                if(value!=null) effective=new ParameterModels.Effective(key,value,effective.unit(),"GROUP",id,revision,evaluated);
                chain=sql.chain(rs,tenant,device,effective);
                var inherited=new ArrayList<>(chain);
                inherited.add(new ParameterModels.ChainEntry("GROUP",id,value!=null,value,revision,value!=null));
                chain=List.copyOf(inherited);
            }
            return new ParameterModels.PolicyView(key,scope,id,tenant,value!=null,value,revision,effective,chain,OfflinePolicySql.CONSUMER);
        },args.toArray());
        if(rows.isEmpty())throw ApiError.missing();
        return rows.getFirst();
    }
    ParameterModels.Change changeRow(ResultSet rs) throws SQLException { return new ParameterModels.Change(rs.getString("id"),rs.getString("parameter_key"),rs.getString("scope"),rs.getString("scope_id"),rs.getString("tenant_id"),rs.getString("action"),rs.getObject("previous_value",Long.class),rs.getObject("new_value",Long.class),rs.getLong("revision_before"),rs.getLong("revision_after"),rs.getString("actor_id"),rs.getString("reason"),rs.getObject("created_at",OffsetDateTime.class)); }
    public ParameterModels.Change change(String tenant,String scope,String target,String key,String change) { var rows=jdbc.query("SELECT * FROM parameter_changes WHERE id=? AND tenant_id=? AND scope=? AND scope_id=? AND parameter_key=?",(rs,n)->changeRow(rs),change,tenant,scope,target,key); if(rows.isEmpty()) throw ApiError.missing(); return rows.getFirst(); }
    @Transactional(readOnly=true) public Page<ParameterModels.Change> history(String actor,String tenant,String scope,String id,String key,int page,Integer size) {
        validate(actor,tenant,scope,id,key,false); var p=Pagination.of(page,size,sizes.defaultPageSize());var from=" FROM parameter_changes WHERE tenant_id=? AND scope=? AND scope_id=? AND parameter_key=?";var args=new ArrayList<Object>();args.add(tenant);args.add(scope);args.add(id);args.add(key);
        if(scope.equals("DEVICE")){var access=new DeviceAccessScope.Filter("EXISTS(SELECT 1 FROM devices d WHERE d.tenant_id=parameter_changes.tenant_id AND d.id=parameter_changes.scope_id"+scopes.predicate(actor,"d.tenant_id","d.id").and()+")",scopes.predicate(actor,"d.tenant_id","d.id").bindings());from+= " AND "+access.sql();args.addAll(access.bindings());}
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());args.add(p.size());args.add(p.offset());
        var rows=jdbc.query("SELECT *"+from+" ORDER BY revision_after DESC,id LIMIT ? OFFSET ?",(rs,n)->changeRow(rs),args.toArray()); return new Page<>(rows,total,p.page(),p.size());
    }
}
