package com.fireplatform.shared;

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
 * Scoped AES-GCM payload cipher shared by durable payload owners (data transfer, device-service secrets and
 * fire-archive attachments). It keeps the single configured key and refuses to operate when the key is absent;
 * callers decide what a decryption failure means for their own business refusal.
 */
@Service
public final class DataTransferPayloadCipher {
    public record Encrypted(String ciphertext,String nonce) { }
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();
    public DataTransferPayloadCipher(Environment environment) {
        byte[] decoded=null;
        try {
            String value=environment.getProperty("app.data-transfer-key",environment.getProperty("APP_DATA_TRANSFER_KEY"));
            if(value!=null&&!value.isBlank()) { decoded=Base64.getDecoder().decode(value); if(decoded.length!=32)decoded=null; }
        } catch(IllegalArgumentException ignored) { decoded=null; }
        this.key=decoded;
    }
    public boolean ready() { return key != null; }
    public Encrypted encrypt(String tenant,String plaintext) { return encryptScope("bulkio-plan-v1\\0"+String.valueOf(tenant),plaintext); }
    public String decrypt(String tenant,String ciphertext,String nonceText) { return decryptScope("bulkio-plan-v1\\0"+String.valueOf(tenant),ciphertext,nonceText); }
    public Encrypted encryptScope(String scope,byte[] plaintext) { return encryptScope(scope,Base64.getEncoder().encodeToString(plaintext)); }
    public byte[] decryptScopeBytes(String scope,String ciphertext,String nonceText) { return Base64.getDecoder().decode(decryptScope(scope,ciphertext,nonceText)); }
    private Encrypted encryptScope(String scope,String plaintext) {
        requireKey();
        try {
            byte[] nonce=new byte[12];random.nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(scope));
            return new Encrypted(Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8))),Base64.getEncoder().encodeToString(nonce));
        } catch(GeneralSecurityException failure) { throw new ApiError(503,"DATA_TRANSFER_ENCRYPTION_UNAVAILABLE","文件加密不可用，未保存文件内容"); }
    }
    private String decryptScope(String scope,String ciphertext,String nonceText) {
        requireKey();
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(nonceText)));cipher.updateAAD(aad(scope));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)),StandardCharsets.UTF_8);
        } catch(Exception failure) { throw new ApiError(503,"DATA_TRANSFER_ENCRYPTION_UNAVAILABLE","文件无法解密，请重新导出或上传"); }
    }
    private void requireKey() { if(key==null)throw new ApiError(503,"DATA_TRANSFER_ENCRYPTION_UNAVAILABLE","未配置 APP_DATA_TRANSFER_KEY，未保存或读取文件内容"); }
    private static byte[] aad(String scope) { return scope.getBytes(StandardCharsets.UTF_8); }
}
