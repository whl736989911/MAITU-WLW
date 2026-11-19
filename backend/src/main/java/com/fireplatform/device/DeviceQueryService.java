package com.fireplatform.device;

import com.fireplatform.alarm.AlarmQueryService;
import com.fireplatform.configuration.*;
import com.fireplatform.legacyingest.facts.DeviceStatusComposer;
import com.fireplatform.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceQueryService {
    public record DeviceRow(String id,String tenantId,String tenantName,String name,String deviceEui,String model,String address,String connectivity,String physicalState,OffsetDateTime lastMessageAt,java.math.BigDecimal batteryPercent,boolean isSimulated,String productId,String productVersionId,String productName,Long versionNumber,long revision,String integrationState,String placeId,String placeName,String systemType,String deviceCategory,ParameterModels.Effective offlinePolicy,BatteryThresholdModels.Observation batteryPolicy,DeviceStatusComposer.Status statusDisplay) { }
    public record Summary(long deviceTotal,long online,long offline,long unactivated,long unknownConnectivity,long todayAlarms,long unhandledAlarms,long lowBattery) { }
    public record Detail(OffsetDateTime asOf,String timezone,String consumer,DeviceRow device) { }
    public record PlaceHistory(String id,String action,String fromPlaceId,String fromPlaceName,String toPlaceId,String toPlaceName,Long revisionBefore,long revisionAfter,String actorId,OffsetDateTime occurredAt) { }
    private final JdbcTemplate jdbc; private final SettingsService settings; private final AlarmQueryService alarms; private final OfflinePolicySql policy; private final DatabaseTime time; private final TransactionRequirements transactions; private final DeviceAccessScope scopes; private final ScopeFilter scopeFilter;
    private final DeviceStatusComposer statuses;
    public DeviceQueryService(JdbcTemplate jdbc,SettingsService settings,AlarmQueryService alarms,OfflinePolicySql policy,DatabaseTime time,TransactionRequirements transactions,DeviceAccessScope scopes,ScopeFilter scopeFilter,DeviceStatusComposer statuses) { this.jdbc=jdbc; this.settings=settings; this.alarms=alarms; this.policy=policy; this.time=time; this.transactions=transactions; this.scopes=scopes; this.scopeFilter=scopeFilter; this.statuses=statuses; }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Detail detail(String actor,String tenant,String id) {
        transactions.repeatableReadOnly();
        var asOf=time.now().withOffsetSameInstant(ZoneOffset.UTC);
        var scope=scopes.predicate(actor,"d.tenant_id","d.id");
        var args=new ArrayList<Object>(); args.add(asOf); args.add(asOf); args.add(tenant); args.addAll(scope.bindings()); args.add(id);
        var rows=jdbc.query(select()+" WHERE d.tenant_id=?"+scope.and()+" AND d.id=?",(rs,n)->row(rs),args.toArray());
        if(rows.isEmpty())throw ApiError.missing();
        return new Detail(asOf,"Asia/Shanghai",OfflinePolicySql.CONSUMER,rows.getFirst());
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page<PlaceHistory> placeHistory(String actor,String tenant,String id,int page,Integer size) {
        transactions.repeatableReadOnly();
        var pagination=Pagination.of(page,size,settings.defaultPageSize());
        var scope=scopes.predicate(actor,"d.tenant_id","d.id");
        var args=new ArrayList<Object>(); args.add(id); if(tenant!=null) args.add(tenant); args.addAll(scope.bindings());
        String where=" WHERE d.id=?"+(tenant==null ? "" : " AND d.tenant_id=?")+scope.and();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM devices d"+where,Long.class,args.toArray())==0) throw ApiError.missing();
        String from=" FROM device_place_history h JOIN devices d ON d.id=h.device_id AND d.tenant_id=h.tenant_id"+where;
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
        args.add(pagination.size()); args.add(pagination.offset());
        var items=jdbc.query("SELECT h.*"+from+" ORDER BY h.revision_after DESC LIMIT ? OFFSET ?",(rs,n)->new PlaceHistory(
            rs.getString("id"),rs.getString("action"),rs.getString("from_place_id"),rs.getString("from_place_name"),
            rs.getString("to_place_id"),rs.getString("to_place_name"),rs.getObject("revision_before",Long.class),
            rs.getLong("revision_after"),rs.getString("actor_id"),rs.getObject("occurred_at",OffsetDateTime.class)),args.toArray());
        return new Page<>(items,total,pagination.page(),pagination.size());
    }
    public record WorkbenchMetrics(long deviceTotal,long online,long offline,long unactivated,long unknownConnectivity,long fire,long normal,long unknown,long batteryLow,long batteryNormal,long batteryNotConfigured,long batteryNoData) { }
    public record WorkbenchDefinitions(String scope,String connectivity,String physicalState,String binding,String consumer,String battery) { }
    public record WorkbenchResult(List<DeviceRow> items,long total,int page,int size,OffsetDateTime asOf,String timezone,DeviceWorkbenchFilter filters,WorkbenchMetrics metrics,WorkbenchDefinitions metricDefinitions) { }
    private static final WorkbenchDefinitions WORKBENCH_DEFINITIONS=new WorkbenchDefinitions("FILTERED_ALL_PAGES","REPORT_INTERVAL_EVIDENCE_AT_AS_OF","CURRENT_DEVICE_PHYSICAL_STATE","DEVICE_SAVED_METADATA_NOT_LATEST",OfflinePolicySql.CONSUMER,"EFFECTIVE_BATTERY_LOW_THRESHOLD_AT_AS_OF_STORED_PERCENT");
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public WorkbenchResult workbench(String actor,String tenant,int page,Integer size,DeviceWorkbenchFilter filter) { return workbench(actor,tenant,page,size,filter,ScopeFilter.Selection.all()); }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public WorkbenchResult workbench(String actor,String tenant,int page,Integer size,DeviceWorkbenchFilter filter,ScopeFilter.Selection selection) {
        transactions.repeatableReadOnly();
        var p=Pagination.of(page,size,settings.defaultPageSize());
        var asOf=time.now().withOffsetSameInstant(ZoneOffset.UTC);
        var scope=scopes.predicate(actor,"d.tenant_id","d.id");
        var plan=filter.plan(select(),tenant,asOf,scope);
        var tree=scopeFilter.devices(actor,"q",tenant,selection);
        String fromWhere=plan.fromWhere()+" AND ("+tree.sql()+")";
        var bound=new ArrayList<>(plan.bindings());bound.addAll(tree.bindings());
        String counts="SELECT COUNT(*),COALESCE(SUM(CASE WHEN q.computed_connectivity='ONLINE' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN q.computed_connectivity='OFFLINE' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN q.computed_connectivity='UNACTIVATED' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN q.computed_connectivity='UNKNOWN' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN q.physical_state='FIRE' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN q.physical_state='NORMAL' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN q.physical_state='UNKNOWN' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN q.computed_battery_status='LOW' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN q.computed_battery_status='NORMAL' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN q.computed_battery_status='NOT_CONFIGURED' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN q.computed_battery_status='NO_DATA' THEN 1 ELSE 0 END),0)";
        var metrics=jdbc.queryForObject(counts+fromWhere,(rs,n)->new WorkbenchMetrics(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getLong(8),rs.getLong(9),rs.getLong(10),rs.getLong(11),rs.getLong(12)),bound.toArray());
        bound.add(p.size());bound.add(p.offset());
        var items=jdbc.query("SELECT q.*"+fromWhere+" ORDER BY q.id LIMIT ? OFFSET ?",(rs,n)->row(rs),bound.toArray());
        return new WorkbenchResult(items,metrics.deviceTotal(),p.page(),p.size(),asOf,"Asia/Shanghai",filter,metrics,WORKBENCH_DEFINITIONS);
    }
    private String fromClause() {
        return "FROM devices d "+policy.connectivityJoins()+" LEFT JOIN product_versions pv ON pv.id=d.product_version_id LEFT JOIN device_battery_threshold_overrides dbo ON dbo.device_id=d.id LEFT JOIN places p ON p.id=d.place_id AND p.tenant_id=d.tenant_id LEFT JOIN tenants t ON t.id=d.tenant_id ";
    }
    static String batteryStatus(String deviceAlias) {
        String value="COALESCE(dbo.threshold,pv.battery_low_threshold)";
        String comparator="COALESCE(dbo.comparator,pv.battery_low_comparator)";
        return "CASE WHEN "+value+" IS NULL THEN 'NOT_CONFIGURED' WHEN "+deviceAlias+".battery_percent IS NULL THEN 'NO_DATA' WHEN ("+comparator+"='LT' AND "+deviceAlias+".battery_percent<"+value+") OR ("+comparator+"='LTE' AND "+deviceAlias+".battery_percent<="+value+") THEN 'LOW' ELSE 'NORMAL' END";
    }
    private String select() {
        String batteryStatus=batteryStatus("d");
        return "SELECT CAST(? AS TIMESTAMP WITH TIME ZONE) AS battery_policy_evaluated_at,d.*,p.name AS place_name,t.name AS tenant_name,"+policy.columns()+","+policy.connectivity()+" AS computed_connectivity,dbo.comparator AS battery_override_comparator,dbo.threshold AS battery_override_threshold,COALESCE(dbo.revision,0) AS battery_override_revision,pv.battery_low_comparator AS version_battery_comparator,pv.battery_low_threshold AS version_battery_threshold,COALESCE(pv.revision,0) AS version_battery_revision,"+batteryStatus+" AS computed_battery_status"+statuses.columns()+" "+fromClause();
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ) public Page<DeviceRow> devices(String actor,String tenant,int page,Integer requestedSize,String keyword) {
        return devices(actor,tenant,page,requestedSize,keyword,null,null);
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ) public Page<DeviceRow> devices(String actor,String tenant,int page,Integer requestedSize,String keyword,String batteryStatus) {
        return devices(actor,tenant,page,requestedSize,keyword,batteryStatus,null,null);
    }
    public Page<DeviceRow> devices(String actor,String tenant,int page,Integer requestedSize,String keyword,String batteryStatus,String field) {
        return devices(actor,tenant,page,requestedSize,keyword,batteryStatus,field,null);
    }
    public Page<DeviceRow> devices(String actor,String tenant,int page,Integer requestedSize,String keyword,String batteryStatus,String field,String placeId) {
        return devices(actor,tenant,null,page,requestedSize,keyword,batteryStatus,field,placeId);
    }
    public Page<DeviceRow> devices(String actor,String tenant,String tenantFilter,int page,Integer requestedSize,String keyword,String batteryStatus,String field,String placeId) {
        return devices(actor,tenant,tenantFilter,page,requestedSize,keyword,batteryStatus,field,placeId,List.of(),List.of());
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page<DeviceRow> devices(String actor,String tenant,String tenantFilter,int page,Integer requestedSize,String keyword,String batteryStatus,String field,String placeId,ScopeFilter.Selection selection) {
        return devices(actor,tenant,tenantFilter,page,requestedSize,keyword,batteryStatus,field,placeId,selection,DeviceListFilter.parse(null,null,null,null,null));
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page<DeviceRow> devices(String actor,String tenant,String tenantFilter,int page,Integer requestedSize,String keyword,String batteryStatus,String field,String placeId,ScopeFilter.Selection selection,DeviceListFilter statusFilter) {
        if(selection.empty()) { var p=Pagination.of(page,requestedSize,settings.value("default_page_size")); return new Page<>(List.of(),0,p.page(),p.size()); }
        return devices(actor,tenant,tenantFilter,page,requestedSize,keyword,batteryStatus,field,placeId,selection.tenantIds(),selection.placeIds(),statusFilter);
    }
    public Page<DeviceRow> devices(String actor,String tenant,String tenantFilter,int page,Integer requestedSize,String keyword,String batteryStatus,String field,String placeId,List<String> scopeTenantIds,List<String> scopePlaceIds) {
        return devices(actor,tenant,tenantFilter,page,requestedSize,keyword,batteryStatus,field,placeId,scopeTenantIds,scopePlaceIds,DeviceListFilter.parse(null,null,null,null,null));
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page<DeviceRow> devices(String actor,String tenant,String tenantFilter,int page,Integer requestedSize,String keyword,String batteryStatus,String field,String placeId,List<String> scopeTenantIds,List<String> scopePlaceIds,DeviceListFilter statusFilter) {
        transactions.repeatableReadOnly();
        if(batteryStatus!=null && !batteryStatus.isBlank() && !Set.of("LOW","NORMAL","NO_DATA","NOT_CONFIGURED").contains(batteryStatus)) throw ApiError.bad("电量状态筛选未确认");
        var p=Pagination.of(page,requestedSize,settings.value("default_page_size")); if(keyword==null) keyword=""; if(keyword.length()>Limits.MAX_KEYWORD_LENGTH) throw ApiError.bad("搜索关键字过长");
        String query="%"+keyword.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        var scope=scopes.predicate(actor,"d.tenant_id","d.id");
        String fieldSql = switch (field == null ? "" : field) {
            case "eui" -> "LOWER(d.device_eui) LIKE ? ESCAPE '!'";
            case "name" -> "LOWER(d.name) LIKE ? ESCAPE '!'";
            case "model" -> "LOWER(d.model) LIKE ? ESCAPE '!'";
            case "address" -> "LOWER(d.address) LIKE ? ESCAPE '!'";
            case "" -> "(LOWER(d.name) LIKE ? ESCAPE '!' OR LOWER(d.device_eui) LIKE ? ESCAPE '!' OR LOWER(d.address) LIKE ? ESCAPE '!')";
            default -> throw ApiError.bad("设备搜索字段无效");
        };
        String effectiveTenant=tenant!=null?tenant:tenantFilter;
        var scoped=scopeFilter.clause("d",scopeTenantIds,scopePlaceIds);
        String where=(effectiveTenant==null?" WHERE 1=1":" WHERE d.tenant_id=?")+scope.and()+" AND d.is_simulated=FALSE AND d.archived_at IS NULL AND "+fieldSql;if(placeId!=null&&!placeId.isBlank())where+=" AND d.place_id=?";
        if(scoped.sql()!=null) where+=" AND "+scoped.sql();
        if(batteryStatus!=null && !batteryStatus.isBlank()) where+=" AND ("+batteryStatus("d")+") = '"+batteryStatus+"'";
        if(statusFilter.connectivity()!=null)where+=" AND "+policy.connectivity()+"=?";
        if(statusFilter.physicalState()!=null)where+=" AND d.physical_state=?";
        if(statusFilter.integrationState()!=null)where+=" AND d.integration_state=?";
        if(statusFilter.systemType()!=null)where+=" AND d.system_type=?";
        if(statusFilter.deviceCategory()!=null)where+=" AND d.device_category=?";
        var evaluated=time.now().withOffsetSameInstant(ZoneOffset.UTC);
        boolean filtered=(batteryStatus!=null&&!batteryStatus.isBlank())||statusFilter.connectivity()!=null;
        boolean allFields=field==null||field.isBlank();var countArgs=new ArrayList<Object>();if(filtered) countArgs.add(evaluated);if(effectiveTenant!=null)countArgs.add(effectiveTenant);countArgs.addAll(scope.bindings());countArgs.add(query);if(allFields){countArgs.add(query);countArgs.add(query);}if(placeId!=null&&!placeId.isBlank())countArgs.add(placeId);countArgs.addAll(scoped.bindings());addStatusBindings(countArgs,statusFilter);
        long total=jdbc.queryForObject("SELECT COUNT(*) "+(filtered?fromClause():"FROM devices d ")+where,Long.class,countArgs.toArray());
        var args=new ArrayList<Object>();args.add(evaluated);args.add(evaluated);if(effectiveTenant!=null)args.add(effectiveTenant);args.addAll(scope.bindings());args.add(query);if(allFields){args.add(query);args.add(query);}if(placeId!=null&&!placeId.isBlank())args.add(placeId);args.addAll(scoped.bindings());addStatusBindings(args,statusFilter);args.add(p.size());args.add(p.offset());
        var items=jdbc.query(select()+where+" ORDER BY d.id LIMIT ? OFFSET ?",(rs,n)->row(rs),args.toArray()); return new Page<>(items,total,p.page(),p.size());
    }
    private static void addStatusBindings(List<Object> args,DeviceListFilter filter) {
        if(filter.connectivity()!=null)args.add(filter.connectivity());
        if(filter.physicalState()!=null)args.add(filter.physicalState());
        if(filter.integrationState()!=null)args.add(filter.integrationState());
        if(filter.systemType()!=null)args.add(filter.systemType());
        if(filter.deviceCategory()!=null)args.add(filter.deviceCategory());
    }
    private DeviceRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        var evaluated=rs.getObject("battery_policy_evaluated_at",OffsetDateTime.class);
        var display=statuses.compose(DeviceStatusComposer.Code.valueOf(rs.getString("computed_connectivity")),rs.getBoolean("pending_alarm"),rs.getBoolean("notification_shielded"),rs.getString("pending_faults"));
        return new DeviceRow(rs.getString("id"),rs.getString("tenant_id"),rs.getString("tenant_name"),rs.getString("name"),rs.getString("device_eui"),rs.getString("model"),rs.getString("address"),rs.getString("computed_connectivity"),rs.getString("physical_state"),rs.getObject("last_message_at",OffsetDateTime.class),rs.getBigDecimal("battery_percent"),rs.getBoolean("is_simulated"),rs.getString("product_id"),rs.getString("product_version_id"),rs.getString("product_name"),rs.getObject("version_number",Long.class),rs.getLong("revision"),rs.getString("integration_state"),rs.getString("place_id"),rs.getString("place_name"),rs.getString("system_type"),rs.getString("device_category"),policy.effective(rs,rs.getString("tenant_id"),rs.getString("id")),BatteryThresholdQueries.observation(rs,evaluated),display);
    }
    public DeviceRow device(String actor,String tenant,String id,boolean lock) {
        // Never FOR UPDATE the policy JOIN: only device may be locked, before a nonlocking policy read.
        var scope=scopes.predicate(actor, "devices.tenant_id", "devices.id");
        var args=new ArrayList<Object>();args.add(tenant);args.addAll(scope.bindings());args.add(id);
        if(lock && jdbc.query("SELECT id FROM devices WHERE tenant_id=?"+scope.and()+" AND id=? AND archived_at IS NULL FOR UPDATE",(rs,n)->rs.getString(1),args.toArray()).isEmpty()) throw ApiError.missing();
        var scoped=scopes.predicate(actor,"d.tenant_id","d.id");var evaluated=time.now().withOffsetSameInstant(ZoneOffset.UTC);var readArgs=new ArrayList<Object>();readArgs.add(evaluated);readArgs.add(evaluated);readArgs.add(tenant);readArgs.addAll(scoped.bindings());readArgs.add(id);
        var rows=jdbc.query(select()+" WHERE d.tenant_id=?"+scoped.and()+" AND d.id=?",(rs,n)->row(rs),readArgs.toArray()); if(rows.isEmpty()) throw ApiError.missing(); return rows.getFirst();
    }
    @Transactional(readOnly=true) public Summary summary(String actor,String tenant) { return summary(actor,tenant,ScopeFilter.Selection.all()); }
    @Transactional(readOnly=true) public Summary summary(String actor,String tenant,ScopeFilter.Selection selection) {
        var evaluated=time.now().withOffsetSameInstant(ZoneOffset.UTC); String online=policy.online(); var scope=scopes.predicate(actor,"d.tenant_id","d.id"); var tree=scopeFilter.devices(actor,"d",tenant,selection);
        var args=new ArrayList<Object>();args.add(evaluated);args.addAll(scope.bindings());args.addAll(tree.bindings());
        long[] buckets=jdbc.queryForObject("SELECT COUNT(*),COALESCE(SUM(CASE WHEN "+online+" THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN "+policy.offline()+" THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN d.last_message_at IS NULL THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN "+policy.unconfirmedTimeout()+" THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN ("+batteryStatus("d")+")='LOW' THEN 1 ELSE 0 END),0) "+fromClause()+" WHERE d.is_simulated=FALSE AND d.archived_at IS NULL"+scope.and()+" AND ("+tree.sql()+")",(rs,n)->new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6)},args.toArray());
        var counts=alarms.metrics(actor,tenant,selection);
        return new Summary(buckets[0],buckets[1],buckets[2],buckets[3],buckets[4],counts.todayAlarms(),counts.unhandledAlarms(),buckets[5]);
    }
    public ParameterModels.DeviceEffective effective(String actor,String tenant,String id) { var device=device(actor,tenant,id,false); return new ParameterModels.DeviceEffective(id,List.of(device.offlinePolicy()),OfflinePolicySql.CONSUMER); }
}
