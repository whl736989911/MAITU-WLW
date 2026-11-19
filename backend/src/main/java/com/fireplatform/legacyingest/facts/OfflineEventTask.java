package com.fireplatform.legacyingest.facts;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.configuration.OfflinePolicySql;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OfflineEventTask {
    private record Candidate(String tenantId,String deviceId,OffsetDateTime lastMessageAt,long thresholdMinutes) { }
    private final JdbcTemplate jdbc;
    private final OfflinePolicySql policy;
    private final DatabaseTime time;

    public OfflineEventTask(JdbcTemplate jdbc,OfflinePolicySql policy,DatabaseTime time) {
        this.jdbc=jdbc;this.policy=policy;this.time=time;
    }

    @Scheduled(initialDelayString="${fire.legacy.offline-initial-delay-ms:60000}", fixedDelayString="${fire.legacy.offline-scan-ms:300000}")
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public int scan() {
        OffsetDateTime asOf=time.now().withOffsetSameInstant(ZoneOffset.UTC);
        String select="SELECT d.tenant_id,d.id,d.last_message_at,"+policy.value()+" AS threshold_minutes FROM devices d"+policy.connectivityJoins()+
            " LEFT JOIN (SELECT tenant_id,device_id,MAX(observed_at) AS last_offline_at FROM legacy_ingest_events WHERE kind='OFFLINE' GROUP BY tenant_id,device_id) pol_round ON pol_round.tenant_id=d.tenant_id AND pol_round.device_id=d.id"+
            " WHERE d.is_simulated=FALSE AND d.archived_at IS NULL AND "+policy.offline()+
            " AND (pol_round.last_offline_at IS NULL OR d.last_message_at>pol_round.last_offline_at)";
        List<Candidate> candidates;
        try { candidates=jdbc.query(select+" FOR UPDATE OF d SKIP LOCKED",(r,n)->new Candidate(r.getString(1),r.getString(2),r.getObject(3,OffsetDateTime.class),r.getLong(4)),asOf); }
        catch(DataAccessException unsupported) { candidates=jdbc.query(select,(r,n)->new Candidate(r.getString(1),r.getString(2),r.getObject(3,OffsetDateTime.class),r.getLong(4)),asOf); }
        int created=0;
        for(Candidate c:candidates) {
            int locked=jdbc.update("UPDATE devices SET last_message_at=last_message_at WHERE id=? AND tenant_id=? AND last_message_at=? AND is_simulated=FALSE AND archived_at IS NULL",c.deviceId(),c.tenantId(),c.lastMessageAt());
            if(locked==0)continue;
            String params="{\"reason\":\"offline_threshold\",\"evidence\":\"accepted_heartbeat\",\"thresholdMinutes\":"+c.thresholdMinutes()+",\"evaluatedAt\":\""+asOf+"\",\"lastMessageAt\":\""+c.lastMessageAt()+"\"}";
            created+=jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at) VALUES (?,?,NULL,?,'OFFLINE','offline.scan',?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),c.tenantId(),c.deviceId(),params,asOf);
        }
        return created;
    }
}
