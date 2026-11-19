package com.fireplatform.duty;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.legacyingest.FaultProjection;
import com.fireplatform.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Complete time-window aggregations over visible stored alarm and legacy event facts. */
@Service
public class DutyAnalyticsQueries {
    private static final String REAL_DEVICE=" AND EXISTS(SELECT 1 FROM devices real_device WHERE real_device.id=e.device_id AND real_device.tenant_id=e.tenant_id AND real_device.is_simulated=FALSE)";
    public record FaultTypeRank(String code,String label,long count) { }
    public record OfflineDeviceRank(String deviceId,String tenantId,String deviceName,String address,long reportedOfflineEvents) { }
    public record DayCount(String date,long count) { }
    public record Analytics(OffsetDateTime asOf,OffsetDateTime from,OffsetDateTime to,String timezone,int days,
        long alarms,long faults,long untimedFaultEvents,long reportedOfflineEvents,List<FaultTypeRank> faultTypes,List<OfflineDeviceRank> reportedOfflineDevices,
        List<DayCount> alarmsByDay,List<DayCount> faultsByDay,List<DayCount> reportedOfflineEventsByDay,
        String offlineSemantics,String faultSemantics) { }
    private final JdbcTemplate jdbc;private final DeviceAccessScope access;private final ScopeFilter scope;private final DatabaseTime time;
    public DutyAnalyticsQueries(JdbcTemplate jdbc,DeviceAccessScope access,ScopeFilter scope,DatabaseTime time){this.jdbc=jdbc;this.access=access;this.scope=scope;this.time=time;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Analytics read(String actor,String tenant,ScopeFilter.Selection selection,int days){
        if(days!=7&&days!=30)throw ApiError.bad("运维统计周期仅支持7天或30天");var now=time.now().withOffsetSameInstant(ZoneOffset.UTC);var from=now.minusDays(days);
        if(selection.empty())return new Analytics(now,from,now,"UTC",days,0,0,0,0,List.of(),List.of(),List.of(),List.of(),List.of(),"ONLY_REPORTED_OFFLINE_EVENTS","ONLY_LEGACY_INGEST_EVENTS_KIND_FAULT");
        var faultScope=access.predicate(actor,"e.tenant_id","e.device_id");var faultTree=scope.alarmDevices("e",selection.tenantIds(),selection.placeIds());
        var faultArgs=args(from,now,tenant,faultScope.bindings(),faultTree.bindings());String faultWhere=eventWhere("FAULT",tenant,faultScope,faultTree);
        long faults=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events e"+faultWhere,Long.class,faultArgs.toArray());
        var untimedFaultArgs=new ArrayList<Object>();if(tenant!=null)untimedFaultArgs.add(tenant);untimedFaultArgs.addAll(faultScope.bindings());untimedFaultArgs.addAll(faultTree.bindings());
        String untimedFaultWhere=" WHERE e.kind='FAULT' AND e.observed_at IS NULL"+REAL_DEVICE+(tenant==null?"":" AND e.tenant_id=?")+faultScope.and()+faultTree.and();
        long untimedFaults=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events e"+untimedFaultWhere,Long.class,untimedFaultArgs.toArray());
        var faultCounts=new HashMap<FaultProjection.Description,long[]>();
        jdbc.query("SELECT e.method,e.params_json FROM legacy_ingest_events e"+faultWhere,(RowCallbackHandler)rs->{
            var description=FaultProjection.describe(rs.getString(1),rs.getString(2));
            faultCounts.computeIfAbsent(description,key->new long[1])[0]++;
        },faultArgs.toArray());
        var faultTypes=faultCounts.entrySet().stream().map(e->new FaultTypeRank(e.getKey().code(),e.getKey().label(),e.getValue()[0]))
            .sorted(Comparator.comparingLong(FaultTypeRank::count).reversed().thenComparing(FaultTypeRank::label).thenComparing(FaultTypeRank::code,Comparator.nullsFirst(Comparator.naturalOrder())))
            .limit(10).toList();
        var offlineScope=access.predicate(actor,"e.tenant_id","e.device_id");var offlineTree=scope.alarmDevices("e",selection.tenantIds(),selection.placeIds());
        var offlineArgs=args(from,now,tenant,offlineScope.bindings(),offlineTree.bindings());String offlineWhere=eventWhere("OFFLINE",tenant,offlineScope,offlineTree);
        long offline=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events e"+offlineWhere,Long.class,offlineArgs.toArray());
        var topOffline=jdbc.query("SELECT e.device_id,e.tenant_id,d.name,d.address,COUNT(*) AS total FROM legacy_ingest_events e LEFT JOIN devices d ON d.id=e.device_id AND d.tenant_id=e.tenant_id"+offlineWhere+" AND e.device_id IS NOT NULL GROUP BY e.device_id,e.tenant_id,d.name,d.address ORDER BY total DESC,e.device_id LIMIT 10",(rs,n)->new OfflineDeviceRank(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)),offlineArgs.toArray());
        var alarmScope=access.predicate(actor,"a.tenant_id","a.device_id");var alarmTree=scope.alarmDevices("a",selection.tenantIds(),selection.placeIds());var alarmArgs=args(from,now,tenant,alarmScope.bindings(),alarmTree.bindings());String alarmWhere=" WHERE a.is_simulated=FALSE AND a.source_time_known IS NOT FALSE AND a.occurred_at>=? AND a.occurred_at<?"+(tenant==null?"":" AND a.tenant_id=?")+alarmScope.and()+alarmTree.and();
        long alarms=jdbc.queryForObject("SELECT COUNT(*) FROM alarms a"+alarmWhere,Long.class,alarmArgs.toArray());
        var alarmsByDay=countsByDay("alarms a",alarmWhere,alarmArgs,from,days,"a.occurred_at");
        var faultsByDay=countsByDay("legacy_ingest_events e",faultWhere,faultArgs,from,days,"e.observed_at");
        var offlineByDay=countsByDay("legacy_ingest_events e",offlineWhere,offlineArgs,from,days,"e.observed_at");
        return new Analytics(now,from,now,"UTC",days,alarms,faults,untimedFaults,offline,List.copyOf(faultTypes),List.copyOf(topOffline),alarmsByDay,faultsByDay,offlineByDay,"ONLY_REPORTED_OFFLINE_EVENTS","ONLY_LEGACY_INGEST_EVENTS_KIND_FAULT");
    }
    private List<DayCount> countsByDay(String table,String where,List<Object> args,OffsetDateTime from,int days,String timeColumn){
        var totals=new HashMap<LocalDate,Long>();
        String utcDay="CAST(FLOOR(EXTRACT(EPOCH FROM "+timeColumn+") / 86400) AS BIGINT)";
        String sql="SELECT "+utcDay+" AS event_day,COUNT(*) AS total FROM "+table+where+" GROUP BY "+utcDay;
        jdbc.query(sql,rs->{var day=LocalDate.ofEpochDay(rs.getLong("event_day"));totals.put(day,rs.getLong("total"));},args.toArray());
        var first=from.toLocalDate();var result=new ArrayList<DayCount>(days+1);
        for(int offset=0;offset<=days;offset++){var date=first.plusDays(offset);result.add(new DayCount(date.toString(),totals.getOrDefault(date,0L)));}
        return List.copyOf(result);
    }
    private static ArrayList<Object> args(OffsetDateTime from,OffsetDateTime to,String tenant,List<Object> access,List<Object> tree){var result=new ArrayList<Object>();result.add(from);result.add(to);if(tenant!=null)result.add(tenant);result.addAll(access);result.addAll(tree);return result;}
    private static String eventWhere(String kind,String tenant,DeviceAccessScope.Filter access,ScopeFilter.Clause tree){return " WHERE e.kind='"+kind+"'"+REAL_DEVICE+" AND e.observed_at>=? AND e.observed_at<?"+(tenant==null?"":" AND e.tenant_id=?")+access.and()+tree.and();}
}
