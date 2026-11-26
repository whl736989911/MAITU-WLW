package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.DeviceAccessScope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity-owned device scope policy. It reads only identity/scope tables plus the read-only place
 * hierarchy and device placement; device existence and tenant ownership remain the device-owning
 * domain's responsibility.
 *
 * <p>PLACE_SUBTREE is evaluated from the stored roots on every read. Descendant places are resolved
 * with one recursive CTE per statement, so devices added later enter the scope and devices moved to a
 * place outside the subtree leave it without rewriting scope rows.
 */
@Service
public class DeviceScopes implements DeviceAccessScope {
    private record StoredScope(String userId, String tenantId, String mode, long revision) { }
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*");
    private static final String ALL = "ALL_TENANT", SET = "DEVICE_SET", PLACE = "PLACE_SUBTREE";
    private static final String PLACE_TREE = "(WITH RECURSIVE __authz_place(id,tenant_id) AS ("
        + "SELECT __authz_root.place_id,__authz_root.tenant_id FROM user_device_scope_places __authz_root WHERE __authz_root.user_id=?"
        + " UNION ALL SELECT __authz_child.id,__authz_child.tenant_id FROM places __authz_child"
        + " JOIN __authz_place __authz_parent ON __authz_child.parent_place_id=__authz_parent.id AND __authz_child.tenant_id=__authz_parent.tenant_id"
        + ") SELECT id,tenant_id FROM __authz_place) __authz_tree";
    private final JdbcTemplate jdbc;

    public DeviceScopes(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Current visible-device predicate. Only trusted source constants may name SQL columns; a request
     * value can never widen the predicate.
     */
    @Override public Filter predicate(String actorId, String tenantColumn, String deviceColumn) {
        String tenant = column(tenantColumn), device = column(deviceColumn);
        return new Filter(visible(actorId, tenant, device), List.of(actorId, actorId));
    }

    @Override public Filter placePredicate(String actorId, String tenantColumn, String placeColumn) {
        requireActor(actorId);
        return placeFilter(actorId, column(tenantColumn), column(placeColumn));
    }

    /** Trusted-expression builder shared by the public predicate and identity's own place coverage check. */
    private static Filter placeFilter(String actorId, String tenantExpression, String placeExpression) {
        String sql = "EXISTS (SELECT 1 FROM effective_user_device_scopes __authz_scope WHERE __authz_scope.user_id=?"
            + " AND __authz_scope.tenant_id=(" + tenantExpression + ") AND (__authz_scope.mode='ALL_TENANT'"
            + " OR (__authz_scope.mode='PLACE_SUBTREE' AND EXISTS (SELECT 1 FROM " + PLACE_TREE
            + " WHERE __authz_tree.tenant_id=__authz_scope.tenant_id AND __authz_tree.id=(" + placeExpression + ")))))";
        return new Filter(sql, List.of(actorId, actorId));
    }

    @Override public Filter allPredicate(String actorId, String tenantColumn) {
        requireActor(actorId);
        String tenant = column(tenantColumn);
        return new Filter("EXISTS (SELECT 1 FROM effective_user_device_scopes __authz_scope WHERE __authz_scope.user_id=?"
            + " AND __authz_scope.tenant_id=(" + tenant + ") AND __authz_scope.mode='ALL_TENANT')", List.of(actorId));
    }

    @Override public boolean all(String actorId, String tenantId) {
        StoredScope scope = stored(actorId, tenantId);
        return scope != null && ALL.equals(scope.mode());
    }

    @Override public boolean contains(String actorId, String tenantId, String deviceId) {
        if (actorId == null || tenantId == null || deviceId == null || deviceId.isBlank()) return false;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM devices __authz_device WHERE __authz_device.id=?"
            + " AND __authz_device.tenant_id=? AND " + visible(actorId, "__authz_device.tenant_id", "__authz_device.id"),
            Long.class, deviceId, tenantId, actorId, actorId);
        return count != null && count == 1L;
    }

    @Override public boolean coversPlace(String actorId, String tenantId, String placeId) {
        if (actorId == null || tenantId == null || placeId == null || placeId.isBlank()) return false;
        requireActor(actorId);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM places __authz_place WHERE __authz_place.id=?"
            + " AND __authz_place.tenant_id=? AND " + placeFilter(actorId, "__authz_place.tenant_id", "__authz_place.id").sql(),
            Long.class, placeId, tenantId, actorId, actorId);
        return count != null && count == 1L;
    }

    /** Batch candidate check: two queries regardless of candidate count. */
    @Override public Set<String> usersWithDevice(List<String> userIds, String tenantId, String deviceId) {
        if (userIds == null || userIds.isEmpty() || tenantId == null || deviceId == null) return Set.of();
        List<String> ids = userIds.stream().filter(id -> id != null && !id.isBlank()).distinct().limit(1000).toList();
        if (ids.isEmpty()) return Set.of();
        Set<String> ancestors = new HashSet<>(jdbc.queryForList("SELECT id FROM (WITH RECURSIVE __authz_anc(id,tenant_id,parent_place_id) AS ("
            + "SELECT p.id,p.tenant_id,p.parent_place_id FROM places p WHERE p.id=(SELECT place_id FROM devices WHERE id=? AND tenant_id=?)"
            + " UNION ALL SELECT x.id,x.tenant_id,x.parent_place_id FROM places x JOIN __authz_anc a ON x.id=a.parent_place_id AND x.tenant_id=a.tenant_id"
            + ") SELECT id FROM __authz_anc) __authz_ancestors", String.class, deviceId, tenantId));
        Set<String> allowed = new LinkedHashSet<>();
        Object[] params = new Object[1 + ids.size()];
        params[0] = tenantId;
        for (int i = 0; i < ids.size(); i++) params[i + 1] = ids.get(i);
        jdbc.query("SELECT DISTINCT s.user_id,s.mode,sd.device_id,r.place_id FROM effective_user_device_scopes s"
            + " LEFT JOIN user_device_scope_devices sd ON sd.user_id=s.user_id AND sd.tenant_id=s.tenant_id"
            + " LEFT JOIN user_device_scope_places r ON r.user_id=s.user_id AND r.tenant_id=s.tenant_id"
            + " WHERE s.tenant_id=? AND s.user_id IN (" + marks(ids.size()) + ")",
            (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, params)
            .forEach(row -> {
                String user = (String) row[0], mode = (String) row[1];
                if (ALL.equals(mode)) allowed.add(user);
                else if (SET.equals(mode) && deviceId.equals(row[2])) allowed.add(user);
                else if (PLACE.equals(mode) && row[3] != null && ancestors.contains((String) row[3])) allowed.add(user);
            });
        return allowed;
    }

    private static String marks(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    /** Current stored scope as the contract DTO; a missing row is a normal unconfigured scope. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccessModels.DeviceScope scope(String userId, String tenantId) {
        StoredScope scope = stored(userId, tenantId);
        if (scope == null) return new AccessModels.DeviceScope(userId, tenantId, false, null, 0, List.of(), List.of());
        List<String> devices = SET.equals(scope.mode())
            ? jdbc.queryForList("SELECT device_id FROM user_device_scope_devices WHERE user_id=? AND tenant_id=? ORDER BY device_id", String.class, userId, tenantId)
            : List.of();
        List<String> places = PLACE.equals(scope.mode())
            ? jdbc.queryForList("SELECT place_id FROM user_device_scope_places WHERE user_id=? AND tenant_id=? ORDER BY place_id", String.class, userId, tenantId)
            : List.of();
        if (PLACE.equals(scope.mode()) && places.isEmpty())
            throw new ApiError(503, "ACCESS_READ_UNCONFIRMED", "位置范围无法确认");
        if (!devices.isEmpty() && !SET.equals(scope.mode()))
            throw new ApiError(503, "ACCESS_READ_UNCONFIRMED", "设备范围无法确认");
        if (!places.isEmpty() && !PLACE.equals(scope.mode()))
            throw new ApiError(503, "ACCESS_READ_UNCONFIRMED", "位置范围无法确认");
        return new AccessModels.DeviceScope(userId, tenantId, true, scope.mode(), scope.revision(), devices, places);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String fingerprint(String actorId, String tenantId) {
        var current = scope(actorId, tenantId);
        if (!current.configured()) return com.fireplatform.shared.Digests.sha256("UNCONFIGURED");
        var canonical = new StringBuilder(current.mode());
        for (String id : current.deviceIds().stream().sorted().toList())
            canonical.append(":d:").append(id.length()).append(':').append(id);
        for (String id : current.placeIds().stream().sorted().toList())
            canonical.append(":p:").append(id.length()).append(':').append(id);
        return com.fireplatform.shared.Digests.sha256(canonical.toString());
    }

    /**
     * True when every device or place root the target can reach is also structurally reachable by the
     * actor in the same tenant. A DEVICE_SET target never upgrades to future places; a PLACE target
     * requires the actor to be ALL_TENANT or to hold a PLACE_SUBTREE covering every target root.
     */
    public boolean covers(String actorId, String targetId) {
        requireActor(actorId);
        for (StoredScope scope : jdbc.query("SELECT user_id,tenant_id,mode,revision FROM effective_user_device_scopes WHERE user_id=?",
            (rs, n) -> row(rs), targetId)) {
            if (ALL.equals(scope.mode())) {
                if (!all(actorId, scope.tenantId())) return false;
            } else if (SET.equals(scope.mode())) {
                Long outside = jdbc.queryForObject("SELECT COUNT(*) FROM user_device_scope_devices __authz_target"
                    + " WHERE __authz_target.user_id=? AND __authz_target.tenant_id=?"
                    + " AND NOT (" + visible(actorId, "__authz_target.tenant_id", "__authz_target.device_id") + ")",
                    Long.class, targetId, scope.tenantId(), actorId, actorId);
                if (outside == null || outside != 0L) return false;
            } else if (PLACE.equals(scope.mode())) {
                List<String> roots = jdbc.queryForList("SELECT place_id FROM user_device_scope_places WHERE user_id=? AND tenant_id=? ORDER BY place_id",
                    String.class, targetId, scope.tenantId());
                if (roots.isEmpty() || !coversPlaces(actorId, scope.tenantId(), roots)) return false;
            } else {
                throw new IllegalStateException("Stored device scope mode is invalid");
            }
        }
        return true;
    }

    /** Structural coverage of place roots: ALL_TENANT, or a PLACE_SUBTREE whose roots are ancestors of each root. */
    public boolean coversPlaces(String actorId, String tenantId, List<String> placeIds) {
        requireActor(actorId);
        if (placeIds == null || placeIds.isEmpty() || tenantId == null) return false;
        StoredScope scope = stored(actorId, tenantId);
        if (scope == null || SET.equals(scope.mode())) return false;
        var requested = new HashSet<>(placeIds);
        var bindings = new ArrayList<Object>(requested.size() + 2);
        String source = "places";
        if (PLACE.equals(scope.mode())) {
            source = PLACE_TREE;
            bindings.add(actorId);
        }
        bindings.add(tenantId);
        bindings.addAll(requested);
        Long covered = jdbc.queryForObject("SELECT COUNT(DISTINCT id) FROM " + source
            + " WHERE tenant_id=? AND id IN (" + marks(requested.size()) + ")", Long.class, bindings.toArray());
        return covered != null && covered == requested.size();
    }

    private String visible(String actorId, String tenantExpression, String deviceExpression) {
        requireActor(actorId);
        return "EXISTS (SELECT 1 FROM effective_user_device_scopes __authz_scope WHERE __authz_scope.user_id=?"
            + " AND __authz_scope.tenant_id=(" + tenantExpression + ") AND ("
            + "__authz_scope.mode='ALL_TENANT'"
            + " OR (__authz_scope.mode='DEVICE_SET' AND EXISTS (SELECT 1 FROM user_device_scope_devices __authz_member"
            + " WHERE __authz_member.user_id=__authz_scope.user_id AND __authz_member.tenant_id=__authz_scope.tenant_id"
            + " AND __authz_member.device_id=(" + deviceExpression + ")))"
            + " OR (__authz_scope.mode='PLACE_SUBTREE' AND EXISTS (SELECT 1 FROM " + PLACE_TREE
            + " WHERE __authz_tree.tenant_id=__authz_scope.tenant_id AND __authz_tree.id=(SELECT __authz_scope_device.place_id"
            + " FROM devices __authz_scope_device WHERE __authz_scope_device.id=(" + deviceExpression + ")"
            + " AND __authz_scope_device.tenant_id=__authz_scope.tenant_id))))"
            + ")";
    }

    private static void requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) throw ApiError.forbidden("缺少明确操作者身份，拒绝设备范围查询");
    }

    private StoredScope stored(String userId, String tenantId) {
        var rows = jdbc.query("SELECT user_id,tenant_id,mode,revision FROM effective_user_device_scopes WHERE user_id=? AND tenant_id=?",
            (rs, n) -> row(rs), userId, tenantId);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalStateException("Duplicate device scope row");
        return rows.getFirst();
    }

    private static StoredScope row(java.sql.ResultSet rs) throws java.sql.SQLException {
        StoredScope scope = new StoredScope(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4));
        if ((!ALL.equals(scope.mode()) && !SET.equals(scope.mode()) && !PLACE.equals(scope.mode()))
            || scope.revision() < 0 || scope.revision() > AccessInputs.MAX_REVISION)
            throw new ApiError(503, "ACCESS_READ_UNCONFIRMED", "设备范围无法确认");
        return scope;
    }

    private static String column(String value) {
        if (value == null || !COLUMN.matcher(value).matches() || value.regionMatches(true, 0, "__authz_", 0, 8))
            throw new IllegalStateException("Device scope columns must use trusted, qualified outer references");
        return value;
    }
}
