package com.fireplatform.video;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Digests;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable snapshot jobs. Queueing inserts only; the worker performs bounded I/O outside the transaction and
 * stores an encrypted real image with its hash and the actual platform capture time. No source frame time is
 * guessed, and a missing ffmpeg binary is NOT_CONFIGURED rather than a fabricated image.
 */
@Service
public class VideoCaptureService {
    private static final int MAX_PLAYLIST_BYTES = 1_048_576;

    public record Job(String id, String tenantId, String resourceId, String deviceId, String protocol, String state,
                      String failureCode, String failureReason, OffsetDateTime captureAt, String imageSha256,
                      String imageMediaType, Long imageBytes, OffsetDateTime createdAt, OffsetDateTime startedAt,
                      OffsetDateTime finishedAt) { }

    public record Image(byte[] bytes, String mediaType, String sha256, OffsetDateTime captureAt) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DatabaseTime time;
    private final VideoSecretCipher cipher;
    private final VideoUpstreamClient upstream;
    private final VideoPolicy policy;
    private final FfmpegExtractor ffmpeg;
    private final VideoResourceQueries queries;
    private final DeviceAccessScope scopes;
    private final RuntimeConfig runtime;

    public VideoCaptureService(JdbcTemplate jdbc, TransactionTemplate transactions, DatabaseTime time,
                               VideoSecretCipher cipher, VideoUpstreamClient upstream, VideoPolicy policy,
                               FfmpegExtractor ffmpeg, VideoResourceQueries queries, DeviceAccessScope scopes,
                               RuntimeConfig runtime) {
        this.jdbc = jdbc; this.transactions = transactions; this.time = time; this.cipher = cipher;
        this.upstream = upstream; this.policy = policy; this.ffmpeg = ffmpeg; this.queries = queries;
        this.scopes = scopes; this.runtime = runtime;
    }

    @Transactional
    public Job queueManual(String actorId, String tenant, String resourceId, String deviceId, String sourceId) {
        var resource = queries.raw(tenant, resourceId);
        assertAssociated(tenant, resourceId, deviceId);
        assertDevice(actorId, tenant, deviceId);
        String key = "manual." + UUID.randomUUID();
        return insertJob(tenant, resource, deviceId, null, actorId, "MANUAL", sourceId, null, key,
            Digests.sha256(tenant + "|" + resourceId + "|" + deviceId + "|" + sourceId));
    }

    /** Linkage path: joins the alarm transaction and performs no I/O. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Job queueFromLinkage(String tenant, String resourceId, String deviceId, String sourceKind,
                                String sourceId, String ruleId) {
        var resource = queries.raw(tenant, resourceId);
        assertAssociated(tenant, resourceId, deviceId);
        String key = "linkage.capture." + Digests.sha256(tenant + "|" + resourceId + "|" + deviceId + "|"
            + sourceKind + "|" + sourceId + "|" + ruleId);
        return insertJob(tenant, resource, deviceId, null, null, sourceKind, sourceId, ruleId, key,
            Digests.sha256(tenant + "|" + resourceId + "|" + deviceId + "|" + sourceKind + "|" + sourceId + "|" + ruleId));
    }

    private Job insertJob(String tenant, VideoResourceQueries.RawResource resource, String deviceId,
                          String associationId, String actorId, String sourceKind, String sourceId, String ruleId,
                          String idemKey, String requestHash) {
        var existing = jdbc.query("SELECT id FROM video_capture_jobs WHERE tenant_id=? AND idem_key=?",
            (rs, n) -> rs.getString(1), tenant, idemKey);
        if (!existing.isEmpty()) return job(tenant, existing.getFirst());
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO video_capture_jobs(id,tenant_id,video_resource_id,device_id,association_id,"
                    + "requested_by,source_kind,source_id,rule_id,idem_key,request_hash,protocol,state,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'QUEUED',?)",
                id, tenant, resource.id(), deviceId, associationId, actorId, sourceKind, sourceId, ruleId, idemKey,
                requestHash, resource.protocol(), time.now());
        } catch (DuplicateKeyException race) {
            return job(tenant, jdbc.queryForObject("SELECT id FROM video_capture_jobs WHERE tenant_id=? AND idem_key=?",
                String.class, tenant, idemKey));
        }
        return job(tenant, id);
    }

    public Job job(String tenant, String id) {
        var rows = jdbc.query("SELECT * FROM video_capture_jobs WHERE id=? AND tenant_id=?",
            (rs, n) -> jobRow(rs), id, tenant);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private static Job jobRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Job(rs.getString("id"), rs.getString("tenant_id"), rs.getString("video_resource_id"),
            rs.getString("device_id"), rs.getString("protocol"), rs.getString("state"),
            rs.getString("failure_code"), rs.getString("failure_reason"),
            rs.getObject("capture_at", OffsetDateTime.class), rs.getString("image_sha256"),
            rs.getString("image_media_type"), rs.getObject("image_bytes", Long.class),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class));
    }

    /** Bounded newest ALARM capture jobs for one alarm; caller has already checked device scope. */
    public java.util.List<Job> recentAlarmJobs(String actorId, String tenant, String resourceId, String deviceId,
                                               String alarmId, int limit) {
        if (!scopes.contains(actorId, tenant, deviceId)) throw ApiError.missing();
        int bounded = Math.max(1, Math.min(limit, 10));
        return jdbc.query("SELECT * FROM video_capture_jobs WHERE tenant_id=? AND video_resource_id=? AND device_id=?"
                + " AND source_kind='ALARM' AND source_id=? ORDER BY created_at DESC,id DESC LIMIT ?",
            (rs, n) -> jobRow(rs), tenant, resourceId, deviceId, alarmId, bounded);
    }

    public Job visibleJob(String actorId, String tenant, String resourceId, String id) {
        Job job = job(tenant, id);
        if (!job.resourceId().equals(resourceId)) throw ApiError.missing();
        if (!scopes.contains(actorId, tenant, job.deviceId())) throw ApiError.missing();
        return job;
    }

    public Image image(String actorId, String tenant, String resourceId, String id) {
        Job job = visibleJob(actorId, tenant, resourceId, id);
        Long permissions = jdbc.queryForObject("SELECT COUNT(DISTINCT permission) FROM effective_user_permissions"
            + " WHERE user_id=? AND permission IN ('video:read','video:play')", Long.class, actorId);
        if (permissions == null || permissions != 2L)
            throw ApiError.forbidden("视频读取或播放权限不足，不能读取抓图");
        if (!"SUCCEEDED".equals(job.state())) throw ApiError.conflict("CAPTURE_NOT_SUCCEEDED", "抓图尚未成功");
        var rows = jdbc.query("SELECT image_cipher,image_nonce,image_sha256,image_media_type,capture_at"
                + " FROM video_capture_jobs WHERE id=? AND tenant_id=?",
            (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                String.valueOf(rs.getObject(5, OffsetDateTime.class))}, id, tenant);
        if (rows.isEmpty()) throw ApiError.missing();
        String[] row = rows.getFirst();
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(cipher.decrypt("video-capture-v1\0" + tenant + "\0" + id,
                row[0], row[1]));
        } catch (Exception unreadable) {
            throw new ApiError(503, "CAPTURE_UNREADABLE", "抓图无法解密");
        }
        if (!sha256(bytes).equals(row[2])) throw new ApiError(503, "CAPTURE_INTEGRITY_FAILED", "抓图完整性校验失败");
        OffsetDateTime captureAt = OffsetDateTime.parse(row[4]);
        return new Image(bytes, row[3], row[2], captureAt);
    }

    // ---------------- worker ----------------

    @Scheduled(fixedDelayString = "${app.video.capture-poll-ms:2000}", scheduler = "videoCaptureScheduler")
    public void poll() {
        if (!pollEnabled()) return;
        recoverInterrupted();
        Claimed claimed = transactions.execute(status -> claim());
        if (claimed == null) return;
        Outcome outcome = extract(claimed);
        transactions.executeWithoutResult(status -> finish(claimed, outcome));
    }

    private boolean pollEnabled() {
        return runtime.videoCaptureWorkerEnabled();
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        if (!pollEnabled()) return;
        var now = time.now();
        jdbc.update("UPDATE video_capture_jobs SET state='FAILED',failure_code='WORKER_INTERRUPTED',"
                + "failure_reason='抓图工作进程中断，未保存图像',finished_at=? "
                + "WHERE state='RUNNING' AND started_at<?",
            now, now.minusMinutes(10));
    }

    private record Claimed(String id, String tenantId, String resourceId, String deviceId, String protocol) { }
    private record Outcome(String state, String code, String reason, byte[] image, OffsetDateTime captureAt,
                           String mediaType) { }

    private Claimed claim() {
        var ids = jdbc.queryForList("SELECT id FROM video_capture_jobs WHERE state='QUEUED' ORDER BY created_at,id LIMIT 1",
            String.class);
        if (ids.isEmpty()) return null;
        String id = ids.getFirst();
        var rows = jdbc.query("SELECT id,tenant_id,video_resource_id,device_id,protocol FROM video_capture_jobs"
                + " WHERE id=? AND state='QUEUED' FOR UPDATE",
            (rs, n) -> new Claimed(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), id);
        if (rows.isEmpty()) return null;
        if (jdbc.update("UPDATE video_capture_jobs SET state='RUNNING',started_at=? WHERE id=? AND state='QUEUED'",
            time.now(), id) != 1) return null;
        return rows.getFirst();
    }

    private Outcome extract(Claimed job) {
        try {
            if (!cipher.ready()) return fail("VIDEO_ENCRYPTION_UNAVAILABLE", "未配置 APP_VIDEO_KEY，不能保存抓图");
            var resource = queries.raw(job.tenantId(), job.resourceId());
            if (resource.archivedAt() != null || !resource.enabled())
                return fail("VIDEO_UNAVAILABLE", "视频资源已归档或停用");
            String protocol = resource.protocol();
            String endpoint;
            boolean hls;
            if ("RTSP".equals(protocol)) {
                if (resource.gatewayCipher() == null)
                    return fail("RTSP_GATEWAY_NOT_CONFIGURED", "RTSP 资源未配置转封装网关");
                endpoint = cipher.decrypt("video-gateway-v1\0" + job.tenantId() + "\0" + job.resourceId(),
                    resource.gatewayCipher(), resource.gatewayNonce());
                hls = true;
            } else {
                if (resource.endpointCipher() == null)
                    return fail("ENDPOINT_NOT_CONFIGURED", "视频端点未配置");
                endpoint = cipher.decrypt(VideoResourceCommands.endpointScope(job.tenantId(), job.resourceId()),
                    resource.endpointCipher(), resource.endpointNonce());
                hls = "HTTPS_HLS".equals(protocol);
            }
            String authorization = basic(resource, job.tenantId());
            if ("HTTPS_JPEG".equals(protocol)) {
                byte[] jpeg = fetch(endpoint, endpoint, authorization, policy.maxPrimaryBytes());
                if (!isJpeg(jpeg)) return fail("UPSTREAM_NOT_JPEG", "上游内容不是JPEG图片");
                return new Outcome("SUCCEEDED", null, null, jpeg, time.now(), "image/jpeg");
            }
            if (!ffmpeg.available())
                return fail("FFMPEG_NOT_CONFIGURED", "未配置 APP_VIDEO_FFMPEG_BINARY，无法从视频流抽取真实帧");
            byte[] input;
            String format;
            if (hls) {
                var source = hlsSource(endpoint, authorization);
                input = source.bytes();
                format = source.format();
            } else {
                input = fetch(endpoint, endpoint, authorization, policy.maxPrimaryBytes());
                format = "mp4";
            }
            byte[] jpeg;
            try {
                jpeg = ffmpeg.extract(input, format);
            } catch (IllegalStateException failure) {
                String code = failure.getMessage() == null ? "FFMPEG_EXTRACTION_FAILED" : failure.getMessage();
                return fail(code, "ffmpeg 未生成可验证的JPEG帧（" + code + "）");
            }
            if (!isJpeg(jpeg)) return fail("FFMPEG_OUTPUT_NOT_JPEG", "抽取结果不是有效JPEG");
            return new Outcome("SUCCEEDED", null, null, jpeg, time.now(), "image/jpeg");
        } catch (ApiError failure) {
            return fail(failure.code, failure.getMessage());
        } catch (RuntimeException failure) {
            return fail("CAPTURE_FAILED", "抓图过程失败；未保存替代图片");
        }
    }

    private Outcome fail(String code, String reason) {
        String state = switch (code) {
            case "FFMPEG_NOT_CONFIGURED", "RTSP_GATEWAY_NOT_CONFIGURED", "ENDPOINT_NOT_CONFIGURED", "VIDEO_ENCRYPTION_UNAVAILABLE", "VIDEO_ALLOWLIST_NOT_CONFIGURED" -> "NOT_CONFIGURED";
            case "HLS_ENCRYPTED_UNSUPPORTED" -> "UNSUPPORTED";
            default -> "FAILED";
        };
        return new Outcome(state, code, reason, null, null, null);
    }

    private void finish(Claimed job, Outcome outcome) {
        var now = time.now();
        if ("SUCCEEDED".equals(outcome.state())) {
            String scope = "video-capture-v1\0" + job.tenantId() + "\0" + job.id();
            var encrypted = cipher.encrypt(scope, java.util.Base64.getEncoder().encodeToString(outcome.image()));
            if (jdbc.update("UPDATE video_capture_jobs SET state='SUCCEEDED',capture_at=?,image_sha256=?,image_media_type=?,"
                    + "image_cipher=?,image_nonce=?,image_bytes=?,finished_at=? WHERE id=? AND state='RUNNING'",
                outcome.captureAt(), sha256(outcome.image()), outcome.mediaType(), encrypted.ciphertext(),
                encrypted.nonce(), (long) outcome.image().length, now, job.id()) != 1)
                throw ApiError.conflict("CAPTURE_STATE_CHANGED", "抓图任务状态已变化");
            return;
        }
        if (jdbc.update("UPDATE video_capture_jobs SET state=?,failure_code=?,failure_reason=?,finished_at=?"
                + " WHERE id=? AND state='RUNNING'",
            outcome.state(), outcome.code(), outcome.reason(), now, job.id()) != 1)
            throw ApiError.conflict("CAPTURE_STATE_CHANGED", "抓图任务状态已变化");
    }

    // ---------------- secure fetching ----------------

    byte[] fetch(String url, String primaryUrl, String authorization, long maximum) {
        var target = upstream.resolve(url);
        String forwardedAuthorization = sameOrigin(primaryUrl, target.uri()) ? authorization : null;
        try (var opened = upstream.open(target, null, forwardedAuthorization, maximum)) {
            return upstream.readBounded(opened, maximum);
        }
    }

    private record HlsSource(byte[] bytes, String format) { }

    private HlsSource hlsSource(String playlistUrl, String authorization) {
        String playlist = new String(fetch(playlistUrl, playlistUrl, authorization, MAX_PLAYLIST_BYTES), StandardCharsets.UTF_8);
        URI base = uri(playlistUrl);
        for (int depth = 0; depth < 2; depth++) {
            if (!playlist.contains("#EXT-X-STREAM-INF")) break;
            String variant = firstUri(playlist, "#EXT-X-STREAM-INF");
            if (variant == null) throw new ApiError(502, "HLS_PLAYLIST_UNREADABLE", "HLS主播放列表缺少变体");
            URI next = base.resolve(variant);
            playlist = new String(fetch(next.toString(), playlistUrl, authorization, MAX_PLAYLIST_BYTES), StandardCharsets.UTF_8);
            base = next;
        }
        if (!playlist.contains("#EXTM3U")) throw new ApiError(502, "HLS_PLAYLIST_UNREADABLE", "上游不是HLS播放列表");
        if (playlist.contains("#EXT-X-KEY") && !playlist.contains("METHOD=NONE"))
            throw new ApiError(502, "HLS_ENCRYPTED_UNSUPPORTED", "HLS 使用加密/DRM，抓图未实现");
        String init = tagUri(playlist, "#EXT-X-MAP");
        String segment = firstUri(playlist, null);
        if (segment == null) throw new ApiError(502, "HLS_PLAYLIST_UNREADABLE", "HLS 播放列表没有媒体片段");
        var out = new ByteArrayOutputStream();
        String format = "mpegts";
        if (init != null) {
            byte[] initBytes = fetch(base.resolve(init).toString(), playlistUrl, authorization, policy.maxSubresourceBytes());
            out.writeBytes(initBytes);
            format = "mp4";
        }
        byte[] segmentBytes = fetch(base.resolve(segment).toString(), playlistUrl, authorization, policy.maxSubresourceBytes());
        if (isMp4Box(segmentBytes)) format = "mp4";
        out.writeBytes(segmentBytes);
        return new HlsSource(out.toByteArray(), format);
    }

    private static URI uri(String value) {
        try { return new URI(value); } catch (Exception invalid) { throw ApiError.bad("HLS地址无效"); }
    }

    private static String firstUri(String playlist, String afterTag) {
        boolean take = afterTag == null;
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (!take) {
                if (line.startsWith(afterTag)) take = true;
                continue;
            }
            if (line.startsWith("#")) continue;
            return line;
        }
        return null;
    }

    private static String tagUri(String playlist, String tag) {
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.startsWith(tag)) continue;
            int marker = line.indexOf("URI=\"");
            if (marker < 0) return null;
            int start = marker + 5;
            int end = line.indexOf('"', start);
            return end < start ? null : line.substring(start, end);
        }
        return null;
    }

    private static boolean isMp4Box(byte[] bytes) {
        return bytes.length > 8 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p';
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 4 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
            && (bytes[bytes.length - 2] & 0xFF) == 0xFF && (bytes[bytes.length - 1] & 0xFF) == 0xD9;
    }

    private String basic(VideoResourceQueries.RawResource resource, String tenant) {
        if (resource.credentialsCipher() == null) return null;
        try {
            String credentials = cipher.decrypt(VideoResourceCommands.credentialsScope(tenant, resource.id()),
                resource.credentialsCipher(), resource.credentialsNonce());
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(credentials);
            String username = node.path("username").asText(null), password = node.path("password").asText(null);
            if (username == null || password == null) return null;
            return "Basic " + java.util.Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        } catch (Exception unreadable) {
            return null;
        }
    }

    private void assertAssociated(String tenant, String resourceId, String deviceId) {
        Long active = jdbc.queryForObject("SELECT COUNT(*) FROM video_associations WHERE tenant_id=?"
            + " AND video_resource_id=? AND device_id=? AND state='ACTIVE'", Long.class, tenant, resourceId, deviceId);
        if (active == null || active == 0) throw ApiError.conflict("VIDEO_ASSOCIATION_REQUIRED", "视频未与该设备建立有效关联");
    }

    private void assertDevice(String actorId, String tenant, String deviceId) {
        var rows = jdbc.query("SELECT archived_at FROM devices WHERE id=? AND tenant_id=?",
            (rs, n) -> rs.getObject(1, OffsetDateTime.class), deviceId, tenant);
        if (rows.isEmpty()) throw ApiError.conflict("DEVICE_NOT_IN_TENANT", "设备不存在或不属于当前单位");
        if (rows.getFirst() != null) throw ApiError.conflict("DEVICE_ARCHIVED", "设备已归档");
        if (!scopes.contains(actorId, tenant, deviceId)) throw ApiError.forbidden("设备不在当前操作者的设备范围内");
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
