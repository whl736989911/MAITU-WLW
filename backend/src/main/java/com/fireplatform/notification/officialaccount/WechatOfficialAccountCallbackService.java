package com.fireplatform.notification.officialaccount;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.notification.NotificationDeliveryReceiptCommands;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticated Official Account callback handling.
 *
 * <p>Plain and AES modes are supported with the currently configured and retained rotated keys.
 * A template completion event maps only the published statuses: {@code success} becomes DELIVERED,
 * both documented failure statuses become FAILED, and anything else stays UNCONFIRMED. There is no
 * blind resend and no invented delivery evidence.</p>
 */
@Service
public class WechatOfficialAccountCallbackService {
    private static final Logger log = LoggerFactory.getLogger(WechatOfficialAccountCallbackService.class);
    private static final int MAX_ECHOSTR = 2048;
    public record ReceiveResult(boolean accepted, String code) { }

    private final WechatOfficialAccountConfigService config;
    private final WechatOfficialAccountBindingService bindings;
    private final NotificationDeliveryReceiptCommands receipts;
    private final JdbcTemplate jdbc;
    private final DatabaseTime time;

    public WechatOfficialAccountCallbackService(WechatOfficialAccountConfigService config, WechatOfficialAccountBindingService bindings,
                                                NotificationDeliveryReceiptCommands receipts,
                                                JdbcTemplate jdbc, DatabaseTime time) {
        this.config = config; this.bindings = bindings; this.receipts = receipts; this.jdbc = jdbc; this.time = time;
    }

    /** Public GET server-URL verification. Secure mode may carry an encrypted echostr. */
    public String verify(String signature, String timestamp, String nonce, String echostr, String msgSignature, String encryptType) {
        if (echostr == null || echostr.length() > MAX_ECHOSTR) return null;
        for (var key : config.verificationKeys()) {
            if ("aes".equalsIgnoreCase(encryptType) && key.encodingAesKey() != null) {
                if (!WechatOfficialAccountCrypto.valid(
                    WechatOfficialAccountCrypto.messageSignature(key.token(), timestamp, nonce, echostr), msgSignature)) continue;
                try {
                    return WechatOfficialAccountCrypto.decrypt(key.encodingAesKey(), echostr, key.appId());
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
            }
            if (WechatOfficialAccountCrypto.valid(WechatOfficialAccountCrypto.signature(key.token(), timestamp, nonce), signature))
                return echostr;
        }
        return null;
    }

    @Transactional
    public ReceiveResult receive(byte[] rawBody, String signature, String timestamp, String nonce,
                                 String encryptType, String msgSignature) {
        Map<String, String> envelope;
        try { envelope = WechatOfficialAccountCrypto.parseXml(rawBody); }
        catch (IllegalArgumentException invalid) { return new ReceiveResult(false, "INVALID_XML"); }
        String encrypt = envelope.get("Encrypt");
        boolean encrypted = encrypt != null && !encrypt.isBlank();
        for (var key : config.verificationKeys()) {
            Map<String, String> fields;
            String mode;
            if (encrypted) {
                if (key.encodingAesKey() == null) continue;
                if (!WechatOfficialAccountCrypto.valid(
                    WechatOfficialAccountCrypto.messageSignature(key.token(), timestamp, nonce, encrypt), msgSignature)) continue;
                try {
                    String plain = WechatOfficialAccountCrypto.decrypt(key.encodingAesKey(), encrypt, key.appId());
                    fields = WechatOfficialAccountCrypto.parseXml(plain.getBytes(StandardCharsets.UTF_8));
                } catch (IllegalArgumentException invalid) { continue; }
                mode = "AES";
            } else {
                if (encryptType != null && !encryptType.isBlank() && !"raw".equalsIgnoreCase(encryptType)) continue;
                if (!WechatOfficialAccountCrypto.valid(WechatOfficialAccountCrypto.signature(key.token(), timestamp, nonce), signature)) continue;
                fields = envelope;
                mode = "PLAIN";
            }
            return handle(key, fields, mode);
        }
        return new ReceiveResult(false, "SIGNATURE_INVALID");
    }

    private ReceiveResult handle(WechatOfficialAccountConfigService.VerificationKey key, Map<String, String> fields, String mode) {
        String eventKey = WechatOfficialAccountCrypto.eventKey(fields);
        try {
            jdbc.update("INSERT INTO notification_wechat_callback_events(id,event_key,app_id,signature_mode,msg_type,event,msg_id,"
                    + "from_openid_masked,provider_status,received_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                java.util.UUID.randomUUID().toString(), eventKey, key.appId(), mode, truncate(fields.get("MsgType"), 32),
                truncate(fields.get("Event"), 64), truncate(fields.get("MsgID"), 128),
                WechatOfficialAccountBindingService.maskOpenid(fields.get("FromUserName")), truncate(fields.get("Status"), 64), time.now());
        } catch (DuplicateKeyException replay) {
            return new ReceiveResult(true, "DUPLICATE");
        }
        String msgType = fields.getOrDefault("MsgType", "");
        String event = fields.getOrDefault("Event", "");
        String from = fields.get("FromUserName");
        if ("event".equalsIgnoreCase(msgType) && "TEMPLATESENDJOBFINISH".equalsIgnoreCase(event)) {
            String msgId = fields.get("MsgID");
            String status = fields.get("Status");
            if (msgId == null || msgId.isBlank() || from == null || from.isBlank())
                return new ReceiveResult(true, "IGNORED_INCOMPLETE");
            var attempts = jdbc.query("SELECT id,tenant_id,binding_id FROM notification_provider_attempts "
                    + "WHERE channel='WECHAT' AND provider_id=? AND status='SENT' ORDER BY created_at DESC",
                (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)}, msgId.trim());
            if (attempts.size() != 1) return new ReceiveResult(true, "ATTEMPT_NOT_FOUND");
            String attemptId = (String) attempts.getFirst()[0];
            String tenant = (String) attempts.getFirst()[1];
            String bindingId = (String) attempts.getFirst()[2];
            if (bindingId == null) return new ReceiveResult(true, "BINDING_NOT_FOUND");
            var fingerprints = jdbc.queryForList("SELECT openid_fingerprint FROM notification_wechat_bindings WHERE id=?", String.class, bindingId);
            if (fingerprints.isEmpty()) return new ReceiveResult(true, "BINDING_NOT_FOUND");
            String expected = fingerprints.getFirst();
            String supplied = WechatOfficialAccountBindingService.openidFingerprint(key.appId(), from);
            if (!java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII)))
                return new ReceiveResult(true, "OPENID_MISMATCH");
            receipts.recordWechatCallback(attemptId, tenant, status, status, eventKey);
            log.info("wechat template completion recorded attempt={} status={}", attemptId, status);
            return new ReceiveResult(true, "RECORDED");
        }
        if ("event".equalsIgnoreCase(msgType) && from != null && !from.isBlank()) {
            String fingerprint = WechatOfficialAccountBindingService.openidFingerprint(key.appId(), from);
            if ("subscribe".equalsIgnoreCase(event)) bindings.markFollow(key.appId(), fingerprint, true);
            else if ("unsubscribe".equalsIgnoreCase(event)) bindings.markFollow(key.appId(), fingerprint, false);
        }
        return new ReceiveResult(true, "ACKNOWLEDGED");
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
