package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Cross-domain read/lock boundary. Acquire tenant before source/device, never in reverse. */
@Service
public class TenantFence {
    private final JdbcTemplate jdbc;
    public TenantFence(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional(propagation=Propagation.MANDATORY) public void lockEnabled(String tenant) {
        var rows=jdbc.queryForList("SELECT enabled FROM tenants WHERE id=? FOR UPDATE",Boolean.class,tenant);
        if(rows.isEmpty() || !rows.getFirst()) throw new ApiError(403,"UNIT_DISABLED","单位不可用");
    }
    public record State(boolean enabled,long revision) { }
    public State state(String tenant,boolean lock) {
        var rows=jdbc.query("SELECT enabled,revision FROM tenants WHERE id=?"+(lock ? " FOR UPDATE" : ""),(rs,n)->new State(rs.getBoolean(1),rs.getLong(2)),tenant);
        if(rows.isEmpty()) throw new ApiError(403,"UNIT_DISABLED","单位不可用"); return rows.getFirst();
    }
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=ApiError.class) public long lockEnabledRevision(String tenant) { var state=state(tenant,true); if(!state.enabled()) throw new ApiError(403,"UNIT_DISABLED","单位不可用"); return state.revision(); }
    public boolean enabled(String tenant) { return jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id=? AND enabled=TRUE",Long.class,tenant)==1; }
}
