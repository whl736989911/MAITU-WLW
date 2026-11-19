package com.fireplatform.video;

import java.time.OffsetDateTime;
import java.util.List;

/** Projections for video resources. No endpoint, signed URL or credential is ever returned. */
public final class VideoModels {
    private VideoModels() { }
    public static final long MAX_SAFE = 9_007_199_254_740_991L;

    public record Capability(String playback, String snapshot, boolean media_ready, String note) { }

    public record Resource(String id, String tenantId, String name, String resourceKind, String protocol,
                           boolean enabled, boolean endpointConfigured, String endpointHost,
                           boolean credentialsConfigured, boolean gatewayConfigured, String gatewayHost,
                           OffsetDateTime archivedAt, long revision,
                           long visibleAssociations, String playback, String snapshot, boolean mediaReady,
                           String note, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record Association(String id, String tenantId, String videoResourceId, String deviceId,
                              String deviceName, String deviceEui, String placeName, String deviceArchived,
                              String state, long revision, OffsetDateTime createdAt, OffsetDateTime revokedAt,
                              String revokeReason) { }

    public record ResourceDetail(Resource resource, List<Association> associations) { }

    public record MediaSession(String id, String resourceId, String deviceId, String purpose, String mediaKind,
                               String playback, String snapshot, OffsetDateTime expiresAt, String mediaPath,
                               String note) { }

    public record IdResult(String id) { }

    public static Capability capability(String protocol, boolean enabled, OffsetDateTime archivedAt,
                                        boolean endpointConfigured, boolean allowlistConfigured,
                                        boolean gatewayConfigured, boolean ffmpegConfigured) {
        if (archivedAt != null) return new Capability("ARCHIVED", "ARCHIVED", false, "资源已归档，媒体入口不可用");
        if (!enabled) return new Capability("DISABLED", "DISABLED", false, "资源未启用");
        if ("RTSP".equals(protocol)) {
            if (!gatewayConfigured) return new Capability("RTSP_GATEWAY_NOT_CONFIGURED", ffmpegConfigured ? "SNAPSHOT_VIA_GATEWAY" : "NOT_CONFIGURED", false,
                "浏览器不能直接播放 RTSP；尚未配置加密的RTSP→HLS网关地址");
            return new Capability("RTSP_VIA_HLS_GATEWAY", ffmpegConfigured ? "SNAPSHOT_VIA_GATEWAY" : "NOT_CONFIGURED", true,
                "RTSP 通过已配置的HLS网关复用同源代理播放；本地抓图依赖可选ffmpeg二进制");
        }
        if (!endpointConfigured) return new Capability("ENDPOINT_NOT_CONFIGURED", "ENDPOINT_NOT_CONFIGURED", false,
            "尚未配置写保护的真实端点；不会生成演示流");
        if (!allowlistConfigured) return new Capability("ALLOWLIST_NOT_CONFIGURED", "ALLOWLIST_NOT_CONFIGURED", false,
            "部署未配置 APP_VIDEO_ALLOWED_HOSTS，媒体代理拒绝连接任何主机");
        String snapshot = ffmpegConfigured ? "JPEG_VIA_FFMPEG" : "NOT_CONFIGURED";
        return switch (protocol) {
            case "HTTPS_MP4" -> new Capability("NATIVE_MP4", snapshot, true,
                ffmpegConfigured ? "HTTPS MP4 原生播放；抓图由可选ffmpeg从同源安全字节抽帧"
                    : "HTTPS MP4 原生播放；未配置 APP_VIDEO_FFMPEG_BINARY，抓图不可用");
            case "HTTPS_HLS" -> new Capability("HLS_JS", snapshot, true,
                ffmpegConfigured ? "HTTPS HLS 通过同源代理与 hls.js 播放；抓图由可选ffmpeg抽帧"
                    : "HTTPS HLS 通过同源代理与 hls.js 播放；未配置 APP_VIDEO_FFMPEG_BINARY，抓图不可用");
            case "HTTPS_JPEG" -> new Capability("SNAPSHOT_ONLY", "JPEG", true,
                "HTTPS JPEG 仅提供抓图；没有连续视频流");
            default -> new Capability("NOT_CONFIGURED", "NOT_CONFIGURED", false, "协议未配置");
        };
    }
}
