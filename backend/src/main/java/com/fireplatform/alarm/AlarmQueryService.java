package com.fireplatform.alarm;

import com.fireplatform.configuration.SettingsService;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.Pagination;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.ScopeFilter;
import java.util.ArrayList;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlarmQueryService {
    public record AlarmRow(String id,String tenantId,String deviceId,String deviceEui,String deviceName,String address,String eventType,OffsetDateTime occurredAt,
        String physicalState,String responseState,String conclusion,long revision,boolean isSimulated,String timeQuality,OffsetDateTime recordedAt,
        OffsetDateTime latestAlarmAt,OffsetDateTime recoveredAt) { }
    public record Metrics(long todayAlarms,long unhandledAlarms) { }
    static final String DEVICE_EUI_COLUMN="(SELECT d.device_eui FROM devices d WHERE d.id=alarms.device_id AND d.tenant_id=alarms.tenant_id) AS device_eui";
    static final String RECOVERY_COLUMN="(SELECT t.occurred_at FROM alarm_trace_entries t WHERE t.tenant_id=alarms.tenant_id AND t.alarm_id=alarms.id AND t.action IN ('ALARM_RECOVERED','DEVICE_RECOVERED') ORDER BY t.revision_after DESC LIMIT 1) AS recovered_at";
    private final JdbcTemplate jdbc; private final SettingsService settings; private final Clock clock; private final DeviceAccessScope scopes; private final ScopeFilter scopeFilter;
    public AlarmQueryService(JdbcTemplate jdbc,SettingsService settings,Clock clock,DeviceAccessScope scopes,ScopeFilter scopeFilter) { this.jdbc=jdbc; this.settings=settings; this.clock=clock; this.scopes=scopes; this.scopeFilter=scopeFilter; }
    static AlarmRow row(ResultSet rs) throws SQLException {
        Boolean known=rs.getObject("source_time_known",Boolean.class);
        return new AlarmRow(rs.getString("id"),rs.getString("tenant_id"),rs.getString("device_id"),rs.getString("device_eui"),rs.getString("device_name"),rs.getString("address"),rs.getString("event_type"),Boolean.FALSE.equals(known)?null:rs.getObject("occurred_at",OffsetDateTime.class),rs.getString("physical_state"),rs.getString("response_state"),rs.getString("conclusion"),rs.getLong("revision"),rs.getBoolean("is_simulated"),known==null?"LEGACY_UNVERIFIED":known?"SOURCE_REPORTED":"SOURCE_TIME_MISSING",rs.getObject("platform_recorded_at",OffsetDateTime.class),Boolean.TRUE.equals(known)?rs.getObject("last_event_at",OffsetDateTime.class):null,rs.getObject("recovered_at",OffsetDateTime.class));
    }
    public AlarmRow alarm(String actor,String tenant,String id) {
        var scope=scopes.predicate(actor, "alarms.tenant_id", "alarms.device_id");var args=new ArrayList<Object>();args.add(tenant);args.addAll(scope.bindings());args.add(id);
        var rows=jdbc.query("SELECT alarms.*,"+DEVICE_EUI_COLUMN+","+RECOVERY_COLUMN+" FROM alarms WHERE tenant_id=?"+scope.and()+" AND id=?",(rs,n)->row(rs),args.toArray());
        if(rows.isEmpty()) throw ApiError.missing(); return rows.getFirst();
    }
    @Transactional(readOnly=true) public Page<AlarmRow> alarms(String actor,String tenant,int page,Integer requestedSize) { return alarms(actor,tenant,page,requestedSize,ScopeFilter.Selection.all(),null); }
    @Transactional(readOnly=true) public Page<AlarmRow> alarms(String actor,String tenant,int page,Integer requestedSize,ScopeFilter.Selection selection) { return alarms(actor,tenant,page,requestedSize,selection,null); }
    @Transactional(readOnly=true) public Page<AlarmRow> alarms(String actor,String tenant,int page,Integer requestedSize,ScopeFilter.Selection selection,String physicalState) {
        var p=Pagination.of(page,requestedSize,settings.value("default_page_size"));
        if(selection.empty()) return new Page<>(java.util.List.of(),0,p.page(),p.size());
        var scope=scopes.predicate(actor,"alarms.tenant_id","alarms.device_id");
        var tree=scopeFilter.alarmDevices("alarms",selection.tenantIds(),selection.placeIds());
        var args=new ArrayList<Object>();if(tenant!=null)args.add(tenant);args.addAll(scope.bindings());args.addAll(tree.bindings());if(physicalState!=null&&!physicalState.isEmpty())args.add(physicalState);
        String where=(tenant==null?" WHERE 1=1":" WHERE alarms.tenant_id=?")+scope.and()+tree.and()+" AND alarms.is_simulated=FALSE"+(physicalState==null||physicalState.isEmpty()?"":" AND alarms.physical_state=?");
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM alarms"+where,Long.class,args.toArray());
        args.add(p.size());args.add(p.offset());
        var items=jdbc.query("SELECT alarms.*,"+DEVICE_EUI_COLUMN+","+RECOVERY_COLUMN+" FROM alarms"+where+" ORDER BY alarms.occurred_at DESC,alarms.id LIMIT ? OFFSET ?",(rs,n)->row(rs),args.toArray());
        return new Page<>(items,total,p.page(),p.size());
    }
    /** Read-only cross-module summary interface; no caller reads alarm tables directly. */
    public Metrics metrics(String actor,String tenant) { return metrics(actor,tenant,ScopeFilter.Selection.all()); }
    public Metrics metrics(String actor,String tenant,ScopeFilter.Selection selection) {
        if(selection.empty()) return new Metrics(0,0);
        var zone=ZoneId.of("Asia/Shanghai"); var today=LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toOffsetDateTime();var scope=scopes.predicate(actor,"a.tenant_id","a.device_id");
        var tree=scopeFilter.alarmDevices("a",selection.tenantIds(),selection.placeIds());
        var args=new ArrayList<Object>();args.add(today);args.add(today.plusDays(1));if(tenant!=null)args.add(tenant);args.addAll(scope.bindings());args.addAll(tree.bindings());
        String where=(tenant==null?" WHERE 1=1":" WHERE a.tenant_id=?")+scope.and()+tree.and()+" AND a.is_simulated=FALSE";
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN a.source_time_known IS NOT FALSE AND a.occurred_at>=? AND a.occurred_at<? THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN a.response_state='UNHANDLED' THEN 1 ELSE 0 END),0) FROM alarms a"+where,(rs,n)->new Metrics(rs.getLong(1),rs.getLong(2)),args.toArray());
    }
}
