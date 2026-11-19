package com.fireplatform.alarmgroup;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ExecutionAuthorization;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Management-only writer for the explicit alarm grouping window. Default is absent/disabled. */
@Service
public class AlarmMergePolicyCommands {
    private final JdbcTemplate jdbc;
    private final AlarmMergePolicyQueries queries;
    private final ExecutionAuthorization authorization;
    private final TenantFence tenants;
    private final AuditService audit;
    private final DatabaseTime time;
    private final IdempotencyStore idem;

    public AlarmMergePolicyCommands(JdbcTemplate jdbc,AlarmMergePolicyQueries queries,ExecutionAuthorization authorization,
            TenantFence tenants,AuditService audit,DatabaseTime time,IdempotencyStore idem) {
        this.jdbc=jdbc; this.queries=queries; this.authorization=authorization; this.tenants=tenants; this.audit=audit; this.time=time; this.idem=idem;
    }

    @Transactional
    public AlarmMergePolicyQueries.Policy save(AuthService.Identity actor,String tenant,String key,
            long expectedRevision,boolean enabled,Integer windowSeconds,String reason,String requestId) {
        authorization.lockRequest(actor,tenant,List.of("alarm-base:write"));
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        if(reason==null || reason.trim().isEmpty() || reason.length()>1000) throw ApiError.bad("必须填写配置原因");
        if(windowSeconds!=null && (windowSeconds<1 || windowSeconds>604800)) throw ApiError.bad("分组窗口必须在1至604800秒之间");
        if(enabled && windowSeconds==null) throw ApiError.bad("启用报警分组必须明确配置源时间窗口秒数");
        String trimmedReason=reason.trim();
        String hash=idem.fingerprint(Arrays.asList(tenant,expectedRevision,trimmedReason,enabled,windowSeconds,"DEVICE_EVENT_TYPE","UNIT"));
        var replay=idem.replay(actor.user().id(),"alarm-merge-policy",key,hash,AlarmMergePolicyQueries.Policy.class);
        if(replay!=null) return replay;
        var current=jdbc.query("SELECT revision,enabled,window_seconds FROM alarm_merge_policies WHERE tenant_id=? FOR UPDATE",
            (rs,n)->new Object[]{rs.getLong(1),rs.getBoolean(2),rs.getObject(3,Integer.class)},tenant);
        long revision=current.isEmpty()?0:(Long)current.getFirst()[0];
        if(revision!=expectedRevision) throw ApiError.conflict("REVISION_CONFLICT","报警分组策略已变化，请刷新");
        boolean previouslyEnabled=!current.isEmpty() && Boolean.TRUE.equals(current.getFirst()[1]);
        Integer previousWindow=current.isEmpty()?null:(Integer)current.getFirst()[2];
        OffsetDateTime now=time.now();
        if(revision==0) {
            jdbc.update("INSERT INTO alarm_merge_policies(tenant_id,enabled,window_seconds,group_by,scope_kind,revision,updated_by,updated_at) VALUES(?,?,?,'DEVICE_EVENT_TYPE','UNIT',1,?,?)",
                tenant,enabled,windowSeconds,actor.user().id(),now);
            revision=1;
        } else {
            if(jdbc.update("UPDATE alarm_merge_policies SET enabled=?,window_seconds=?,group_by='DEVICE_EVENT_TYPE',scope_kind='UNIT',revision=revision+1,updated_by=?,updated_at=? WHERE tenant_id=? AND revision=?",
                    enabled,windowSeconds,actor.user().id(),now,tenant,revision)!=1) throw ApiError.conflict("REVISION_CONFLICT","报警分组策略已变化，请刷新");
            revision=revision+1;
        }
        jdbc.update("INSERT INTO alarm_merge_policy_revisions(tenant_id,revision,enabled,window_seconds,group_by,scope_kind,reason,updated_by,updated_at) VALUES(?,?,?,?,'DEVICE_EVENT_TYPE','UNIT',?,?,?)",
            tenant,revision,enabled,windowSeconds,trimmedReason,actor.user().id(),now);
        // A changed grouping meaning closes currently captured windows; members and physical alarms stay untouched.
        if(previouslyEnabled!=enabled || !Objects.equals(previousWindow,windowSeconds)) closeOpenSessions(tenant,now);
        audit.recordReason(actor.user().id(),tenant,"ALARM_MERGE_POLICY_UPDATED",tenant,"SUCCESS",requestId,trimmedReason);
        var result=queries.policy(tenant);
        idem.save(actor.user().id(),"alarm-merge-policy",key,hash,result);
        return result;
    }

    private void closeOpenSessions(String tenant,OffsetDateTime now) {
        var open=jdbc.queryForList("SELECT id FROM alarm_sessions WHERE tenant_id=? AND open_slot='OPEN' FOR UPDATE",String.class,tenant);
        for(String session:open) {
            jdbc.update("UPDATE alarm_sessions SET open_slot=?,closed_at=?,revision=revision+1,updated_at=? WHERE id=? AND tenant_id=? AND open_slot='OPEN'",session,now,now,session,tenant);
            jdbc.update("INSERT INTO alarm_session_events(id,tenant_id,session_id,alarm_id,event_kind,occurred_at,recorded_at) VALUES(?,?,?,NULL,'POLICY_CLOSED',NULL,?)",
                UUID.randomUUID().toString(),tenant,session,now);
        }
    }
}
