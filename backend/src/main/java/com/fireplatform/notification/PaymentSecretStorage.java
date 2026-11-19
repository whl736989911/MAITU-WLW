package com.fireplatform.notification;

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

/**
 * Write-only storage for the page-managed WeChat Pay APIv2 key and for immutable bill evidence.
 *
 * <p>The AES-GCM key is derived with domain separation from an independent payment master key.
 * When no dedicated master is deployed the existing notification secret master is reused with a
 * different derivation label, so payment ciphertext is never interchangeable with provider
 * ciphertext and no new mandatory deployment secret is introduced.</p>
 */
@Service
public class PaymentSecretStorage {
    public record Protected(String cipher, String nonce, String masked) { }
    private static final String AAD = "payment-wechat/v1";
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public PaymentSecretStorage(Environment env) {
        String raw = firstNonBlank(env.getProperty("app.payment-config-key"), env.getProperty("APP_PAYMENT_CONFIG_KEY"),
            env.getProperty("app.notification-provider-key"), env.getProperty("APP_NOTIFICATION_PROVIDER_KEY"),
            env.getProperty("app.notification-contact-key"), env.getProperty("APP_NOTIFICATION_CONTACT_KEY"));
        byte[] derived = null;
        try {
            if (raw != null) {
                byte[] master = Base64.getDecoder().decode(raw);
                if (master.length == 32) {
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(master, "HmacSHA256"));
                    derived = mac.doFinal("payment-config/aes/v1".getBytes(StandardCharsets.UTF_8));
                }
                java.util.Arrays.fill(master, (byte) 0);
            }
        } catch (IllegalArgumentException | GeneralSecurityException ignored) {
            derived = null;
        }
        key = derived;
    }

    public boolean ready() { return key != null; }

    public Protected protect(String value) {
        if (value == null) throw new IllegalArgumentException("value");
        return protectBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public String reveal(String cipherText, String nonceText) {
        return new String(revealBytes(cipherText, nonceText), StandardCharsets.UTF_8);
    }

    public Protected protectBytes(byte[] value) {
        if (key == null) throw new IllegalStateException("payment secret key unavailable");
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value);
            return new Protected(Base64.getEncoder().encodeToString(encrypted),
                Base64.getEncoder().encodeToString(nonce), mask(value));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("payment secret protection unavailable");
        }
    }

    public byte[] revealBytes(String cipherText, String nonceText) {
        if (key == null) throw new IllegalStateException("payment secret key unavailable");
        try {
            byte[] nonce = Base64.getDecoder().decode(nonceText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(Base64.getDecoder().decode(cipherText));
        } catch (Exception error) {
            throw new IllegalStateException("payment secret protection unavailable");
        }
    }

    private static String mask(byte[] value) {
        if (value.length <= 4) return "****";
        return "****" + new String(value, value.length - 4, 4, StandardCharsets.US_ASCII);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }
}
