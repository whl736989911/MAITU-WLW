package com.fireplatform.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Video metadata commands. Endpoints and credentials are encrypted write-only values; enabling requires a
 * configured endpoint, and RTSP is recorded honestly as not playable without a gateway adapter.
 */
@Service
public class VideoResourceCommands {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ManagementGuard guard;
    private final TenantFence tenants;
    private final DeviceAccessScope scopes;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final DatabaseTime time;
    private final VideoPolicy policy;
    private final VideoSecretCipher cipher;
    private final VideoUpstreamClient upstream;
    private final VideoResourceQueries queries;

    public VideoResourceCommands(JdbcTemplate jdbc, ObjectMapper json, ManagementGuard guard, TenantFence tenants,
                                 DeviceAccessScope scopes, IdempotencyStore idem, AuditService audit,
                                 DatabaseTime time, VideoPolicy policy, VideoSecretCipher cipher,
                                 VideoUpstreamClient upstream, VideoResourceQueries queries) {
        this.jdbc = jdbc; this.json = json; this.guard = guard; this.tenants = tenants; this.scopes = scopes;
        this.idem = idem; this.audit = audit; this.time = time; this.policy = policy; this.cipher = cipher;
        this.upstream = upstream; this.queries = queries;
    }

    @Transactional
    public VideoModels.Resource create(AuthService.Identity actor, String tenant, String key,
                                       VideoInputs.CreateResource input, String requestId) {
        guard.begin(actor, "video:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, input.name(), input.resourceKind(), input.protocol(), input.reason()));
        var replay = idem.replay(actor.user().id(), "video-resource-create", key, hash, VideoModels.Resource.class);
        if (replay != null) return replay;
        String id = UUID.randomUUID().toString();
        var now = time.now();
        try {
            jdbc.update("INSERT INTO video_resources(id,tenant_id,name,resource_kind,protocol,enabled,revision,"
                    + "created_by,created_at,updated_by,updated_at) VALUES(?,?,?,?,?,FALSE,0,?,?,?,?)",
                id, tenant, input.name(), input.resourceKind(), input.protocol(), actor.user().id(), now,
                actor.user().id(), now);
        } catch (DuplicateKeyException race) {
            throw ApiError.conflict("VIDEO_NAME_EXISTS", "该单位已存在同名视频资源");
        }
        audit.recordReason(actor.user().id(), tenant, "VIDEO_RESOURCE_CREATED", id, "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "video-resource-create", key, hash, result);
        return result;
    }

    @Transactional
    public VideoModels.Resource update(AuthService.Identity actor, String tenant, String id, String key,
                                       VideoInputs.UpdateResource input, String requestId) {
        guard.begin(actor, "video:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.name(),
            input.resourceKind(), input.protocol(), input.reason()));
        var replay = idem.replay(actor.user().id(), "video-resource-update", key, hash, VideoModels.Resource.class);
        if (replay != null) return replay;
        var current = locked(tenant, id);
        revision(input.expectedRevision(), current.revision());
        if (!current.protocol().equals(input.protocol()) && current.endpointCipher() != null)
            throw ApiError.conflict("PROTOCOL_CHANGE_REQUIRES_ENDPOINT_RESET",
                "修改协议前必须先清除现有端点与凭据，避免把旧密钥用于新协议");
        try {
            if (jdbc.update("UPDATE video_resources SET name=?,resource_kind=?,protocol=?,revision=revision+1,"
                    + "updated_by=?,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",
                input.name(), input.resourceKind(), input.protocol(), actor.user().id(), time.now(), id, tenant,
                input.expectedRevision()) != 1)
                throw ApiError.conflict("REVISION_CONFLICT", "视频资源已变化，请刷新");
        } catch (DuplicateKeyException race) {
            throw ApiError.conflict("VIDEO_NAME_EXISTS", "该单位已存在同名视频资源");
        }
        audit.recordReason(actor.user().id(), tenant, "VIDEO_RESOURCE_UPDATED", id, "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "video-resource-update", key, hash, result);
        return result;
    }

    @Transactional
    public VideoModels.Resource saveEndpoint(AuthService.Identity actor, String tenant, String id, String key,
                                             VideoInputs.Endpoint input, String requestId) {
        guard.begin(actor, "video:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.endpointUrl(), input.username() != null,
            input.reason()));
        var replay = idem.replay(actor.user().id(), "video-endpoint-save", key, hash, VideoModels.Resource.class);
        if (replay != null) return replay;
        var current = locked(tenant, id);
        if (current.archivedAt() != null) throw ApiError.conflict("VIDEO_ARCHIVED", "已归档资源不能修改端点");
        String host = validateEndpoint(current.protocol(), input.endpointUrl());
        String scope = endpointScope(tenant, id);
        var encrypted = cipher.encrypt(scope, input.endpointUrl());
        String credentialsCipher = null, credentialsNonce = null;
        if (input.username() != null) {
            ObjectNode credentials = json.createObjectNode().put("username", input.username()).put("password", input.password());
            var encryptedCredentials = cipher.encrypt(credentialsScope(tenant, id), credentials.toString());
            credentialsCipher = encryptedCredentials.ciphertext();
            credentialsNonce = encryptedCredentials.nonce();
        }
        if (jdbc.update("UPDATE video_resources SET endpoint_cipher=?,endpoint_nonce=?,endpoint_host=?,"
                + "credentials_cipher=?,credentials_nonce=?,revision=revision+1,updated_by=?,updated_at=?"
                + " WHERE id=? AND tenant_id=? AND revision=?",
            encrypted.ciphertext(), encrypted.nonce(), host, credentialsCipher, credentialsNonce,
            actor.user().id(), time.now(), id, tenant, current.revision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "视频资源已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant, "VIDEO_ENDPOINT_ROTATED", id, "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "video-endpoint-save", key, hash, result);
        return result;
    }

    @Transactional
    public VideoModels.Resource clearEndpoint(AuthService.Identity actor, String tenant, String id, String key,
                                              VideoInputs.Versioned input, String requestId) {
        guard.begin(actor, "video:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.reason()));
        var replay = idem.replay(actor.user().id(), "video-endpoint-clear", key, hash, VideoModels.Resource.class);
        if (replay != null) return replay;
        var current = locked(tenant, id);
        revision(input.expectedRevision(), current.revision());
        if (jdbc.update("UPDATE video_resources SET endpoint_cipher=NULL,endpoint_nonce=NULL,endpoint_host=NULL,"
                + "credentials_cipher=NULL,credentials_nonce=NULL,enabled=FALSE,revision=revision+1,"
                + "updated_by=?,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",
            actor.user().id(), time.now(), id, tenant, input.expectedRevision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "视频资源已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant, "VIDEO_ENDPOINT_CLEARED", id, "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "video-endpoint-clear", key, hash, result);
        return result;
    }

    @Transactional
    public VideoModels.Resource saveCredentials(AuthService.Identity actor, String tenant, String id, String key,
                                                VideoInputs.Credentials input, String requestId) {
        guard.begin(actor, "video:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.username(), input.reason()));
        var replay = idem.replay(actor.user().id(), "video-credentials-save", key, hash, VideoModels.Resource.class);
        if (replay != null) return replay;
        var current = locked(tenant, id);
        if (current.endpointCipher() == null) throw ApiError.conflict("ENDPOINT_NOT_CONFIGURED", "请先配置真实端点");
        if ("RTSP".equals(current.protocol()))
            throw new ApiError(409, "RTSP_GATEWAY_NOT_CONFIGURED", "RTSP 没有已配置的转封装网关，凭据不会被使用");
        ObjectNode credentials = json.createObjectNode().put("username", input.username()).put("password", input.password());
        var encrypted = cipher.encrypt(credentialsScope(tenant, id), credentials.toString());
        if (jdbc.update("UPDATE video_resources SET credentials_cipher=?,credentials_nonce=?,revision=revision+1,"
                + "updated_by=?,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",
            encrypted.ciphertext(), encrypted.nonce(), actor.user().id(), time.now(), id, tenant,
            current.revision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "视频资源已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant, "VIDEO_CREDENTIALS_ROTATED", id, "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "video-credentials-save", key, hash, result);
        return result;
    }

    @Transactional
    public VideoModels.Resource clearCredentials(AuthService.Identity actor, String tenant, String id, String key,
                                                 VideoInputs.Versioned input, String requestId) {
        guard.begin(actor, "video:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.reason()));
        var replay = idem.replay(actor.user().id(), "video-credentials-clear", key, hash, VideoModels.Resource.class);
        if (replay != null) return replay;
        var current = locked(tenant, id);
        revision(input.expectedRevision(), current.revision());
        if (jdbc.update("UPDATE video_resources SET credentials_cipher=NULL,credentials_nonce=NULL,"
                + "revision=revision+1,updated_by=?,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",
            actor.user().id(), time.now(), id, tenant, input.expectedRevision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "视频资源已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant, "VIDEO_CREDENTIALS_CLEARED", id, "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "video-credentials-clear", key, hash, result);
        return result;
    }

    @Transactional
    public VideoModels.Resource setEnabled(AuthService.Identity actor, String tenant, String id, String key,
                                           VideoInputs.Enabled input, String requestId) {
        guard.begin(actor, "video:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.enabled(), input.reason()));
        var replay = idem.replay(actor.user().id(), "video-enabled", key, hash, VideoModels.Resource.class);
        if (replay != null) return replay;
        var current = locked(tenant, id);
        revision(input.expectedRevision(), current.revision());
        if (current.archivedAt() != null) throw ApiError.conflict("VIDEO_ARCHIVED", "已归档资源不能启用");
        if (input.enabled() && current.endpointCipher() == null)
            throw ApiError.conflict("ENDPOINT_NOT_CONFIGURED", "启用前必须保存真实端点；不会启用空壳资源");
        if (input.enabled() && "RTSP".equals(current.protocol()) && current.gatewayCipher() == null)
            throw ApiError.conflict("RTSP_GATEWAY_NOT_CONFIGURED", "启用RTSP资源前必须配置转封装HLS网关");
        if (input.enabled() && !cipher.ready())
            throw new ApiError(503, "VIDEO_ENCRYPTION_UNAVAILABLE", "未配置 APP_VIDEO_KEY，不能启用媒体资源");
        if (input.enabled() && !"RTSP".equals(current.protocol()) && !policy.allowlistConfigured())
            throw new ApiError(409, "VIDEO_ALLOWLIST_NOT_CONFIGURED", "未配置 APP_VIDEO_ALLOWED_HOSTS，不能启用HTTPS媒体");
        if (jdbc.update("UPDATE video_resources SET enabled=?,revision=revision+1,updated_by=?,updated_at=?"
                + " WHERE id=? AND tenant_id=? AND revision=?",
            input.enabled(), actor.user().id(), time.now(), id, tenant, input.expectedRevision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "视频资源已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant, input.enabled() ? "VIDEO_ENABLED" : "VIDEO_DISABLED", id,
            "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "video-enabled", key, hash, result);
        return result;
    }

    @Transactional
    public VideoModels.Resource archive(AuthService.Identity actor, String tenant, String id, String key,
                                        VideoInputs.Versioned input, String requestId) {
        guard.begin(actor, "video:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.reason()));
        var replay = idem.replay(actor.user().id(), "video-archive", key, hash, VideoModels.Resource.class);
        if (replay != null) return replay;
        var current = locked(tenant, id);
        revision(input.expectedRevision(), current.revision());
        if (current.archivedAt() != null) throw ApiError.conflict("VIDEO_ALREADY_ARCHIVED", "资源已归档");
        var now = time.now();
        if (jdbc.update("UPDATE video_resources SET archived_at=?,enabled=FALSE,revision=revision+1,updated_by=?,updated_at=?"
                + " WHERE id=? AND tenant_id=? AND revision=?", now, actor.user().id(), now, id, tenant,
                input.expectedRevision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "视频资源已变化，请刷新");
        jdbc.update("UPDATE video_media_sessions SET state='REVOKED',revoked_at=? WHERE tenant_id=? AND video_resource_id=? AND state='ACTIVE'",
            now, tenant, id);
        audit.recordReason(actor.user().id(), tenant, "VIDEO_ARCHIVED", id, "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "video-archive", key, hash, result);
        return result;
    }

    @Transactional
    public VideoModels.Resource saveGateway(AuthService.Identity actor, String tenant, String id, String key,
                                            VideoInputs.Gateway input, String requestId) {
        guard.begin(actor, "video:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.url(), input.reason()));
        var replay = idem.replay(actor.user().id(), "video-gateway-save", key, hash, VideoModels.Resource.class);
        if (replay != null) return replay;
        var current = locked(tenant, id);
        if (!"RTSP".equals(current.protocol()))
            throw ApiError.conflict("GATEWAY_ONLY_FOR_RTSP", "只有 RTSP 资源需要配置转封装网关");
        if (current.archivedAt() != null) throw ApiError.conflict("VIDEO_ARCHIVED", "已归档资源不能修改网关");
        String host = upstream.resolve(input.url()).host();
        var encrypted = cipher.encrypt(gatewayScope(tenant, id), input.url());
        if (jdbc.update("UPDATE video_resources SET gateway_cipher=?,gateway_nonce=?,gateway_host=?,revision=revision+1,"
                + "updated_by=?,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",
            encrypted.ciphertext(), encrypted.nonce(), host, actor.user().id(), time.now(), id, tenant,
            current.revision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "视频资源已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant, "VIDEO_RTSP_GATEWAY_ROTATED", id, "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "video-gateway-save", key, hash, result);
        return result;
    }

    @Transactional
    public VideoModels.Resource clearGateway(AuthService.Identity actor, String tenant, String id, String key,
                                             VideoInputs.Versioned input, String requestId) {
        guard.begin(actor, "video:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.reason()));
        var replay = idem.replay(actor.user().id(), "video-gateway-clear", key, hash, VideoModels.Resource.class);
        if (replay != null) return replay;
        var current = locked(tenant, id);
        revision(input.expectedRevision(), current.revision());
        if (jdbc.update("UPDATE video_resources SET gateway_cipher=NULL,gateway_nonce=NULL,gateway_host=NULL,"
                + "revision=revision+1,updated_by=?,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",
            actor.user().id(), time.now(), id, tenant, input.expectedRevision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "视频资源已变化，请刷新");
        audit.recordReason(actor.user().id(), tenant, "VIDEO_RTSP_GATEWAY_CLEARED", id, "SUCCESS", requestId, input.reason());
        var result = queries.byId(tenant, id);
        idem.save(actor.user().id(), "video-gateway-clear", key, hash, result);
        return result;
    }

    // ---------------- associations ----------------

    @Transactional
    public VideoModels.Association associate(AuthService.Identity actor, String tenant, String key,
                                             VideoInputs.Association input, String requestId) {
        guard.begin(actor, "video:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, input.videoResourceId(), input.deviceId(), input.reason()));
        var replay = idem.replay(actor.user().id(), "video-association-create", key, hash,
            VideoModels.Association.class);
        if (replay != null) return replay;
        var resource = queries.raw(tenant, input.videoResourceId());
        if (resource.archivedAt() != null) throw ApiError.conflict("VIDEO_ARCHIVED", "已归档资源不能新增关联");
        var device = jdbc.query("SELECT archived_at FROM devices WHERE id=? AND tenant_id=?",
            (rs, n) -> rs.getObject(1, OffsetDateTime.class), input.deviceId(), tenant);
        if (device.isEmpty()) throw ApiError.conflict("DEVICE_NOT_IN_TENANT", "设备不存在或不属于当前单位");
        if (device.getFirst() != null) throw ApiError.conflict("DEVICE_ARCHIVED", "已归档设备不能新增关联");
        if (!scopes.contains(actor.user().id(), tenant, input.deviceId()))
            throw ApiError.forbidden("设备不在当前操作者的设备范围内");
        String id = UUID.randomUUID().toString();
        var now = time.now();
        try {
            jdbc.update("INSERT INTO video_associations(id,tenant_id,video_resource_id,device_id,state,active_key,"
                    + "revision,created_by,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',?,0,?,?,?)",
                id, tenant, input.videoResourceId(), input.deviceId(),
                input.videoResourceId() + ":" + input.deviceId(), actor.user().id(), now, now);
        } catch (DuplicateKeyException race) {
            throw ApiError.conflict("VIDEO_ALREADY_ASSOCIATED", "该设备已关联此视频资源");
        }
        audit.recordReason(actor.user().id(), tenant, "VIDEO_ASSOCIATION_CREATED", id, "SUCCESS", requestId, input.reason());
        var result = associationRow(tenant, id);
        idem.save(actor.user().id(), "video-association-create", key, hash, result);
        return result;
    }

    @Transactional
    public VideoModels.Association revokeAssociation(AuthService.Identity actor, String tenant, String id, String key,
                                                     VideoInputs.RevokeAssociation input, String requestId) {
        guard.begin(actor, "video:write", tenant, "management");
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
        String hash = idem.fingerprint(Arrays.asList(tenant, id, input.expectedRevision(), input.revokeReason(), input.reason()));
        var replay = idem.replay(actor.user().id(), "video-association-revoke", key, hash,
            VideoModels.Association.class);
        if (replay != null) return replay;
        var rows = jdbc.query("SELECT state,revision,device_id FROM video_associations WHERE id=? AND tenant_id=? FOR UPDATE",
            (rs, n) -> new Object[]{rs.getString(1), rs.getLong(2), rs.getString(3)}, id, tenant);
        if (rows.isEmpty()) throw ApiError.missing();
        Object[] current = rows.getFirst();
        revision(input.expectedRevision(), ((Number) current[1]).longValue());
        if (!"ACTIVE".equals(current[0])) throw ApiError.conflict("VIDEO_ASSOCIATION_REVOKED", "关联已撤销");
        if (!scopes.contains(actor.user().id(), tenant, (String) current[2]))
            throw ApiError.forbidden("设备不在当前操作者的设备范围内");
        var now = time.now();
        if (jdbc.update("UPDATE video_associations SET state='REVOKED',active_key=NULL,revoked_by=?,revoked_at=?,"
                + "revoke_reason=?,revision=revision+1,updated_at=? WHERE id=? AND tenant_id=? AND revision=?",
            actor.user().id(), now, input.revokeReason(), now, id, tenant, input.expectedRevision()) != 1)
            throw ApiError.conflict("REVISION_CONFLICT", "关联已变化，请刷新");
        jdbc.update("UPDATE video_media_sessions SET state='REVOKED',revoked_at=? WHERE tenant_id=? AND device_id=? AND state='ACTIVE'",
            now, tenant, (String) current[2]);
        audit.recordReason(actor.user().id(), tenant, "VIDEO_ASSOCIATION_REVOKED", id, "SUCCESS", requestId, input.reason());
        var result = associationRow(tenant, id);
        idem.save(actor.user().id(), "video-association-revoke", key, hash, result);
        return result;
    }

    // ---------------- validation ----------------

    private String validateEndpoint(String protocol, String rawUrl) {
        if ("RTSP".equals(protocol)) {
            URI uri;
            try { uri = new URI(rawUrl); } catch (Exception invalid) { throw ApiError.bad("RTSP端点格式无效"); }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("rtsp") && !scheme.equals("rtsps"))
                throw ApiError.bad("RTSP资源端点必须使用 rtsp:// 或 rtsps://");
            if (uri.getUserInfo() != null) throw ApiError.bad("RTSP端点不能内嵌账号密码；凭据必须单独写入");
            if (uri.getHost() == null || uri.getHost().isBlank()) throw ApiError.bad("RTSP端点缺少主机");
            return uri.getHost().toLowerCase(Locale.ROOT);
        }
        return upstream.resolve(rawUrl).host();
    }

    private VideoResourceQueries.RawResource locked(String tenant, String id) {
        var rows = jdbc.query("SELECT * FROM video_resources WHERE id=? AND tenant_id=? FOR UPDATE",
            (rs, n) -> new VideoResourceQueries.RawResource(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("name"), rs.getString("resource_kind"), rs.getString("protocol"),
                rs.getString("endpoint_cipher"), rs.getString("endpoint_nonce"), rs.getString("endpoint_host"),
                rs.getString("credentials_cipher"), rs.getString("credentials_nonce"),
                rs.getString("gateway_cipher"), rs.getString("gateway_nonce"), rs.getString("gateway_host"),
                rs.getBoolean("enabled"), rs.getObject("archived_at", OffsetDateTime.class), rs.getLong("revision")), id, tenant);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private VideoModels.Association associationRow(String tenant, String id) {
        var rows = jdbc.query("SELECT a.id,a.tenant_id,a.video_resource_id,a.device_id,d.name AS device_name,d.device_eui,"
                + "p.name AS place_name,d.archived_at,a.state,a.revision,a.created_at,a.revoked_at,a.revoke_reason"
                + " FROM video_associations a JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id"
                + " LEFT JOIN places p ON p.id=d.place_id AND p.tenant_id=d.tenant_id"
                + " WHERE a.tenant_id=? AND a.id=?",
            (rs, n) -> new VideoModels.Association(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("video_resource_id"), rs.getString("device_id"), rs.getString("device_name"),
                rs.getString("device_eui"), rs.getString("place_name"),
                rs.getObject("archived_at", OffsetDateTime.class) == null ? "ACTIVE" : "ARCHIVED",
                rs.getString("state"), rs.getLong("revision"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class), rs.getString("revoke_reason")), tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    public static String endpointScope(String tenant, String resourceId) {
        return "video-endpoint-v1\0" + tenant + "\0" + resourceId;
    }

    public static String credentialsScope(String tenant, String resourceId) {
        return "video-credentials-v1\0" + tenant + "\0" + resourceId;
    }

    public static String gatewayScope(String tenant, String resourceId) {
        return "video-gateway-v1\0" + tenant + "\0" + resourceId;
    }

    private static void revision(long expected, long actual) {
        if (expected != actual) throw ApiError.conflict("REVISION_CONFLICT", "数据已变化，请刷新后重试");
    }
}
