package com.fireplatform.identity;

import com.fireplatform.shared.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnitDirectoryQueries {
    private static final String COLUMNS="t.id,t.name,t.enabled,t.revision,t.credit_code,t.region_id,t.unit_type,t.industry,t.map_address,t.detail_address,t.contact_name,t.contact_phone,t.remark,t.latitude,t.longitude,t.service_start,t.service_end,t.engineering_service_start,t.engineering_service_end,t.archived_at";
    private final JdbcTemplate jdbc; private final ScopeFilter scopes; private final PageSizePolicy sizes;
    public UnitDirectoryQueries(JdbcTemplate jdbc,ScopeFilter scopes,PageSizePolicy sizes){this.jdbc=jdbc;this.scopes=scopes;this.sizes=sizes;}
    private static UnitDirectoryModels.UnitDetails row(ResultSet r)throws SQLException{return new UnitDirectoryModels.UnitDetails(r.getString("id"),r.getString("name"),r.getBoolean("enabled"),r.getLong("revision"),r.getString("credit_code"),r.getString("region_id"),r.getString("unit_type"),r.getString("industry"),r.getString("map_address"),r.getString("detail_address"),r.getString("contact_name"),r.getString("contact_phone"),r.getString("remark"),r.getObject("latitude",Double.class),r.getObject("longitude",Double.class),r.getObject("service_start",LocalDate.class),r.getObject("service_end",LocalDate.class),r.getObject("engineering_service_start",LocalDate.class),r.getObject("engineering_service_end",LocalDate.class),r.getObject("archived_at",java.time.OffsetDateTime.class));}
    private static String selection(ScopeFilter.Selection selection,List<Object> args){if(selection.empty())return "1=0";if(selection.tenantIds().isEmpty()&&selection.placeIds().isEmpty())return "1=1";var choices=new ArrayList<String>();if(!selection.tenantIds().isEmpty()){choices.add("t.id IN ("+String.join(",",Collections.nCopies(selection.tenantIds().size(),"?"))+")");args.addAll(selection.tenantIds());}if(!selection.placeIds().isEmpty()){choices.add("EXISTS(SELECT 1 FROM places p WHERE p.tenant_id=t.id AND p.id IN ("+String.join(",",Collections.nCopies(selection.placeIds().size(),"?"))+"))");args.addAll(selection.placeIds());}return "("+String.join(" OR ",choices)+")";}
    private static String disabledOwner(String alias){return alias+".enabled=FALSE AND EXISTS(SELECT 1 FROM user_tenant_grants g WHERE g.user_id=? AND g.tenant_id="+alias+".id)";}
    @Transactional(readOnly=true) public Page<UnitDirectoryModels.UnitDetails> list(String actor,int page,Integer size,String keyword,String status,ScopeFilter.Selection selected){
        var p=Pagination.of(page,size,sizes.defaultPageSize());if(keyword==null)keyword="";if(keyword.length()>Limits.MAX_KEYWORD_LENGTH)throw ApiError.bad("搜索关键字过长");String like="%"+keyword.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        var scope=scopes.units(actor,"t","id",null,selected);var args=new ArrayList<Object>(scope.bindings());String access=scope.sql();
        if("disabled".equals(status)){args.clear();var disabledArgs=new ArrayList<Object>();String disabled=disabledOwner("t");disabledArgs.add(actor);disabled+=" AND "+selection(selected,disabledArgs);access="("+disabled+")";args.addAll(disabledArgs);}else if(status==null||status.isBlank()){var disabledArgs=new ArrayList<Object>();String disabled=disabledOwner("t");disabledArgs.add(actor);disabled+=" AND "+selection(selected,disabledArgs);access="("+access+" OR ("+disabled+"))";args.addAll(disabledArgs);}
        String where=" FROM tenants t WHERE "+access+" AND (LOWER(t.name) LIKE ? ESCAPE '!' OR LOWER(COALESCE(t.credit_code,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(t.detail_address,'')) LIKE ? ESCAPE '!')";args.add(like);args.add(like);args.add(like);
        if("enabled".equals(status))where+=" AND t.enabled=TRUE";else if("disabled".equals(status))where+=" AND t.enabled=FALSE";else if(status!=null&&!status.isBlank())throw ApiError.bad("单位状态筛选无效");long total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray());args.add(p.size());args.add(p.offset());var rows=jdbc.query("SELECT "+COLUMNS+where+" ORDER BY t.name,t.id LIMIT ? OFFSET ?",(r,n)->row(r),args.toArray());return new Page<>(rows,total,p.page(),p.size());
    }
    @Transactional(readOnly=true) public UnitDirectoryModels.UnitDetails detail(String actor,String id){var scope=scopes.units(actor,"t","id",null,ScopeFilter.Selection.all());var args=new ArrayList<Object>(scope.bindings());args.add(actor);args.add(id);var rows=jdbc.query("SELECT "+COLUMNS+" FROM tenants t WHERE ("+scope.sql()+" OR ("+disabledOwner("t")+")) AND t.id=?",(r,n)->row(r),args.toArray());if(rows.isEmpty())throw ApiError.missing();return rows.getFirst();}
}
