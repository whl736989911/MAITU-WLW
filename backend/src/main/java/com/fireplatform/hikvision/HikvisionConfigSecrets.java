package com.fireplatform.hikvision;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Isolated AES-GCM protection for Hikvision credentials; never shares another domain's master key. */
@Service
public final class HikvisionConfigSecrets {
    public record Protected(String cipher, String nonce, String masked) { }
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    public HikvisionConfigSecrets(Environment env) {
        byte[] derived = null;
        try {
            String raw = env.getProperty("app.hikvision-config-key", env.getProperty("APP_HIKVISION_CONFIG_KEY"));
            if (raw != null && !raw.isBlank()) {
                byte[] master = Base64.getDecoder().decode(raw);
                if (master.length == 32) {
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(master, "HmacSHA256"));
                    derived = mac.doFinal("hikvision-config/aes/v1".getBytes(StandardCharsets.UTF_8));
                }
                java.util.Arrays.fill(master, (byte) 0);
            }
        } catch (IllegalArgumentException | GeneralSecurityException ignored) { derived = null; }
        key = derived;
    }
    public boolean ready() { return key != null; }
    public Protected protect(String value) {
        if (!ready()) throw new IllegalStateException("HIKVISION_CONFIG_KEY_UNAVAILABLE");
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD("hikvision-config/v1".getBytes(StandardCharsets.UTF_8));
            return new Protected(Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8))),
                Base64.getEncoder().encodeToString(nonce), mask(value));
        } catch (GeneralSecurityException error) { throw new IllegalStateException("HIKVISION_SECRET_PROTECTION_UNAVAILABLE"); }
    }
    public String reveal(String encrypted, String nonce) {
        if (!ready()) throw new IllegalStateException("HIKVISION_CONFIG_KEY_UNAVAILABLE");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Base64.getDecoder().decode(nonce)));
            cipher.updateAAD("hikvision-config/v1".getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("HIKVISION_SECRET_DECRYPTION_FAILED"); }
    }
    public String fingerprint(String value) {
        if (!ready()) throw new IllegalStateException("HIKVISION_CONFIG_KEY_UNAVAILABLE");
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) { throw new IllegalStateException("HIKVISION_FINGERPRINT_UNAVAILABLE"); }
    }
    public static String mask(String value) { return value.length() <= 8 ? "****" : "****" + value.substring(value.length() - 4); }
}
