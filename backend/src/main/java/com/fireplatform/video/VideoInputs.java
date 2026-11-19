package com.fireplatform.video;

import com.fireplatform.shared.ApiError;
import java.util.Locale;
import java.util.Set;

/** Strict normalization for video metadata. Endpoint/credential text is write-only and never echoed. */
public final class VideoInputs {
    private VideoInputs() { }

    public static final Set<String> KINDS = Set.of("CAMERA", "VIDEO_BOX", "NVR");
    public static final Set<String> PROTOCOLS = Set.of("HTTPS_MP4", "HTTPS_HLS", "HTTPS_JPEG", "RTSP");
    public static final Set<String> PURPOSES = Set.of("PLAYBACK", "SNAPSHOT");

    public record CreateResourceBody(String name, String resource_kind, String protocol,
                                     String reason) { }
    public record UpdateResourceBody(Long expected_revision, String name, String resource_kind, String protocol, String reason) { }
    public record EndpointBody(Long expected_revision, String endpoint_url, String username, String password, String reason) { }
    public record ClearEndpointBody(Long expected_revision, String reason) { }
    public record CredentialsBody(Long expected_revision, String username, String password, String reason) { }
    public record GatewayBody(Long expected_revision, String gateway_url, String reason) { }
    public record EnabledBody(Long expected_revision, Boolean enabled, String reason) { }
    public record ArchiveBody(Long expected_revision, String reason) { }
    public record AssociationBody(String video_resource_id, String device_id, String reason) { }
    public record RevokeAssociationBody(Long expected_revision, String revoke_reason,
                                        String reason) { }
    public record MediaSessionBody(String device_id, String purpose) { }

    public record CreateResource(String name, String resourceKind, String protocol, String reason) { }
    public record UpdateResource(long expectedRevision, String name, String resourceKind, String protocol, String reason) { }
    public record Endpoint(String endpointUrl, String username, String password, String reason) { }
    public record Versioned(long expectedRevision, String reason) { }
    public record Credentials(String username, String password, String reason) { }
    public record Gateway(String url, String reason) { }
    public record Enabled(long expectedRevision, boolean enabled, String reason) { }
    public record Association(String videoResourceId, String deviceId, String reason) { }
    public record RevokeAssociation(long expectedRevision, String revokeReason, String reason) { }
    public record MediaSession(String deviceId, String purpose) { }

    public static CreateResource createResource(CreateResourceBody body) {
        if (body == null) throw ApiError.bad("视频资源请求无效");
        return new CreateResource(text(body.name(), 200, "视频资源名称"), kind(body.resource_kind()),
            protocol(body.protocol()), reason(body.reason()));
    }

    public static UpdateResource updateResource(UpdateResourceBody body) {
        if (body == null) throw ApiError.bad("视频资源请求无效");
        return new UpdateResource(revision(body.expected_revision()), text(body.name(), 200, "视频资源名称"),
            kind(body.resource_kind()), protocol(body.protocol()),
            reason(body.reason()));
    }

    public static Endpoint endpoint(EndpointBody body) {
        if (body == null) throw ApiError.bad("视频端点请求无效");
        String url = secretText(body.endpoint_url(), 4096, "视频端点地址");
        String username = blank(body.username()) ? null : text(body.username(), 128, "端点用户名");
        String credential = blank(body.password()) ? null : secretText(body.password(), 256, "端点密码");
        if ((username == null) != (credential == null))
            throw ApiError.bad("端点用户名和密码必须同时提供或同时留空");
        return new Endpoint(url, username, credential, reason(body.reason()));
    }

    public static Versioned versioned(Long expectedRevision, String reason, String field) {
        return new Versioned(revision(expectedRevision), reason(reason));
    }

    public static Credentials credentials(CredentialsBody body) {
        if (body == null) throw ApiError.bad("端点凭据请求无效");
        return new Credentials(text(body.username(), 128, "端点用户名"),
            secretText(body.password(), 256, "端点密码"), reason(body.reason()));
    }

    public static Gateway gateway(GatewayBody body) {
        if (body == null) throw ApiError.bad("网关地址请求无效");
        return new Gateway(secretText(body.gateway_url(), 4096, "RTSP转封装网关地址"),
            reason(body.reason()));
    }

    public static Enabled enabled(EnabledBody body) {
        if (body == null || body.enabled() == null) throw ApiError.bad("启用状态必须明确");
        return new Enabled(revision(body.expected_revision()), body.enabled(),
            reason(body.reason()));
    }

    public static Association association(AssociationBody body) {
        if (body == null) throw ApiError.bad("视频关联请求无效");
        return new Association(id(body.video_resource_id(), "视频资源ID"), id(body.device_id(), "设备ID"),
            reason(body.reason()));
    }

    public static RevokeAssociation revokeAssociation(RevokeAssociationBody body) {
        if (body == null) throw ApiError.bad("关联撤销请求无效");
        return new RevokeAssociation(revision(body.expected_revision()),
            text(body.revoke_reason(), 1000, "撤销原因"), reason(body.reason()));
    }

    public static MediaSession mediaSession(MediaSessionBody body) {
        if (body == null) throw ApiError.bad("媒体会话请求无效");
        String purpose = body.purpose() == null ? "" : body.purpose().trim().toUpperCase(Locale.ROOT);
        if (!PURPOSES.contains(purpose)) throw ApiError.bad("媒体用途必须为 PLAYBACK 或 SNAPSHOT");
        return new MediaSession(id(body.device_id(), "设备ID"), purpose);
    }

    public static long revision(Long value) {
        if (value == null) throw ApiError.bad("预期版本不能为空");
        if (value < 0 || value > VideoModels.MAX_SAFE) throw ApiError.bad("预期版本无效");
        return value;
    }

    public static String id(String value, String field) {
        if (value == null || value.isEmpty() || value.length() > 64 || controls(value)) throw ApiError.bad(field + "无效");
        return value;
    }

    public static String keyword(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
        if (normalized.length() > 100 || normalized.indexOf('\0') >= 0 || malformed(normalized))
            throw ApiError.bad("搜索关键字无效或过长");
        return normalized;
    }

    static String text(String value, int max, String field) {
        if (value == null) throw ApiError.bad(field + "不能为空");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > max || malformed(normalized) || controls(normalized))
            throw ApiError.bad(field + "无效或超过长度限制");
        return normalized;
    }

    static String secretText(String value, int max, String field) {
        if (value == null) throw ApiError.bad(field + "不能为空");
        if (value.isEmpty() || value.length() > max || malformed(value) || controls(value))
            throw ApiError.bad(field + "无效或超过长度限制");
        return value;
    }

    static String reason(String value) { return text(value, 1000, "操作原因"); }

    static String kind(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalized)) throw ApiError.bad("视频资源类型必须为 CAMERA、VIDEO_BOX 或 NVR");
        return normalized;
    }

    static String protocol(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!PROTOCOLS.contains(normalized))
            throw ApiError.bad("视频协议必须为 HTTPS_MP4、HTTPS_HLS、HTTPS_JPEG 或 RTSP");
        return normalized;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static boolean controls(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private static boolean malformed(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(c)) return true;
        }
        return false;
    }
}
