package com.fireplatform.notification.officialaccount;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Write-only storage for Official Account AppSecret, callback Token, EncodingAESKey and bound
 * OpenID ciphertext.
 *
 * <p>The AES and MAC subkeys are domain-separated from the notification master key. This is
 * deliberately not the WeChat Pay APIv2 key: merchant payments and Official Account API credentials
 * never share ciphertext, derivation labels or a management page.</p>
 */
@Service
public class WechatOfficialAccountSecretStorage {
    public record Protected(String cipher, String nonce, String masked) { }
    private static final String AAD = "wechat-official-account/v1";
    private static final int MAX_VALUE_BYTES = 4096;
    private final byte[] encryption;
    private final byte[] signing;
    private final SecureRandom random = new SecureRandom();

    public WechatOfficialAccountSecretStorage(Environment env) {
        String raw = firstNonBlank(env.getProperty("app.wechat-official-account-key"), env.getProperty("APP_WECHAT_OFFICIAL_ACCOUNT_KEY"),
            env.getProperty("app.notification-provider-key"), env.getProperty("APP_NOTIFICATION_PROVIDER_KEY"),
            env.getProperty("app.notification-contact-key"), env.getProperty("APP_NOTIFICATION_CONTACT_KEY"));
        byte[] aes = null;
        byte[] mac = null;
        try {
            if (raw != null) {
                byte[] master = Base64.getDecoder().decode(raw);
                if (master.length == 32) {
                    aes = derive(master, "wechat-official-account/aes/v1");
                    mac = derive(master, "wechat-official-account/hmac/v1");
                }
                Arrays.fill(master, (byte) 0);
            }
        } catch (IllegalArgumentException | GeneralSecurityException ignored) {
            aes = null;
            mac = null;
        }
        encryption = aes;
        signing = mac;
    }

    public boolean ready() { return encryption != null && signing != null; }

    public Protected protect(String value) {
        if (value == null) throw new IllegalArgumentException("value");
        if (encryption == null) throw new IllegalStateException("official account secret key unavailable");
        try {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_VALUE_BYTES) throw new IllegalArgumentException("value too long");
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryption, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(bytes);
            return new Protected(Base64.getEncoder().encodeToString(encrypted), Base64.getEncoder().encodeToString(nonce), mask(value));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("official account secret protection unavailable");
        }
    }

    public String reveal(String cipherText, String nonceText) {
        if (encryption == null) throw new IllegalStateException("official account secret key unavailable");
        try {
            byte[] nonce = Base64.getDecoder().decode(nonceText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryption, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("official account secret protection unavailable");
        }
    }

    /** Stable keyed fingerprint for OpenID matching; never the raw OpenID. */
    public String fingerprint(String value) {
        if (signing == null) throw new IllegalStateException("official account secret key unavailable");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signing, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("official account secret protection unavailable");
        }
    }

    public static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] derive(byte[] master, String label) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(master, "HmacSHA256"));
        return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
    }

    private static String mask(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.length() <= 8) return "****";
        return "****" + value.substring(value.length() - 4);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }
}
