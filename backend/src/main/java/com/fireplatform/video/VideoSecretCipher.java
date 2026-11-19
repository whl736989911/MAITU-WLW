package com.fireplatform.video;

import com.fireplatform.shared.ApiError;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * AEAD storage for video endpoints, signed URLs and credentials. Values are never returned by an API:
 * the UI only learns whether a secret is configured and the non-secret host.
 */
@Service
public final class VideoSecretCipher {
    public record Encrypted(String ciphertext, String nonce) { }
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public VideoSecretCipher(Environment environment) {
        byte[] decoded = null;
        try {
            String value = environment.getProperty("app.video.key");
            if (value == null || value.isBlank()) value = environment.getProperty("APP_VIDEO_KEY");
            if (value != null && !value.isBlank()) {
                decoded = Base64.getDecoder().decode(value.trim());
                if (decoded.length != 32) decoded = null;
            }
        } catch (IllegalArgumentException ignored) {
            decoded = null;
        }
        this.key = decoded;
    }

    public boolean ready() { return key != null; }

    public Encrypted encrypt(String scope, String plaintext) {
        requireKey();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(Base64.getEncoder().encodeToString(encrypted), Base64.getEncoder().encodeToString(nonce));
        } catch (GeneralSecurityException failure) {
            throw new ApiError(503, "VIDEO_ENCRYPTION_UNAVAILABLE", "视频密钥不可用，未保存内容");
        }
    }

    public String decrypt(String scope, String ciphertext, String nonceText) {
        requireKey();
        if (ciphertext == null || nonceText == null)
            throw new ApiError(503, "VIDEO_ENDPOINT_UNAVAILABLE", "视频端点未配置");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, Base64.getDecoder().decode(nonceText)));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new ApiError(503, "VIDEO_ENDPOINT_UNAVAILABLE", "视频密钥不可用或端点数据无法解密");
        }
    }

    private void requireKey() {
        if (key == null) throw new ApiError(503, "VIDEO_ENCRYPTION_UNAVAILABLE",
            "未配置 APP_VIDEO_KEY（32字节Base64），视频端点与凭据不可保存或读取");
    }
}
