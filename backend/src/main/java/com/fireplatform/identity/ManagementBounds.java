package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Shared delegation bounds: nobody may manage, reset, re-role or re-scope an account above their own reach. */
@Service
public class ManagementBounds {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final DeviceScopes scopes;

    public ManagementBounds(JdbcTemplate jdbc, AuthService auth, DeviceScopes scopes) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.scopes = scopes;
    }

    /** Self and protected root accounts are never management targets. */
    public void protect(AuthService.Identity actor, IdentityQueries.ManagedUser target) {
        if (actor.user().id().equals(target.id())) throw ApiError.conflict("SELF_MANAGEMENT_FORBIDDEN", "不能通过管理命令修改自己");
        if (target.isProtected()) throw ApiError.conflict("PROTECTED_ACCOUNT", "受保护根账户不可管理");
    }

    /** The CURRENT target must stay inside the operator's effective permissions, grants and device scopes. */
    public void check(AuthService.Identity actor, IdentityQueries.ManagedUser target) {
        var actorPermissions = auth.permissions(actor.user().id());
        if (!actorPermissions.containsAll(auth.permissions(target.id())))
            throw new ApiError(403, "TARGET_MANAGEMENT_FORBIDDEN", "目标当前权限超出操作者管理范围");
        if (target.userType().equals("PLATFORM")) {
            var actorGrants = jdbc.queryForList("SELECT tenant_id FROM user_tenant_grants WHERE user_id=?", String.class, actor.user().id());
            if (!actorGrants.containsAll(target.tenantIds()))
                throw new ApiError(403, "TARGET_MANAGEMENT_FORBIDDEN", "目标当前单位授权超出操作者管理范围");
        }
        if (!scopes.covers(actor.user().id(), target.id()))
            throw new ApiError(403, "TARGET_MANAGEMENT_FORBIDDEN", "目标当前设备范围超出操作者管理范围");
    }

    /** For a template edit every assigned user must remain inside the operator's bounds. */
    public void checkRoleImpact(AuthService.Identity actor, String userId, List<String> effectiveAfter) {
        if (actor.user().id().equals(userId)) throw ApiError.conflict("SELF_MANAGEMENT_FORBIDDEN", "不能修改分配给自己的角色模板");
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_protected FROM app_users WHERE id=?", Boolean.class, userId)))
            throw ApiError.conflict("PROTECTED_ACCOUNT", "受保护根账户不可管理");
        var actorPermissions = auth.permissions(actor.user().id());
        if (!actorPermissions.containsAll(effectiveAfter))
            throw new ApiError(403, "TARGET_MANAGEMENT_FORBIDDEN", "受影响用户权限超出操作者管理范围");
        // Unit bound: platform users need a grant superset; customer users live in exactly one unit that must be granted.
        if ("PLATFORM".equals(jdbc.queryForObject("SELECT user_type FROM app_users WHERE id=?", String.class, userId))) {
            var actorGrants = jdbc.queryForList("SELECT tenant_id FROM user_tenant_grants WHERE user_id=?", String.class, actor.user().id());
            var targetGrants = jdbc.queryForList("SELECT tenant_id FROM user_tenant_grants WHERE user_id=?", String.class, userId);
            if (!actorGrants.containsAll(targetGrants))
                throw new ApiError(403, "TARGET_MANAGEMENT_FORBIDDEN", "受影响用户单位授权超出操作者管理范围");
        } else {
            String tenant = jdbc.queryForObject("SELECT tenant_id FROM app_users WHERE id=?", String.class, userId);
            Long grant = jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id=? AND tenant_id=?", Long.class, actor.user().id(), tenant);
            if (grant == null || grant != 1L)
                throw new ApiError(403, "TARGET_MANAGEMENT_FORBIDDEN", "受影响用户所属单位超出操作者管理范围");
        }
        if (!scopes.covers(actor.user().id(), userId))
            throw new ApiError(403, "TARGET_MANAGEMENT_FORBIDDEN", "受影响用户设备范围超出操作者管理范围");
    }
}
