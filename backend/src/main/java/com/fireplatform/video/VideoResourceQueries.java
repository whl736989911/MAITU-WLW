package com.fireplatform.video;

import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.LiteralLike;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.PageSizePolicy;
import com.fireplatform.shared.Pagination;
import com.fireplatform.shared.ScopeFilter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scoped video reads. Customers and narrowed scopes only see resources associated with devices they may
 * read; a resource with no visible association is indistinguishable from a missing one.
 */
@Service
public class VideoResourceQueries {
    private final JdbcTemplate jdbc;
    private final DeviceAccessScope scopes;
    private final ScopeFilter tree;
    private final PageSizePolicy sizes;
    private final VideoPolicy policy;
    private final VideoSecretCipher cipher;
    private final FfmpegExtractor ffmpeg;

    public VideoResourceQueries(JdbcTemplate jdbc, DeviceAccessScope scopes, ScopeFilter tree, PageSizePolicy sizes,
                                VideoPolicy policy, VideoSecretCipher cipher, FfmpegExtractor ffmpeg) {
        this.jdbc = jdbc; this.scopes = scopes; this.tree = tree; this.sizes = sizes; this.policy = policy;
        this.cipher = cipher; this.ffmpeg = ffmpeg;
    }

    @Transactional(readOnly = true)
    public Page<VideoModels.Resource> page(String actorId, String tenant, ScopeFilter.Selection selection,
                                           String keyword, boolean includeArchived, int page, Integer size,
                                           boolean requireAssociation) {
        var pagination = Pagination.of(page, size, sizes.defaultPageSize());
        String term = VideoInputs.keyword(keyword);
        var visibleCd = visibleDevice(actorId, tenant, selection, "cd");
        var visibleVd = visibleDevice(actorId, tenant, selection, "vd");
        boolean restricted = requireAssociation || restricted(actorId, tenant, selection);
        String visibility = restricted
            ? " AND EXISTS (SELECT 1 FROM video_associations va JOIN devices vd ON vd.id=va.device_id AND vd.tenant_id=va.tenant_id"
                + " WHERE va.tenant_id=r.tenant_id AND va.video_resource_id=r.id AND va.state='ACTIVE' AND " + visibleVd.sql() + ")"
            : "";
        String archive = includeArchived ? "" : " AND r.archived_at IS NULL";
        String filter = "";
        if (!term.isEmpty()) {
            filter = " AND (LOWER(r.name) LIKE LOWER(?) ESCAPE '!' OR LOWER(r.endpoint_host) LIKE LOWER(?) ESCAPE '!')";
        }
        var countArgs = new ArrayList<Object>();
        countArgs.add(tenant);
        if (restricted) countArgs.addAll(visibleVd.bindings());
        if (!term.isEmpty()) { countArgs.add(LiteralLike.contains(term)); countArgs.add(LiteralLike.contains(term)); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM video_resources r WHERE r.tenant_id=?"
            + archive + visibility + filter, Long.class, countArgs.toArray());

        var pageArgs = new ArrayList<Object>(visibleCd.bindings());
        pageArgs.add(tenant);
        if (restricted) pageArgs.addAll(visibleVd.bindings());
        if (!term.isEmpty()) { pageArgs.add(LiteralLike.contains(term)); pageArgs.add(LiteralLike.contains(term)); }
        pageArgs.add(pagination.size());
        pageArgs.add(pagination.offset());
        String sql = "SELECT r.*,(SELECT COUNT(*) FROM video_associations ac JOIN devices cd ON cd.id=ac.device_id AND cd.tenant_id=ac.tenant_id"
            + " WHERE ac.tenant_id=r.tenant_id AND ac.video_resource_id=r.id AND ac.state='ACTIVE' AND " + visibleCd.sql()
            + ") AS visible_associations FROM video_resources r WHERE r.tenant_id=?"
            + archive + visibility + filter + " ORDER BY r.name,r.id LIMIT ? OFFSET ?";
        var items = jdbc.query(sql, (rs, n) -> resource(rs), pageArgs.toArray());
        return new Page<>(items, total, pagination.page(), pagination.size());
    }

    @Transactional(readOnly = true)
    public VideoModels.ResourceDetail detail(String actorId, String tenant, ScopeFilter.Selection selection, String id,
                                             boolean requireAssociation) {
        var resource = visibleResource(actorId, tenant, selection, id, requireAssociation);
        return new VideoModels.ResourceDetail(resource, associations(actorId, tenant, selection, id, true));
    }

    @Transactional(readOnly = true)
    public List<VideoModels.Association> associations(String actorId, String tenant, ScopeFilter.Selection selection,
                                                      String resourceId, boolean activeOnly) {
        var visible = visibleDevice(actorId, tenant, selection, "d");
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.add(resourceId);
        args.addAll(visible.bindings());
        String state = activeOnly ? " AND a.state='ACTIVE'" : "";
        return jdbc.query("SELECT a.id,a.tenant_id,a.video_resource_id,a.device_id,d.name AS device_name,d.device_eui,"
                + "p.name AS place_name,d.archived_at,a.state,a.revision,a.created_at,a.revoked_at,a.revoke_reason"
                + " FROM video_associations a JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id"
                + " LEFT JOIN places p ON p.id=d.place_id AND p.tenant_id=d.tenant_id"
                + " WHERE a.tenant_id=? AND a.video_resource_id=?" + state + " AND " + visible.sql()
                + " ORDER BY d.name,a.id",
            (rs, n) -> new VideoModels.Association(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("video_resource_id"), rs.getString("device_id"), rs.getString("device_name"),
                rs.getString("device_eui"), rs.getString("place_name"),
                rs.getObject("archived_at", OffsetDateTime.class) == null ? "ACTIVE" : "ARCHIVED",
                rs.getString("state"), rs.getLong("revision"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class), rs.getString("revoke_reason")),
            args.toArray());
    }

    /** Linkage-time projection: real associated videos and truthful capability, no media fetch. */
    @Transactional(readOnly = true)
    public List<VideoModels.Resource> associatedForDevice(String tenant, String deviceId) {
        return jdbc.query("SELECT r.*,0 AS visible_associations FROM video_resources r"
                + " JOIN video_associations a ON a.video_resource_id=r.id AND a.tenant_id=r.tenant_id"
                + " WHERE r.tenant_id=? AND a.device_id=? AND a.state='ACTIVE'"
                + " ORDER BY r.name,r.id",
            (rs, n) -> resource(rs), tenant, deviceId);
    }

    public VideoModels.Resource visibleResource(String actorId, String tenant, ScopeFilter.Selection selection, String id,
                                                boolean requireAssociation) {
        var visibleCd = visibleDevice(actorId, tenant, selection, "cd");
        var visibleVd = visibleDevice(actorId, tenant, selection, "vd");
        boolean restricted = requireAssociation || restricted(actorId, tenant, selection);
        var args = new ArrayList<Object>();
        args.addAll(visibleCd.bindings());
        args.add(tenant);
        args.add(id);
        if (restricted) args.addAll(visibleVd.bindings());
        var rows = jdbc.query("SELECT r.*,(SELECT COUNT(*) FROM video_associations ac JOIN devices cd ON cd.id=ac.device_id AND cd.tenant_id=ac.tenant_id"
                + " WHERE ac.tenant_id=r.tenant_id AND ac.video_resource_id=r.id AND ac.state='ACTIVE' AND " + visibleCd.sql()
                + ") AS visible_associations FROM video_resources r WHERE r.tenant_id=? AND r.id=?"
                + (requireAssociation ? " AND r.archived_at IS NULL" : "")
                + (restricted ? " AND EXISTS (SELECT 1 FROM video_associations va JOIN devices vd ON vd.id=va.device_id AND vd.tenant_id=va.tenant_id"
                    + " WHERE va.tenant_id=r.tenant_id AND va.video_resource_id=r.id AND va.state='ACTIVE' AND " + visibleVd.sql() + ")" : ""),
            (rs, n) -> resource(rs), args.toArray());
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    /** Write-result projection for an actor who already passed a write check on this resource. */
    public VideoModels.Resource byId(String tenant, String id) {
        var rows = jdbc.query("SELECT r.*,0 AS visible_associations FROM video_resources r WHERE r.tenant_id=? AND r.id=?",
            (rs, n) -> resource(rs), tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    /** Internal write/authorization lookup; callers must still enforce permissions and device scope. */
    public RawResource raw(String tenant, String id) {
        var rows = jdbc.query("SELECT * FROM video_resources WHERE tenant_id=? AND id=?", (rs, n) -> new RawResource(
            rs.getString("id"), rs.getString("tenant_id"), rs.getString("name"), rs.getString("resource_kind"),
            rs.getString("protocol"), rs.getString("endpoint_cipher"), rs.getString("endpoint_nonce"),
            rs.getString("endpoint_host"), rs.getString("credentials_cipher"), rs.getString("credentials_nonce"),
            rs.getString("gateway_cipher"), rs.getString("gateway_nonce"), rs.getString("gateway_host"),
            rs.getBoolean("enabled"), rs.getObject("archived_at", OffsetDateTime.class), rs.getLong("revision")),
            tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    public record RawResource(String id, String tenantId, String name, String resourceKind, String protocol,
                              String endpointCipher, String endpointNonce, String endpointHost,
                              String credentialsCipher, String credentialsNonce,
                              String gatewayCipher, String gatewayNonce, String gatewayHost,
                              boolean enabled, OffsetDateTime archivedAt, long revision) {
        public boolean endpointConfigured() { return endpointCipher != null && endpointNonce != null; }
        public boolean credentialsConfigured() { return credentialsCipher != null && credentialsNonce != null; }
    }

    public ScopeFilter.Clause visibleDevice(String actorId, String tenant, ScopeFilter.Selection selection, String alias) {
        var access = scopes.predicate(actorId, alias + ".tenant_id", alias + ".id");
        var treeClause = tree.devices(actorId, alias, tenant, selection);
        var args = new ArrayList<Object>();
        args.addAll(access.bindings());
        args.addAll(treeClause.bindings());
        return new ScopeFilter.Clause("(" + access.sql() + ") AND " + treeClause.sql(), List.copyOf(args));
    }

    public boolean restricted(String actorId, String tenant, ScopeFilter.Selection selection) {
        return !selection.tenantIds().isEmpty() || !selection.placeIds().isEmpty() || selection.empty()
            || !scopes.all(actorId, tenant);
    }

    private VideoModels.Resource resource(java.sql.ResultSet rs) throws java.sql.SQLException {
        String protocol = rs.getString("protocol");
        boolean enabled = rs.getBoolean("enabled");
        OffsetDateTime archived = rs.getObject("archived_at", OffsetDateTime.class);
        boolean endpoint = rs.getString("endpoint_cipher") != null;
        boolean keyReady = cipher.ready();
        boolean gateway = rs.getString("gateway_cipher") != null;
        var capability = VideoModels.capability(protocol, enabled, archived, endpoint && keyReady,
            policy.allowlistConfigured(), gateway && keyReady, ffmpeg.available());
        if ((endpoint || gateway) && !keyReady)
            capability = new VideoModels.Capability("ENCRYPTION_UNAVAILABLE", "ENCRYPTION_UNAVAILABLE", false,
                "视频端点已保存但 APP_VIDEO_KEY 不可用，无法解密；未生成替代内容");
        return new VideoModels.Resource(rs.getString("id"), rs.getString("tenant_id"), rs.getString("name"),
            rs.getString("resource_kind"), protocol, enabled, endpoint, rs.getString("endpoint_host"),
            rs.getString("credentials_cipher") != null, gateway, rs.getString("gateway_host"),
            archived, rs.getLong("revision"),
            rs.getLong("visible_associations"), capability.playback(), capability.snapshot(),
            capability.media_ready(), capability.note(), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));
    }
}
