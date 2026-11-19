package com.fireplatform.alarmgroup;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the tenant alarm merge policy and its immutable revision history. */
@Service
public class AlarmMergePolicyQueries {
    public record Policy(String tenantId,long revision,boolean enabled,Integer windowSeconds,String groupBy,String scopeKind,OffsetDateTime updatedAt) { }
    public record Revision(long revision,boolean enabled,Integer windowSeconds,String groupBy,String scopeKind,String reason,String updatedBy,OffsetDateTime updatedAt) { }

    private final JdbcTemplate jdbc;

    public AlarmMergePolicyQueries(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional(readOnly=true)
    public Policy policy(String tenantId) {
        var rows=jdbc.query("SELECT revision,enabled,window_seconds,group_by,scope_kind,updated_at FROM alarm_merge_policies WHERE tenant_id=?",
            (rs,n)->new Policy(tenantId,rs.getLong("revision"),rs.getBoolean("enabled"),rs.getObject("window_seconds",Integer.class),
                rs.getString("group_by"),rs.getString("scope_kind"),rs.getObject("updated_at",OffsetDateTime.class)),tenantId);
        if(rows.isEmpty()) return new Policy(tenantId,0,false,null,"DEVICE_EVENT_TYPE","UNIT",null);
        return rows.getFirst();
    }

    @Transactional(readOnly=true)
    public List<Revision> history(String tenantId) {
        return jdbc.query("SELECT revision,enabled,window_seconds,group_by,scope_kind,reason,updated_by,updated_at FROM alarm_merge_policy_revisions WHERE tenant_id=? ORDER BY revision DESC",
            (rs,n)->new Revision(rs.getLong("revision"),rs.getBoolean("enabled"),rs.getObject("window_seconds",Integer.class),
                rs.getString("group_by"),rs.getString("scope_kind"),rs.getString("reason"),rs.getString("updated_by"),
                rs.getObject("updated_at",OffsetDateTime.class)),tenantId);
    }
}
