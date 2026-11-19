package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Security commands alone take this coordination row before taking ANY user row lock. */
@Service
public class ManagementGuard {
    private final JdbcTemplate jdbc; private final AuthService auth;
    public ManagementGuard(JdbcTemplate jdbc,AuthService auth) { this.jdbc=jdbc; this.auth=auth; }
    public void begin(AuthService.Identity actor,String permission,String tenant,String audience) {
        jdbc.queryForObject("SELECT id FROM security_management_lock WHERE id=1 FOR UPDATE",Integer.class);
        auth.lockForManagement(actor,permission,tenant,audience);
    }
    public void requireCurrent(AuthService.Identity actor,String permission,String tenant,String audience) { auth.lockForManagement(actor,permission,tenant,audience); }
    public boolean hasCurrentPermission(AuthService.Identity actor,String permission) { return auth.permissions(actor.user().id()).contains(permission); }
    public void grantRequired(String actor,String tenant) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id=? AND tenant_id=?",Long.class,actor,tenant)!=1) throw ApiError.forbidden("未授权访问此单位");
    }
    public List<String> grants(String actor,List<String> requested) {
        if(requested==null || requested.size()>com.fireplatform.shared.Limits.MAX_DELEGATED_GRANTS) throw ApiError.bad("单位授权列表无效");
        var existing=jdbc.queryForList("SELECT tenant_id FROM user_tenant_grants WHERE user_id=?",String.class,actor);
        if(requested.stream().anyMatch(t->t==null || !existing.contains(t))) throw new ApiError(403,"GRANT_DELEGATION_FORBIDDEN","只能委派操作者已有的单位授权");
        return requested.stream().distinct().sorted().toList();
    }
}
