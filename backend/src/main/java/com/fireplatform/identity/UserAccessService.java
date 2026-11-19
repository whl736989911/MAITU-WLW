package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.ParameterTargets;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** User-role assignment and explicit per-tenant device scope administration. */
@Service
public class UserAccessService {
    private static final String ALL = "ALL_TENANT", SET = "DEVICE_SET", PLACE = "PLACE_SUBTREE";
    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final SecretCommandStore commands;
    private final AuthService auth;
    private final IdentityQueries targets;
    private final AccessQueries queries;
    private final ManagementBounds bounds;
    private final DeviceScopes scopes;
    private final ParameterTargets deviceTargets;
    private final AuditService audit;

    public UserAccessService(JdbcTemplate jdbc, ManagementGuard guard, SecretCommandStore commands, AuthService auth,
                             IdentityQueries targets, AccessQueries queries, ManagementBounds bounds, DeviceScopes scopes, ParameterTargets deviceTargets, AuditService audit) {
        this.jdbc = jdbc; this.guard = guard; this.commands = commands; this.auth = auth;
        this.targets = targets; this.queries = queries; this.bounds = bounds; this.scopes = scopes; this.deviceTargets = deviceTargets; this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccessModels.UserAccess readAccess(String userType, String tenant, String id) {
        IdentityQueries.ManagedUser target = targets.target(id, userType, tenant, false);
        return queries.access(target.id());
    }

    /** Target scope reads are bounded: never surface device IDs the caller cannot itself reach. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccessModels.DeviceScope readScope(AuthService.Identity actor, String userType, String tenant, String id) {
        IdentityQueries.ManagedUser target = targets.target(id, userType, tenant, false);
        if (userType.equals("PLATFORM")) grantRequired(target.id(), tenant);
        if (!scopes.covers(actor.user().id(), target.id()))
            throw new ApiError(403, "TARGET_MANAGEMENT_FORBIDDEN", "目标设备范围超出操作者管理范围");
        return scopes.scope(target.id(), tenant);
    }

    @Transactional
    public AccessModels.UserAccess assignRoles(AuthService.Identity actor, String userType, String tenant, String id,
                                               String key, AccessInputs.AssignRoles in, String requestId) {
        guard.begin(actor, userType.equals("PLATFORM") ? "platform-users:assign-roles" : "users:assign-roles",
            tenant, userType.equals("PLATFORM") ? "management" : null);
        IdentityQueries.ManagedUser target = targets.target(id, userType, tenant, true);
        bounds.protect(actor, target);
        bounds.check(actor, target);
        List<String> roleIds = in.roleIds().stream().distinct().sorted().toList();
        String reason = AccessInputs.reason(in.reason());
        String hash = commands.hashBusiness(key, List.of(userType, tenant == null ? "" : tenant, id, roleIds, in.expectedRevision(), reason));
        var replay = commands.replay(actor.user().id(), "account-roles-assign", key, hash, null, AccessModels.UserAccess.class);
        if (replay != null) return replay;
        var actorPermissions = auth.permissions(actor.user().id());
        for (String roleId : roleIds) {
            AccessModels.Role role = queries.role(roleId);
            if (role.deleted()) throw ApiError.conflict("ROLE_DELETED", "角色模板已删除，不能分配");
            if (!role.userType().equals(userType)) throw ApiError.bad("角色类型与目标账号类型不一致");
            if (!actorPermissions.containsAll(role.permissions()))
                throw new ApiError(403, "PERMISSION_DELEGATION_FORBIDDEN", "不允许委派指定权限");
        }
        // Role rows after the target user row, matching template edit/delete lock order.
        if (!roleIds.isEmpty())
            jdbc.queryForList("SELECT id FROM access_roles WHERE id IN (" + marks(roleIds.size()) + ") ORDER BY id FOR UPDATE", String.class, roleIds.toArray());
        if (target.revision() != in.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT", "账号已更新，请刷新");
        jdbc.update("DELETE FROM access_user_roles WHERE user_id=?", id);
        for (String roleId : roleIds) jdbc.update("INSERT INTO access_user_roles(user_id,role_id,user_type) VALUES (?,?,?)", id, roleId, userType);
        touch(id);
        AccessModels.UserAccess result = queries.access(id);
        audit.recordReason(actor.user().id(), tenant, "USER-ROLES-ASSIGN", id, "SUCCESS", requestId, reason);
        commands.save(actor.user().id(), "account-roles-assign", key, hash, null, result);
        return result;
    }

    @Transactional
    public AccessModels.DeviceScope assignScope(AuthService.Identity actor, String userType, String tenant, String id,
                                                String key, AccessInputs.AssignScope in, String requestId) {
        guard.begin(actor, userType.equals("PLATFORM") ? "platform-users:assign-scope" : "users:assign-scope",
            tenant, userType.equals("PLATFORM") ? "management" : null);
        IdentityQueries.ManagedUser target = targets.target(id, userType, tenant, true);
        bounds.protect(actor, target);
        bounds.check(actor, target);
        if (userType.equals("PLATFORM")) grantRequired(target.id(), tenant);
        String mode = in.mode();
        List<String> devices = in.deviceIds().stream().distinct().sorted().toList();
        List<String> places = in.placeIds().stream().distinct().sorted().toList();
        String reason = AccessInputs.reason(in.reason());
        String hash = commands.hashBusiness(key, List.of(userType, tenant, id, mode, devices, places, in.expectedRevision(), reason));
        var replay = commands.replay(actor.user().id(), "account-scope-assign", key, hash, null, AccessModels.DeviceScope.class);
        if (replay != null) return replay;
        if (mode.equals(ALL)) {
            if (!devices.isEmpty()) throw ApiError.bad("ALL_TENANT必须使用空device_ids");
            if (!places.isEmpty()) throw ApiError.bad("ALL_TENANT必须使用空place_ids");
            scopes.requireAll(actor.user().id(), tenant);
        } else if (mode.equals(SET)) {
            if (!places.isEmpty()) throw ApiError.bad("DEVICE_SET必须使用空place_ids");
            // Device-owning domain validates real tenant + existence + actor scope; missing and out-of-scope are one 404.
            for (String device : devices) deviceTargets.require(actor.user().id(), tenant, device, false);
        } else if (mode.equals(PLACE)) {
            if (!devices.isEmpty()) throw ApiError.bad("PLACE_SUBTREE必须使用空device_ids");
            if (places.isEmpty()) throw ApiError.bad("PLACE_SUBTREE必须指定至少一个位置根");
            for (String place : places) {
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL", Long.class, place, tenant);
                if (count == null || count != 1L) throw ApiError.missing();
            }
            // A DEVICE_SET, even one that currently covers the same devices, never becomes future place authority.
            if (!scopes.coversPlaces(actor.user().id(), tenant, places))
                throw new ApiError(403, "TARGET_MANAGEMENT_FORBIDDEN", "位置范围超出操作者管理范围");
        } else {
            throw ApiError.bad("设备范围模式无效");
        }
        var locked = jdbc.queryForList("SELECT revision FROM user_device_scopes WHERE user_id=? AND tenant_id=? FOR UPDATE", Long.class, id, tenant);
        if (locked.size() > 1) throw new IllegalStateException("Duplicate device scope row");
        long current = locked.isEmpty() ? 0L : locked.getFirst();
        if (current != in.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT", "设备范围已更新，请刷新");
        RoleAdminService.requireNextRevision(current);
        if (locked.isEmpty()) jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES (?,?,?,1)", id, tenant, mode);
        else jdbc.update("UPDATE user_device_scopes SET mode=?,revision=revision+1 WHERE user_id=? AND tenant_id=?", mode, id, tenant);
        jdbc.update("DELETE FROM user_device_scope_devices WHERE user_id=? AND tenant_id=?", id, tenant);
        jdbc.update("DELETE FROM user_device_scope_places WHERE user_id=? AND tenant_id=?", id, tenant);
        if (mode.equals(SET)) for (String device : devices)
            jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES (?,?,?)", id, tenant, device);
        if (mode.equals(PLACE)) for (String place : places)
            jdbc.update("INSERT INTO user_device_scope_places(user_id,tenant_id,place_id) VALUES (?,?,?)", id, tenant, place);
        touch(id);
        AccessModels.DeviceScope result = scopes.scope(id, tenant);
        audit.recordReason(actor.user().id(), tenant, "USER-SCOPE-ASSIGN", id, "SUCCESS", requestId, reason);
        commands.save(actor.user().id(), "account-scope-assign", key, hash, null, result);
        return result;
    }

    private void grantRequired(String userId, String tenant) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id=? AND tenant_id=?", Long.class, userId, tenant);
        if (count == null || count != 1L) throw ApiError.missing();
    }

    /** Role/scope changes atomically bump the target revision and revoke every target session. */
    private void touch(String userId) {
        RoleAdminService.requireNextRevision(jdbc.queryForObject("SELECT revision FROM app_users WHERE id=?", Long.class, userId));
        jdbc.update("UPDATE app_users SET revision=revision+1 WHERE id=?", userId);
        jdbc.update("UPDATE sessions SET revoked=TRUE WHERE user_id=?", userId);
    }

    private static String marks(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) { if (i > 0) builder.append(','); builder.append('?'); }
        return builder.toString();
    }
}
