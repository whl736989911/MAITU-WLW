package com.fireplatform.alarmgroup;

import com.fireplatform.configuration.DatabaseTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates logical sessions for newly created physical FIRE alarms only.
 * It never rewrites alarms, device state, source dedup/occurrence counters or recovery.
 */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class AlarmSessionGrouping {
    private record Open(String id,int windowSeconds,OffsetDateTime lastSourceAt) { }
    private record Alarm(String deviceName,String address,boolean simulated) { }

    private final JdbcTemplate jdbc;
    private final AlarmMergePolicyQueries policies;
    private final DatabaseTime time;

    public AlarmSessionGrouping(JdbcTemplate jdbc,AlarmMergePolicyQueries policies,DatabaseTime time) {
        this.jdbc=jdbc; this.policies=policies; this.time=time;
    }

    /** Source-time only: an unknown source time never fabricates membership. */
    public void onAlarmCreated(String tenantId,String alarmId,String deviceId,String eventType,OffsetDateTime occurredAt) {
        if(occurredAt==null || !"FIRE".equals(eventType)) return;
        var early=policies.policy(tenantId);
        if(!early.enabled() || early.windowSeconds()==null) return;
        var alarm=jdbc.query("SELECT device_name,address,is_simulated FROM alarms WHERE tenant_id=? AND id=? AND device_id=?",
            (rs,n)->new Alarm(rs.getString(1),rs.getString(2),rs.getBoolean(3)),tenantId,alarmId,deviceId);
        if(alarm.isEmpty() || alarm.getFirst().simulated()) return;
        // Serializes session selection per source device without touching the alarm or source-dedup path.
        if(jdbc.queryForList("SELECT id FROM devices WHERE tenant_id=? AND id=? FOR UPDATE",tenantId,deviceId).isEmpty()) return;
        // Re-read under the policy row lock so a concurrent policy revision cannot create a stale captured session.
        var locked=jdbc.query("SELECT revision,enabled,window_seconds FROM alarm_merge_policies WHERE tenant_id=? FOR UPDATE",
            (rs,n)->new Object[]{rs.getLong(1),rs.getBoolean(2),rs.getObject(3,Integer.class)},tenantId);
        if(locked.isEmpty() || !Boolean.TRUE.equals(locked.getFirst()[1]) || locked.getFirst()[2]==null) return;
        long policyRevision=(Long)locked.getFirst()[0];
        int policyWindow=(Integer)locked.getFirst()[2];
        var open=jdbc.query("SELECT id,window_seconds,last_source_at FROM alarm_sessions WHERE tenant_id=? AND device_id=? AND event_type='FIRE' AND open_slot='OPEN' FOR UPDATE",
            (rs,n)->new Open(rs.getString(1),rs.getInt(2),rs.getObject(3,OffsetDateTime.class)),tenantId,deviceId).stream().findFirst();
        OffsetDateTime now=time.now();
        String sessionId;
        boolean opened=false;
        // A later source fire starts a new cycle after all prior physical members recover, regardless of review.
        if(open.isEmpty() || occurredAt.isAfter(open.get().lastSourceAt().plusSeconds(open.get().windowSeconds()))
                || recoveredBefore(tenantId,open.get().id(),occurredAt)) {
            if(open.isPresent()) {
                var previous=open.get();
                jdbc.update("UPDATE alarm_sessions SET open_slot=?,closed_at=?,revision=revision+1,updated_at=? WHERE id=? AND tenant_id=? AND open_slot='OPEN'",
                    previous.id(),now,now,previous.id(),tenantId);
                event(tenantId,previous.id(),null,"SESSION_CLOSED",occurredAt,now);
            }
            sessionId=UUID.randomUUID().toString();
            var row=alarm.getFirst();
            jdbc.update("INSERT INTO alarm_sessions(id,tenant_id,device_id,device_name,address,event_type,group_by,policy_revision,window_seconds,first_source_at,last_source_at,open_slot,closed_at,revision,created_at,updated_at) VALUES(?,?,?,?,?,'FIRE','DEVICE_EVENT_TYPE',?,?,?,?,'OPEN',NULL,0,?,?)",
                sessionId,tenantId,deviceId,row.deviceName(),row.address(),policyRevision,policyWindow,occurredAt,occurredAt,now,now);
            opened=true;
        } else {
            sessionId=open.get().id();
            jdbc.update("UPDATE alarm_sessions SET last_source_at=CASE WHEN last_source_at<? THEN ? ELSE last_source_at END,revision=revision+1,updated_at=? WHERE id=? AND tenant_id=?",
                occurredAt,occurredAt,now,sessionId,tenantId);
        }
        if(jdbc.queryForList("SELECT alarm_id FROM alarm_session_members WHERE tenant_id=? AND alarm_id=?",tenantId,alarmId).isEmpty()) {
            jdbc.update("INSERT INTO alarm_session_members(session_id,tenant_id,alarm_id,device_id,source_at,added_at) VALUES(?,?,?,?,?,?)",
                sessionId,tenantId,alarmId,deviceId,occurredAt,now);
            if(opened) event(tenantId,sessionId,null,"SESSION_OPENED",occurredAt,now);
            event(tenantId,sessionId,alarmId,"MEMBER_ADDED",occurredAt,now);
        }
    }

    private boolean recoveredBefore(String tenantId,String sessionId,OffsetDateTime nextSourceAt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT NOT EXISTS(SELECT 1 FROM alarm_session_members m
                JOIN alarms a ON a.tenant_id=m.tenant_id AND a.id=m.alarm_id
                WHERE m.tenant_id=? AND m.session_id=? AND a.physical_state='FIRE')
                AND (SELECT MAX(occurred_at) FROM alarm_session_events
                    WHERE tenant_id=? AND session_id=? AND event_kind='MEMBER_RECOVERED'
                    HAVING COUNT(*)=COUNT(occurred_at)) < ?
            """,Boolean.class,tenantId,sessionId,tenantId,sessionId,nextSourceAt));
    }

    /** Recovery is recorded as an independent session fact; it never closes the grouping window or clears another member. */
    public void onAlarmRecovered(String tenantId,String alarmId,String deviceId,OffsetDateTime occurredAt) {
        var rows=jdbc.queryForList("SELECT session_id FROM alarm_session_members WHERE tenant_id=? AND alarm_id=? AND device_id=?",String.class,tenantId,alarmId,deviceId);
        if(rows.isEmpty()) return;
        event(tenantId,rows.getFirst(),alarmId,"MEMBER_RECOVERED",occurredAt,time.now());
    }

    private void event(String tenant,String session,String alarm,String kind,OffsetDateTime occurred,OffsetDateTime recorded) {
        jdbc.update("INSERT INTO alarm_session_events(id,tenant_id,session_id,alarm_id,event_kind,occurred_at,recorded_at) VALUES(?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),tenant,session,alarm,kind,occurred,recorded);
    }
}
