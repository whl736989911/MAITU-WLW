package com.fireplatform.notification;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import com.fireplatform.transport.WechatPayV2Client;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Page-managed WeChat Pay APIv2 configuration.
 *
 * <p>The page never reads a secret back: the API returns masked merchant/appid values and a boolean
 * for the APIv2 key. Blank merchant/appid/key inputs keep the stored value, so saving a form cannot
 * silently erase a working credential. The deployed environment remains the compatibility source
 * only while no page configuration exists; after the first save the page configuration governs.</p>
 */
@Service
public class PaymentConfigService {
    public record View(boolean configured, boolean enabled, String source, boolean readyForNewOrders,
                       String mchIdMasked, String appIdMasked, boolean apiKeyConfigured, String notifyUrl,
                       Integer orderExpiryMinutes, boolean autoLifecycleEnabled, boolean autoReconcileEnabled,
                       long revision, long credentialsRevision, boolean secretStorageReady, OffsetDateTime updatedAt) { }
    public record Status(boolean secretStorageReady, boolean configured, boolean enabled, String source,
                         boolean readyForNewOrders, Integer orderExpiryMinutes, boolean autoLifecycleEnabled,
                         boolean autoReconcileEnabled, long pendingOrders, long expiredPendingOrders,
                         OffsetDateTime lastReconciliationAt, String lastReconciliationStatus) { }
    /** The merchant identity and APIv2 key revision an order was created with. */
    public record OrderMerchant(WechatPayV2Client.Credentials credentials, Long configRevision) { }

    private record Row(boolean configured, boolean enabled, String mchId, String appId, String keyCipher,
                       String keyNonce, String notifyUrl, Integer orderExpiryMinutes, boolean autoLifecycleEnabled,
                       boolean autoReconcileEnabled, long credentialsRevision, long revision, OffsetDateTime updatedAt) { }

    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final PaymentSecretStorage secrets;
    private final WechatPayV2Client client;

    public PaymentConfigService(JdbcTemplate jdbc, ManagementGuard guard, IdempotencyStore idem, AuditService audit,
                                PaymentSecretStorage secrets, WechatPayV2Client client) {
        this.jdbc = jdbc; this.guard = guard; this.idem = idem; this.audit = audit; this.secrets = secrets; this.client = client;
    }

    public View view() { return view(row(false)); }

    public Status status() {
        Row current = row(false);
        var env = client.envCredentials();
        String source = current.configured() ? "CONFIG" : env != null ? "ENV" : "NONE";
        var last = jdbc.query("SELECT status FROM payment_reconciliation_runs ORDER BY created_at DESC,id DESC LIMIT 1",
            (rs, n) -> rs.getString("status"));
        var lastFinished = jdbc.query("SELECT finished_at FROM payment_reconciliation_runs WHERE finished_at IS NOT NULL ORDER BY finished_at DESC,id DESC LIMIT 1",
            (rs, n) -> rs.getObject("finished_at", OffsetDateTime.class));
        return new Status(secrets.ready(), current.configured(), current.enabled(), source,
            activeMerchant() != null, current.orderExpiryMinutes(), current.autoLifecycleEnabled(), current.autoReconcileEnabled(),
            jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_orders WHERE status='PENDING'", Long.class),
            jdbc.queryForObject("SELECT COUNT(*) FROM notification_resource_orders WHERE status='PENDING' AND expires_at IS NOT NULL AND expires_at<=CURRENT_TIMESTAMP", Long.class),
            lastFinished.isEmpty() ? null : lastFinished.getFirst(),
            last.isEmpty() ? null : last.getFirst());
    }

    @Transactional
    public View update(AuthService.Identity actor, String key, PaymentConfigInputs.Config input, String request) {
        guard.begin(actor, "payments:write", null, "management");
        idem.validateKey(key);
        Row current = row(true);
        if (input.expectedRevision() == null) throw ApiError.bad("缺少支付配置版本");
        if (input.expectedRevision() != current.revision())
            throw ApiError.conflict("REVISION_CONFLICT", "支付配置已更新，请刷新后重试");
        boolean enabled = NotificationPolicy.flag(input.enabled());
        boolean autoLifecycle = input.autoLifecycleEnabled() == null ? current.autoLifecycleEnabled() : NotificationPolicy.flag(input.autoLifecycleEnabled());
        boolean autoReconcile = input.autoReconcileEnabled() == null ? current.autoReconcileEnabled() : NotificationPolicy.flag(input.autoReconcileEnabled());
        Integer expiryMinutes = input.orderExpiryMinutes() == null ? null : (int) NotificationPolicy.number(input.orderExpiryMinutes(), 1, 15 * 24 * 60);
        String suppliedKey = input.apiV2Key() == null ? "" : input.apiV2Key().trim();
        String suppliedMch = trimmed(input.mchId());
        String suppliedAppId = trimmed(input.appId());
        String suppliedNotify = trimmed(input.notifyUrl());
        String hash = idem.fingerprint(Arrays.asList(input.expectedRevision(), enabled, suppliedMch, suppliedAppId, suppliedKey,
            suppliedNotify, expiryMinutes, autoLifecycle, autoReconcile));
        var replay = idem.replay(actor.user().id(), "payment-wechat-config", key, hash, View.class);
        if (replay != null) return replay;

        String mchId = suppliedMch == null ? current.mchId() : merchantId(suppliedMch);
        String appId = suppliedAppId == null ? current.appId() : appId(suppliedAppId);
        String cipher = current.keyCipher(), nonce = current.keyNonce(), masked = null;
        long credentialsRevision = current.credentialsRevision();
        boolean rotate = false;
        if (!suppliedKey.isEmpty()) {
            if (suppliedKey.length() != 32 || !suppliedKey.chars().allMatch(c -> c >= 33 && c <= 126))
                throw ApiError.bad("APIv2 密钥必须为 32 位可见 ASCII 字符");
            if (!secrets.ready())
                throw new ApiError(503, "PAYMENT_SECRET_STORAGE_UNAVAILABLE", "支付密钥存储未配置，不能保存 APIv2 密钥");
            String existing = current.keyCipher() == null ? null : reveal(current.keyCipher(), current.keyNonce());
            if (!suppliedKey.equals(existing)) {
                var protectedKey = secrets.protect(suppliedKey);
                cipher = protectedKey.cipher(); nonce = protectedKey.nonce(); masked = protectedKey.masked();
                credentialsRevision = current.credentialsRevision() + 1;
                rotate = true;
            }
        }
        boolean keyConfigured = cipher != null;
        String notifyUrl = suppliedNotify == null ? current.notifyUrl() : notifyUrl(suppliedNotify);
        if (notifyUrl == null || notifyUrl.isBlank()) notifyUrl = notifyUrl(client.envNotifyUrl());
        if (notifyUrl == null) throw ApiError.bad("必须配置 HTTPS 支付结果通知地址");
        if (enabled && (!keyConfigured || mchId == null || appId == null))
            throw ApiError.conflict("PAYMENT_CONFIG_INCOMPLETE", "启用微信支付前必须配置商户号、AppID 并填写 APIv2 密钥（环境变量密钥不会复制到页面配置）");
        if ((autoLifecycle || autoReconcile) && (!keyConfigured || mchId == null || appId == null))
            throw ApiError.conflict("PAYMENT_CONFIG_INCOMPLETE", "启用自动查单/关单或每日对账前必须先完成商户号、AppID 与 APIv2 密钥配置");
        if ((autoLifecycle || autoReconcile) && !secrets.ready())
            throw new ApiError(503, "PAYMENT_SECRET_STORAGE_UNAVAILABLE", "支付密钥存储未配置，不能启用自动支付任务");

        if (rotate)
            jdbc.update("INSERT INTO payment_wechat_key_history(revision,api_v2_key_cipher,api_v2_key_nonce,api_v2_key_masked,created_at,created_by) VALUES (?,?,?,?,CURRENT_TIMESTAMP,?)",
                credentialsRevision, cipher, nonce, masked, actor.user().id());
        int changed = jdbc.update("UPDATE payment_wechat_config SET configured=TRUE,enabled=?,mch_id=?,app_id=?,api_v2_key_cipher=?,api_v2_key_nonce=?,api_v2_key_masked=?,notify_url=?,order_expiry_minutes=?,auto_lifecycle_enabled=?,auto_reconcile_enabled=?,credentials_revision=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE id=1 AND revision=?",
            enabled, mchId, appId, cipher, nonce, masked, notifyUrl, expiryMinutes, autoLifecycle, autoReconcile, credentialsRevision, actor.user().id(), input.expectedRevision());
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "支付配置已更新，请刷新后重试");
        audit.record(actor.user().id(), null, "PAYMENT_WECHAT_CONFIG_UPDATE", "payment-wechat-config", "SUCCESS", request);
        View view = view(row(false));
        idem.save(actor.user().id(), "payment-wechat-config", key, hash, view);
        return view;
    }

    public Integer orderExpiryMinutes() { return row(false).orderExpiryMinutes(); }
    public boolean autoLifecycleEnabled() { Row current = row(false); return current.configured() && current.autoLifecycleEnabled(); }
    public boolean autoReconcileEnabled() { Row current = row(false); return current.configured() && current.autoReconcileEnabled(); }

    /** Credentials and identity revision for a new order; null when new merchant payments are not configured. */
    public OrderMerchant newOrderMerchant() {
        Row current = row(false);
        if (!current.configured()) {
            var env = client.envCredentials();
            return env == null || !env.orderReady() ? null : new OrderMerchant(env, null);
        }
        if (!current.enabled() || current.keyCipher() == null || current.mchId() == null || current.appId() == null || !secrets.ready()) return null;
        String key = reveal(current.keyCipher(), current.keyNonce());
        if (key == null) return null;
        String notify = current.notifyUrl() == null || current.notifyUrl().isBlank() ? client.envNotifyUrl() : current.notifyUrl();
        var credentials = new WechatPayV2Client.Credentials(current.mchId(), current.appId(), key, notify);
        return credentials.orderReady() ? new OrderMerchant(credentials, current.credentialsRevision()) : null;
    }

    /** Credentials for merchant-side read-only reporting (bill download); independent of the new-order enabled flag. */
    public WechatPayV2Client.Credentials reportingCredentials() {
        Row current = row(false);
        if (!current.configured()) return client.envCredentials();
        if (current.keyCipher() == null || current.mchId() == null || current.appId() == null || !secrets.ready()) return null;
        String key = reveal(current.keyCipher(), current.keyNonce());
        if (key == null) return null;
        String notify = current.notifyUrl() == null || current.notifyUrl().isBlank() ? client.envNotifyUrl() : current.notifyUrl();
        return new WechatPayV2Client.Credentials(current.mchId(), current.appId(), key, notify);
    }

    /** Merchant identity used for reconciliation evidence, from the page configuration or legacy environment. */
    public String reportingMerchantId() {
        Row current = row(false);
        if (current.configured()) return current.mchId();
        var env = client.envCredentials();
        return env == null ? null : env.mchId();
    }

    private WechatPayV2Client.Credentials activeMerchant() {
        var merchant = newOrderMerchant();
        return merchant == null ? null : merchant.credentials();
    }

    /** Credentials for the merchant identity and key revision captured on an order. */
    public WechatPayV2Client.Credentials credentialsForSnapshot(Long revision, String mchId, String appId) {
        if (mchId == null || mchId.isBlank() || appId == null || appId.isBlank()) {
            if (revision != null) return null;
            return client.envCredentials();
        }
        if (revision == null) {
            var env = client.envCredentials();
            return env != null && env.mchId().equals(mchId) && env.appId().equals(appId) ? env : null;
        }
        String key = keyForRevision(revision);
        if (key == null) return null;
        Row current = row(false);
        String notify = current.notifyUrl() == null || current.notifyUrl().isBlank() ? client.envNotifyUrl() : current.notifyUrl();
        return new WechatPayV2Client.Credentials(mchId, appId, key, notify);
    }

    /** Every retained APIv2 key that may legitimately have signed a callback for this order. */
    public List<String> verificationKeys(Long revision) {
        var keys = new LinkedHashSet<String>();
        if (revision != null) {
            String snapshotKey = keyForRevision(revision);
            if (snapshotKey != null) keys.add(snapshotKey);
        } else {
            // Legacy orders carry no key revision: accept any key this platform legitimately retained.
            keys.addAll(retainedHistoryKeys());
        }
        Row current = row(false);
        if (current.keyCipher() != null) {
            String currentKey = reveal(current.keyCipher(), current.keyNonce());
            if (currentKey != null) keys.add(currentKey);
        }
        var env = client.envCredentials();
        if (env != null) keys.add(env.apiV2Key());
        return List.copyOf(keys);
    }

    private List<String> retainedHistoryKeys() {
        if (!secrets.ready()) return List.of();
        var keys = new ArrayList<String>();
        var rows = jdbc.query("SELECT api_v2_key_cipher,api_v2_key_nonce FROM payment_wechat_key_history ORDER BY revision",
            (rs, n) -> new String[]{rs.getString(1), rs.getString(2)});
        for (var row : rows) {
            try {
                keys.add(secrets.reveal(row[0], row[1]));
            } catch (RuntimeException unavailable) {
                // A corrupt retained key cannot verify anything; other retained keys still can.
            }
        }
        return keys;
    }

    /** True when the merchant identity belongs to this platform's current page or environment configuration. */
    public boolean matchesKnownMerchant(String mchId, String appId) {
        if (mchId == null || appId == null) return false;
        Row current = row(false);
        if (current.configured() && current.mchId() != null && current.appId() != null
            && current.mchId().equals(mchId) && current.appId().equals(appId)) return true;
        var env = client.envCredentials();
        return env != null && env.mchId().equals(mchId) && env.appId().equals(appId);
    }

    private String keyForRevision(long revision) {
        if (!secrets.ready()) return null;
        Row current = row(false);
        try {
            if (current.configured() && revision == current.credentialsRevision() && current.keyCipher() != null)
                return secrets.reveal(current.keyCipher(), current.keyNonce());
            var rows = jdbc.query("SELECT api_v2_key_cipher,api_v2_key_nonce FROM payment_wechat_key_history WHERE revision=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, revision);
            return rows.isEmpty() ? null : secrets.reveal(rows.getFirst()[0], rows.getFirst()[1]);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private String reveal(String cipher, String nonce) {
        try {
            return secrets.ready() ? secrets.reveal(cipher, nonce) : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private Row row(boolean lock) {
        var rows = jdbc.query("SELECT configured,enabled,mch_id,app_id,api_v2_key_cipher,api_v2_key_nonce,notify_url,order_expiry_minutes,auto_lifecycle_enabled,auto_reconcile_enabled,credentials_revision,revision,updated_at FROM payment_wechat_config WHERE id=1" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> new Row(rs.getBoolean("configured"), rs.getBoolean("enabled"), rs.getString("mch_id"), rs.getString("app_id"),
                rs.getString("api_v2_key_cipher"), rs.getString("api_v2_key_nonce"), rs.getString("notify_url"),
                rs.getObject("order_expiry_minutes", Integer.class), rs.getBoolean("auto_lifecycle_enabled"),
                rs.getBoolean("auto_reconcile_enabled"), rs.getLong("credentials_revision"), rs.getLong("revision"),
                rs.getObject("updated_at", OffsetDateTime.class)));
        if (rows.isEmpty()) throw new ApiError(503, "PAYMENT_CONFIG_MISSING", "支付配置缺失");
        return rows.getFirst();
    }

    private View view(Row current) {
        var env = client.envCredentials();
        String source = current.configured() ? "CONFIG" : env != null ? "ENV" : "NONE";
        Boolean ready = current.configured() ? current.enabled() && current.keyCipher() != null && current.mchId() != null && current.appId() != null && secrets.ready() : env != null && env.orderReady();
        String mchId = current.configured() ? current.mchId() : env == null ? null : env.mchId();
        String appId = current.configured() ? current.appId() : env == null ? null : env.appId();
        String notify = current.configured() ? current.notifyUrl() : client.envNotifyUrl();
        return new View(current.configured(), current.enabled(), source, ready,
            mask(mchId), mask(appId), current.configured() ? current.keyCipher() != null : env != null,
            current.configured() || env != null ? notify : null,
            current.orderExpiryMinutes(), current.autoLifecycleEnabled(), current.autoReconcileEnabled(),
            current.revision(), current.credentialsRevision(), secrets.ready(), current.updatedAt());
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    private static String merchantId(String value) {
        if (!value.matches("\\d{6,32}")) throw ApiError.bad("微信商户号必须为 6 至 32 位数字");
        return value;
    }
    private static String appId(String value) {
        if (!value.matches("[A-Za-z0-9]{6,64}")) throw ApiError.bad("微信 AppID 格式无效");
        return value;
    }
    private static String notifyUrl(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 500) throw ApiError.bad("支付结果通知地址过长");
        if (!value.startsWith("https://") || value.indexOf('?') >= 0 || value.indexOf('#') >= 0)
            throw ApiError.bad("支付结果通知地址必须是可公网访问的 HTTPS 地址且不能带查询参数");
        String authority = value.substring("https://".length());
        int slash = authority.indexOf('/');
        String host = slash < 0 ? authority : authority.substring(0, slash);
        if (host.isBlank() || host.startsWith(":") || host.contains("@"))
            throw ApiError.bad("支付结果通知地址必须是有效的 HTTPS URL");
        return value;
    }
    private static String mask(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }
}
