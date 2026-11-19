package com.fireplatform.video;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.Digests;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scoped media sessions and HLS rewrite. A session is bound to one user, device, resource and purpose;
 * every subresource request re-validates the user, permission, device scope, association and expiry.
 * Playlist URIs never become an open proxy: each one is host-allowlisted, resolved, pinned and stored.
 */
@Service
public class VideoMediaService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]*)\"");
    private static final int MAX_PLAYLIST_BYTES = 1_048_576;
    private static final int MAX_HLS_ENTRIES = 2000;
    private static final long KEY_MAX_BYTES = 65_536;

    private final JdbcTemplate jdbc;
    private final DeviceAccessScope scopes;
    private final VideoPolicy policy;
    private final VideoSecretCipher cipher;
    private final VideoUpstreamClient upstream;
    private final VideoResourceQueries queries;
    private final AuditService audit;
    private final DatabaseTime time;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final FfmpegExtractor ffmpeg;

    public VideoMediaService(JdbcTemplate jdbc, DeviceAccessScope scopes, VideoPolicy policy, VideoSecretCipher cipher,
                             VideoUpstreamClient upstream, VideoResourceQueries queries, AuditService audit,
                             DatabaseTime time, com.fasterxml.jackson.databind.ObjectMapper json,
                             FfmpegExtractor ffmpeg) {
        this.jdbc = jdbc; this.scopes = scopes; this.policy = policy; this.cipher = cipher; this.upstream = upstream;
        this.queries = queries; this.audit = audit; this.time = time; this.json = json; this.ffmpeg = ffmpeg;
    }

    public record Issued(VideoModels.MediaSession session, String token, String cookiePath) { }

    public record Context(String sessionId, String audience, String tenantId, String resourceId, String deviceId,
                          String protocol, String purpose, String endpointUrl, String username, String password) { }

    public record MediaResponse(String contentType, byte[] bytes, VideoUpstreamClient.Upstream streaming,
                                String contentRange, boolean acceptsRanges) implements AutoCloseable {
        public boolean isBytes() { return bytes != null; }
        @Override public void close() { if (streaming != null) streaming.close(); }
    }

    @Transactional
    public Issued issue(AuthService.Identity actor, String audience, String tenant, String resourceId,
                        VideoInputs.MediaSession input, String requestId) {
        var resource = queries.raw(tenant, resourceId);
        if (resource.archivedAt() != null) throw ApiError.conflict("VIDEO_ARCHIVED", "资源已归档，不能播放或抓图");
        if (!resource.enabled()) throw ApiError.conflict("VIDEO_DISABLED", "资源未启用");
        var association = jdbc.queryForObject("SELECT COUNT(*) FROM video_associations WHERE tenant_id=? AND video_resource_id=? AND device_id=? AND state='ACTIVE'",
            Long.class, tenant, resourceId, input.deviceId());
        if (association == null || association != 1L)
            throw ApiError.conflict("VIDEO_ASSOCIATION_REQUIRED", "该视频资源未与该设备建立有效关联");
        var deviceRows = jdbc.query("SELECT archived_at FROM devices WHERE id=? AND tenant_id=?",
            (rs, n) -> rs.getObject(1, OffsetDateTime.class), input.deviceId(), tenant);
        if (deviceRows.isEmpty()) throw ApiError.missing();
        if (deviceRows.getFirst() != null) throw ApiError.conflict("DEVICE_ARCHIVED", "设备已归档");
        if (!scopes.contains(actor.user().id(), tenant, input.deviceId()))
            throw ApiError.forbidden("设备不在当前操作者的设备范围内");
        if (!cipher.ready())
            throw new ApiError(503, "VIDEO_ENCRYPTION_UNAVAILABLE", "未配置 APP_VIDEO_KEY，不能建立媒体会话");
        boolean rtsp = "RTSP".equals(resource.protocol());
        if (rtsp && resource.gatewayCipher() == null)
            throw new ApiError(409, "RTSP_GATEWAY_NOT_CONFIGURED", "RTSP 资源未配置转封装网关，不能播放");
        if (!rtsp && resource.endpointCipher() == null)
            throw ApiError.conflict("ENDPOINT_NOT_CONFIGURED", "尚未配置真实端点");
        String effectiveProtocol = rtsp ? "HTTPS_HLS" : resource.protocol();
        requirePurpose(effectiveProtocol, input.purpose());
        if (!policy.allowlistConfigured())
            throw new ApiError(409, "VIDEO_ALLOWLIST_NOT_CONFIGURED", "未配置 APP_VIDEO_ALLOWED_HOSTS，媒体代理不可用");
        var capability = VideoModels.capability(resource.protocol(), resource.enabled(), resource.archivedAt(),
            resource.endpointConfigured(), policy.allowlistConfigured(), resource.gatewayCipher() != null,
            ffmpeg.available());
        if (!capability.media_ready())
            throw new ApiError(409, "VIDEO_MEDIA_UNAVAILABLE", capability.note());
        String token = token();
        String id = UUID.randomUUID().toString();
        var now = time.now();
        var expires = now.plus(policy.sessionTtl());
        jdbc.update("UPDATE video_media_sessions SET state='REVOKED',revoked_at=? WHERE user_id=? AND tenant_id=?"
                + " AND video_resource_id=? AND device_id=? AND purpose=? AND state='ACTIVE'",
            now, actor.user().id(), tenant, resourceId, input.deviceId(), input.purpose());
        jdbc.update("INSERT INTO video_media_sessions(id,tenant_id,video_resource_id,device_id,audience,user_id,purpose,"
                + "token_hash,state,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
            id, tenant, resourceId, input.deviceId(), audience, actor.user().id(), input.purpose(),
            Digests.sha256(token), expires, now);
        audit.record(actor.user().id(), tenant, "VIDEO_MEDIA_SESSION_CREATED", id, "SUCCESS", requestId);
        return new Issued(new VideoModels.MediaSession(id, resourceId, input.deviceId(), input.purpose(),
            mediaKind(effectiveProtocol), capability.playback(), capability.snapshot(), expires,
            mediaPath(audience, resourceId, "primary"),
            "受权媒体会话已建立；代理取到字节不等于浏览器已解码或已播放"), token,
            cookiePath(audience, resourceId));
    }

    @Transactional
    public void revoke(AuthService.Identity actor, String audience, String resourceId, String token) {
        if (token == null || token.isBlank()) return;
        jdbc.update("UPDATE video_media_sessions SET state='REVOKED',revoked_at=? WHERE token_hash=? AND video_resource_id=?"
                + " AND audience=? AND user_id=? AND state='ACTIVE'",
            time.now(), Digests.sha256(token), resourceId, audience, actor.user().id());
    }

    @Transactional
    public Context validate(String audience, String resourceId, String token, String requiredPurpose) {
        if (token == null || token.isBlank() || token.length() > 256)
            throw new ApiError(401, "VIDEO_MEDIA_SESSION_REQUIRED", "缺少视频媒体会话，请重新授权播放");
        var rows = jdbc.query("SELECT s.id,s.tenant_id,s.video_resource_id,s.device_id,s.purpose,s.expires_at,s.state,"
                + "r.protocol,r.enabled,r.archived_at,r.endpoint_cipher,r.endpoint_nonce,r.credentials_cipher,r.credentials_nonce,"
                + "r.gateway_cipher,r.gateway_nonce,"
                + "u.enabled AS user_enabled,u.must_change_password,u.user_type,u.tenant_id AS user_tenant"
                + " FROM video_media_sessions s"
                + " JOIN video_resources r ON r.id=s.video_resource_id AND r.tenant_id=s.tenant_id"
                + " JOIN app_users u ON u.id=s.user_id"
                + " WHERE s.token_hash=? AND s.video_resource_id=? AND s.audience=?",
            (rs, n) -> new Object[]{rs.getString("id"), rs.getString("tenant_id"), rs.getString("video_resource_id"),
                rs.getString("device_id"), rs.getString("purpose"), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("state"), rs.getString("protocol"), rs.getBoolean("enabled"),
                rs.getObject("archived_at", OffsetDateTime.class), rs.getString("endpoint_cipher"),
                rs.getString("endpoint_nonce"), rs.getString("credentials_cipher"), rs.getString("credentials_nonce"),
                rs.getString("gateway_cipher"), rs.getString("gateway_nonce"),
                rs.getBoolean("user_enabled"), rs.getBoolean("must_change_password"), rs.getString("user_type"),
                rs.getString("user_tenant")},
            Digests.sha256(token), resourceId, audience);
        if (rows.isEmpty()) throw new ApiError(401, "VIDEO_MEDIA_SESSION_REQUIRED", "媒体会话不存在或已失效");
        Object[] row = rows.getFirst();
        OffsetDateTime now = time.now();
        String state = (String) row[6];
        if (!"ACTIVE".equals(state) || ((OffsetDateTime) row[5]).isBefore(now))
            throw new ApiError(401, "VIDEO_MEDIA_SESSION_EXPIRED", "媒体会话已过期，请重新授权播放");
        String tenant = (String) row[1], deviceId = (String) row[3], purpose = (String) row[4];
        Long tenantEnabled = jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id=? AND enabled=TRUE", Long.class, tenant);
        if (tenantEnabled == null || tenantEnabled != 1L)
            throw new ApiError(403, "UNIT_DISABLED", "单位已停用，媒体会话失效");
        if (!"true".equalsIgnoreCase(String.valueOf(row[16])) || Boolean.TRUE.equals(row[17]))
            throw new ApiError(401, "VIDEO_MEDIA_SESSION_EXPIRED", "当前账户不可用，媒体会话已失效");
        boolean customer = "customer".equals(audience);
        if (customer && (!"CUSTOMER".equals(row[18]) || !tenant.equals(row[19])))
            throw new ApiError(401, "VIDEO_MEDIA_SESSION_EXPIRED", "客户媒体会话与当前单位不一致");
        if (!customer) {
            if (!"PLATFORM".equals(row[18])) throw new ApiError(401, "VIDEO_MEDIA_SESSION_EXPIRED", "媒体会话主体无效");
            Long grant = jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id=?"
                + " AND tenant_id=?", Long.class, sessionUser(row[0]), tenant);
            if (grant == null || grant == 0)
                throw ApiError.forbidden("单位授权已撤销，媒体会话失效");
        }
        if (!requiredPurpose.equals(purpose))
            throw ApiError.conflict("VIDEO_MEDIA_PURPOSE_MISMATCH", "媒体会话用途与请求不一致");
        if (((OffsetDateTime) row[9]) != null || !Boolean.TRUE.equals(row[8]))
            throw ApiError.conflict("VIDEO_MEDIA_UNAVAILABLE", "视频资源已归档或停用");
        String sourceProtocol = (String) row[7];
        boolean rtsp = "RTSP".equals(sourceProtocol);
        boolean gateway = row[14] != null && row[15] != null;
        if (rtsp && !gateway)
            throw new ApiError(409, "RTSP_GATEWAY_NOT_CONFIGURED", "RTSP 网关已清除，媒体会话失效");
        if (!rtsp && (row[10] == null || row[11] == null))
            throw ApiError.conflict("ENDPOINT_NOT_CONFIGURED", "视频端点已清除");
        if (!cipher.ready())
            throw new ApiError(503, "VIDEO_ENCRYPTION_UNAVAILABLE", "未配置 APP_VIDEO_KEY，不能读取媒体端点");
        String protocol = rtsp ? "HTTPS_HLS" : sourceProtocol;
        requirePurpose(protocol, purpose);
        if (!policy.allowlistConfigured())
            throw new ApiError(409, "VIDEO_ALLOWLIST_NOT_CONFIGURED", "未配置 APP_VIDEO_ALLOWED_HOSTS，媒体代理不可用");
        Long currentPermissions = jdbc.queryForObject("SELECT COUNT(DISTINCT permission) FROM effective_user_permissions"
            + " WHERE user_id=? AND permission IN ('video:read','video:play')", Long.class, sessionUser(row[0]));
        if (currentPermissions == null || currentPermissions != 2L)
            throw ApiError.forbidden("视频读取或播放权限已撤销");
        if (!scopes.contains(sessionUser(row[0]), tenant, deviceId))
            throw ApiError.forbidden("设备范围已变化，媒体会话失效");
        Long association = jdbc.queryForObject("SELECT COUNT(*) FROM video_associations WHERE tenant_id=?"
            + " AND video_resource_id=? AND device_id=? AND state='ACTIVE'", Long.class, tenant, resourceId, deviceId);
        if (association == null || association == 0)
            throw ApiError.conflict("VIDEO_ASSOCIATION_REVOKED", "视频与设备的关联已撤销");
        String endpoint = rtsp
            ? cipher.decrypt(VideoResourceCommands.gatewayScope(tenant, resourceId), (String) row[14], (String) row[15])
            : cipher.decrypt(VideoResourceCommands.endpointScope(tenant, resourceId), (String) row[10], (String) row[11]);
        String username = null, password = null;
        if (row[12] != null && row[13] != null) {
            String credentials = cipher.decrypt(VideoResourceCommands.credentialsScope(tenant, resourceId),
                (String) row[12], (String) row[13]);
            try {
                var node = json.readTree(credentials);
                username = node.path("username").asText(null);
                password = node.path("password").asText(null);
            } catch (Exception unreadable) {
                throw new ApiError(503, "VIDEO_ENDPOINT_UNAVAILABLE", "视频凭据无法读取");
            }
        }
        return new Context((String) row[0], audience, tenant, resourceId, deviceId, protocol, purpose, endpoint, username, password);
    }

    /** Opens the configured primary media. HLS playlists are rewritten to this proxy's own subresources. */
    public MediaResponse openPrimary(Context context, String rangeHeader) {
        var target = upstream.resolve(context.endpointUrl());
        String authorization = basic(context);
        String range = "PLAYBACK".equals(context.purpose()) && "HTTPS_MP4".equals(context.protocol()) ? range(rangeHeader) : null;
        var opened = upstream.open(target, range, authorization, policy.maxPrimaryBytes());
        try {
            if ("HTTPS_HLS".equals(context.protocol())) {
                byte[] playlist = upstream.readBounded(opened, MAX_PLAYLIST_BYTES);
                opened.close();
                return new MediaResponse("application/vnd.apple.mpegurl",
                    rewritePlaylist(context, context.endpointUrl(), playlist), null, null, false);
            }
            String type = primaryType(context.protocol(), opened.contentType());
            long cap = policy.maxPrimaryBytes();
            if (opened.contentLength() > cap) {
                opened.close();
                throw new ApiError(502, "VIDEO_UPSTREAM_TOO_LARGE", "视频上游内容超过大小上限");
            }
            return new MediaResponse(type, null, opened, opened.contentRange(), opened.acceptsRanges());
        } catch (RuntimeException failure) {
            opened.close();
            throw failure;
        }
    }

    /** Reads a stored HLS subresource. Nested playlists are rewritten and stay inside the same session. */
    public MediaResponse openHls(Context context, int entryNo) {
        if (!"HTTPS_HLS".equals(context.protocol()))
            throw ApiError.conflict("VIDEO_NOT_HLS", "该资源不是HLS协议");
        if (entryNo < 0 || entryNo >= MAX_HLS_ENTRIES) throw ApiError.bad("HLS子资源序号无效");
        var rows = jdbc.query("SELECT url_cipher,url_nonce FROM video_media_subresources WHERE session_id=? AND entry_no=?",
            (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, context.sessionId(), entryNo);
        if (rows.isEmpty()) throw ApiError.missing();
        String url = cipher.decrypt(subresourceScope(context.sessionId(), entryNo), rows.getFirst()[0], rows.getFirst()[1]);
        var target = upstream.resolve(url);
        var opened = upstream.open(target, null, basicForTarget(context, target), policy.maxSubresourceBytes());
        try {
            long cap = policy.maxSubresourceBytes();
            if (opened.contentLength() > cap) {
                throw new ApiError(502, "VIDEO_UPSTREAM_TOO_LARGE", "HLS子资源超过大小上限");
            }
            byte[] body = upstream.readBounded(opened, cap);
            if (isPlaylist(opened.contentType())
                || new String(body, 0, Math.min(body.length, 8), StandardCharsets.US_ASCII).stripLeading().startsWith("#EXTM3U"))
                return new MediaResponse("application/vnd.apple.mpegurl", rewritePlaylist(context, url, body),
                    null, null, false);
            return new MediaResponse(subresourceType(opened.contentType()), body, null, null, false);
        } finally {
            opened.close();
        }
    }

    private byte[] rewritePlaylist(Context context, String baseUrl, byte[] playlist) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(playlist)).toString();
        } catch (Exception malformed) {
            throw new ApiError(502, "HLS_PLAYLIST_UNREADABLE", "HLS播放列表不是有效UTF-8");
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (!text.stripLeading().startsWith("#EXTM3U"))
            throw new ApiError(502, "HLS_PLAYLIST_UNREADABLE", "上游响应不是HLS播放列表");
        URI base;
        try { base = new URI(baseUrl); } catch (Exception invalid) { throw new ApiError(502, "HLS_PLAYLIST_UNREADABLE", "HLS基地址无效"); }
        var out = new StringBuilder(text.length() + 256);
        for (String line : text.split("\\r?\\n", -1)) {
            if (line.isBlank()) { out.append('\n'); continue; }
            if (line.startsWith("#")) {
                out.append(rewriteAttributes(context, base, line)).append('\n');
            } else {
                out.append(proxyPath(context, base, line.trim())).append('\n');
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String rewriteAttributes(Context context, URI base, String line) {
        Matcher matcher = URI_ATTRIBUTE.matcher(line);
        var out = new StringBuffer(line.length() + 64);
        while (matcher.find()) {
            String replacement = "URI=\"" + proxyPath(context, base, matcher.group(1)) + "\"";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String proxyPath(Context context, URI base, String raw) {
        URI resolved;
        try {
            resolved = base.resolve(raw);
        } catch (IllegalArgumentException invalid) {
            throw new ApiError(502, "HLS_UNSUPPORTED_URI", "HLS播放列表包含无法解析的URI");
        }
        String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"https".equals(scheme))
            throw new ApiError(502, "HLS_UNSUPPORTED_URI_SCHEME", "HLS子资源只允许HTTPS（不支持 " + scheme + "）");
        var target = upstream.resolve(resolved.toString());
        String normalized = target.uri().toString();
        int entry = entryFor(context, target, normalized);
        return mediaPath(context.audience(), context.resourceId(), "hls/" + entry);
    }

    private int entryFor(Context context, VideoUpstreamClient.Target target, String normalized) {
        String hash = Digests.sha256(normalized);
        var existing = jdbc.query("SELECT entry_no FROM video_media_subresources WHERE session_id=? AND url_sha256=?",
            (rs, n) -> rs.getInt(1), context.sessionId(), hash);
        if (!existing.isEmpty()) return existing.getFirst();
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(entry_no),-1)+1 FROM video_media_subresources WHERE session_id=?",
            Integer.class, context.sessionId());
        if (next == null || next >= MAX_HLS_ENTRIES)
            throw new ApiError(502, "HLS_PLAYLIST_TOO_LARGE", "HLS子资源数量超过上限");
        var encrypted = cipher.encrypt(subresourceScope(context.sessionId(), next), normalized);
        try {
            jdbc.update("INSERT INTO video_media_subresources(id,session_id,entry_no,host,url_sha256,url_cipher,url_nonce,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), context.sessionId(), next, target.host(), hash,
                encrypted.ciphertext(), encrypted.nonce(), time.now());
        } catch (DuplicateKeyException race) {
            return jdbc.queryForObject("SELECT entry_no FROM video_media_subresources WHERE session_id=? AND url_sha256=?",
                Integer.class, context.sessionId(), hash);
        }
        return next;
    }

    private static String primaryType(String protocol, String upstreamType) {
        String normalized = upstreamType == null ? "" : upstreamType.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("text/html") || normalized.startsWith("application/json"))
            throw new ApiError(502, "VIDEO_UPSTREAM_NOT_MEDIA", "视频上游返回了非媒体内容");
        return switch (protocol) {
            case "HTTPS_MP4" -> "video/mp4";
            case "HTTPS_JPEG" -> "image/jpeg";
            default -> "application/octet-stream";
        };
    }

    private static String subresourceType(String upstreamType) {
        if (upstreamType == null) return "application/octet-stream";
        String type = upstreamType.toLowerCase(java.util.Locale.ROOT);
        if (type.startsWith("text/html") || type.startsWith("application/json"))
            throw new ApiError(502, "VIDEO_UPSTREAM_NOT_MEDIA", "HLS子资源返回了非媒体内容");
        return switch (type.replace(" ", "")) {
            case "video/mp2t", "video/mp4", "audio/mp4", "audio/aac", "audio/mpeg", "application/octet-stream" -> type;
            default -> "application/octet-stream";
        };
    }

    private boolean isPlaylist(String type) {
        if (type == null) return false;
        String normalized = type.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("application/vnd.apple.mpegurl") || normalized.startsWith("application/x-mpegurl")
            || normalized.startsWith("audio/mpegurl") || normalized.startsWith("audio/x-mpegurl");
    }

    private static String range(String header) {
        if (header == null || header.isBlank()) return null;
        String value = header.trim();
        if (!value.matches("bytes=\\d{0,18}-\\d{0,18}") || value.equals("bytes=-"))
            throw new ApiError(416, "RANGE_UNSUPPORTED", "仅支持单段字节范围请求");
        if (value.startsWith("bytes=-")) {
            long suffix = Long.parseLong(value.substring(7));
            if (suffix <= 0 || suffix > 64L * 1024 * 1024) throw new ApiError(416, "RANGE_UNSUPPORTED", "范围后缀过大");
        } else {
            String[] parts = value.substring(6).split("-", -1);
            long start = Long.parseLong(parts[0]);
            if (!parts[1].isEmpty()) {
                long end = Long.parseLong(parts[1]);
                if (end < start || end - start > 128L * 1024 * 1024)
                    throw new ApiError(416, "RANGE_UNSUPPORTED", "字节范围无效或过大");
            }
        }
        return value;
    }

    private static String basic(Context context) {
        if (context.username() == null || context.password() == null) return null;
        String raw = context.username() + ":" + context.password();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
    private String basicForTarget(Context context, VideoUpstreamClient.Target target) {
        return sameOrigin(context.endpointUrl(), target.uri()) ? basic(context) : null;
    }

    private static boolean sameOrigin(String primary, URI target) {
        try {
            URI base = new URI(primary);
            int basePort = base.getPort() < 0 ? 443 : base.getPort();
            int targetPort = target.getPort() < 0 ? 443 : target.getPort();
            return "https".equalsIgnoreCase(base.getScheme()) && "https".equalsIgnoreCase(target.getScheme())
                && base.getHost() != null && target.getHost() != null
                && base.getHost().equalsIgnoreCase(target.getHost()) && basePort == targetPort;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static void requirePurpose(String protocol, String purpose) {
        if ("PLAYBACK".equals(purpose)) {
            if ("HTTPS_JPEG".equals(protocol))
                throw ApiError.conflict("VIDEO_SNAPSHOT_ONLY", "JPEG 资源只提供抓图，没有播放流");
        } else if ("SNAPSHOT".equals(purpose)) {
            if (!"HTTPS_JPEG".equals(protocol))
                throw new ApiError(409, "VIDEO_SNAPSHOT_REQUIRES_JOB",
                    "视频抓图使用持久化抓图任务抽取真实帧；媒体会话只提供JPEG直取");
        } else {
            throw ApiError.bad("媒体用途无效");
        }
    }

    private static String mediaKind(String protocol) {
        return switch (protocol) {
            case "HTTPS_MP4" -> "MP4";
            case "HTTPS_HLS" -> "HLS";
            case "HTTPS_JPEG" -> "JPEG";
            case "RTSP" -> "RTSP_NOT_IMPLEMENTED";
            default -> "UNKNOWN";
        };
    }

    public static String cookiePath(String audience, String resourceId) {
        return "/api/" + audience + "/video-resources/" + resourceId + "/media";
    }

    public static String mediaPath(String audience, String resourceId, String suffix) {
        return "/api/" + audience + "/video-resources/" + resourceId + "/media/" + suffix;
    }

    public static String cookieName() { return "fp_video_media"; }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String subresourceScope(String sessionId, int entryNo) {
        return "video-subresource-v1\0" + sessionId + "\0" + entryNo;
    }

    private String sessionUser(Object sessionId) {
        return jdbc.queryForObject("SELECT user_id FROM video_media_sessions WHERE id=?", String.class, (String) sessionId);
    }
}
