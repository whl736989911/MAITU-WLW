package com.fireplatform.controller;

import com.fireplatform.shared.ApiError;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict controller association input; arguments remain declarative JSON validated against the live schema. */
public final class ControllerInputs {
    private ControllerInputs() { }

    public static final Set<String> ACTIONS = Set.of("start", "openGate", "closeGate", "output", "releaser",
        "reset", "io-start", "io-stop");
    public static final List<String> ACTION_ORDER = List.of("start", "openGate", "closeGate", "output", "releaser",
        "reset", "io-start", "io-stop");

    public record CreateBody(String name, String device_id, String action, String service_key, String arguments_json, String reason) { }
    public record UpdateBody(Long expected_revision, String name, String service_key, String arguments_json, String reason) { }
    public record EnabledBody(Long expected_revision, Boolean enabled, String reason) { }
    public record VersionedBody(Long expected_revision, String reason) { }

    public record Create(String name, String deviceId, String action, String serviceKey, String argumentsJson, String reason) { }
    public record Update(long expectedRevision, String name, String serviceKey, String argumentsJson, String reason) { }
    public record Enabled(long expectedRevision, boolean enabled, String reason) { }
    public record Versioned(long expectedRevision, String reason) { }

    public static Create create(CreateBody body) {
        if (body == null) throw ApiError.bad("控制器资源请求无效");
        return new Create(text(body.name(), 200, "控制器名称"), id(body.device_id(), "设备ID"),
            action(body.action()), id(body.service_key(), "服务操作"), arguments(body.arguments_json()), reason(body.reason()));
    }

    public static Update update(UpdateBody body) {
        if (body == null) throw ApiError.bad("控制器资源请求无效");
        return new Update(revision(body.expected_revision()), text(body.name(), 200, "控制器名称"),
            id(body.service_key(), "服务操作"), arguments(body.arguments_json()),
            reason(body.reason()));
    }

    public static Enabled enabled(EnabledBody body) {
        if (body == null || body.enabled() == null) throw ApiError.bad("启用状态必须明确");
        return new Enabled(revision(body.expected_revision()), body.enabled(),
            reason(body.reason()));
    }

    public static Versioned versioned(VersionedBody body) {
        if (body == null) throw ApiError.bad("控制器变更请求无效");
        return new Versioned(revision(body.expected_revision()), reason(body.reason()));
    }

    public static String keyword(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
        if (normalized.length() > 100 || normalized.indexOf('\0') >= 0 || malformed(normalized))
            throw ApiError.bad("搜索关键字无效或过长");
        return normalized;
    }

    static String id(String value, String field) {
        if (value == null || value.isEmpty() || value.length() > 64 || controls(value)) throw ApiError.bad(field + "无效");
        return value;
    }

    static String action(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(normalized)) throw ApiError.bad("控制器动作必须为受支持的联动动作");
        return normalized;
    }

    static String arguments(String value) {
        if (value == null) return "{}";
        String normalized = value.trim();
        if (normalized.isEmpty()) return "{}";
        if (normalized.length() > 16384 || controls(normalized)) throw ApiError.bad("控制器参数无效或过大");
        return normalized;
    }

    static long revision(Long value) {
        if (value == null) throw ApiError.bad("预期版本不能为空");
        if (value < 0 || value > ControllerModels.MAX_SAFE) throw ApiError.bad("预期版本无效");
        return value;
    }

    static String text(String value, int max, String field) {
        if (value == null) throw ApiError.bad(field + "不能为空");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > max || malformed(normalized) || controls(normalized))
            throw ApiError.bad(field + "无效或超过长度限制");
        return normalized;
    }

    static String reason(String value) { return text(value, 1000, "操作原因"); }

    private static boolean controls(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) continue;
            if (c == '\n' || c == '\r' || c == '\t') continue;
            return true;
        }
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
