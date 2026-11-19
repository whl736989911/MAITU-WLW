package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.PageSizePolicy;
import com.fireplatform.shared.Pagination;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read side for role templates, user authorization and device scopes. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AccessQueries {
    private record RoleRow(String id, String code, String name, String userType, boolean enabled, boolean deleted, long revision) { }
    private final JdbcTemplate jdbc;
    private final PageSizePolicy sizes;

    public AccessQueries(JdbcTemplate jdbc, PageSizePolicy sizes) { this.jdbc = jdbc; this.sizes = sizes; }

    public Page<AccessModels.Role> roles(String userType, int page, Integer size,String keyword) {
        Pagination p = Pagination.of(page, size, sizes.defaultPageSize()); String like=IdentityQueries.keyword(keyword);
        String where="user_type=? AND deleted=FALSE AND (LOWER(name) LIKE ? ESCAPE '!' OR LOWER(code) LIKE ? ESCAPE '!')";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM access_roles WHERE "+where, Long.class,userType,like,like);
        List<String> ids = jdbc.queryForList("SELECT id FROM access_roles WHERE "+where+" ORDER BY code,id LIMIT ? OFFSET ?",
            String.class, userType,like,like,p.size(), p.offset());
        if (total == null || total < 0) throw new ApiError(503, "ACCESS_READ_UNCONFIRMED", "授权读取无法确认");
        return new Page<>(ids.stream().map(this::role).toList(), total, p.page(), p.size());
    }

    /** Includes soft-deleted templates so a delete receipt and historic references stay readable. */
    public AccessModels.Role role(String id) {
        var rows = jdbc.query("SELECT id,code,name,user_type,enabled,deleted,revision FROM access_roles WHERE id=?",
            (rs, n) -> new RoleRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6), rs.getLong(7)), id);
        if (rows.isEmpty()) throw ApiError.missing();
        RoleRow row = rows.getFirst();
        if (row.revision() < 0 || row.revision() > AccessInputs.MAX_REVISION)
            throw new ApiError(503, "ACCESS_READ_UNCONFIRMED", "角色版本无法确认");
        List<String> permissions = jdbc.queryForList("SELECT permission FROM access_role_permissions WHERE role_id=? ORDER BY permission", String.class, id);
        return new AccessModels.Role(row.id(), row.code(), row.name(), row.userType(), row.enabled(), row.deleted(), row.revision(), permissions);
    }

    public AccessModels.UserAccess access(String userId) {
        String userType = jdbc.queryForObject("SELECT user_type FROM app_users WHERE id=?", String.class, userId);
        Long revision = jdbc.queryForObject("SELECT revision FROM app_users WHERE id=?", Long.class, userId);
        if (revision == null || revision < 0 || revision > AccessInputs.MAX_REVISION)
            throw new ApiError(503, "ACCESS_READ_UNCONFIRMED", "授权版本无法确认");
        List<String> direct = jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id=? ORDER BY permission", String.class, userId);
        List<String> effective = jdbc.queryForList("SELECT permission FROM effective_user_permissions WHERE user_id=? ORDER BY permission", String.class, userId);
        List<String> roleIds = jdbc.queryForList("SELECT ur.role_id FROM access_user_roles ur JOIN access_roles r ON r.id=ur.role_id"
            + " WHERE ur.user_id=? AND r.deleted=FALSE ORDER BY r.code,r.id", String.class, userId);
        List<AccessModels.Role> assigned = new ArrayList<>();
        for (String roleId : roleIds) assigned.add(role(roleId));
        return new AccessModels.UserAccess(userId, userType, revision, direct, effective, List.copyOf(assigned));
    }

    /**
     * Effective permissions a user would hold with one role replaced by the given definition.
     * Used to prove an edited assigned template stays inside the operator's management bounds.
     */
    public List<String> effectiveIf(String userId, String editedRoleId, List<String> editedPermissions, boolean editedEnabled) {
        TreeSet<String> permissions = new TreeSet<>(jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id=?", String.class, userId));
        var roles = jdbc.query("SELECT r.id,r.enabled FROM access_user_roles ur JOIN access_roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.deleted=FALSE",
            (rs, n) -> new String[]{rs.getString(1), Boolean.toString(rs.getBoolean(2))}, userId);
        for (String[] role : roles) {
            if (role[0].equals(editedRoleId)) {
                if (editedEnabled) permissions.addAll(editedPermissions);
            } else if (Boolean.parseBoolean(role[1])) {
                permissions.addAll(jdbc.queryForList("SELECT permission FROM access_role_permissions WHERE role_id=? ORDER BY permission", String.class, role[0]));
            }
        }
        return List.copyOf(permissions);
    }
}
