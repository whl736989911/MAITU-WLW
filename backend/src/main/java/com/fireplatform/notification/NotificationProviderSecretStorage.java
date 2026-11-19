package com.fireplatform.notification;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Write-only provider secret storage; uses an independent AES-GCM key derived from the configured master key. */
@Service
public class NotificationProviderSecretStorage {
    public record Protected(String cipher, String nonce, String masked) { }
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    public NotificationProviderSecretStorage(Environment env) {
        String raw = env.getProperty("app.notification-provider-key", env.getProperty("APP_NOTIFICATION_PROVIDER_KEY",
            env.getProperty("app.notification-contact-key", env.getProperty("APP_NOTIFICATION_CONTACT_KEY"))));
        byte[] derived = null;
        try {
            if (raw != null && !raw.isBlank()) {
                byte[] master = Base64.getDecoder().decode(raw);
                if (master.length == 32) {
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(master, "HmacSHA256"));
                    derived = mac.doFinal("notification-provider/aes/v1".getBytes(StandardCharsets.UTF_8));
                }
                java.util.Arrays.fill(master, (byte) 0);
            }
        } catch (IllegalArgumentException | GeneralSecurityException ignored) { derived = null; }
        key = derived;
    }
    public boolean ready() { return key != null; }
    public Protected protect(String value) {
        if (key == null) throw new IllegalStateException("provider secret key unavailable");
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD("notification-provider/v1".getBytes(StandardCharsets.UTF_8));
            String masked = value.length() <= 8 ? "****" : "****" + value.substring(value.length() - 4);
            return new Protected(Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8))),
                Base64.getEncoder().encodeToString(nonce), masked);
        } catch (GeneralSecurityException error) { throw new IllegalStateException("provider secret protection unavailable"); }
    }
    public String reveal(String cipherText, String nonceText) {
        if (key == null) throw new IllegalStateException("provider secret key unavailable");
        try {
            byte[] nonce = Base64.getDecoder().decode(nonceText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD("notification-provider/v1".getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("provider secret protection unavailable"); }
    }
}
