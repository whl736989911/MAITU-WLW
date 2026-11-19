package com.fireplatform.notification;

import com.fireplatform.shared.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Phone storage only. No reveal API. Independent encryption and MAC subkeys; all failures are value-free. */
@Service
public class ContactPhoneStorage implements NotificationStorageReadiness {
    public record Phone(String cipher,String nonce,String masked,String mac) { }
    private final JdbcTemplate jdbc; private final byte[] aes,mac; private final String fingerprint; private final SecureRandom random=new SecureRandom();
    public ContactPhoneStorage(JdbcTemplate jdbc,Environment env) {
        this.jdbc=jdbc; byte[] encryption=null,signing=null; String fp=null;
        try { String raw=env.getProperty("app.notification-contact-key",env.getProperty("APP_NOTIFICATION_CONTACT_KEY")); if(raw!=null && !raw.isBlank()) { byte[] master=Base64.getDecoder().decode(raw); if(master.length!=32) throw new GeneralSecurityException(); encryption=sign(master,"notification/aes/v1"); signing=sign(master,"notification/hmac/v1"); fp=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(master)); Arrays.fill(master,(byte)0); } } catch(IllegalArgumentException|GeneralSecurityException ignored) { encryption=null; signing=null; fp=null; }
        aes=encryption; mac=signing; fingerprint=fp;
    }
    private static byte[] sign(byte[] key,String value)throws GeneralSecurityException { var h=Mac.getInstance("HmacSHA256"); h.init(new SecretKeySpec(key,"HmacSHA256")); return h.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
    @Override public boolean contactStorageReady() { if(fingerprint==null)return false; var stored=jdbc.queryForObject("SELECT fingerprint FROM notification_contact_key_guard WHERE id=1",String.class); return stored==null || stored.equals(fingerprint); }
    @Transactional(propagation=Propagation.MANDATORY) public void requireReady() { var stored=jdbc.queryForObject("SELECT fingerprint FROM notification_contact_key_guard WHERE id=1 FOR UPDATE",String.class); if(fingerprint==null || stored!=null && !stored.equals(fingerprint)) throw new ApiError(503,"NOTIFICATION_CONTACT_STORAGE_UNAVAILABLE","通知联系人密钥未配置或与数据库不一致，未执行写入"); if(stored==null) jdbc.update("UPDATE notification_contact_key_guard SET fingerprint=? WHERE id=1",fingerprint); }
    public String normalize(String input) { String value=NotificationPolicy.text(input,Limits.NOTIFICATION_PHONE_MAX).trim(); if(value.matches("(?:\\+86)?1[3-9][0-9]{9}")) return value.startsWith("+86")?value:"+86"+value; if(value.matches("0[0-9]{2,3}-[0-9]{7,8}")) return value; throw ApiError.bad("号码须为中国手机号或带明确区号分隔的座机"); }
    public String fingerprintPhone(String tenant,String normalized) { if(mac==null) throw new ApiError(503,"NOTIFICATION_CONTACT_STORAGE_UNAVAILABLE","通知联系人密钥未配置"); try { return HexFormat.of().formatHex(sign(mac,"phone/v1\u0000"+tenant+"\u0000"+normalized)); } catch(GeneralSecurityException ignored) { throw new IllegalStateException("Contact protection unavailable"); } }
    /** Internal provider adapter only; never expose through an API. */
    public String reveal(String tenant,String cipherText,String nonceText) {
        if(aes==null) throw new ApiError(503,"NOTIFICATION_CONTACT_STORAGE_UNAVAILABLE","通知联系人密钥未配置");
        try {
            byte[] nonce=Base64.getDecoder().decode(nonceText);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(aes,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(tenant.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(cipherText)),StandardCharsets.UTF_8);
        } catch(Exception error) { throw new IllegalStateException("Contact protection unavailable"); }
    }
    public Phone protect(String tenant,String normalized) { try { byte[] nonce=new byte[12]; random.nextBytes(nonce); var cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(aes,"AES"),new GCMParameterSpec(128,nonce)); cipher.updateAAD(tenant.getBytes(StandardCharsets.UTF_8)); String mask=normalized.startsWith("+86")?normalized.substring(3,6)+"****"+normalized.substring(normalized.length()-4):normalized.substring(0,normalized.indexOf('-')+1)+"****"+normalized.substring(normalized.length()-4); return new Phone(Base64.getEncoder().encodeToString(cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8))),Base64.getEncoder().encodeToString(nonce),mask,fingerprintPhone(tenant,normalized)); } catch(GeneralSecurityException ignored) { throw new IllegalStateException("Contact protection unavailable"); } }
}
