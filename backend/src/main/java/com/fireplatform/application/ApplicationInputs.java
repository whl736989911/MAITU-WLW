package com.fireplatform.application;

import com.fireplatform.shared.ApiError;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded request validation for open applications, authorizations and outputs. */
public final class ApplicationInputs {
    private ApplicationInputs() { }

    public static final Set<String> MODES = Set.of("NORMALIZED", "SINGLE_DEVICE_RAW");
    public static final Set<String> PROTOCOLS = Set.of("HTTPS", "MQTT_TLS_3_1_1");
    public static final Set<String> SCOPE_MODES = Set.of("ALL_TENANT", "DEVICE_SUBSET");
    public static final Set<String> AUTH_SCHEMES = Set.of("NONE", "BEARER", "BASIC", "HEADER");
    public static final Set<String> REFERENCE_KINDS = Set.of("FORMAL_VERSION", "LEGACY_PRODUCT", "VENDOR_MODEL");
    private static final Set<String> RESERVED_HEADERS = Set.of("host", "content-length", "transfer-encoding",
        "connection", "content-type", "user-agent", "accept", "x-request-id", "authorization");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,62}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final Pattern PLATFORM = Pattern.compile("[a-z0-9][a-z0-9_]{0,63}");

    public record CreateBody(String name, String description, String reason) { }
    public record UpdateBody(String name, String description, Long expected_revision,
                             String reason) { }
    public record ArchiveBody(Long expected_revision, String reason) { }
    public record GrantBody(String reference_kind, String product_id, String product_version_id,
                            String source_platform, String legacy_product_id, String vendor_model_id,
                            String driver_key, String scope_mode, List<String> device_ids, String reason) { }
    public record RevokeBody(Long expected_revision, String reason) { }
    public record CreateOutputBody(String name, String mode, String protocol, String endpoint_url, String auth_scheme,
                                   String auth_header_name, String credential, String mqtt_topic,
                                   String mqtt_username, String mqtt_password, Long timeout_ms, Boolean enabled, String reason) { }
    public record UpdateOutputBody(String name, String protocol, String endpoint_url, String auth_scheme,
                                   String auth_header_name, String credential, String mqtt_topic,
                                   String mqtt_username, String mqtt_password, Long timeout_ms, Boolean enabled,
                                   Long expected_revision, String reason) { }

    public record Create(String name, String description, String reason) { }
    public record Update(String name, String description, long expectedRevision, String reason) { }
    public record Archive(long expectedRevision, String reason) { }
    public record Grant(String referenceKind, String productId, String productVersionId, String sourcePlatform,
                        String legacyProductId, String vendorModelId, String driverKey,
                        String scopeMode, List<String> deviceIds, String reason) { }
    public record Revoke(long expectedRevision, String reason) { }
    public record OutputSpec(String name, String mode, String protocol, String endpointUrl, String authScheme,
                             String authHeaderName, String credential, String mqttTopic, String mqttUsername,
                             String mqttPassword, int timeoutMs, boolean enabled) { }
    public record CreateOutput(OutputSpec spec, String reason) { }
    public record UpdateOutput(OutputSpec spec, long expectedRevision, String reason) { }

    public static Create create(CreateBody body) {
        if (body == null) throw ApiError.bad("应用请求无效");
        return new Create(name(body.name()), description(body.description()),
            reason(body.reason()));
    }

    public static Update update(UpdateBody body) {
        if (body == null) throw ApiError.bad("应用请求无效");
        return new Update(name(body.name()), description(body.description()), revision(body.expected_revision()),
            reason(body.reason()));
    }

    public static Archive archive(ArchiveBody body) {
        if (body == null) throw ApiError.bad("归档请求无效");
        return new Archive(revision(body.expected_revision()), reason(body.reason()));
    }

    public static Grant grant(GrantBody body) {
        if (body == null) throw ApiError.bad("授权请求无效");
        String kind = referenceKind(body.reference_kind());
        String scope = body.scope_mode() == null ? null : body.scope_mode().trim().toUpperCase(Locale.ROOT);
        if (scope == null || !SCOPE_MODES.contains(scope))
            throw ApiError.bad("授权范围必须为ALL_TENANT或DEVICE_SUBSET");
        var devices = new LinkedHashSet<String>();
        if (body.device_ids() != null) {
            if (body.device_ids().size() > com.fireplatform.shared.Limits.MAX_SCOPE_DEVICES)
                throw ApiError.bad("设备快照数量超过上限");
            for (String id : body.device_ids()) devices.add(id(id, "设备ID"));
        }
        if ("DEVICE_SUBSET".equals(scope) && devices.isEmpty())
            throw ApiError.bad("部分设备授权必须明确选择设备");
        if ("ALL_TENANT".equals(scope) && !devices.isEmpty())
            throw ApiError.bad("全单位授权不接受设备快照");
        String productId = optionalId(body.product_id(), "产品ID");
        String versionId = optionalId(body.product_version_id(), "产品版本ID");
        String platform = optionalPlatform(body.source_platform());
        String legacyProduct = optionalId(body.legacy_product_id(), "旧产品ID");
        String vendorModel = optionalModel(body.vendor_model_id());
        String driver = optionalId(body.driver_key(), "适配器标识");
        switch (kind) {
            case "FORMAL_VERSION" -> {
                if (productId == null || versionId == null) throw ApiError.bad("正式版本授权必须提供产品与已发布版本");
                if (platform != null || legacyProduct != null || vendorModel != null || driver != null)
                    throw ApiError.bad("正式版本授权不能包含来源产品标识字段");
            }
            case "LEGACY_PRODUCT" -> {
                if (platform == null || legacyProduct == null)
                    throw ApiError.bad("旧产品授权必须提供来源平台与旧产品ID");
                if (productId != null || versionId != null || vendorModel != null || driver != null)
                    throw ApiError.bad("旧产品授权不能包含其他身份字段");
            }
            case "VENDOR_MODEL" -> {
                if (platform == null || vendorModel == null || driver == null)
                    throw ApiError.bad("厂商型号授权必须提供来源平台、厂商型号ID与来源适配器");
                if (productId != null || versionId != null || legacyProduct != null)
                    throw ApiError.bad("厂商型号授权不能包含正式版本或旧产品字段");
            }
            default -> throw ApiError.bad("授权身份类型无效");
        }
        return new Grant(kind, productId, versionId, platform, legacyProduct, vendorModel, driver,
            scope, List.copyOf(devices), reason(body.reason()));
    }

    public static Revoke revoke(RevokeBody body) {
        if (body == null) throw ApiError.bad("撤销请求无效");
        return new Revoke(revision(body.expected_revision()), reason(body.reason()));
    }

    public static CreateOutput createOutput(CreateOutputBody body) {
        if (body == null) throw ApiError.bad("输出配置请求无效");
        String mode = mode(body.mode());
        String protocol = protocol(body.protocol(), "HTTPS");
        var spec = outputSpec(body.name(), mode, protocol, body.endpoint_url(), body.auth_scheme(),
            body.auth_header_name(), body.credential(), body.mqtt_topic(), body.mqtt_username(),
            body.mqtt_password(), body.timeout_ms(), body.enabled(), "NONE");
        return new CreateOutput(spec, reason(body.reason()));
    }

    public static UpdateOutput updateOutput(UpdateOutputBody body, String fixedMode, String fixedProtocol) {
        if (body == null) throw ApiError.bad("输出配置请求无效");
        if (body.protocol() != null && !body.protocol().isBlank()
            && !protocol(body.protocol(), fixedProtocol).equals(fixedProtocol))
            throw ApiError.bad("输出协议创建后不可修改；如需切换请新建输出配置");
        var spec = outputSpec(body.name(), fixedMode, fixedProtocol, body.endpoint_url(), body.auth_scheme(),
            body.auth_header_name(), body.credential(), body.mqtt_topic(), body.mqtt_username(),
            body.mqtt_password(), body.timeout_ms(), body.enabled(), null);
        return new UpdateOutput(spec, revision(body.expected_revision()),
            reason(body.reason()));
    }

    private static OutputSpec outputSpec(String name, String mode, String protocol, String endpointUrl,
                                         String authSchemeValue, String authHeaderName, String credential,
                                         String mqttTopic, String mqttUsername, String mqttPassword,
                                         Long timeoutMs, Boolean enabled, String authFallback) {
        boolean mqtt = "MQTT_TLS_3_1_1".equals(protocol);
        String scheme = authScheme(authSchemeValue, mqtt ? "NONE" : authFallback);
        if (mqtt && !"NONE".equals(scheme))
            throw ApiError.bad("MQTT输出不能使用HTTP认证方式；请使用MQTT用户名与密码");
        if (mqtt && ((authHeaderName != null && !authHeaderName.isBlank())
            || (credential != null && !credential.isEmpty())))
            throw ApiError.bad("MQTT输出不能提交HTTP请求头或HTTP凭据");
        String httpHeader = mqtt ? null : headerName(authHeaderName);
        String httpCredential = mqtt ? null : credential(credential);
        if (!mqtt && (mqttTopic != null || mqttUsername != null || mqttPassword != null))
            throw ApiError.bad("HTTPS输出不能提交MQTT主题或MQTT凭据");
        return new OutputSpec(name(name), mode, protocol, url(endpointUrl), scheme, httpHeader, httpCredential,
            mqtt ? mqttTopic(mqttTopic) : null, mqtt ? mqttUsername(mqttUsername) : null,
            mqtt ? mqttPassword(mqttPassword) : null, timeout(timeoutMs), Boolean.TRUE.equals(enabled));
    }

    public static String applicationId(String value) {
        if (value == null || !ID.matcher(value).matches()) throw ApiError.missing();
        return value;
    }

    public static String outputId(String value) {
        if (value == null || !ID.matcher(value).matches()) throw ApiError.missing();
        return value;
    }

    public static String deliveryId(String value) {
        if (value == null || !ID.matcher(value).matches()) throw ApiError.missing();
        return value;
    }

    public static String referenceKind(String value) {
        if (value == null) throw ApiError.bad("授权身份类型不能为空");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!REFERENCE_KINDS.contains(normalized)) throw ApiError.bad("授权身份类型必须为FORMAL_VERSION、LEGACY_PRODUCT或VENDOR_MODEL");
        return normalized;
    }

    public static long revision(Long value) {
        if (value == null) throw ApiError.bad("预期版本不能为空");
        if (value < 0 || value > ApplicationModels.MAX_SAFE) throw ApiError.bad("预期版本无效");
        return value;
    }

    public static String reason(String value) {
        if (value == null) throw ApiError.bad("变更原因不能为空");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 1000 || controls(normalized))
            throw ApiError.bad("变更原因无效或超过1000字符");
        return normalized;
    }

    public static String name(String value) {
        if (value == null) throw ApiError.bad("名称不能为空");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 200 || controls(normalized))
            throw ApiError.bad("名称无效或超过200字符");
        return normalized;
    }

    public static String description(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.length() > 2000 || controls(normalized)) throw ApiError.bad("说明无效或超过2000字符");
        return normalized;
    }

    public static String keyword(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.length() > com.fireplatform.shared.Limits.MAX_KEYWORD_LENGTH || controls(normalized))
            throw ApiError.bad("关键字无效或超过长度限制");
        return normalized;
    }

    public static String mode(String value) {
        if (value == null) throw ApiError.bad("输出模式不能为空");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(normalized)) throw ApiError.bad("输出模式必须为NORMALIZED或SINGLE_DEVICE_RAW");
        return normalized;
    }

    public static String protocol(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!PROTOCOLS.contains(normalized)) throw ApiError.bad("输出协议必须为HTTPS或MQTT_TLS_3_1_1");
        return normalized;
    }

    private static String mqttTopic(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return com.fireplatform.transport.ApplicationMqttTransport.topic(value);
        } catch (IllegalArgumentException invalid) {
            throw ApiError.bad("MQTT主题无效：必须是固定主题，不能包含通配符、空层级、控制字符或以$开头，且不超过255字节");
        }
    }

    private static String mqttUsername(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.length() > 256 || controls(value) || value.indexOf('\0') >= 0)
            throw ApiError.bad("MQTT用户名无效或超过256字符");
        return value;
    }

    private static String mqttPassword(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.length() > 2048 || controls(value) || value.indexOf('\0') >= 0)
            throw ApiError.bad("MQTT密码无效或超过2048字符");
        return value;
    }

    private static String authScheme(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!AUTH_SCHEMES.contains(normalized)) throw ApiError.bad("认证方式无效");
        return normalized;
    }

    private static String headerName(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (!HEADER_NAME.matcher(normalized).matches() || RESERVED_HEADERS.contains(normalized.toLowerCase(Locale.ROOT)))
            throw ApiError.bad("认证请求头名称无效或属于保留头");
        return normalized;
    }

    private static String credential(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.length() > 2048 || controls(value)) throw ApiError.bad("凭据无效或超过2048字符");
        return value;
    }

    private static String url(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static int timeout(Long value) {
        if (value == null) return 5000;
        if (value < 1000 || value > 30000) throw ApiError.bad("超时必须为1000至30000毫秒");
        return Math.toIntExact(value);
    }

    private static String id(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 64) throw ApiError.bad(field + "无效");
        String normalized = value.trim();
        if (!ID.matcher(normalized).matches()) throw ApiError.bad(field + "无效");
        return normalized;
    }

    private static String optionalId(String value, String field) {
        if (value == null || value.isBlank()) return null;
        return id(value, field);
    }

    private static String optionalPlatform(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!PLATFORM.matcher(normalized).matches()) throw ApiError.bad("来源平台标识无效");
        return normalized;
    }

    private static String optionalModel(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > 200 || controls(normalized)) throw ApiError.bad("厂商型号标识无效或超过200字符");
        return normalized;
    }

    private static boolean controls(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) return true;
        }
        return false;
    }
}
