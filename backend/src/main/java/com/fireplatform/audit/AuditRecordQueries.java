package com.fireplatform.audit;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.shared.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AuditRecordQueries {
    public record Scope(String kind,List<String> tenantIds,List<String> selectedTenantIds,List<String> selectedPlaceIds,String audience) { }
    public record Coverage(String source,String scope,String missingHistory,String references,String rawRequestId) { }
    public record Result(List<AuditRecordProjection.Row> items,long total,int page,int size,OffsetDateTime asOf,String timezone,Scope scope,AuditRecordFilter filters,List<AuditRecordCatalog.Definition> actionDefinitions,List<AuditRecordCatalog.Definition> outcomeDefinitions,Coverage coverage,AuditLogSearch search,Map<String,AuditLogSearch.Metadata> details) { }
    record Entry(AuditRecordProjection.Row row,AuditLogSearch.Metadata metadata) { }
    private static final Coverage COVERAGE=new Coverage("PERSISTED_AUDIT_LOG","AUTHORIZED_UNITS_ONLY","NOT_RECONSTRUCTED","UUID_ONLY","NOT_EXPOSED");
    private static final Comparator<AuditRecordProjection.Row> ORDER=Comparator.comparing(AuditRecordProjection.Row::occurredAt).reversed().thenComparing(AuditRecordProjection.Row::id);
    private final JdbcTemplate jdbc;private final PageSizePolicy sizes;private final DatabaseTime time;private final TransactionRequirements transactions;private final ScopeFilter scopes;
    public AuditRecordQueries(JdbcTemplate jdbc,PageSizePolicy sizes,DatabaseTime time,TransactionRequirements transactions,ScopeFilter scopes){this.jdbc=jdbc;this.sizes=sizes;this.time=time;this.transactions=transactions;this.scopes=scopes;}
    AuditRecordFilter.Sql plan(String actor,AuditRecordFilter filter,ScopeFilter.Selection selection,AuditLogSearch search,AuditLogSearch.Access access) {
        return search.sql(filter.sql(scopes.units(actor,"a","tenant_id",null,selection)),access);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result query(String actor,int page,Integer size,AuditRecordFilter filter,ScopeFilter.Selection selection,AuditLogSearch search,AuditLogSearch.Access access){
        transactions.repeatableReadOnly();long defaultSize=sizes.defaultPageSize();var definition=Limits.SETTINGS.get("default_page_size");if(defaultSize<definition.min()||defaultSize>definition.max())throw AuditRecordProjection.unconfirmed();
        var pagination=Pagination.of(page,size,defaultSize);var asOf=AuditRecordProjection.instant(time.now());var plan=plan(actor,filter,selection,search,access);
        var unitScope=scopes.units(actor,"t","id",null,selection);
        var unitIds=jdbc.queryForList("SELECT t.id FROM tenants t WHERE "+unitScope.sql()+" ORDER BY t.id",String.class,unitScope.bindings().toArray());
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM audit_log a"+plan.where(),(rs,n)->AuditRecordProjection.total(rs),plan.bindings().toArray());
        if(total==null)throw AuditRecordProjection.unconfirmed();var args=new ArrayList<>(plan.bindings());args.add(pagination.size());args.add(pagination.offset());
        var entries=jdbc.query("SELECT "+AuditRecordProjection.COLUMNS+AuditLogSearch.columns(access)+" FROM audit_log a JOIN tenants t ON t.id=a.tenant_id"+plan.where()+" ORDER BY a.occurred_at DESC,a.id ASC LIMIT ? OFFSET ?",(rs,n)->new Entry(AuditRecordProjection.row(rs),new AuditLogSearch.Metadata(rs.getString("log_actor_username"),rs.getString("log_module"),rs.getString("log_platform"))),args.toArray());
        var rows=entries.stream().map(Entry::row).toList();
        var details=new LinkedHashMap<String,AuditLogSearch.Metadata>();
        for(var entry:entries) details.put(entry.row().id(),entry.metadata());
        if(rows.size()!=Math.min(pagination.size(),Math.max(0,total-pagination.offset()))||rows.stream().map(AuditRecordProjection.Row::id).distinct().count()!=rows.size())throw AuditRecordProjection.unconfirmed();
        for(int i=1;i<rows.size();i++)if(ORDER.compare(rows.get(i-1),rows.get(i))>0)throw AuditRecordProjection.unconfirmed();
        var scope=new Scope("AUTHORIZED_UNITS",List.copyOf(unitIds),selection.tenantIds(),selection.placeIds(),"management");
        return new Result(rows,total,pagination.page(),pagination.size(),asOf,"Asia/Shanghai",scope,filter,AuditRecordCatalog.ACTIONS,AuditRecordCatalog.OUTCOMES,COVERAGE,search,Collections.unmodifiableMap(details));
    }
}
