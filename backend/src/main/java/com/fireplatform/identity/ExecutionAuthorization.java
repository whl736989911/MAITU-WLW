package com.fireplatform.identity;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Public authorization-generation fence. Worker authorization is not a retained user session. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class ExecutionAuthorization {
    private record Actor(long revision,boolean enabled,boolean mustChange,String type) { }
    private final JdbcTemplate jdbc; private final ManagementGuard management; private final AuthService auth; private final CoordinationFence coordination; private final DeviceScopes scopes;
    public ExecutionAuthorization(JdbcTemplate jdbc,ManagementGuard management,AuthService auth,CoordinationFence coordination,DeviceScopes scopes) { this.jdbc=jdbc; this.management=management; this.auth=auth; this.coordination=coordination; this.scopes=scopes; }
    public long lockRequest(AuthService.Identity actor,String tenant,List<String> required) {
        management.begin(actor,required.getFirst(),tenant,"management");
        for(String permission:required.subList(1,required.size())) management.requireCurrent(actor,permission,tenant,"management");
        return jdbc.queryForObject("SELECT revision FROM app_users WHERE id=?",Long.class,actor.user().id());
    }
    /**
     * Takes coordination -> actor only; caller must take tenant -> source -> device -> job next.
     * {@code deviceId} is the persisted target device: null is reserved for explicitly tenant-level work and
     * never bypasses a device check; a non-null value must be in the actor's current explicit device scope.
     */
    public String lockExecution(String actor,long expectedRevision,String tenant,List<String> required,String deviceId) {
        coordination.lock();
        var rows=jdbc.query("SELECT revision,enabled,must_change_password,user_type FROM app_users WHERE id=? FOR UPDATE",(rs,n)->new Actor(rs.getLong(1),rs.getBoolean(2),rs.getBoolean(3),rs.getString(4)),actor);
        if(rows.isEmpty()) return "ACTOR_UNAVAILABLE"; var current=rows.getFirst();
        if(current.revision()!=expectedRevision) return "ACTOR_CHANGED";
        if(!current.enabled() || current.mustChange() || !current.type().equals("PLATFORM")) return "ACTOR_UNAVAILABLE";
        if(!auth.permissions(actor).containsAll(required)) return "AUTHORIZATION_REVOKED";
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id=? AND tenant_id=?",Long.class,actor,tenant)!=1) return "AUTHORIZATION_REVOKED";
        if(deviceId!=null && (deviceId.isBlank() || !scopes.contains(actor,tenant,deviceId))) return "AUTHORIZATION_REVOKED";
        return null;
    }
}
