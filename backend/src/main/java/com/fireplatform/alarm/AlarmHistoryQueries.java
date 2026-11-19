package com.fireplatform.alarm;

import com.fireplatform.shared.*;
import java.sql.*;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AlarmHistoryQueries {
    private record Aggregate(long total,Long first,Long last,OffsetDateTime baselineAt,Long baselineRevision,long baselines,long createdZero) { }
    private final JdbcTemplate jdbc; private final AlarmQueryService alarms; private final PageSizePolicy sizes;private final DeviceAccessScope scopes;
    public AlarmHistoryQueries(JdbcTemplate jdbc,AlarmQueryService alarms,PageSizePolicy sizes,DeviceAccessScope scopes) { this.jdbc=jdbc; this.alarms=alarms; this.sizes=sizes;this.scopes=scopes; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public AlarmTraceModels.History history(String actor,String tenant,String id,int page,Integer size,String viewer,boolean management) {
        var current=alarms.alarm(actor,tenant,id); var p=Pagination.of(page,size,sizes.defaultPageSize());var scope=scopes.predicate(actor,"scope_alarm.tenant_id","scope_alarm.device_id");
        String visibility=" AND EXISTS(SELECT 1 FROM alarms scope_alarm WHERE scope_alarm.id=alarm_trace_entries.alarm_id AND scope_alarm.tenant_id=alarm_trace_entries.tenant_id"+scope.and()+")";
        var args=new java.util.ArrayList<Object>();args.add(tenant);args.add(id);args.addAll(scope.bindings());
        var all=jdbc.queryForObject("SELECT COUNT(*),MIN(revision_after),MAX(revision_after),MIN(CASE WHEN action='MIGRATION_BASELINE' THEN recorded_at ELSE NULL END),MIN(CASE WHEN action='MIGRATION_BASELINE' THEN revision_after ELSE NULL END),COALESCE(SUM(CASE WHEN action='MIGRATION_BASELINE' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN action='FIRE_CREATED' AND revision_after=0 THEN 1 ELSE 0 END),0) FROM alarm_trace_entries WHERE tenant_id=? AND alarm_id=?"+visibility,(rs,n)->new Aggregate(rs.getLong(1),rs.getObject(2,Long.class),rs.getObject(3,Long.class),rs.getObject(4,OffsetDateTime.class),rs.getObject(5,Long.class),rs.getLong(6),rs.getLong(7)),args.toArray());
        String kind="PARTIAL"; boolean contiguous=all.total()>0 && all.last()==current.revision() && all.total()-1==all.last()-all.first();
        if(contiguous && all.first()==0 && all.createdZero()==1 && all.baselines()==0) kind="FROM_CREATION";
        else if(contiguous && all.baselines()==1 && all.first().equals(all.baselineRevision()) && all.createdZero()==0) kind="SINCE_MIGRATION";
        var coverage=new AlarmTraceModels.Coverage(kind,all.first(),all.last(),current.revision(),all.total(),all.baselineAt());
        args.add(p.size());args.add(p.offset());
        var items=jdbc.query("SELECT * FROM alarm_trace_entries WHERE tenant_id=? AND alarm_id=?"+visibility+" ORDER BY revision_after DESC,id LIMIT ? OFFSET ?",(rs,n)->AlarmTraceModels.project(row(rs),viewer,management),args.toArray());
        return new AlarmTraceModels.History(items,all.total(),p.page(),p.size(),coverage);
    }
    private AlarmTraceModels.Stored row(ResultSet rs) throws SQLException {
        Long before=rs.getObject("revision_before",Long.class); var prior=before==null?null:new AlarmTraceModels.State(rs.getString("before_physical_state"),rs.getString("before_response_state"),rs.getString("before_conclusion"));
        return new AlarmTraceModels.Stored(rs.getString("id"),rs.getString("alarm_id"),rs.getString("action"),before,rs.getLong("revision_after"),prior,new AlarmTraceModels.State(rs.getString("after_physical_state"),rs.getString("after_response_state"),rs.getString("after_conclusion")),rs.getObject("occurred_at",OffsetDateTime.class),rs.getObject("recorded_at",OffsetDateTime.class),rs.getString("actor_kind"),rs.getString("actor_id"),rs.getString("actor_name"),rs.getString("remark"),rs.getString("origin"));
    }
}
