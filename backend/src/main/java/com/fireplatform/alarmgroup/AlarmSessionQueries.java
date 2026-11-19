package com.fireplatform.alarmgroup;

import com.fireplatform.configuration.SettingsService;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.Pagination;
import com.fireplatform.shared.ScopeFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only projections over logical sessions; physical alarm rows remain the only source of alarm state. */
@Service
public class AlarmSessionQueries {
    public record ClaimSummary(long total,long claimed,long sent,long unknown,long failed) { }
    public record SessionRow(String id,String tenantId,String deviceId,String deviceName,String address,String eventType,
            long policyRevision,String policyState,int windowSeconds,OffsetDateTime firstSourceAt,OffsetDateTime lastSourceAt,
            boolean open,OffsetDateTime closedAt,OffsetDateTime windowEndsAt,long memberCount,long activeMemberCount,
            long unhandledMemberCount,long revision,OffsetDateTime createdAt,OffsetDateTime updatedAt) { }
    public record SessionDetail(SessionRow session,ClaimSummary claims) { }
    public record MemberRow(String sessionId,String alarmId,String tenantId,String deviceId,String deviceName,String address,
            String eventType,OffsetDateTime sourceAt,OffsetDateTime addedAt,OffsetDateTime occurredAt,OffsetDateTime lastEventAt,
            long occurrenceCount,String physicalState,String responseState,String conclusion,long revision,boolean simulated) { }

    private static final String SELECT="SELECT s.id,s.tenant_id,s.device_id,s.device_name,s.address,s.event_type,s.policy_revision,"
        +"COALESCE((SELECT CASE WHEN p.revision=s.policy_revision THEN 'CURRENT' ELSE 'STALE' END FROM alarm_merge_policies p WHERE p.tenant_id=s.tenant_id),'UNKNOWN') AS policy_state,"
        +"s.window_seconds,s.first_source_at,s.last_source_at,s.open_slot,s.closed_at,s.revision,s.created_at,s.updated_at,"
        +"(SELECT COUNT(*) FROM alarm_session_members m WHERE m.session_id=s.id AND m.tenant_id=s.tenant_id) AS member_count,"
        +"(SELECT COUNT(*) FROM alarm_session_members m JOIN alarms a ON a.id=m.alarm_id AND a.tenant_id=m.tenant_id WHERE m.session_id=s.id AND m.tenant_id=s.tenant_id AND a.physical_state='FIRE' AND a.is_simulated=FALSE) AS active_member_count,"
        +"(SELECT COUNT(*) FROM alarm_session_members m JOIN alarms a ON a.id=m.alarm_id AND a.tenant_id=m.tenant_id WHERE m.session_id=s.id AND m.tenant_id=s.tenant_id AND a.response_state='UNHANDLED') AS unhandled_member_count "
        +"FROM alarm_sessions s";

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final DeviceAccessScope deviceScope;
    private final ScopeFilter scopeFilter;

    public AlarmSessionQueries(JdbcTemplate jdbc,SettingsService settings,DeviceAccessScope deviceScope,ScopeFilter scopeFilter) {
        this.jdbc=jdbc; this.settings=settings; this.deviceScope=deviceScope; this.scopeFilter=scopeFilter;
    }

    @Transactional(readOnly=true)
    public Page<SessionRow> sessions(String actor,String tenant,int page,Integer requestedSize,ScopeFilter.Selection selection,String deviceId,String eventType) {
        var paging=Pagination.of(page,requestedSize,settings.value("default_page_size"));
        if(selection.empty()) return new Page<>(java.util.List.of(),0,paging.page(),paging.size());
        var where=where(actor,tenant,selection,deviceId,eventType);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM alarm_sessions s"+where.sql(),Long.class,where.args().toArray());
        var args=new ArrayList<>(where.args());args.add(paging.size());args.add(paging.offset());
        var items=jdbc.query(SELECT+where.sql()+" ORDER BY s.last_source_at DESC,s.id LIMIT ? OFFSET ?",(rs,n)->row(rs),args.toArray());
        return new Page<>(items,total,paging.page(),paging.size());
    }

    @Transactional(readOnly=true)
    public java.util.List<SessionRow> exportSessions(String actor,String tenant,ScopeFilter.Selection selection,String deviceId,String eventType,java.util.List<String> selectedIds) {
        if(selection.empty()||selectedIds!=null&&selectedIds.isEmpty())return java.util.List.of();
        var plan=where(actor,tenant,selection,deviceId,eventType);
        var args=new ArrayList<Object>(plan.args());String selected="";
        if(selectedIds!=null){selected=" AND s.id IN ("+String.join(",",java.util.Collections.nCopies(selectedIds.size(),"?"))+")";args.addAll(selectedIds);}
        var rows=jdbc.query(SELECT+plan.sql()+selected+" ORDER BY s.last_source_at DESC,s.id LIMIT 10001",(rs,n)->row(rs),args.toArray());
        if(rows.size()>10000)throw ApiError.bad("导出超过10000行，请收窄范围");
        return rows;
    }

    @Transactional(readOnly=true)
    public SessionDetail session(String actor,String tenant,String sessionId) {
        var scope=deviceScope.predicate(actor,"s.tenant_id","s.device_id");
        var args=new ArrayList<Object>();args.add(sessionId);if(tenant!=null)args.add(tenant);args.addAll(scope.bindings());
        var rows=jdbc.query(SELECT+" WHERE s.id=?"+(tenant==null?"":" AND s.tenant_id=?")+scope.and(),(rs,n)->row(rs),args.toArray());
        if(rows.isEmpty()) throw ApiError.missing();
        var session=rows.getFirst();
        var claim=jdbc.queryForObject("SELECT COUNT(*),COALESCE(SUM(CASE WHEN status='CLAIMED' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN status='SENT' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN status='UNKNOWN' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) FROM alarm_notification_claims WHERE tenant_id=? AND session_id=?",
            (rs,n)->new ClaimSummary(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5)),session.tenantId(),session.id());
        return new SessionDetail(session,claim);
    }

    @Transactional(readOnly=true)
    public Page<MemberRow> members(String actor,String tenant,String sessionId,int page,Integer requestedSize) {
        var session=session(actor,tenant,sessionId).session();
        var paging=Pagination.of(page,requestedSize,settings.value("default_page_size"));
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM alarm_session_members m WHERE m.tenant_id=? AND m.session_id=?",Long.class,session.tenantId(),session.id());
        var items=jdbc.query("SELECT m.session_id,m.alarm_id,m.tenant_id,m.device_id,a.device_name,a.address,a.event_type,m.source_at,m.added_at,a.occurred_at,a.last_event_at,a.occurrence_count,a.physical_state,a.response_state,a.conclusion,a.revision,a.is_simulated FROM alarm_session_members m JOIN alarms a ON a.id=m.alarm_id AND a.tenant_id=m.tenant_id WHERE m.tenant_id=? AND m.session_id=? ORDER BY m.source_at,m.alarm_id LIMIT ? OFFSET ?",
            (rs,n)->member(rs),session.tenantId(),session.id(),paging.size(),paging.offset());
        return new Page<>(items,total,paging.page(),paging.size());
    }

    private record Where(String sql,java.util.List<Object> args) { }
    private Where where(String actor,String tenant,ScopeFilter.Selection selection,String deviceId,String eventType) {
        var scope=deviceScope.predicate(actor,"s.tenant_id","s.device_id");
        var tree=scopeFilter.alarmDevices("s",selection.tenantIds(),selection.placeIds());
        var args=new ArrayList<Object>();
        String sql=(tenant==null?" WHERE 1=1":" WHERE s.tenant_id=?");
        if(tenant!=null)args.add(tenant);
        sql+=scope.and()+tree.and();
        args.addAll(scope.bindings());args.addAll(tree.bindings());
        if(deviceId!=null&&!deviceId.isBlank()){sql+=" AND s.device_id=?";args.add(deviceId);}
        if(eventType!=null&&!eventType.isBlank()){sql+=" AND s.event_type=?";args.add(eventType);}
        return new Where(sql,args);
    }

    private static SessionRow row(ResultSet rs) throws SQLException {
        OffsetDateTime first=rs.getObject("first_source_at",OffsetDateTime.class),last=rs.getObject("last_source_at",OffsetDateTime.class);
        int window=rs.getInt("window_seconds");
        boolean open="OPEN".equals(rs.getString("open_slot"));
        return new SessionRow(rs.getString("id"),rs.getString("tenant_id"),rs.getString("device_id"),rs.getString("device_name"),rs.getString("address"),rs.getString("event_type"),
            rs.getLong("policy_revision"),rs.getString("policy_state"),window,first,last,open,rs.getObject("closed_at",OffsetDateTime.class),last==null?null:last.plusSeconds(window),
            rs.getLong("member_count"),rs.getLong("active_member_count"),rs.getLong("unhandled_member_count"),rs.getLong("revision"),
            rs.getObject("created_at",OffsetDateTime.class),rs.getObject("updated_at",OffsetDateTime.class));
    }

    private static MemberRow member(ResultSet rs) throws SQLException {
        return new MemberRow(rs.getString("session_id"),rs.getString("alarm_id"),rs.getString("tenant_id"),rs.getString("device_id"),rs.getString("device_name"),rs.getString("address"),
            rs.getString("event_type"),rs.getObject("source_at",OffsetDateTime.class),rs.getObject("added_at",OffsetDateTime.class),
            rs.getObject("occurred_at",OffsetDateTime.class),rs.getObject("last_event_at",OffsetDateTime.class),rs.getLong("occurrence_count"),
            rs.getString("physical_state"),rs.getString("response_state"),rs.getString("conclusion"),rs.getLong("revision"),rs.getBoolean("is_simulated"));
    }
}
