package com.fireplatform.deviceservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.shared.DataTransferPayloadCipher;
import com.fireplatform.shared.ApiError;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Encrypts only operator-declared sensitive argument values and registration verification codes.
 * Plain business arguments stay readable for honest history; masked values are never returned.
 */
@Component
public final class DeviceServiceSecrets {
    private static final String ARG_SCOPE = "device-service-args-v1\u0000";
    private static final String REGISTRATION_SCOPE = "device-registration-verification-v1\u0000";
    private static final String VENDOR_PAYLOAD_SCOPE = "device-registration-vendor-v1\u0000";
    private static final String PREFIX = "ENC:v1:";

    private final DataTransferPayloadCipher cipher;
    private final ObjectMapper json;

    public DeviceServiceSecrets(DataTransferPayloadCipher cipher, ObjectMapper json) {
        this.cipher = cipher;
        this.json = json;
    }

    public boolean ready() { return cipher.ready(); }

    public ObjectNode protectArguments(String commandId, ObjectNode canonical, List<ServiceSchema.Field> fields) {
        if (!hasSensitive(canonical, fields)) return canonical;
        requireReady();
        ObjectNode stored = canonical.deepCopy();
        for (ServiceSchema.Field field : fields) {
            if (field.sensitive() && stored.has(field.key()) && stored.get(field.key()).isTextual()) {
                var encrypted = cipher.encryptScope(ARG_SCOPE + commandId, stored.get(field.key()).asText().getBytes(StandardCharsets.UTF_8));
                stored.put(field.key(), PREFIX + encrypted.nonce() + ":" + encrypted.ciphertext());
            }
        }
        return stored;
    }

    public ObjectNode revealArguments(String commandId, JsonNode stored, List<ServiceSchema.Field> fields) {
        if (!stored.isObject()) return json.createObjectNode();
        ObjectNode revealed = stored.deepCopy();
        for (ServiceSchema.Field field : fields) {
            JsonNode value = revealed.get(field.key());
            if (field.sensitive() && value != null && value.isTextual() && value.asText().startsWith(PREFIX)) {
                requireReady();
                String[] parts = value.asText().substring(PREFIX.length()).split(":", 2);
                if (parts.length != 2) throw new ApiError(409, "SERVICE_ARGUMENT_UNREADABLE", "已存参数无法解密，不能重发；请新建指令");
                revealed.put(field.key(), new String(cipher.decryptScopeBytes(ARG_SCOPE + commandId, parts[1], parts[0]), StandardCharsets.UTF_8));
            }
        }
        return revealed;
    }

    public DataTransferPayloadCipher.Encrypted protectVerification(String registrationId, String verificationCode) {
        if (verificationCode == null || verificationCode.isBlank()) return null;
        requireReady();
        return cipher.encryptScope(REGISTRATION_SCOPE + registrationId, verificationCode.getBytes(StandardCharsets.UTF_8));
    }

    public String revealVerification(String registrationId, String ciphertext, String nonce) {
        if (ciphertext == null && nonce == null) return null;
        if (ciphertext == null || ciphertext.isBlank() || nonce == null || nonce.isBlank())
            throw new ApiError(409, "REGISTRATION_SECRET_UNREADABLE", "校验码密文或随机数缺失");
        requireReady();
        return new String(cipher.decryptScopeBytes(REGISTRATION_SCOPE + registrationId, ciphertext, nonce), StandardCharsets.UTF_8);
    }

    /** Vendor registration payload can contain provisioning material, so the whole object is encrypted. */
    public String protectVendorPayload(String registrationId, JsonNode payload) {
        if (payload == null || payload.isNull()) return null;
        if (!payload.isObject()) throw ApiError.bad("厂商登记字段必须是JSON对象");
        requireReady();
        var encrypted = cipher.encryptScope(VENDOR_PAYLOAD_SCOPE + registrationId,
            payload.toString().getBytes(StandardCharsets.UTF_8));
        return PREFIX + encrypted.nonce() + ":" + encrypted.ciphertext();
    }

    public JsonNode revealVendorPayload(String registrationId, String stored) {
        if (stored == null || stored.isBlank()) return null;
        if (!stored.startsWith(PREFIX)) throw new ApiError(409, "REGISTRATION_VENDOR_PAYLOAD_UNREADABLE", "厂商登记字段存储格式未确认");
        requireReady();
        String[] parts = stored.substring(PREFIX.length()).split(":", 2);
        if (parts.length != 2) throw new ApiError(409, "REGISTRATION_VENDOR_PAYLOAD_UNREADABLE", "厂商登记字段无法解密");
        try {
            JsonNode parsed = json.readTree(new String(cipher.decryptScopeBytes(VENDOR_PAYLOAD_SCOPE + registrationId, parts[1], parts[0]), StandardCharsets.UTF_8));
            if (parsed == null || !parsed.isObject()) throw ApiError.conflict("REGISTRATION_VENDOR_PAYLOAD_UNREADABLE", "厂商登记字段不是JSON对象");
            return parsed;
        } catch (ApiError failure) {
            throw failure;
        } catch (Exception invalid) {
            throw ApiError.conflict("REGISTRATION_VENDOR_PAYLOAD_UNREADABLE", "厂商登记字段无法解析");
        }
    }

    public static String mask(String value) {
        return value == null || value.isBlank() ? null : "****" + (value.length() <= 4 ? "" : value.substring(value.length() - 2));
    }

    private static boolean hasSensitive(ObjectNode canonical, List<ServiceSchema.Field> fields) {
        return fields.stream().anyMatch(field -> field.sensitive() && canonical.has(field.key()));
    }

    private void requireReady() {
        if (!cipher.ready()) throw new ApiError(503, "SERVICE_SECRET_STORAGE_UNAVAILABLE", "未配置应用加密主密钥，不能保存或读取敏感参数");
    }
}
