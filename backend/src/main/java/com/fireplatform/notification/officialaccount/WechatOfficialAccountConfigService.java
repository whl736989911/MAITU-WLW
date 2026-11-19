package com.fireplatform.notification.officialaccount;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.notification.NotificationPolicy;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Page-managed Official Account configuration and honest readiness.
 *
 * <p>Opening the page performs no vendor call. Access tokens are only acquired when a real send or
 * OAuth exchange needs them. AppSecret, callback Token, EncodingAESKey and bound OpenIDs are
 * write-only/encrypted; no API returns them and no log line contains them.</p>
 */
@Service
public class WechatOfficialAccountConfigService {
    public record KeyMapping(String deviceKey, String addressKey, String timeKey, String eventKey) {
        public boolean complete() { return deviceKey != null && timeKey != null; }
    }
    /** Frozen sender snapshot. Never serialized through an API. */
    public record Snapshot(boolean enabled, String appId, String appSecret, String callbackToken, String encodingAesKey,
                           String oauthRedirectUri, String callbackUrl, String fireTemplateId, String faultTemplateId,
                           KeyMapping fireKeys, KeyMapping faultKeys, long revision, long credentialsRevision,
                           String accountFingerprint) {
        public boolean apiReady() { return appId != null && appSecret != null && !appSecret.isBlank(); }
        public boolean tokenReady() { return apiReady(); }
        public boolean oauthReady() { return enabled && apiReady() && oauthRedirectUri != null; }
        public boolean callbackReady() { return callbackToken != null && !callbackToken.isBlank(); }
        public String templateFor(boolean fire) { return fire ? fireTemplateId : faultTemplateId; }
        public KeyMapping keysFor(boolean fire) { return fire ? fireKeys : faultKeys; }
    }
    public record Diagnostics(long activeBindings, long staleBindings, long contactLinks, long staleContactLinks,
                              long rulesWithWechat, long pendingOauthRequests, OffsetDateTime lastCallbackAt,
                              String lastCallbackEvent, String lastCallbackStatus, OffsetDateTime lastOauthCallbackAt,
                              String lastOauthCallbackStatus) { }
    public record View(boolean configured, boolean enabled, String appIdMasked, boolean appSecretConfigured,
                       boolean callbackTokenConfigured, boolean aesKeyConfigured, String oauthRedirectUri, String callbackUrl,
                       String fireTemplateId, String faultTemplateId, KeyMapping fireKeys, KeyMapping faultKeys,
                       boolean apiCredentialsReady, boolean oauthReady, boolean callbackReady,
                       boolean fireTemplateReady, boolean faultTemplateReady, boolean secretStorageReady,
                       String tokenState, OffsetDateTime tokenExpiresAt, boolean tokenCurrent,
                       long revision, long credentialsRevision, OffsetDateTime updatedAt, Diagnostics diagnostics) { }
    public record VerificationKey(String appId, String token, String encodingAesKey, long credentialsRevision) { }

    private record Row(boolean enabled, String appId, String secretCipher, String secretNonce, String secretMasked,
                       String tokenCipher, String tokenNonce, String tokenMasked, String aesCipher, String aesNonce, String aesMasked,
                       String oauthRedirectUri, String callbackUrl, String fireTemplateId, String faultTemplateId,
                       KeyMapping fireKeys, KeyMapping faultKeys, long credentialsRevision, long revision, OffsetDateTime updatedAt) { }

    private static final Map<String, Integer> VALUE_LIMITS = Map.of(
        "thing", 20, "const", 20, "character_string", 32, "phone_number", 17, "car_number", 8, "amount", 20);
    private static final int MAX_HISTORY = 10;

    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final WechatOfficialAccountSecretStorage secrets;
    private final WechatOfficialAccountTokenCache tokens;

    public WechatOfficialAccountConfigService(JdbcTemplate jdbc, ManagementGuard guard, IdempotencyStore idem,
                                              AuditService audit, WechatOfficialAccountSecretStorage secrets,
                                              WechatOfficialAccountTokenCache tokens) {
        this.jdbc = jdbc; this.guard = guard; this.idem = idem; this.audit = audit; this.secrets = secrets; this.tokens = tokens;
    }

    public View view() { return view(row(false)); }

    @Transactional
    public View update(AuthService.Identity actor, String key, WechatOfficialAccountInputs.Config input, String requestId) {
        guard.begin(actor, "notification-config:write", null, "management");
        idem.validateKey(key);
        if (input.expectedRevision() == null) throw ApiError.bad("微信公众平台配置必须提供 expected_revision");
        String reason = NotificationPolicy.text(input.reason(), 1000);
        Row current = row(true);
        long currentRevision = current.revision();
        Map<String, String> suppliedSecrets = new LinkedHashMap<>();
        if (nonBlank(input.appSecret())) suppliedSecrets.put("secret", input.appSecret().trim());
        if (nonBlank(input.callbackToken())) suppliedSecrets.put("token", input.callbackToken().trim());
        if (nonBlank(input.encodingAesKey())) suppliedSecrets.put("aes", input.encodingAesKey().trim());
        String hash = idem.fingerprint(Arrays.asList(input.expectedRevision(), NotificationPolicy.flag(input.enabled()),
            nonBlank(input.appId()) ? input.appId().trim() : null,
            suppliedSecrets.containsKey("secret") ? secrets.ready() ? secrets.fingerprint("cfg\u0000" + suppliedSecrets.get("secret")) : "unavailable" : null,
            suppliedSecrets.containsKey("token") ? secrets.ready() ? secrets.fingerprint("cfg\u0000" + suppliedSecrets.get("token")) : "unavailable" : null,
            suppliedSecrets.containsKey("aes") ? secrets.ready() ? secrets.fingerprint("cfg\u0000" + suppliedSecrets.get("aes")) : "unavailable" : null,
            Boolean.TRUE.equals(input.clearAppSecret()), Boolean.TRUE.equals(input.clearCallbackToken()), Boolean.TRUE.equals(input.clearEncodingAesKey()),
            input.oauthRedirectUri(), input.callbackUrl(), input.fireTemplateId(), input.faultTemplateId(),
            input.fireDeviceKey(), input.fireAddressKey(), input.fireTimeKey(), input.fireEventKey(),
            input.faultDeviceKey(), input.faultAddressKey(), input.faultTimeKey(), input.faultEventKey(), reason));
        View replay = idem.replay(actor.user().id(), "wechat-official-account-config", key, hash, View.class);
        if (replay != null) return replay;
        if (input.expectedRevision() != currentRevision)
            throw ApiError.conflict("REVISION_CONFLICT", "微信公众平台配置已更新，请刷新后重试");

        String appId = nonBlank(input.appId()) ? validateAppId(input.appId().trim()) : current.appId();
        String secretCipher = current.secretCipher(), secretNonce = current.secretNonce(), secretMasked = current.secretMasked();
        boolean clearSecret = Boolean.TRUE.equals(input.clearAppSecret());
        if (clearSecret) { secretCipher = null; secretNonce = null; secretMasked = null; }
        if (suppliedSecrets.containsKey("secret")) {
            if (!secrets.ready()) throw new ApiError(503, "WECHAT_SECRET_STORAGE_UNAVAILABLE", "公众号密钥存储未配置，不能保存 AppSecret");
            var protectedValue = secrets.protect(validateAppSecret(suppliedSecrets.get("secret")));
            secretCipher = protectedValue.cipher(); secretNonce = protectedValue.nonce(); secretMasked = protectedValue.masked();
        }
        String tokenCipher = current.tokenCipher(), tokenNonce = current.tokenNonce(), tokenMasked = current.tokenMasked();
        if (Boolean.TRUE.equals(input.clearCallbackToken())) { tokenCipher = null; tokenNonce = null; tokenMasked = null; }
        if (suppliedSecrets.containsKey("token")) {
            if (!secrets.ready()) throw new ApiError(503, "WECHAT_SECRET_STORAGE_UNAVAILABLE", "公众号密钥存储未配置，不能保存服务器配置 Token");
            var protectedValue = secrets.protect(validateCallbackToken(suppliedSecrets.get("token")));
            tokenCipher = protectedValue.cipher(); tokenNonce = protectedValue.nonce(); tokenMasked = protectedValue.masked();
        }
        String aesCipher = current.aesCipher(), aesNonce = current.aesNonce(), aesMasked = current.aesMasked();
        if (Boolean.TRUE.equals(input.clearEncodingAesKey())) { aesCipher = null; aesNonce = null; aesMasked = null; }
        if (suppliedSecrets.containsKey("aes")) {
            if (!secrets.ready()) throw new ApiError(503, "WECHAT_SECRET_STORAGE_UNAVAILABLE", "公众号密钥存储未配置，不能保存 EncodingAESKey");
            var protectedValue = secrets.protect(validateAesKey(suppliedSecrets.get("aes")));
            aesCipher = protectedValue.cipher(); aesNonce = protectedValue.nonce(); aesMasked = protectedValue.masked();
        }
        String oauthRedirect = nonBlank(input.oauthRedirectUri()) ? endpoint(input.oauthRedirectUri(), "/api/wechat/oauth/callback") : null;
        String callbackUrl = nonBlank(input.callbackUrl()) ? endpoint(input.callbackUrl(), "/api/wechat/callback") : null;
        String fireTemplate = optional(input.fireTemplateId(), 128);
        String faultTemplate = optional(input.faultTemplateId(), 128);
        KeyMapping fireKeys = mapping(input.fireDeviceKey(), input.fireAddressKey(), input.fireTimeKey(), input.fireEventKey());
        KeyMapping faultKeys = mapping(input.faultDeviceKey(), input.faultAddressKey(), input.faultTimeKey(), input.faultEventKey());
        if (fireTemplate != null) validateTemplateId(fireTemplate);
        if (faultTemplate != null) validateTemplateId(faultTemplate);
        boolean enabled = NotificationPolicy.flag(input.enabled());
        boolean apiReady = appId != null && secretCipher != null;
        boolean callbackReady = tokenCipher != null;
        boolean fireReady = fireTemplate != null && fireKeys.complete();
        boolean faultReady = faultTemplate != null && faultKeys.complete();
        if (enabled && (!apiReady || !callbackReady || oauthRedirect == null || !(fireReady || faultReady)))
            throw ApiError.conflict("WECHAT_CONFIG_INCOMPLETE", "启用公众号通知前必须配置 AppID、AppSecret、服务器配置 Token、OAuth 回调地址，并至少完整配置火警或故障模板");

        long credentialsRevision = current.credentialsRevision();
        boolean credentialsChanged = !Objects.equals(current.appId(), appId)
            || !Objects.equals(current.secretCipher(), secretCipher) || !Objects.equals(current.secretNonce(), secretNonce)
            || !Objects.equals(current.tokenCipher(), tokenCipher) || !Objects.equals(current.tokenNonce(), tokenNonce)
            || !Objects.equals(current.aesCipher(), aesCipher) || !Objects.equals(current.aesNonce(), aesNonce);
        if (credentialsChanged) credentialsRevision++;
        int changed = jdbc.update("UPDATE notification_wechat_config SET enabled=?,app_id=?,app_secret_cipher=?,app_secret_nonce=?,app_secret_masked=?,"
                + "callback_token_cipher=?,callback_token_nonce=?,callback_token_masked=?,encoding_aes_key_cipher=?,encoding_aes_key_nonce=?,encoding_aes_key_masked=?,"
                + "oauth_redirect_uri=?,callback_url=?,fire_template_id=?,fault_template_id=?,"
                + "fire_device_key=?,fire_address_key=?,fire_time_key=?,fire_event_key=?,fault_device_key=?,fault_address_key=?,fault_time_key=?,fault_event_key=?,"
                + "credentials_revision=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE id=1 AND revision=?",
            enabled, appId, secretCipher, secretNonce, secretMasked, tokenCipher, tokenNonce, tokenMasked, aesCipher, aesNonce, aesMasked,
            oauthRedirect, callbackUrl, fireTemplate, faultTemplate,
            fireKeys.deviceKey(), fireKeys.addressKey(), fireKeys.timeKey(), fireKeys.eventKey(),
            faultKeys.deviceKey(), faultKeys.addressKey(), faultKeys.timeKey(), faultKeys.eventKey(),
            credentialsRevision, actor.user().id(), currentRevision);
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "微信公众平台配置已更新，请刷新后重试");
        if (credentialsChanged) {
            jdbc.update("INSERT INTO notification_wechat_credentials_history(credentials_revision,app_id,app_secret_cipher,app_secret_nonce,"
                    + "callback_token_cipher,callback_token_nonce,encoding_aes_key_cipher,encoding_aes_key_nonce,created_at,created_by) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?)",
                credentialsRevision, appId, secretCipher, secretNonce, tokenCipher, tokenNonce, aesCipher, aesNonce, actor.user().id());
            jdbc.update("DELETE FROM notification_wechat_credentials_history WHERE credentials_revision<?", credentialsRevision - MAX_HISTORY);
        }
        View view = view(row(false));
        idem.save(actor.user().id(), "wechat-official-account-config", key, hash, view);
        audit.recordReason(actor.user().id(), null, "WECHAT_OFFICIAL_ACCOUNT_CONFIG", "wechat-official-account", "SUCCESS", requestId, reason);
        return view;
    }

    /** Current snapshot for a real send/OAuth action. Secrets are decrypted here only, never exposed. */
    public Snapshot currentSnapshot() {
        Row row = row(false);
        String secret = reveal(row.secretCipher(), row.secretNonce());
        String token = reveal(row.tokenCipher(), row.tokenNonce());
        String aes = reveal(row.aesCipher(), row.aesNonce());
        return snapshot(row, secret, token, aes);
    }

    /** Current + retained verification keys for in-flight callback/rotation safety. */
    public List<VerificationKey> verificationKeys() {
        Row row = row(false);
        List<VerificationKey> keys = new ArrayList<>();
        addKey(keys, row.appId(), reveal(row.tokenCipher(), row.tokenNonce()), reveal(row.aesCipher(), row.aesNonce()), row.credentialsRevision());
        var history = jdbc.query("SELECT app_id,callback_token_cipher,callback_token_nonce,encoding_aes_key_cipher,encoding_aes_key_nonce,credentials_revision "
                + "FROM notification_wechat_credentials_history ORDER BY credentials_revision DESC LIMIT ?",
            (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)}, MAX_HISTORY);
        for (var item : history) {
            addKey(keys, (String) item[0], reveal((String) item[1], (String) item[2]), reveal((String) item[3], (String) item[4]), (Long) item[5]);
        }
        return List.copyOf(keys);
    }

    private static void addKey(List<VerificationKey> keys, String appId, String token, String aes, long revision) {
        if (appId == null || token == null) return;
        if (keys.stream().anyMatch(existing -> existing.credentialsRevision() == revision)) return;
        keys.add(new VerificationKey(appId, token, aes, revision));
    }

    private View view(Row row) {
        String secret = reveal(row.secretCipher(), row.secretNonce());
        String token = reveal(row.tokenCipher(), row.tokenNonce());
        String aes = reveal(row.aesCipher(), row.aesNonce());
        Snapshot snapshot = snapshot(row, secret, token, aes);
        var tokenState = tokens.state(snapshot);
        boolean apiReady = row.appId() != null && secret != null;
        boolean callbackReady = token != null;
        boolean oauthReady = row.enabled() && apiReady && row.oauthRedirectUri() != null;
        boolean fireReady = row.fireTemplateId() != null && row.fireKeys().complete();
        boolean faultReady = row.faultTemplateId() != null && row.faultKeys().complete();
        return new View(row.appId() != null || row.secretCipher() != null || row.fireTemplateId() != null || row.faultTemplateId() != null,
            row.enabled(), maskAppId(row.appId()), row.secretCipher() != null, row.tokenCipher() != null, row.aesCipher() != null,
            row.oauthRedirectUri(), row.callbackUrl(), row.fireTemplateId(), row.faultTemplateId(), row.fireKeys(), row.faultKeys(),
            apiReady, oauthReady, callbackReady, fireReady, faultReady, secrets.ready(),
            tokenState.cached() ? (tokenState.current() ? "VALID" : "STALE") : "NOT_FETCHED",
            tokenState.expiresAt(), tokenState.current(), row.revision(), row.credentialsRevision(), row.updatedAt(), diagnostics());
    }

    private Snapshot snapshot(Row row, String secret, String token, String aes) {
        String fingerprint = row.appId() == null ? null : WechatOfficialAccountSecretStorage.sha256(row.appId());
        return new Snapshot(row.enabled(), row.appId(), secret, token, aes, row.oauthRedirectUri(), row.callbackUrl(),
            row.fireTemplateId(), row.faultTemplateId(), row.fireKeys(), row.faultKeys(), row.revision(), row.credentialsRevision(), fingerprint);
    }

    private Diagnostics diagnostics() {
        Row current = row(false);
        long active = current.appId() == null ? 0 : count("SELECT COUNT(*) FROM notification_wechat_bindings b JOIN app_users u ON u.id=b.user_id WHERE b.status='ACTIVE' AND b.app_id=? AND u.enabled=TRUE", current.appId());
        long stale = current.appId() == null ? count("SELECT COUNT(*) FROM notification_wechat_bindings WHERE status='ACTIVE'")
            : count("SELECT COUNT(*) FROM notification_wechat_bindings WHERE status='ACTIVE' AND app_id<>?", current.appId());
        long contactLinks = current.appId() == null ? 0 : count("SELECT COUNT(*) FROM notification_contacts c JOIN notification_wechat_bindings b ON b.id=c.wechat_binding_id AND b.tenant_id=c.tenant_id WHERE b.status='ACTIVE' AND b.app_id=?", current.appId());
        long staleContactLinks = current.appId() == null ? count("SELECT COUNT(*) FROM notification_contacts c JOIN notification_wechat_bindings b ON b.id=c.wechat_binding_id AND b.tenant_id=c.tenant_id WHERE b.status<>'ACTIVE'")
            : count("SELECT COUNT(*) FROM notification_contacts c JOIN notification_wechat_bindings b ON b.id=c.wechat_binding_id AND b.tenant_id=c.tenant_id WHERE b.status<>'ACTIVE' OR b.app_id<>?", current.appId());
        long rules = count("SELECT COUNT(DISTINCT r.id) FROM notification_rules r JOIN notification_rule_receivers rr ON rr.rule_id=r.id AND rr.tenant_id=r.tenant_id WHERE rr.channel='WECHAT'");
        long pending = count("SELECT COUNT(*) FROM notification_wechat_oauth_states WHERE status IN ('PENDING','CALLBACK_RECEIVED') AND expires_at>CURRENT_TIMESTAMP");
        var callback = jdbc.query("SELECT received_at,event,provider_status FROM notification_wechat_callback_events ORDER BY received_at DESC LIMIT 1",
            (rs, n) -> new Object[]{rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getString(3)});
        var oauth = jdbc.query("SELECT callback_at,status FROM notification_wechat_oauth_states WHERE callback_at IS NOT NULL ORDER BY callback_at DESC LIMIT 1",
            (rs, n) -> new Object[]{rs.getObject(1, OffsetDateTime.class), rs.getString(2)});
        return new Diagnostics(active, stale, contactLinks, staleContactLinks, rules, pending,
            callback.isEmpty() ? null : (OffsetDateTime) callback.getFirst()[0],
            callback.isEmpty() ? null : (String) callback.getFirst()[1],
            callback.isEmpty() ? null : (String) callback.getFirst()[2],
            oauth.isEmpty() ? null : (OffsetDateTime) oauth.getFirst()[0],
            oauth.isEmpty() ? null : (String) oauth.getFirst()[1]);
    }

    private long count(String sql, Object... args) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class, args);
            return value == null ? 0 : value;
        } catch (RuntimeException missingTable) {
            return 0;
        }
    }

    private Row row(boolean lock) {
        var rows = jdbc.query("SELECT * FROM notification_wechat_config WHERE id=1" + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Row(
            rs.getBoolean("enabled"), rs.getString("app_id"), rs.getString("app_secret_cipher"), rs.getString("app_secret_nonce"), rs.getString("app_secret_masked"),
            rs.getString("callback_token_cipher"), rs.getString("callback_token_nonce"), rs.getString("callback_token_masked"),
            rs.getString("encoding_aes_key_cipher"), rs.getString("encoding_aes_key_nonce"), rs.getString("encoding_aes_key_masked"),
            rs.getString("oauth_redirect_uri"), rs.getString("callback_url"), rs.getString("fire_template_id"), rs.getString("fault_template_id"),
            new KeyMapping(rs.getString("fire_device_key"), rs.getString("fire_address_key"), rs.getString("fire_time_key"), rs.getString("fire_event_key")),
            new KeyMapping(rs.getString("fault_device_key"), rs.getString("fault_address_key"), rs.getString("fault_time_key"), rs.getString("fault_event_key")),
            rs.getLong("credentials_revision"), rs.getLong("revision"), rs.getObject("updated_at", OffsetDateTime.class)));
        if (rows.isEmpty()) throw new ApiError(503, "WECHAT_CONFIG_MISSING", "微信公众平台配置缺失");
        return rows.getFirst();
    }

    private String reveal(String cipher, String nonce) {
        if (cipher == null || nonce == null) return null;
        try { return secrets.ready() ? secrets.reveal(cipher, nonce) : null; }
        catch (RuntimeException unavailable) { return null; }
    }

    private static KeyMapping mapping(String device, String address, String time, String event) {
        String deviceKey = keyName(device, "设备");
        String addressKey = keyName(address, "地址");
        String timeKey = keyName(time, "时间");
        String eventKey = keyName(event, "事件");
        Set<String> unique = new HashSet<>();
        for (String key : List.of(deviceKey, addressKey, timeKey, eventKey)) {
            if (key == null) continue;
            if (!unique.add(key)) throw ApiError.bad("同一模板中的字段键不能重复");
        }
        return new KeyMapping(deviceKey, addressKey, timeKey, eventKey);
    }

    private static String keyName(String value, String label) {
        String key = optional(value, 32);
        if (key == null) return null;
        if (!key.matches("[a-z][a-z0-9_]{0,31}")) throw ApiError.bad(label + "模板字段键必须为官方模板键，例如 thing1、time2");
        return key;
    }

    /** Truncates only to the documented display limit of a known template value type. */
    public static String valueFor(String key, String value) {
        if (value == null) return "";
        String type = key == null ? "" : key.replaceAll("[0-9]+$", "");
        Integer limit = VALUE_LIMITS.get(type);
        if (limit == null || value.codePointCount(0, value.length()) <= limit) return value;
        int end = value.offsetByCodePoints(0, limit - 1);
        return value.substring(0, end) + "…";
    }

    private static String validateAppId(String value) {
        if (!value.matches("wx[A-Za-z0-9]{10,30}")) throw ApiError.bad("微信 AppID 格式无效（应以 wx 开头）");
        return value;
    }

    private static String validateAppSecret(String value) {
        if (value.length() != 32 || !value.chars().allMatch(c -> c >= 33 && c <= 126))
            throw ApiError.bad("公众号 AppSecret 必须为 32 位可见 ASCII 字符");
        return value;
    }

    private static String validateCallbackToken(String value) {
        if (value.length() < 3 || value.length() > 32 || !value.chars().allMatch(c -> c >= 33 && c <= 126))
            throw ApiError.bad("服务器配置 Token 必须为 3 至 32 位可见 ASCII 字符");
        return value;
    }

    private static String validateAesKey(String value) {
        if (value.length() != 43 || !value.matches("[A-Za-z0-9+/]{43}"))
            throw ApiError.bad("EncodingAESKey 必须为 43 位 Base64 字符");
        return value;
    }

    private static void validateTemplateId(String value) {
        if (!value.matches("[A-Za-z0-9_-]{6,128}")) throw ApiError.bad("模板 ID 格式无效");
    }

    private static String endpoint(String value, String fixedPath) {
        try {
            return com.fireplatform.transport.NotificationEndpoint.fixedHttpsPath(value, fixedPath);
        } catch (IllegalArgumentException invalid) {
            throw ApiError.bad("回调地址必须是固定 HTTPS 路径 " + fixedPath + "，不能带查询参数或用户信息");
        }
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > max) throw ApiError.bad("公众号配置文本过长");
        return value.trim();
    }

    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }

    private static String maskAppId(String value) {
        if (value == null) return null;
        if (value.length() <= 8) return "****";
        return value.substring(0, 6) + "****" + value.substring(value.length() - 2);
    }
}
