package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform-global role templates. Customer accounts may read CUSTOMER templates but never maintain any. */
@Service
public class RoleAdminService {
    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final SecretCommandStore commands;
    private final PermissionCatalog catalog;
    private final AuthService auth;
    private final AccessQueries queries;
    private final ManagementBounds bounds;
    private final AuditService audit;

    public RoleAdminService(JdbcTemplate jdbc, ManagementGuard guard, SecretCommandStore commands, PermissionCatalog catalog,
                            AuthService auth, AccessQueries queries, ManagementBounds bounds, AuditService audit) {
        this.jdbc = jdbc; this.guard = guard; this.commands = commands; this.catalog = catalog;
        this.auth = auth; this.queries = queries; this.bounds = bounds; this.audit = audit;
    }

    @Transactional
    public AccessModels.Role create(AuthService.Identity actor, String key, AccessInputs.CreateRole in, String requestId) {
        guard.begin(actor, "roles:write", null, "management");
        List<String> allowed = catalog.validate(in.userType(), in.permissions(), auth.permissions(actor.user().id()));
        String reason = AccessInputs.reason(in.reason());
        String hash = commands.hashBusiness(key, List.of(in.code(), in.name(), in.userType(), in.enabled(), allowed, reason));
        var replay = commands.replay(actor.user().id(), "role-create", key, hash, null, AccessModels.Role.class);
        if (replay != null) return replay;
        Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM access_roles WHERE user_type=? AND code=?", Long.class, in.userType(), in.code());
        if (existing != null && existing > 0) throw ApiError.conflict("ROLE_CODE_CONFLICT", "角色编码已存在（含已删除模板，编码不可复用）");
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO access_roles(id,code,name,user_type,enabled,deleted,revision) VALUES (?,?,?,?,?,FALSE,0)",
            id, in.code(), in.name(), in.userType(), in.enabled());
        permissions(id, allowed);
        AccessModels.Role result = queries.role(id);
        audit.recordReason(actor.user().id(), null, "ROLE-CREATE", id, "SUCCESS", requestId, reason);
        commands.save(actor.user().id(), "role-create", key, hash, null, result);
        return result;
    }

    @Transactional
    public AccessModels.Role update(AuthService.Identity actor, String id, String key, AccessInputs.UpdateRole in, String requestId) {
        guard.begin(actor, "roles:write", null, "management");
        AccessModels.Role current = queries.role(id);
        List<String> allowed = catalog.validate(current.userType(), in.permissions(), auth.permissions(actor.user().id()));
        String reason = AccessInputs.reason(in.reason());
        String hash = commands.hashBusiness(key, List.of(id, in.name(), in.enabled(), allowed, in.expectedRevision(), reason));
        var replay = commands.replay(actor.user().id(), "role-update", key, hash, null, AccessModels.Role.class);
        if (replay != null) return replay;
        if (current.deleted()) throw ApiError.conflict("ROLE_DELETED", "角色模板已删除");
        if (assigned(id, actor.user().id())) throw ApiError.conflict("SELF_MANAGEMENT_FORBIDDEN", "不能修改分配给自己的角色模板");
        // Affected users are locked in id order BEFORE the role row, matching every other identity write order.
        List<String> affected = jdbc.queryForList("SELECT user_id FROM access_user_roles WHERE role_id=? ORDER BY user_id", String.class, id);
        lockUsers(affected);
        var locked = jdbc.query("SELECT revision,deleted FROM access_roles WHERE id=? FOR UPDATE",
            (rs, n) -> new Object[]{rs.getLong(1), rs.getBoolean(2)}, id);
        if (locked.isEmpty()) throw ApiError.missing();
        if (Boolean.TRUE.equals(locked.getFirst()[1])) throw ApiError.conflict("ROLE_DELETED", "角色模板已删除");
        long revision = (Long) locked.getFirst()[0];
        if (revision != in.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT", "角色模板已更新，请刷新");
        requireNextRevision(revision);
        for (String userId : affected) {
            bounds.checkRoleImpact(actor, userId, queries.effectiveIf(userId, id, current.permissions(), current.enabled()));
            bounds.checkRoleImpact(actor, userId, queries.effectiveIf(userId, id, allowed, in.enabled()));
        }
        int changed = jdbc.update("UPDATE access_roles SET name=?,enabled=?,revision=revision+1 WHERE id=? AND revision=?",
            in.name(), in.enabled(), id, revision);
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "角色模板已更新，请刷新");
        jdbc.update("DELETE FROM access_role_permissions WHERE role_id=?", id);
        permissions(id, allowed);
        touch(affected);
        AccessModels.Role result = queries.role(id);
        audit.recordReason(actor.user().id(), null, "ROLE-UPDATE", id, "SUCCESS", requestId, reason);
        commands.save(actor.user().id(), "role-update", key, hash, null, result);
        return result;
    }

    @Transactional
    public AccessModels.Role delete(AuthService.Identity actor, String id, String key, AccessInputs.DeleteRole in, String requestId) {
        guard.begin(actor, "roles:write", null, "management");
        String reason = AccessInputs.reason(in.reason());
        String hash = commands.hashBusiness(key, List.of(id, in.expectedRevision(), reason));
        var replay = commands.replay(actor.user().id(), "role-delete", key, hash, null, AccessModels.Role.class);
        if (replay != null) return replay;
        var locked = jdbc.query("SELECT revision,deleted FROM access_roles WHERE id=? FOR UPDATE",
            (rs, n) -> new Object[]{rs.getLong(1), rs.getBoolean(2)}, id);
        if (locked.isEmpty()) throw ApiError.missing();
        if (Boolean.TRUE.equals(locked.getFirst()[1])) throw ApiError.conflict("ROLE_DELETED", "角色模板已删除");
        long revision = (Long) locked.getFirst()[0];
        if (revision != in.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT", "角色模板已更新，请刷新");
        Long assigned = jdbc.queryForObject("SELECT COUNT(*) FROM access_user_roles WHERE role_id=?", Long.class, id);
        if (assigned != null && assigned > 0) throw ApiError.conflict("ROLE_ASSIGNED", "角色仍分配给用户，不能删除");
        requireNextRevision(revision);
        int changed = jdbc.update("UPDATE access_roles SET enabled=FALSE,deleted=TRUE,revision=revision+1 WHERE id=? AND revision=?", id, revision);
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "角色模板已更新，请刷新");
        AccessModels.Role result = queries.role(id);
        audit.recordReason(actor.user().id(), null, "ROLE-DELETE", id, "SUCCESS", requestId, reason);
        commands.save(actor.user().id(), "role-delete", key, hash, null, result);
        return result;
    }

    private boolean assigned(String roleId, String userId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM access_user_roles WHERE role_id=? AND user_id=?", Long.class, roleId, userId);
        return count != null && count > 0;
    }

    private void permissions(String roleId, List<String> permissions) {
        for (String permission : permissions) jdbc.update("INSERT INTO access_role_permissions(role_id,permission) VALUES (?,?)", roleId, permission);
    }

    private void lockUsers(List<String> users) {
        if (users.isEmpty()) return;
        jdbc.queryForList("SELECT id FROM app_users WHERE id IN (" + marks(users.size()) + ") ORDER BY id FOR UPDATE", String.class, users.toArray());
    }

    /** Any template/permission change invalidates the affected users' authority generation and sessions. */
    private void touch(List<String> users) {
        if (users.isEmpty()) return;
        for (long revision : jdbc.queryForList("SELECT revision FROM app_users WHERE id IN (" + marks(users.size()) + ")", Long.class, users.toArray()))
            requireNextRevision(revision);
        String marks = marks(users.size());
        jdbc.update("UPDATE app_users SET revision=revision+1 WHERE id IN (" + marks + ")", users.toArray());
        jdbc.update("UPDATE sessions SET revoked=TRUE WHERE user_id IN (" + marks + ")", users.toArray());
    }

    static void requireNextRevision(long revision) {
        if (revision < 0 || revision >= AccessInputs.MAX_REVISION)
            throw ApiError.conflict("ACCESS_REVISION_EXHAUSTED", "授权版本已耗尽");
    }

    private static String marks(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) { if (i > 0) builder.append(','); builder.append('?'); }
        return builder.toString();
    }
}
