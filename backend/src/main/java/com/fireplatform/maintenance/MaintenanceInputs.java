package com.fireplatform.maintenance;

import com.fireplatform.shared.ApiError;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/** Strict request normalization for local maintenance records. Text is never repaired or guessed. */
public final class MaintenanceInputs {
    private MaintenanceInputs() { }

    public static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    public static final Set<String> RESULTS = Set.of("REPAIRED", "NOT_REPAIRED", "REPLACED", "OTHER");

    public record CreateBody(String fault_event_id, String device_id, String title, String description,
                             String priority, String due_at, String reason) { }

    public record AssignBody(Long expected_revision, String assignee_user_id, String reason) { }

    public record WorkLogBody(Long expected_revision, String note, String result, String replacement_device_id, String reason) { }

    public record ReviewBody(Long expected_revision, Boolean pass, String note, String reason) { }

    public record VersionedBody(Long expected_revision, String note, String reason) { }

    public record Create(String faultEventId, String deviceId, String title, String description, String priority,
                         OffsetDateTime dueAt, String reason) { }

    public record Assign(long expectedRevision, String assigneeUserId, String reason) { }

    public record WorkLog(long expectedRevision, String note, String result, String replacementDeviceId, String reason) { }

    public record Review(long expectedRevision, boolean pass, String note, String reason) { }

    public record Versioned(long expectedRevision, String note, String reason) { }

    public static Create create(CreateBody body) {
        if (body == null) throw ApiError.bad("工单请求无效");
        String fault = blank(body.fault_event_id()) ? null : id(body.fault_event_id(), "故障事件ID");
        String device = deviceId(body.device_id());
        String title = text(body.title(), 200, "工单标题", false);
        String description = textAllowEmpty(body.description(), 2000, "工单说明", true);
        String priority = blank(body.priority()) ? null : priority(body.priority());
        OffsetDateTime due = blank(body.due_at()) ? null : timestamp(body.due_at(), "计划完成时间");
        return new Create(fault, device, title, description, priority, due, text(body.reason(), 1000, "变更原因", false));
    }

    public static Assign assign(AssignBody body) {
        if (body == null) throw ApiError.bad("指派请求无效");
        return new Assign(revision(body.expected_revision()), id(body.assignee_user_id(), "负责人ID"), text(body.reason(), 1000, "变更原因", false));
    }

    public static WorkLog workLog(WorkLogBody body) {
        if (body == null) throw ApiError.bad("维护记录请求无效");
        String result = body.result() == null ? "" : body.result().trim().toUpperCase(Locale.ROOT);
        if (!RESULTS.contains(result)) throw ApiError.bad("维护结果必须为 REPAIRED、NOT_REPAIRED、REPLACED 或 OTHER");
        String replacement = blank(body.replacement_device_id()) ? null : deviceId(body.replacement_device_id());
        if ("REPLACED".equals(result) && replacement == null) throw ApiError.bad("已更换设备必须记录更换后的设备ID");
        if (!"REPLACED".equals(result) && replacement != null) throw ApiError.bad("只有维护结果为 REPLACED 时可记录更换设备");
        return new WorkLog(revision(body.expected_revision()), text(body.note(), 2000, "维护记录", true),
            result, replacement, text(body.reason(), 1000, "变更原因", false));
    }

    public static Review review(ReviewBody body) {
        if (body == null) throw ApiError.bad("复核请求无效");
        if (body.pass() == null) throw ApiError.bad("复核结果必须明确为 PASS 或 RETURN");
        return new Review(revision(body.expected_revision()), body.pass(),
            body.note() == null ? "" : textAllowEmpty(body.note(), 1000, "复核说明", true), text(body.reason(), 1000, "变更原因", false));
    }

    public static Versioned versioned(VersionedBody body) {
        if (body == null) throw ApiError.bad("工单变更请求无效");
        return new Versioned(revision(body.expected_revision()),
            body.note() == null ? "" : textAllowEmpty(body.note(), 1000, "变更说明", true), text(body.reason(), 1000, "变更原因", false));
    }

    public static boolean uuid(String value) {
        return value != null && value.length() == 36
            && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    public static String orderId(String value) {
        if (!uuid(value)) throw ApiError.missing();
        return value;
    }

    public static String deviceId(String value) {
        if (value == null || value.isEmpty() || value.length() > 64 || controls(value)) throw ApiError.bad("设备ID无效");
        return value;
    }

    public static String id(String value, String field) {
        if (value == null || value.isEmpty() || value.length() > 64 || controls(value)) throw ApiError.bad(field + "无效");
        return value;
    }

    public static String keyword(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
        if (normalized.length() > 100 || normalized.indexOf('\0') >= 0 || malformed(normalized)) throw ApiError.bad("搜索关键字无效或过长");
        return normalized;
    }

    public static long revision(Long value) {
        if (value == null) throw ApiError.bad("预期版本不能为空");
        if (value < 0 || value > MaintenanceModels.MAX_SAFE) throw ApiError.bad("预期版本无效");
        return value;
    }

    public static OffsetDateTime timestamp(String value, String field) {
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException failure) {
            throw ApiError.bad(field + "必须为带时区的ISO-8601时间");
        }
    }

    public static LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException failure) {
            throw ApiError.bad(field + "必须为 YYYY-MM-DD 日期");
        }
    }

    private static String priority(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!PRIORITIES.contains(normalized)) throw ApiError.bad("优先级必须为 LOW、NORMAL、HIGH 或 URGENT");
        return normalized;
    }

    static String text(String value, int max, String field, boolean multiline) {
        if (value == null) throw ApiError.bad(field + "不能为空");
        if (malformed(value) || invalidControls(value, multiline))
            throw ApiError.bad(field + "无效或超过长度限制");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > max)
            throw ApiError.bad(field + "无效或超过长度限制");
        return normalized;
    }

    static String textAllowEmpty(String value, int max, String field, boolean multiline) {
        if (value == null) throw ApiError.bad(field + "不能为空");
        if (malformed(value) || invalidControls(value, multiline))
            throw ApiError.bad(field + "无效或超过长度限制");
        String normalized = value.trim();
        if (normalized.length() > max)
            throw ApiError.bad(field + "无效或超过长度限制");
        return normalized;
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean controls(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private static boolean invalidControls(String value, boolean multiline) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) continue;
            if (multiline && (c == '\n' || c == '\r' || c == '\t')) continue;
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
