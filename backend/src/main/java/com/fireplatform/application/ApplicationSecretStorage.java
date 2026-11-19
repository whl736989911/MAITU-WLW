package com.fireplatform.application;

import com.fireplatform.shared.ApiError;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Independent AES-GCM storage for the outbound endpoint URL and its write-only credential. The key is derived
 * from {@code app.application-output-key} (base64, 32 bytes) and is unrelated to any other platform key, so a
 * rotation of provider credentials never exposes a destination or a forward secret. Without the key no
 * configuration can be stored, revealed or sent.
 */
@Service
public final class ApplicationSecretStorage {
    public record Protected(String cipher, String nonce) { }

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public ApplicationSecretStorage(Environment environment) {
        byte[] decoded = null;
        try {
            String value = environment.getProperty("app.application-output-key",
                environment.getProperty("APP_APPLICATION_OUTPUT_KEY"));
            if (value != null && !value.isBlank()) {
                byte[] candidate = Base64.getDecoder().decode(value);
                if (candidate.length == 32) decoded = candidate;
                else Arrays.fill(candidate, (byte) 0);
            }
        } catch (IllegalArgumentException ignored) {
            decoded = null;
        }
        this.key = decoded;
    }

    public boolean ready() { return key != null; }

    public Protected protect(String scope, String plaintext) {
        requireReady();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            return new Protected(Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8))),
                Base64.getEncoder().encodeToString(nonce));
        } catch (GeneralSecurityException failure) {
            throw unavailable();
        }
    }

    /** Returns the plaintext only to the delivery transport; never to an API projection. */
    public String reveal(String scope, String ciphertext, String nonceText) {
        requireReady();
        if (ciphertext == null || nonceText == null) throw unavailable();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, Base64.getDecoder().decode(nonceText)));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) return null;
        return secret.length() <= 8 ? "****" : "****" + secret.substring(secret.length() - 4);
    }

    public static String urlScope(String tenant, String output) { return "application-output-url/v1\0" + tenant + "\0" + output; }
    public static String credentialScope(String tenant, String output) { return "application-output-credential/v1\0" + tenant + "\0" + output; }
    public static String mqttTopicScope(String tenant, String output) { return "application-output-mqtt-topic/v1\0" + tenant + "\0" + output; }
    public static String mqttUsernameScope(String tenant, String output) { return "application-output-mqtt-username/v1\0" + tenant + "\0" + output; }
    public static String mqttPasswordScope(String tenant, String output) { return "application-output-mqtt-password/v1\0" + tenant + "\0" + output; }

    private void requireReady() { if (key == null) throw unavailable(); }

    private static ApiError unavailable() {
        return new ApiError(503, "APPLICATION_OUTPUT_ENCRYPTION_UNAVAILABLE",
            "未配置 APP_APPLICATION_OUTPUT_KEY，输出地址与凭据不可保存或读取");
    }
}
