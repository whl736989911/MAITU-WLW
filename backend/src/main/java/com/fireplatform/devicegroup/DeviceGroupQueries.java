package com.fireplatform.devicegroup;

import com.fireplatform.shared.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.fireplatform.devicegroup.DeviceGroupModels.*;

@Service
public class DeviceGroupQueries {
    private final JdbcTemplate jdbc;private final PageSizePolicy sizes;private final TransactionRequirements transactions;private final DeviceAccessScope scope;private final ScopeFilter scopeFilter;
    public DeviceGroupQueries(JdbcTemplate jdbc,PageSizePolicy sizes,TransactionRequirements transactions,DeviceAccessScope scope,ScopeFilter scopeFilter){this.jdbc=jdbc;this.sizes=sizes;this.transactions=transactions;this.scope=scope;this.scopeFilter=scopeFilter;}
    private Pagination page(int page,Integer size){transactions.repeatableReadOnly();long configured=sizes.defaultPageSize();var definition=Limits.SETTINGS.get("default_page_size");if(configured<definition.min()||configured>definition.max())throw DeviceGroupProjection.unconfirmed();return Pagination.of(page,size,configured);}
    static Group current(JdbcTemplate jdbc,String tenant,String id){if(!DeviceGroupProjection.groupId(id))throw DeviceGroupProjection.missing();var rows=jdbc.query("SELECT "+DeviceGroupProjection.GROUP_COLUMNS+" FROM device_groups g WHERE g.tenant_id=? AND g.id=?",(rs,n)->DeviceGroupProjection.group(rs,tenant),tenant,id);if(rows.isEmpty())throw DeviceGroupProjection.missing();if(rows.size()!=1)throw DeviceGroupProjection.unconfirmed();return rows.getFirst();}
    Group visibleGroup(String actor,String tenant,String id){var access=scope.predicate(actor,"cd.tenant_id","cd.id");String cols="g.id,g.tenant_id,g.name,g.description,g.revision,(SELECT COUNT(*) FROM device_group_members cm JOIN devices cd ON cd.id=cm.device_id AND cd.tenant_id=cm.tenant_id WHERE cm.group_id=g.id AND cm.tenant_id=g.tenant_id AND cd.is_simulated=FALSE AND cd.archived_at IS NULL AND ("+access.sql()+")) AS member_count";var rows=jdbc.query("SELECT "+cols+" FROM device_groups g WHERE g.tenant_id=? AND g.id=?",(rs,n)->DeviceGroupProjection.group(rs,tenant),concat(access.bindings(),tenant,id));if(rows.isEmpty())throw DeviceGroupProjection.missing();if(rows.size()!=1)throw DeviceGroupProjection.unconfirmed();return rows.getFirst();}
    private Object[] concat(List<Object> bindings,Object... values){var all=new ArrayList<Object>(bindings);all.addAll(Arrays.asList(values));return all.toArray();}
    private long total(String from,List<Object> args){Long total=jdbc.queryForObject("SELECT COUNT(*)"+from,(rs,n)->DeviceGroupProjection.total(rs),args.toArray());if(total==null)throw DeviceGroupProjection.unconfirmed();return total;}
    private Object[] arguments(List<Object> args,Pagination page){var values=new ArrayList<>(args);values.add(page.size());values.add(page.offset());return values.toArray();}
    private <T> List<T> window(List<T> rows,long total,Pagination page,Function<T,String> id){if(rows.size()!=Math.min(page.size(),Math.max(0,total-page.offset()))||rows.stream().map(id).distinct().count()!=rows.size())throw DeviceGroupProjection.unconfirmed();return List.copyOf(rows);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public GroupPage groups(String actor,String tenant,int page,Integer size,DeviceGroupFilter filter){var p=page(page,size);var plan=filter.groups(tenant);String from=" FROM device_groups g"+plan.where();long total=total(from,plan.arguments());var access=scope.predicate(actor,"cd.tenant_id","cd.id");String cols="g.id,g.tenant_id,g.name,g.description,g.revision,(SELECT COUNT(*) FROM device_group_members cm JOIN devices cd ON cd.id=cm.device_id AND cd.tenant_id=cm.tenant_id WHERE cm.group_id=g.id AND cm.tenant_id=g.tenant_id AND cd.is_simulated=FALSE AND cd.archived_at IS NULL AND ("+access.sql()+")) AS member_count";var args=new ArrayList<Object>(access.bindings());args.addAll(plan.arguments());var rows=jdbc.query("SELECT "+cols+from+" ORDER BY g.id ASC LIMIT ? OFFSET ?",(rs,n)->DeviceGroupProjection.group(rs,tenant),arguments(args,p));return new GroupPage(window(rows,total,p,Group::id),total,p.page(),p.size());}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public MemberPage members(String actor,String tenant,String id,int page,Integer size,DeviceGroupFilter filter){var p=page(page,size);var group=visibleGroup(actor,tenant,id);var plan=filter.devices(tenant,id,false);var access=scope.predicate(actor,"d.tenant_id","d.id");String where=plan.where()+" AND d.is_simulated=FALSE AND d.archived_at IS NULL AND ("+access.sql()+")";var args=new ArrayList<Object>(plan.arguments());args.addAll(access.bindings());String from=" FROM device_group_members m JOIN devices d ON d.id=m.device_id AND d.tenant_id=m.tenant_id"+where;long total=total(from,args);var rows=jdbc.query("SELECT "+DeviceGroupProjection.DEVICE_COLUMNS+from+" ORDER BY d.id ASC LIMIT ? OFFSET ?",(rs,n)->DeviceGroupProjection.device(rs),arguments(args,p));return new MemberPage(group,window(rows,total,p,DeviceReference::deviceId),total,p.page(),p.size());}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CandidatePage candidates(String actor,String tenant,String id,int page,Integer size,DeviceGroupFilter filter){var p=page(page,size);var group=visibleGroup(actor,tenant,id);var plan=filter.devices(tenant,id,true);var access=scope.predicate(actor,"d.tenant_id","d.id");String where=plan.where()+" AND d.is_simulated=FALSE AND d.archived_at IS NULL AND ("+access.sql()+")";var args=new ArrayList<Object>();args.addAll(plan.arguments());args.addAll(access.bindings());long total=total(" FROM devices d"+where,args);var queryArgs=new ArrayList<Object>();queryArgs.add(id);queryArgs.addAll(args);var rows=jdbc.query("SELECT "+DeviceGroupProjection.DEVICE_COLUMNS+",CASE WHEN m.device_id IS NULL THEN FALSE ELSE TRUE END AS is_member FROM devices d LEFT JOIN device_group_members m ON m.tenant_id=d.tenant_id AND m.device_id=d.id AND m.group_id=?"+where+" ORDER BY d.id ASC LIMIT ? OFFSET ?",(rs,n)->DeviceGroupProjection.candidate(rs),arguments(queryArgs,p));if(rows.stream().filter(CandidateReference::isMember).count()>group.memberCount())throw DeviceGroupProjection.unconfirmed();return new CandidatePage(group,window(rows,total,p,CandidateReference::deviceId),total,p.page(),p.size());}
    private Group visibleGroup(String actor,String tenant,String id,ScopeFilter.Selection selection){
        if(!DeviceGroupProjection.groupId(id))throw DeviceGroupProjection.missing();
        var access=scope.predicate(actor,"cd.tenant_id","cd.id");
        var memberTree=scopeFilter.devices(actor,"cd",tenant,selection);
        var tree=scopeFilter.units(actor,"g","tenant_id",tenant,selection);
        String cols="g.id,g.tenant_id,g.name,g.description,g.revision,(SELECT COUNT(*) FROM device_group_members cm JOIN devices cd ON cd.id=cm.device_id AND cd.tenant_id=cm.tenant_id WHERE cm.group_id=g.id AND cm.tenant_id=g.tenant_id AND cd.is_simulated=FALSE AND cd.archived_at IS NULL AND ("+access.sql()+")"+memberTree.and()+") AS member_count";
        var args=new ArrayList<Object>(access.bindings());args.addAll(memberTree.bindings());args.add(id);if(tenant!=null)args.add(tenant);args.addAll(tree.bindings());
        var rows=jdbc.query("SELECT "+cols+" FROM device_groups g WHERE g.id=?"+(tenant==null?"":" AND g.tenant_id=?")+tree.and(),(rs,n)->DeviceGroupProjection.group(rs,tenant),args.toArray());
        if(rows.isEmpty())throw DeviceGroupProjection.missing();if(rows.size()!=1)throw DeviceGroupProjection.unconfirmed();return rows.getFirst();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public GroupPage groups(String actor,String tenant,int page,Integer size,DeviceGroupFilter filter,ScopeFilter.Selection selection){
        var p=page(page,size);if(selection.empty())return new GroupPage(List.of(),0,p.page(),p.size());
        var plan=filter.groups(tenant);var tree=scopeFilter.units(actor,"g","tenant_id",tenant,selection);
        String from=" FROM device_groups g"+plan.where()+tree.and();var countArgs=new ArrayList<Object>(plan.arguments());countArgs.addAll(tree.bindings());long total=total(from,countArgs);
        var access=scope.predicate(actor,"cd.tenant_id","cd.id");
        var memberTree=scopeFilter.devices(actor,"cd",tenant,selection);
        String cols="g.id,g.tenant_id,g.name,g.description,g.revision,(SELECT COUNT(*) FROM device_group_members cm JOIN devices cd ON cd.id=cm.device_id AND cd.tenant_id=cm.tenant_id WHERE cm.group_id=g.id AND cm.tenant_id=g.tenant_id AND cd.is_simulated=FALSE AND cd.archived_at IS NULL AND ("+access.sql()+")"+memberTree.and()+") AS member_count";
        var args=new ArrayList<Object>(access.bindings());args.addAll(memberTree.bindings());args.addAll(plan.arguments());args.addAll(tree.bindings());
        var rows=jdbc.query("SELECT "+cols+from+" ORDER BY g.id ASC LIMIT ? OFFSET ?",(rs,n)->DeviceGroupProjection.group(rs,tenant),arguments(args,p));
        return new GroupPage(window(rows,total,p,Group::id),total,p.page(),p.size());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public MemberPage members(String actor,String tenant,String id,int page,Integer size,DeviceGroupFilter filter,ScopeFilter.Selection selection){
        var p=page(page,size);if(selection.empty())return new MemberPage(visibleGroup(actor,tenant,id,selection),List.of(),0,p.page(),p.size());
        var group=visibleGroup(actor,tenant,id,selection);var plan=filter.devices(group.tenantId(),id,false);
        var access=scope.predicate(actor,"d.tenant_id","d.id");var tree=scopeFilter.devices(actor,"d",group.tenantId(),selection);
        String where=plan.where()+" AND d.is_simulated=FALSE AND d.archived_at IS NULL"+access.and()+tree.and();var args=new ArrayList<Object>(plan.arguments());args.addAll(access.bindings());args.addAll(tree.bindings());
        String from=" FROM device_group_members m JOIN devices d ON d.id=m.device_id AND d.tenant_id=m.tenant_id"+where;
        long total=total(from,args);var rows=jdbc.query("SELECT "+DeviceGroupProjection.DEVICE_COLUMNS+from+" ORDER BY d.id ASC LIMIT ? OFFSET ?",(rs,n)->DeviceGroupProjection.device(rs),arguments(args,p));
        return new MemberPage(group,window(rows,total,p,DeviceReference::deviceId),total,p.page(),p.size());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CandidatePage candidates(String actor,String tenant,String id,int page,Integer size,DeviceGroupFilter filter,ScopeFilter.Selection selection){
        var p=page(page,size);var group=visibleGroup(actor,tenant,id,selection);if(selection.empty())return new CandidatePage(group,List.of(),0,p.page(),p.size());
        var plan=filter.devices(group.tenantId(),id,true);var access=scope.predicate(actor,"d.tenant_id","d.id");var tree=scopeFilter.devices(actor,"d",group.tenantId(),selection);
        String where=plan.where()+" AND d.is_simulated=FALSE AND d.archived_at IS NULL"+access.and()+tree.and();var args=new ArrayList<Object>(plan.arguments());args.addAll(access.bindings());args.addAll(tree.bindings());
        long total=total(" FROM devices d"+where,args);var queryArgs=new ArrayList<Object>();queryArgs.add(id);queryArgs.addAll(args);
        var rows=jdbc.query("SELECT "+DeviceGroupProjection.DEVICE_COLUMNS+",CASE WHEN m.device_id IS NULL THEN FALSE ELSE TRUE END AS is_member FROM devices d LEFT JOIN device_group_members m ON m.tenant_id=d.tenant_id AND m.device_id=d.id AND m.group_id=?"+where+" ORDER BY d.id ASC LIMIT ? OFFSET ?",(rs,n)->DeviceGroupProjection.candidate(rs),arguments(queryArgs,p));
        if(rows.stream().filter(CandidateReference::isMember).count()>group.memberCount())throw DeviceGroupProjection.unconfirmed();
        return new CandidatePage(group,window(rows,total,p,CandidateReference::deviceId),total,p.page(),p.size());
    }
}
