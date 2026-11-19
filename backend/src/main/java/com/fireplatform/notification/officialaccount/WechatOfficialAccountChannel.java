package com.fireplatform.notification.officialaccount;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.notification.NotificationDeliveryReceiptCommands;
import com.fireplatform.transport.WechatOfficialAccountClient;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Real Official Account template submission for the existing durable attempt truth.
 *
 * <p>A rule channel is only a selector: the exact binding, account fingerprint, template and data
 * are frozen before the attempt row is created, then the current binding/follow state and config
 * revision are re-read immediately before external I/O. Provider errcode 0 is recorded as accepted,
 * never as delivered; delivery is only written by the authenticated completion callback.</p>
 */
@Service
public class WechatOfficialAccountChannel {
    public static final class Unavailable extends RuntimeException {
        public final String code;
        Unavailable(String code) { super(code); this.code = code; }
    }
    public record Prepared(String tenantId, String bindingId, String openid, String openidMasked, String openidCipher,
                           String openidNonce, String destinationMac, String accountFingerprint, long configRevision,
                           String templateId, Map<String, String> values, String payloadJson, String payloadDigest) { }
    public record Result(Outcome outcome, String code, String message, String msgid, boolean retryable) {
        public enum Outcome { ACCEPTED, REJECTED, UNKNOWN }
        public boolean accepted() { return outcome == Outcome.ACCEPTED; }
        public boolean unknown() { return outcome == Outcome.UNKNOWN; }
    }

    private static final DateTimeFormatter ALARM_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("Asia/Shanghai"));

    private final JdbcTemplate jdbc;
    private final WechatOfficialAccountConfigService config;
    private final WechatOfficialAccountBindingService bindings;
    private final WechatOfficialAccountSecretStorage secrets;
    private final WechatOfficialAccountTokenCache tokens;
    private final WechatOfficialAccountClient client;
    private final NotificationDeliveryReceiptCommands receipts;
    private final ObjectMapper json;

    public WechatOfficialAccountChannel(JdbcTemplate jdbc, WechatOfficialAccountConfigService config,
                                        WechatOfficialAccountBindingService bindings, WechatOfficialAccountSecretStorage secrets,
                                        WechatOfficialAccountTokenCache tokens, WechatOfficialAccountClient client,
                                        NotificationDeliveryReceiptCommands receipts, ObjectMapper json) {
        this.jdbc = jdbc; this.config = config; this.bindings = bindings; this.secrets = secrets; this.tokens = tokens;
        this.client = client; this.receipts = receipts; this.json = json;
    }

    public Prepared prepare(String tenant, String contactId, Map<String, Object> alarm, boolean fire, String eventCode) {
        String bindingId = jdbc.query("SELECT wechat_binding_id FROM notification_contacts WHERE tenant_id=? AND id=?",
            (rs, n) -> rs.getString(1), tenant, contactId).stream().findFirst().orElse(null);
        if (bindingId == null || bindingId.isBlank()) throw new Unavailable("WECHAT_BINDING_UNAVAILABLE");
        WechatOfficialAccountBindingService.BindingRow binding;
        try { binding = bindings.requireUsable(tenant, bindingId); }
        catch (com.fireplatform.shared.ApiError error) { throw new Unavailable(error.code); }
        WechatOfficialAccountConfigService.Snapshot snapshot = config.currentSnapshot();
        if (!snapshot.enabled() || !snapshot.apiReady()) throw new Unavailable("WECHAT_CONFIG_DISABLED");
        if (!Objects.equals(snapshot.appId(), binding.appId())) throw new Unavailable("WECHAT_BINDING_STALE");
        String templateId = snapshot.templateFor(fire);
        var keys = snapshot.keysFor(fire);
        if (templateId == null || keys == null || !keys.complete()) throw new Unavailable("WECHAT_TEMPLATE_NOT_CONFIGURED");
        String openid;
        try { openid = secrets.reveal(binding.openidCipher(), binding.openidNonce()); }
        catch (RuntimeException unavailable) { throw new Unavailable("WECHAT_BINDING_OPENID_UNAVAILABLE"); }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(keys.deviceKey(), WechatOfficialAccountConfigService.valueFor(keys.deviceKey(), text(alarm.get("device_name"))));
        if (keys.addressKey() != null) values.put(keys.addressKey(), WechatOfficialAccountConfigService.valueFor(keys.addressKey(), text(alarm.get("address"))));
        values.put(keys.timeKey(), WechatOfficialAccountConfigService.valueFor(keys.timeKey(), alarmTime(alarm)));
        if (keys.eventKey() != null) values.put(keys.eventKey(), WechatOfficialAccountConfigService.valueFor(keys.eventKey(), fire ? "火灾报警" : "设备故障"));
        String payloadJson;
        try {
            Map<String, Object> payload = new TreeMap<>();
            payload.put("template_id", templateId);
            payload.put("data", values);
            payloadJson = json.writeValueAsString(payload);
        } catch (Exception encode) {
            throw new Unavailable("WECHAT_PAYLOAD_UNENCODABLE");
        }
        if (payloadJson.length() > 4096) throw new Unavailable("WECHAT_PAYLOAD_TOO_LARGE");
        var protectedOpenid = secrets.protect(openid);
        return new Prepared(tenant, bindingId, openid, binding.openidMasked(), protectedOpenid.cipher(), protectedOpenid.nonce(),
            secrets.fingerprint(openid), snapshot.accountFingerprint(), snapshot.revision(), templateId, Map.copyOf(values),
            payloadJson, WechatOfficialAccountSecretStorage.sha256(payloadJson));
    }

    public Result send(Prepared prepared, String attemptId) {
        WechatOfficialAccountConfigService.Snapshot current = config.currentSnapshot();
        if (!current.enabled() || !Objects.equals(current.accountFingerprint(), prepared.accountFingerprint())
            || current.revision() != prepared.configRevision())
            return new Result(Result.Outcome.REJECTED, "WECHAT_CONFIG_ROTATED", "公众号配置已变化，旧尝试不会改用新账户发送", null, false);
        WechatOfficialAccountBindingService.BindingRow binding = bindings.activeBinding(prepared.tenantId(), prepared.bindingId());
        if (binding == null || !binding.userEnabled()) return new Result(Result.Outcome.REJECTED, "WECHAT_BINDING_REVOKED", "微信绑定已解除或用户已停用", null, false);
        if (!Objects.equals(binding.appId(), current.appId())) return new Result(Result.Outcome.REJECTED, "WECHAT_BINDING_STALE", "微信绑定属于旧公众号", null, false);
        if ("UNSUBSCRIBED".equals(binding.followState())) return new Result(Result.Outcome.REJECTED, "WECHAT_RECIPIENT_UNSUBSCRIBED", "用户已取消关注公众号", null, false);
        WechatOfficialAccountClient.TokenResult token = tokens.get(current);
        if (!token.success()) return new Result(Result.Outcome.REJECTED, "WECHAT_TOKEN_" + token.code(), token.message(), null, false);
        WechatOfficialAccountClient.SendResult sent = client.sendTemplate(token.token(), prepared.openid(), prepared.templateId(), prepared.values(), attemptId);
        return switch (sent.outcome()) {
            case ACCEPTED -> new Result(Result.Outcome.ACCEPTED, sent.code(), sent.message(), sent.msgid(), false);
            case REJECTED -> new Result(Result.Outcome.REJECTED, "WECHAT_" + sent.code(), sent.message(), null, "-1".equals(sent.code()));
            case TOKEN_INVALID -> {
                tokens.invalidate(current);
                yield new Result(Result.Outcome.REJECTED, "WECHAT_TOKEN_INVALID", sent.message(), null, true);
            }
            case UNKNOWN -> new Result(Result.Outcome.UNKNOWN, "WECHAT_" + sent.code(), sent.message(), null, false);
        };
    }

    /** Acceptance is local evidence of the provider's reply, not delivery. */
    public void recordAccepted(String attemptId, String tenant, Result result) {
        if (result.accepted()) receipts.recordWechatAccepted(attemptId, tenant, result.msgid(), result.code());
    }

    private static String alarmTime(Map<String, Object> alarm) {
        Object occurred = alarm.get("occurred_at");
        boolean known = !(Boolean.FALSE.equals(alarm.get("source_time_known"))
            || "false".equalsIgnoreCase(String.valueOf(alarm.get("source_time_known"))));
        if (!known) return "来源时间未知";
        if (occurred instanceof OffsetDateTime time) return ALARM_TIME.format(time);
        return occurred == null ? "来源时间未知" : String.valueOf(occurred);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
