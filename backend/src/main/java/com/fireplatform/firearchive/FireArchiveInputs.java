package com.fireplatform.firearchive;

import com.fireplatform.maintenance.MaintenanceInputs;
import com.fireplatform.shared.ApiError;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/** Bounded metadata and opaque, encrypted attachment input validation. */
public final class FireArchiveInputs {
    private FireArchiveInputs() { }

    public static final int MAX_ATTACHMENT_BYTES = 1_048_576;
    private static final int MAX_BASE64 = 1_500_000;
    private static final java.util.regex.Pattern MIME =
        java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}");

    public record CreateBody(String title, String category, String archive_number, String device_id, String place_id,
                             String issued_on, String expires_on, String description,
                             String reason) { }

    public record UpdateBody(String title, String category, String archive_number, String device_id, String place_id,
                             String issued_on, String expires_on, String description, Long expected_revision, String reason) { }

    public record ArchiveBody(Long expected_revision, String reason) { }

    public record AttachmentBody(String filename, String mime_type, String content_base64, Long expected_revision, String reason) { }

    public record Metadata(String title, String category, String archiveNumber, String deviceId, String placeId,
                           LocalDate issuedOn, LocalDate expiresOn, String description) { }

    public record Create(Metadata metadata, String reason) { }

    public record Update(Metadata metadata, long expectedRevision, String reason) { }

    public record Archive(long expectedRevision, String reason) { }

    public record Attachment(String filename, String mimeType, byte[] content, long expectedRevision, String reason) { }

    public static Create create(CreateBody body) {
        if (body == null) throw ApiError.bad("档案请求无效");
        return new Create(metadata(body.title(), body.category(), body.archive_number(), body.device_id(),
            body.place_id(), body.issued_on(), body.expires_on(), body.description()), reason(body.reason()));
    }

    public static Update update(UpdateBody body) {
        if (body == null) throw ApiError.bad("档案请求无效");
        return new Update(metadata(body.title(), body.category(), body.archive_number(), body.device_id(),
            body.place_id(), body.issued_on(), body.expires_on(), body.description()),
            revision(body.expected_revision()), reason(body.reason()));
    }

    public static Archive archive(ArchiveBody body) {
        if (body == null) throw ApiError.bad("归档请求无效");
        return new Archive(revision(body.expected_revision()), reason(body.reason()));
    }

    public static Attachment attachment(AttachmentBody body) {
        if (body == null) throw ApiError.bad("附件请求无效");
        String filename = filename(body.filename());
        String mime = mime(body.mime_type());
        String encoded = body.content_base64();
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_BASE64) throw ApiError.bad("附件编码无效或超过大小限制");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException failure) {
            throw ApiError.bad("附件必须为有效Base64编码");
        }
        if (bytes.length == 0 || bytes.length > MAX_ATTACHMENT_BYTES) throw ApiError.bad("附件必须为1字节至1MiB");
        return new Attachment(filename, mime, bytes, revision(body.expected_revision()),
            reason(body.reason()));
    }

    public static String archiveId(String value) {
        if (!MaintenanceInputs.uuid(value)) throw ApiError.missing();
        return value;
    }

    public static String attachmentId(String value) {
        if (!MaintenanceInputs.uuid(value)) throw ApiError.missing();
        return value;
    }

    public static long revision(Long value) {
        if (value == null) throw ApiError.bad("预期版本不能为空");
        if (value < 0 || value > FireArchiveModels.MAX_SAFE) throw ApiError.bad("预期版本无效");
        return value;
    }

    private static Metadata metadata(String title, String category, String archiveNumber, String deviceId,
                                     String placeId, String issuedOn, String expiresOn, String description) {
        return new Metadata(text(title, 200, "档案标题"), text(category, 120, "档案分类"),
            optional(archiveNumber, 120, "档案编号"),
            optionalId(deviceId, "设备ID"), optionalId(placeId, "位置ID"),
            date(issuedOn, "签发日期"), date(expiresOn, "有效期至"), optional(description, 4000, "档案说明"));
    }

    private static String text(String value, int max, String field) {
        if (value == null) throw ApiError.bad(field + "不能为空");
        if (malformed(value) || controls(value, false)) throw ApiError.bad(field + "包含无效字符");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > max)
            throw ApiError.bad(field + "无效或超过长度限制");
        return normalized;
    }

    private static String optional(String value, int max, String field) {
        if (value == null) return null;
        if (malformed(value) || controls(value, true)) throw ApiError.bad(field + "包含无效字符");
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > max)
            throw ApiError.bad(field + "无效或超过长度限制");
        return normalized;
    }

    private static String optionalId(String value, String field) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 64 || controls(value, false)) throw ApiError.bad(field + "无效");
        return value;
    }

    private static LocalDate date(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException failure) {
            throw ApiError.bad(field + "必须为 YYYY-MM-DD 日期");
        }
    }

    private static String reason(String value) {
        return text(value, 1000, "操作原因");
    }

    private static String filename(String value) {
        if (value == null) throw ApiError.bad("附件文件名不能为空");
        if (malformed(value) || controls(value, false)) throw ApiError.bad("附件文件名包含无效字符");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 200 || normalized.equals(".") || normalized.equals("..")
            || normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0)
            throw ApiError.bad("附件文件名必须为不含路径的1至200字符文本");
        return normalized;
    }

    private static String mime(String value) {
        if (value == null || value.length() > 120 || !MIME.matcher(value).matches())
            throw ApiError.bad("附件类型必须为不含参数的 MIME 类型");
        return value;
    }

    private static boolean controls(String value, boolean multiline) {
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
