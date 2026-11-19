package com.fireplatform.topology;

import com.fireplatform.shared.ApiError;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/** Strict request normalization; unknown or contradictory evidence is rejected, never repaired. */
public final class TopologyInputs {
    private TopologyInputs() { }

    public static final Set<String> PROTOCOLS = Set.of("LEGACY_TURMASS_MQTT", "HIKVISION", "MANUAL");
    public static final Set<String> CHILD_KINDS = Set.of("DEVICE", "MEASUREMENT");
    public static final Set<String> PROVENANCES = Set.of("OBSERVED", "MANUAL");
    public static final Set<String> OBSERVATION_SOURCES = Set.of("LEGACY_MQTT_EVENT");

    public record CreateGatewayBody(String address, String name, String protocol, String device_id, String reason) { }
    public record UpdateGatewayBody(Long expected_revision, String address, String name, String protocol,
                                    String device_id, String reason) { }
    public record RetireGatewayBody(Long expected_revision, String reason) { }
    public record CreateLinkBody(String gateway_id, String child_kind, String device_id, String channel_id,
                                 String provenance, String observation_source, String observation_event_id,
                                 String observed_at, String reason) { }
    public record VerifyLinkBody(Long expected_revision, String verification_note,
                                 String reason) { }
    public record RevokeLinkBody(Long expected_revision, String revoke_reason,
                                 String reason) { }

    public record CreateGateway(String address, String name, String protocol, String deviceId, String reason) { }
    public record UpdateGateway(long expectedRevision, String address, String name, String protocol,
                                String deviceId, String reason) { }
    public record RetireGateway(long expectedRevision, String reason) { }
    public record CreateLink(String gatewayId, String childKind, String deviceId, String channelId,
                             String provenance, String observationSource, String observationEventId,
                             OffsetDateTime observedAt, String reason) { }
    public record VerifyLink(long expectedRevision, String note, String reason) { }
    public record RevokeLink(long expectedRevision, String revokeReason, String reason) { }

    public static CreateGateway createGateway(CreateGatewayBody body) {
        if (body == null) throw ApiError.bad("网关请求无效");
        String device = blank(body.device_id()) ? null : id(body.device_id(), "网关设备ID");
        return new CreateGateway(address(body.address()), text(body.name(), 200, "网关名称"),
            protocol(body.protocol()), device,
            reason(body.reason()));
    }

    public static UpdateGateway updateGateway(UpdateGatewayBody body) {
        if (body == null) throw ApiError.bad("网关请求无效");
        String device = blank(body.device_id()) ? null : id(body.device_id(), "网关设备ID");
        return new UpdateGateway(revisionValue(body.expected_revision()), address(body.address()),
            text(body.name(), 200, "网关名称"), protocol(body.protocol()), device, reason(body.reason()));
    }

    public static RetireGateway retireGateway(RetireGatewayBody body) {
        if (body == null) throw ApiError.bad("网关请求无效");
        return new RetireGateway(revisionValue(body.expected_revision()), reason(body.reason()));
    }

    public static CreateLink createLink(CreateLinkBody body) {
        if (body == null) throw ApiError.bad("拓扑关系请求无效");
        String kind = upper(body.child_kind());
        if (!CHILD_KINDS.contains(kind)) throw ApiError.bad("关系类型必须为 DEVICE 或 MEASUREMENT");
        String channel = blank(body.channel_id()) ? null : id(body.channel_id(), "测点通道ID");
        if ("DEVICE".equals(kind) && channel != null) throw ApiError.bad("设备关系不能携带测点通道");
        if ("MEASUREMENT".equals(kind) && channel == null) throw ApiError.bad("测点关系必须明确测点通道");
        String provenance = upper(body.provenance());
        if (!PROVENANCES.contains(provenance)) throw ApiError.bad("来源类型必须为 OBSERVED 或 MANUAL");
        String source = blank(body.observation_source()) ? null : upper(body.observation_source());
        if (source != null && !OBSERVATION_SOURCES.contains(source)) throw ApiError.bad("观察来源不受支持");
        String event = blank(body.observation_event_id()) ? null : id(body.observation_event_id(), "观察事件ID");
        OffsetDateTime observed = blank(body.observed_at()) ? null : timestamp(body.observed_at());
        if ("OBSERVED".equals(provenance)) {
            if (source == null || event == null || observed == null)
                throw ApiError.bad("OBSERVED 关系必须提供观察来源、真实事件ID和观察时间");
        } else if (source != null || event != null || observed != null) {
            throw ApiError.bad("MANUAL 关系不能伪造观察证据；如需证据请使用 OBSERVED");
        }
        return new CreateLink(id(body.gateway_id(), "网关ID"), kind, id(body.device_id(), "设备ID"), channel,
            provenance, source, event, observed, reason(body.reason()));
    }

    public static VerifyLink verifyLink(VerifyLinkBody body) {
        if (body == null) throw ApiError.bad("核验请求无效");
        return new VerifyLink(revisionValue(body.expected_revision()),
            text(body.verification_note(), 1000, "核验说明"),
            reason(body.reason()));
    }

    public static RevokeLink revokeLink(RevokeLinkBody body) {
        if (body == null) throw ApiError.bad("撤销请求无效");
        return new RevokeLink(revisionValue(body.expected_revision()),
            text(body.revoke_reason(), 1000, "撤销原因"),
            reason(body.reason()));
    }

    public static String keyword(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
        if (normalized.length() > 100 || normalized.indexOf('\0') >= 0 || malformed(normalized))
            throw ApiError.bad("搜索关键字无效或过长");
        return normalized;
    }

    static String address(String value) {
        String normalized = text(value, 64, "网关地址").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9._:-]+")) throw ApiError.bad("网关地址只允许字母、数字及 . _ : -");
        return normalized;
    }

    static String id(String value, String field) {
        if (value == null || value.isEmpty() || value.length() > 64 || controls(value)) throw ApiError.bad(field + "无效");
        return value;
    }

    public static String revision(Long value) {
        if (value == null) throw ApiError.bad("预期版本不能为空");
        if (value < 0 || value > TopologyModels.MAX_SAFE) throw ApiError.bad("预期版本无效");
        return Long.toString(value);
    }

    static long revisionValue(Long value) {
        if (value == null) throw ApiError.bad("预期版本不能为空");
        if (value < 0 || value > TopologyModels.MAX_SAFE) throw ApiError.bad("预期版本无效");
        return value;
    }

    static String text(String value, int max, String field) {
        if (value == null) throw ApiError.bad(field + "不能为空");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > max || malformed(normalized) || controls(normalized))
            throw ApiError.bad(field + "无效或超过长度限制");
        return normalized;
    }

    static String reason(String value) {
        return text(value, 1000, "操作原因");
    }

    static String protocol(String value) {
        String normalized = upper(value);
        if (!PROTOCOLS.contains(normalized)) throw ApiError.bad("网关协议必须为 LEGACY_TURMASS_MQTT、HIKVISION 或 MANUAL");
        return normalized;
    }

    static OffsetDateTime timestamp(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException failure) {
            throw ApiError.bad("观察时间必须为带时区的ISO-8601时间");
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
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
