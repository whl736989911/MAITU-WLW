package com.fireplatform.alarm;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AlarmWorkbenchQueries {
    public record Metrics(long alarmTotal,long unhandled,long handled,long unrecovered,long recovered,long trueFire) { }
    public record MetricDefinitions(String scope,String timeField,String timeBounds,String unhandled,String unrecovered,String trueFire) { }
    public record Result(List<AlarmQueryService.AlarmRow> items,long total,int page,int size,OffsetDateTime asOf,String timezone,AlarmWorkbenchFilter filters,Metrics metrics,MetricDefinitions metricDefinitions) { }
    private static final MetricDefinitions DEFINITIONS=new MetricDefinitions("FILTERED_ALL_PAGES","ALARM_OCCURRED_AT","FROM_INCLUSIVE_TO_EXCLUSIVE","RESPONSE_UNHANDLED","PHYSICAL_FIRE","CURRENT_CONCLUSION_TRUE_FIRE");
    public record DeviceResult(String deviceId,List<AlarmQueryService.AlarmRow> items,long total,int page,int size,OffsetDateTime asOf,String timezone,AlarmWorkbenchFilter filters,Metrics metrics,MetricDefinitions metricDefinitions) { }
    private final JdbcTemplate jdbc; private final PageSizePolicy sizes; private final DatabaseTime time; private final ParameterTargets targets; private final TransactionRequirements transactions;private final DeviceAccessScope scopes;
    public AlarmWorkbenchQueries(JdbcTemplate jdbc,PageSizePolicy sizes,DatabaseTime time,ParameterTargets targets,TransactionRequirements transactions,DeviceAccessScope scopes) { this.jdbc=jdbc; this.sizes=sizes; this.time=time; this.targets=targets; this.transactions=transactions;this.scopes=scopes; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public DeviceResult device(String actor,String tenant,String id,int page,Integer size,AlarmWorkbenchFilter filter) {
        transactions.repeatableReadOnly(); targets.require(actor,tenant,id,false);var scope=scopes.predicate(actor, "alarms.tenant_id", "alarms.device_id");
        var result=read(page,size,filter,filter.sql(tenant,id,scope));
        return new DeviceResult(id,result.items(),result.total(),result.page(),result.size(),result.asOf(),result.timezone(),result.filters(),result.metrics(),result.metricDefinitions());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result query(String actor,String tenant,int page,Integer size,AlarmWorkbenchFilter filter) { return query(actor,tenant,page,size,filter,null,List.of()); }
    public Result query(String actor,String tenant,int page,Integer size,AlarmWorkbenchFilter filter,String extraSql,List<Object> extraArgs) { return read(page,size,filter,filter.sql(tenant,null,scopes.predicate(actor, "alarms.tenant_id", "alarms.device_id"),extraSql,extraArgs)); }
    private Result read(int page,Integer size,AlarmWorkbenchFilter filter,AlarmWorkbenchFilter.Sql plan) {
        var p=Pagination.of(page,size,sizes.defaultPageSize()); var asOf=time.now().withOffsetSameInstant(ZoneOffset.UTC);
        var metrics=jdbc.queryForObject("SELECT COUNT(*),COALESCE(SUM(CASE WHEN response_state='UNHANDLED' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN response_state='HANDLED' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN physical_state='FIRE' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN physical_state='NORMAL' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN conclusion='TRUE_FIRE' THEN 1 ELSE 0 END),0) FROM alarms"+plan.where(),(rs,n)->new Metrics(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6)),plan.bindings().toArray());
        var pageArgs=new ArrayList<>(plan.bindings()); pageArgs.add(p.size()); pageArgs.add(p.offset());
        var items=jdbc.query("SELECT id,device_id,device_name,address,event_type,occurred_at,last_event_at,physical_state,response_state,conclusion,revision,is_simulated,tenant_id,source_time_known,platform_recorded_at,"+AlarmQueryService.DEVICE_EUI_COLUMN+","+AlarmQueryService.RECOVERY_COLUMN+" FROM alarms"+plan.where()+" ORDER BY occurred_at DESC,id LIMIT ? OFFSET ?",(rs,n)->AlarmQueryService.row(rs),pageArgs.toArray());
        return new Result(items,metrics.alarmTotal(),p.page(),p.size(),asOf,"Asia/Shanghai",filter,metrics,DEFINITIONS);
    }
}
