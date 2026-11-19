package com.fireplatform.alarmgroup;

import com.fireplatform.configuration.DatabaseTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable per-session notification gate. The claim row is committed before any external send, so a crash or an
 * ambiguous provider outcome can never silently produce a second external request. A definite not-sent failure may
 * reuse the same bounded retry budget but the budget belongs to the session claim, never to an individual member.
 */
@Service
public class AlarmSessionNotificationGate {
    public record Session(String id,String tenantId,String deviceId,long policyRevision,int windowSeconds) { }
    public record ClaimDecision(boolean proceed,String claimId,int attemptNo,String reason) { }
    private record Existing(String id,String status,int attemptNo,String ruleId,int level) { }

    private final JdbcTemplate jdbc;
    private final DatabaseTime time;

    public AlarmSessionNotificationGate(JdbcTemplate jdbc,DatabaseTime time) { this.jdbc=jdbc; this.time=time; }

    @Transactional(readOnly=true)
    public Session sessionFor(String tenantId,String alarmId) {
        var rows=jdbc.query("SELECT s.id,s.tenant_id,s.device_id,s.policy_revision,s.window_seconds FROM alarm_session_members m JOIN alarm_sessions s ON s.id=m.session_id AND s.tenant_id=m.tenant_id WHERE m.tenant_id=? AND m.alarm_id=?",
            (rs,n)->new Session(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getInt(5)),tenantId,alarmId);
        return rows.isEmpty()?null:rows.getFirst();
    }

    /** Earliest still-physical FIRE member, so a recovered member never suppresses a remaining active member. */
    @Transactional(readOnly=true)
    public Map<String,Object> representative(String tenantId,String sessionId) {
        var rows=jdbc.queryForList("SELECT a.id,a.tenant_id,a.device_id,a.device_name,a.address,a.occurred_at,a.source_time_known,a.physical_state,a.response_state,d.model AS device_model,'FIRE' AS event_kind,'ALARM' AS source_kind,a.event_type AS event_code FROM alarm_session_members m JOIN alarms a ON a.id=m.alarm_id AND a.tenant_id=m.tenant_id JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id WHERE m.session_id=? AND m.tenant_id=? AND a.event_type='FIRE' AND a.is_simulated=FALSE AND a.physical_state='FIRE' ORDER BY CASE WHEN a.response_state='UNHANDLED' THEN 0 ELSE 1 END,m.source_at,a.id LIMIT 1",sessionId,tenantId);
        return rows.isEmpty()?null:rows.getFirst();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public ClaimDecision claim(String tenantId,Session session,String occurrenceKey,String ruleId,String contactId,String channel,int level,String alarmId,int maxAttempts) {
        if(jdbc.queryForList("SELECT id FROM alarm_sessions WHERE id=? AND tenant_id=? FOR UPDATE",session.id(),tenantId).isEmpty()) return new ClaimDecision(false,null,0,"SESSION_MISSING");
        // The winning rule is provenance, not a new permission to send again within this occurrence.
        var rows=jdbc.query("SELECT id,status,attempt_no,rule_id,level FROM alarm_notification_claims "
            +"WHERE tenant_id=? AND session_id=? AND occurrence_key=? AND contact_id=? AND channel=? "
            +"ORDER BY CASE status WHEN 'SENT' THEN 0 WHEN 'UNKNOWN' THEN 1 WHEN 'CLAIMED' THEN 2 ELSE 3 END,attempt_no DESC,id LIMIT 1",
            (rs,n)->new Existing(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getString(4),rs.getInt(5)),
            tenantId,session.id(),occurrenceKey,contactId,channel);
        OffsetDateTime now=time.now();
        if(rows.isEmpty()) {
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO alarm_notification_claims(id,tenant_id,session_id,alarm_id,event_type,rule_id,contact_id,channel,level,occurrence_key,policy_revision,status,attempt_no,created_at,updated_at) VALUES(?,?,?,?,'FIRE',?,?,?,?,?,?,'CLAIMED',0,?,?)",
                id,tenantId,session.id(),alarmId,ruleId,contactId,channel,level,occurrenceKey,session.policyRevision(),now,now);
            return new ClaimDecision(true,id,1,"CLAIMED");
        }
        var existing=rows.getFirst();
        switch(existing.status()) {
            case "SENT": return new ClaimDecision(false,existing.id(),existing.attemptNo(),"ALREADY_SENT");
            case "UNKNOWN": return new ClaimDecision(false,existing.id(),existing.attemptNo(),"ALREADY_UNKNOWN");
            case "CLAIMED": return new ClaimDecision(false,existing.id(),existing.attemptNo(),"IN_PROGRESS");
            case "FAILED":
                if(!ruleId.equals(existing.ruleId())||level!=existing.level())
                    return new ClaimDecision(false,existing.id(),existing.attemptNo(),"PRIOR_RULE_FAILED");
                if(existing.attemptNo()>=maxAttempts) return new ClaimDecision(false,existing.id(),existing.attemptNo(),"RETRY_EXHAUSTED");
                if(jdbc.update("UPDATE alarm_notification_claims SET status='CLAIMED',alarm_id=?,updated_at=? WHERE id=? AND status='FAILED' AND attempt_no=?",alarmId,now,existing.id(),existing.attemptNo())!=1)
                    return new ClaimDecision(false,existing.id(),existing.attemptNo(),"IN_PROGRESS");
                return new ClaimDecision(true,existing.id(),existing.attemptNo()+1,"RETRY");
            default: return new ClaimDecision(false,existing.id(),existing.attemptNo(),"IN_PROGRESS");
        }
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(String claimId,String status,int attemptNo,String providerCode,String providerId) {
        jdbc.update("UPDATE alarm_notification_claims SET status=?,attempt_no=CASE WHEN attempt_no<? THEN ? ELSE attempt_no END,provider_code=?,provider_id=?,updated_at=? WHERE id=? AND status='CLAIMED'",
            status,attemptNo,attemptNo,providerCode,providerId,time.now(),claimId);
    }
}
