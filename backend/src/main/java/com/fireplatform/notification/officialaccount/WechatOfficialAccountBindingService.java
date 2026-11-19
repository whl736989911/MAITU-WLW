package com.fireplatform.notification.officialaccount;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.IdempotencyStore;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.Pagination;
import com.fireplatform.shared.ApiError;
import com.fireplatform.transport.WechatOfficialAccountClient;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Current-user OAuth binding. A login session on this platform creates a random single-use state;
 * the public OAuth callback only exchanges the code and stores the OpenID for confirmation. It never
 * binds by itself, which is why scanning a desktop QR in another WeChat client still requires the
 * original authenticated session to confirm.
 */
@Service
public class WechatOfficialAccountBindingService {
    public record BindingView(String id, String openidMasked, String appIdMasked, String status, String followState,
                              long revision, OffsetDateTime boundAt, OffsetDateTime lastSeenAt, boolean stale) { }
    public record PendingView(String id, String status, OffsetDateTime requestedAt, OffsetDateTime expiresAt,
                              OffsetDateTime callbackAt, String openidMasked, long revision) { }
    public record StatusView(boolean configured, boolean enabled, boolean oauthReady, boolean secretStorageReady,
                             String appIdMasked, BindingView binding, PendingView pending, long staleBindingCount,
                             boolean canStart, String message) { }
    public record StartResult(String pendingId, String authorizeUrl, String qrPayload, OffsetDateTime expiresAt,
                              String appIdMasked, String redirectUri) { }
    public record CallbackOutcome(boolean ok, String code, String message) { }
    public record BindingRow(String id, String tenantId, String userId, String appId, String openidCipher, String openidNonce,
                             String openidMasked, String openidFingerprint, String status, String followState, long revision,
                             OffsetDateTime boundAt, OffsetDateTime lastSeenAt, boolean userEnabled) { }
    public record RecipientView(String bindingId, String userName, String openidMasked, OffsetDateTime boundAt,
                                long revision, String followState, boolean usable) { }
    public record HistoryView(String id, String event, String maskedOpenid, String actorName, String detailCode, OffsetDateTime createdAt) { }
    public record UnbindResult(String bindingId, String status, long revision) { }

    private record OAuthRow(String id, String stateHash, String audience, String userId, String tenantId, String sessionTokenHash,
                            long accountRevision, String appId, String appFingerprint, long configRevision, long credentialsRevision,
                            String status, long revision, OffsetDateTime createdAt, OffsetDateTime expiresAt,
                            String openidCipher, String openidNonce, String openidMasked, String openidFingerprint,
                            String unionidCipher, String unionidNonce, boolean snapshotUser) { }

    private static final int STATE_BYTES = 32;
    private static final int PENDING_TTL_MINUTES = 10;
    private static final int CONFIRM_TTL_MINUTES = 10;

    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final DatabaseTime time;
    private final WechatOfficialAccountConfigService config;
    private final WechatOfficialAccountClient client;
    private final WechatOfficialAccountSecretStorage secrets;
    private final SecureRandom random = new SecureRandom();

    public WechatOfficialAccountBindingService(JdbcTemplate jdbc, AuthService auth, IdempotencyStore idem, AuditService audit,
                                               DatabaseTime time, WechatOfficialAccountConfigService config,
                                               WechatOfficialAccountClient client, WechatOfficialAccountSecretStorage secrets) {
        this.jdbc = jdbc; this.auth = auth; this.idem = idem; this.audit = audit; this.time = time;
        this.config = config; this.client = client; this.secrets = secrets;
    }

    @Transactional(readOnly = true)
    public StatusView status(AuthService.Identity actor) {
        WechatOfficialAccountConfigService.View configView = config.view();
        String appId = currentAppId();
        BindingView binding = latestBinding(actor.user().id(), appId);
        var pendingRows = jdbc.query("SELECT id,status,created_at,expires_at,callback_at,openid_masked,revision FROM notification_wechat_oauth_states "
                + "WHERE user_id=? AND status IN ('PENDING','CALLBACK_RECEIVED') AND expires_at>CURRENT_TIMESTAMP ORDER BY created_at DESC LIMIT 1",
            (rs, n) -> new PendingView(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class),
                rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class), rs.getString(6), rs.getLong(7)), actor.user().id());
        long stale = appId == null ? jdbc.queryForObject("SELECT COUNT(*) FROM notification_wechat_bindings WHERE user_id=? AND status='ACTIVE'", Long.class, actor.user().id())
            : jdbc.queryForObject("SELECT COUNT(*) FROM notification_wechat_bindings WHERE user_id=? AND status='ACTIVE' AND app_id<>?", Long.class, actor.user().id(), appId);
        boolean oauthReady = configView.enabled() && configView.oauthReady();
        String message;
        if (!configView.configured()) message = "平台尚未配置公众号 AppID/AppSecret/回调参数";
        else if (!configView.enabled()) message = "公众号通道已保存但未启用";
        else if (!configView.oauthReady()) message = "公众号 OAuth 参数或凭据未就绪";
        else if (binding != null) message = "已绑定当前账号；通知是否发送仍由单位通道策略、联系人引用和通知规则共同决定";
        else message = "未绑定；开始授权后仍需本人密码确认，不会自动绑定";
        return new StatusView(configView.configured(), configView.enabled(), oauthReady, configView.secretStorageReady(),
            configView.appIdMasked(), binding, pendingRows.isEmpty() ? null : pendingRows.getFirst(), stale, oauthReady, message);
    }

    @Transactional
    public StartResult start(AuthService.Identity actor) {
        String tenant = actor.user().tenantId();
        auth.lockAndAuthorize(actor, "inbox:read", tenant, actor.audience());
        WechatOfficialAccountConfigService.Snapshot snapshot = config.currentSnapshot();
        if (!snapshot.enabled() || !snapshot.oauthReady())
            throw ApiError.conflict("WECHAT_OAUTH_NOT_CONFIGURED", "公众号 OAuth 未启用或 AppID/AppSecret/回调地址未配置");
        if (snapshot.appId() == null || snapshot.accountFingerprint() == null || snapshot.oauthRedirectUri() == null)
            throw ApiError.conflict("WECHAT_OAUTH_NOT_CONFIGURED", "公众号 OAuth 参数不完整");
        if (!secrets.ready()) throw new ApiError(503, "WECHAT_SECRET_STORAGE_UNAVAILABLE", "公众号密钥存储未配置，不能开始绑定");
        OffsetDateTime now = time.now();
        jdbc.update("UPDATE notification_wechat_oauth_states SET status='EXPIRED',revision=revision+1,finished_at=? "
                + "WHERE user_id=? AND app_id=? AND status IN ('PENDING','CALLBACK_RECEIVED') AND expires_at>?",
            now, actor.user().id(), snapshot.appId(), now);
        byte[] raw = new byte[STATE_BYTES];
        random.nextBytes(raw);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String id = UUID.randomUUID().toString();
        long accountRevision = jdbc.queryForObject("SELECT revision FROM app_users WHERE id=?", Long.class, actor.user().id());
        jdbc.update("INSERT INTO notification_wechat_oauth_states(id,state_hash,audience,user_id,tenant_id,session_token_hash,account_revision,"
                + "app_id,app_fingerprint,config_revision,credentials_revision,status,revision,created_at,expires_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,'PENDING',0,?,?)",
            id, WechatOfficialAccountSecretStorage.sha256(state), actor.audience(), actor.user().id(), tenant, actor.tokenHash(),
            accountRevision, snapshot.appId(), snapshot.accountFingerprint(), snapshot.revision(), snapshot.credentialsRevision(),
            now, now.plusMinutes(PENDING_TTL_MINUTES));
        String authorizeUrl = client.authorizeUrl(snapshot.appId(), snapshot.oauthRedirectUri(), state);
        audit.record(actor.user().id(), tenant, "WECHAT_OAUTH_START", id, "SUCCESS", null);
        return new StartResult(id, authorizeUrl, authorizeUrl, now.plusMinutes(PENDING_TTL_MINUTES), maskAppId(snapshot.appId()), snapshot.oauthRedirectUri());
    }

    /**
     * Public OAuth callback. The state is consumed exactly once and only a code exchange is performed;
     * binding still waits for {@link #confirm}.
     */
    @Transactional
    public CallbackOutcome callback(String state, String code) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}"))
            return new CallbackOutcome(false, "STATE_INVALID", "授权状态无效");
        var rows = jdbc.query("SELECT * FROM notification_wechat_oauth_states WHERE state_hash=? FOR UPDATE",
            (rs, n) -> oauthRow(rs), WechatOfficialAccountSecretStorage.sha256(state));
        if (rows.isEmpty()) return new CallbackOutcome(false, "STATE_INVALID", "授权状态无效或已使用");
        OAuthRow row = rows.getFirst();
        if (!"PENDING".equals(row.status())) return new CallbackOutcome(false, "STATE_REPLAY", "授权状态已使用或已结束");
        OffsetDateTime now = time.now();
        if (row.expiresAt() == null || !row.expiresAt().isAfter(now))
            return new CallbackOutcome(false, "STATE_EXPIRED", "授权已过期，请重新发起");
        WechatOfficialAccountConfigService.Snapshot snapshot = config.currentSnapshot();
        if (!snapshot.enabled() || !Objects.equals(snapshot.appId(), row.appId())
            || !Objects.equals(snapshot.accountFingerprint(), row.appFingerprint())
            || snapshot.credentialsRevision() != row.credentialsRevision())
            return reject(row, "CONFIG_ROTATED", "公众号配置已变更，请重新发起绑定", now);
        if (code == null || code.isBlank()) return reject(row, "OAUTH_DENIED", "未取得授权 code", now);
        WechatOfficialAccountClient.OAuthResult result = client.exchangeCode(row.appId(), snapshot.appSecret(), code);
        if (!result.success()) return reject(row, "OAUTH_EXCHANGE_FAILED", "code 换取 OpenID 失败（" + result.code() + "）", now);
        if (result.snapshotUser()) return reject(row, "SNAPSHOT_USER", "微信返回快照虚拟账号，不能作为真实绑定身份", now);
        if (!secrets.ready()) return reject(row, "SECRET_STORAGE_UNAVAILABLE", "服务器密钥存储不可用", now);
        String fingerprint = openidFingerprint(row.appId(), result.openid());
        var protectedOpenid = secrets.protect(result.openid());
        var protectedUnionid = result.unionid() == null || result.unionid().isBlank() ? null : secrets.protect(result.unionid());
        String masked = maskOpenid(result.openid());
        int changed = jdbc.update("UPDATE notification_wechat_oauth_states SET status='CALLBACK_RECEIVED',revision=revision+1,"
                + "callback_at=?,expires_at=?,openid_cipher=?,openid_nonce=?,openid_masked=?,openid_fingerprint=?,"
                + "unionid_cipher=?,unionid_nonce=?,is_snapshot_user=FALSE,failure_code=NULL WHERE id=? AND status='PENDING' AND revision=?",
            now, now.plusMinutes(CONFIRM_TTL_MINUTES), protectedOpenid.cipher(), protectedOpenid.nonce(), masked, fingerprint,
            protectedUnionid == null ? null : protectedUnionid.cipher(), protectedUnionid == null ? null : protectedUnionid.nonce(),
            row.id(), row.revision());
        if (changed != 1) return new CallbackOutcome(false, "STATE_REPLAY", "授权状态已使用");
        audit.record(null, row.tenantId(), "WECHAT_OAUTH_CALLBACK", row.id(), "SUCCESS", null);
        return new CallbackOutcome(true, "PENDING_CONFIRMATION", "授权已完成，请返回平台原登录页确认绑定");
    }

    @Transactional(noRollbackFor = ApiError.class)
    public BindingView confirm(AuthService.Identity actor, String pendingId, String key, Long expectedRevision,
                               String reason, String requestId) {
        String tenant = actor.user().tenantId();
        auth.lockAndAuthorize(actor, "inbox:read", tenant, actor.audience());
        idem.validateKey(key);
        if (expectedRevision == null) throw ApiError.bad("确认绑定必须提供 pending revision");
        String cleanReason = com.fireplatform.notification.NotificationPolicy.text(reason, 1000);
        String hash = idem.fingerprint(Arrays.asList(tenant, pendingId, expectedRevision, cleanReason));
        BindingView replay = idem.replay(actor.user().id(), "wechat-official-account-bind-confirm", key, hash, BindingView.class);
        if (replay != null) return replay;
        var rows = jdbc.query("SELECT * FROM notification_wechat_oauth_states WHERE id=? AND user_id=? FOR UPDATE",
            (rs, n) -> oauthRow(rs), pendingId, actor.user().id());
        if (rows.isEmpty()) throw ApiError.missing();
        OAuthRow row = rows.getFirst();
        if (!"CALLBACK_RECEIVED".equals(row.status()))
            throw ApiError.conflict("WECHAT_BINDING_NOT_PENDING", row.status().equals("CONFIRMED") ? "该绑定请求已确认" : "授权尚未完成或已失效");
        if (row.revision() != expectedRevision) throw ApiError.conflict("REVISION_CONFLICT", "绑定请求状态已变化，请刷新后重试");
        OffsetDateTime now = time.now();
        if (row.expiresAt() == null || !row.expiresAt().isAfter(now)) throw ApiError.conflict("WECHAT_BINDING_EXPIRED", "绑定确认已过期，请重新发起");
        if (!Objects.equals(row.sessionTokenHash(), actor.tokenHash()))
            throw ApiError.conflict("WECHAT_ORIGINAL_SESSION_REQUIRED", "必须在发起授权的原登录会话中确认绑定");
        if (!Objects.equals(row.audience(), actor.audience())) throw ApiError.forbidden("登录入口与绑定请求不匹配");
        if (!Objects.equals(row.tenantId(), actor.user().tenantId())) throw ApiError.forbidden("绑定请求单位与当前账号不一致");
        WechatOfficialAccountConfigService.Snapshot snapshot = config.currentSnapshot();
        if (!snapshot.enabled() || !Objects.equals(snapshot.accountFingerprint(), row.appFingerprint())
            || snapshot.credentialsRevision() != row.credentialsRevision() || !Objects.equals(snapshot.appId(), row.appId()))
            throw rotated(row, actor, now);
        var account = jdbc.query("SELECT revision,enabled,must_change_password FROM app_users WHERE id=? FOR UPDATE",
            (rs, n) -> new Object[]{rs.getLong(1), rs.getBoolean(2), rs.getBoolean(3)}, actor.user().id());
        if (account.isEmpty() || !Boolean.TRUE.equals(account.getFirst()[1]) || Boolean.TRUE.equals(account.getFirst()[2]))
            throw ApiError.forbidden("账号不可用或必须先修改密码");
        if (((Number) account.getFirst()[0]).longValue() != row.accountRevision())
            throw ApiError.conflict("ACCOUNT_CHANGED", "账号安全信息已变化，请重新发起绑定");
        BindingView existing = activeBindingView(actor.user().id(), row.appId());
        var sameFingerprint = jdbc.queryForList("SELECT id FROM notification_wechat_bindings WHERE user_id=? AND app_id=? "
                + "AND status='ACTIVE' AND openid_fingerprint=? FOR UPDATE", String.class,
            actor.user().id(), row.appId(), row.openidFingerprint());
        if (!sameFingerprint.isEmpty()) {
            String existingId = sameFingerprint.getFirst();
            jdbc.update("UPDATE notification_wechat_bindings SET last_seen_at=?,revision=revision+1 WHERE id=? AND user_id=? AND status='ACTIVE'",
                now, existingId, actor.user().id());
            markConfirmed(row.id(), row.revision(), now);
            BindingView result = activeBindingView(actor.user().id(), row.appId());
            idem.save(actor.user().id(), "wechat-official-account-bind-confirm", key, hash, result);
            audit.recordReason(actor.user().id(), tenant, "WECHAT_BINDING_CONFIRM", row.id(), "ALREADY_BOUND", requestId, cleanReason);
            return result;
        }
        var boundElsewhere = jdbc.queryForList("SELECT id FROM notification_wechat_bindings WHERE app_id=? AND openid_fingerprint=? "
                + "AND status='ACTIVE' AND active_slot='ACTIVE' AND user_id<>? FOR UPDATE", String.class,
            row.appId(), row.openidFingerprint(), actor.user().id());
        if (!boundElsewhere.isEmpty()) {
            jdbc.update("UPDATE notification_wechat_oauth_states SET status='REJECTED',failure_code='OPENID_ALREADY_BOUND',revision=revision+1,finished_at=? WHERE id=?",
                now, row.id());
            audit.recordReason(actor.user().id(), tenant, "WECHAT_BINDING_CONFIRM", row.id(), "DENIED", requestId, cleanReason);
            throw ApiError.conflict("WECHAT_OPENID_ALREADY_BOUND", "该微信已绑定其他平台账号，不能重复绑定");
        }
        String bindingId = UUID.randomUUID().toString();
        if (existing != null) {
            jdbc.update("UPDATE notification_wechat_bindings SET status='REVOKED',active_slot=NULL,revoked_at=?,revoked_reason='REPLACED',revision=revision+1 "
                    + "WHERE id=? AND user_id=? AND status='ACTIVE' AND revision=?",
                now, existing.id(), actor.user().id(), existing.revision());
            jdbc.update("INSERT INTO notification_wechat_binding_history(id,binding_id,tenant_id,user_id,event,masked_openid,actor_id,detail_code,created_at) "
                    + "VALUES (?,?,?,?,'REPLACED',?,?,?,?)",
                UUID.randomUUID().toString(), existing.id(), tenant, actor.user().id(), existing.openidMasked(), actor.user().id(), "REPLACED_BY_NEW_BINDING", now);
        }
        jdbc.update("INSERT INTO notification_wechat_bindings(id,tenant_id,user_id,app_id,openid_cipher,openid_nonce,openid_masked,openid_fingerprint,"
                + "unionid_cipher,unionid_nonce,status,active_slot,follow_state,revision,bound_at,last_seen_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,'ACTIVE','ACTIVE','UNKNOWN',0,?,?)",
            bindingId, tenant, actor.user().id(), row.appId(), row.openidCipher(), row.openidNonce(), row.openidMasked(),
            row.openidFingerprint(), row.unionidCipher(), row.unionidNonce(), now, now);
        jdbc.update("INSERT INTO notification_wechat_binding_history(id,binding_id,tenant_id,user_id,event,masked_openid,actor_id,detail_code,created_at) "
                + "VALUES (?,?,?,?,'BOUND',?,?,?,?)",
            UUID.randomUUID().toString(), bindingId, tenant, actor.user().id(), row.openidMasked(), actor.user().id(), null, now);
        markConfirmed(row.id(), row.revision(), now);
        BindingView result = activeBindingView(actor.user().id(), row.appId());
        idem.save(actor.user().id(), "wechat-official-account-bind-confirm", key, hash, result);
        audit.recordReason(actor.user().id(), tenant, "WECHAT_BINDING_CONFIRM", row.id(), "SUCCESS", requestId, cleanReason);
        return result;
    }

    private ApiError rotated(OAuthRow row, AuthService.Identity actor, OffsetDateTime now) {
        jdbc.update("UPDATE notification_wechat_oauth_states SET status='REJECTED',failure_code='CONFIG_ROTATED',revision=revision+1,finished_at=? WHERE id=?",
            now, row.id());
        audit.record(actor.user().id(), row.tenantId(), "WECHAT_BINDING_CONFIRM", row.id(), "DENIED", null);
        return ApiError.conflict("WECHAT_CONFIG_ROTATED", "公众号配置已变更，请重新发起绑定");
    }

    private void markConfirmed(String id, long revision, OffsetDateTime now) {
        int changed = jdbc.update("UPDATE notification_wechat_oauth_states SET status='CONFIRMED',revision=revision+1,finished_at=? "
            + "WHERE id=? AND status='CALLBACK_RECEIVED' AND revision=?", now, id, revision);
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "绑定请求状态已变化，请刷新后重试");
    }

    @Transactional
    public void cancel(AuthService.Identity actor, String pendingId) {
        String tenant = actor.user().tenantId();
        auth.lockAndAuthorize(actor, "inbox:read", tenant, actor.audience());
        OffsetDateTime now = time.now();
        int changed = jdbc.update("UPDATE notification_wechat_oauth_states SET status='REJECTED',failure_code='USER_CANCELLED',revision=revision+1,finished_at=? "
                + "WHERE id=? AND user_id=? AND status IN ('PENDING','CALLBACK_RECEIVED')", now, pendingId, actor.user().id());
        if (changed != 1) throw ApiError.missing();
        audit.record(actor.user().id(), tenant, "WECHAT_OAUTH_CANCEL", pendingId, "SUCCESS", null);
    }

    @Transactional
    public UnbindResult unbind(AuthService.Identity actor, Long expectedRevision, String reason, String key, String requestId) {
        String tenant = actor.user().tenantId();
        auth.lockAndAuthorize(actor, "inbox:read", tenant, actor.audience());
        idem.validateKey(key);
        if (expectedRevision == null) throw ApiError.bad("解除绑定必须提供 revision");
        String cleanReason = com.fireplatform.notification.NotificationPolicy.text(reason, 1000);
        String hash = idem.fingerprint(Arrays.asList(tenant, expectedRevision, cleanReason));
        UnbindResult replay = idem.replay(actor.user().id(), "wechat-official-account-unbind", key, hash, UnbindResult.class);
        if (replay != null) return replay;
        String appId = currentAppId();
        var rows = jdbc.query("SELECT b.*,u.enabled AS user_enabled FROM notification_wechat_bindings b JOIN app_users u ON u.id=b.user_id "
                + "WHERE b.user_id=? AND b.app_id=? AND b.status='ACTIVE' FOR UPDATE",
            (rs, n) -> bindingRow(rs), actor.user().id(), appId);
        if (rows.isEmpty()) throw ApiError.missing();
        BindingRow binding = rows.getFirst();
        if (binding.revision() != expectedRevision) throw ApiError.conflict("REVISION_CONFLICT", "绑定状态已变化，请刷新后重试");
        OffsetDateTime now = time.now();
        int changed = jdbc.update("UPDATE notification_wechat_bindings SET status='REVOKED',active_slot=NULL,revoked_at=?,revoked_reason=?,revision=revision+1 "
                + "WHERE id=? AND user_id=? AND status='ACTIVE' AND revision=?",
            now, "USER_UNBOUND", binding.id(), actor.user().id(), expectedRevision);
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "绑定状态已变化，请刷新后重试");
        jdbc.update("INSERT INTO notification_wechat_binding_history(id,binding_id,tenant_id,user_id,event,masked_openid,actor_id,detail_code,created_at) "
                + "VALUES (?,?,?,?,'UNBOUND',?,?,?,?)",
            UUID.randomUUID().toString(), binding.id(), tenant, actor.user().id(), binding.openidMasked(), actor.user().id(), "USER_UNBOUND", now);
        UnbindResult result = new UnbindResult(binding.id(), "REVOKED", binding.revision() + 1);
        idem.save(actor.user().id(), "wechat-official-account-unbind", key, hash, result);
        audit.recordReason(actor.user().id(), tenant, "WECHAT_BINDING_UNBIND", binding.id(), "SUCCESS", requestId, cleanReason);
        return result;
    }

    @Transactional(readOnly = true)
    public Page<HistoryView> history(AuthService.Identity actor, int page, Integer size) {
        var p = Pagination.of(page, size, 20);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM notification_wechat_binding_history WHERE user_id=?", Long.class, actor.user().id());
        var items = jdbc.query("SELECT h.id,h.event,h.masked_openid,h.detail_code,h.created_at,u.display_name AS actor_name "
                + "FROM notification_wechat_binding_history h LEFT JOIN app_users u ON u.id=h.actor_id "
                + "WHERE h.user_id=? ORDER BY h.created_at DESC,h.id DESC LIMIT ? OFFSET ?",
            (rs, n) -> new HistoryView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(6), rs.getString(4),
                rs.getObject(5, OffsetDateTime.class)), actor.user().id(), p.size(), p.offset());
        return new Page<>(items, total, p.page(), p.size());
    }

    /** Real named selector data for unit contacts/rules. OpenID is always masked. */
    @Transactional(readOnly = true)
    public List<RecipientView> recipients(String tenant) {
        String appId = currentAppId();
        if (appId == null) return List.of();
        return jdbc.query("SELECT b.id,u.display_name,b.openid_masked,b.bound_at,b.revision,b.follow_state,b.status,b.app_id,u.enabled "
                + "FROM notification_wechat_bindings b JOIN app_users u ON u.id=b.user_id "
                + "WHERE b.tenant_id=? AND b.status='ACTIVE' AND b.app_id=? AND u.enabled=TRUE ORDER BY u.display_name,b.bound_at DESC",
            (rs, n) -> new RecipientView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class),
                rs.getLong(5), rs.getString(6), "ACTIVE".equals(rs.getString(7)) && !"UNSUBSCRIBED".equals(rs.getString(6))), tenant, appId);
    }

    /** Validates a contact/rule reference against the current account and recipient opt-in. */
    public BindingRow requireUsable(String tenant, String bindingId) {
        BindingRow row = activeBinding(tenant, bindingId);
        if (row == null || !row.userEnabled()) throw ApiError.conflict("WECHAT_BINDING_UNAVAILABLE", "所选微信绑定不存在、已解绑或用户已停用");
        String appId = currentAppId();
        if (appId == null || !appId.equals(row.appId())) throw ApiError.conflict("WECHAT_BINDING_STALE", "所选微信绑定属于旧公众号，请重新绑定");
        if ("UNSUBSCRIBED".equals(row.followState())) throw ApiError.conflict("WECHAT_RECIPIENT_UNSUBSCRIBED", "该用户已取消关注公众号，不能作为收件人");
        if (!secrets.ready()) throw new ApiError(503, "WECHAT_SECRET_STORAGE_UNAVAILABLE", "公众号密钥存储未配置");
        return row;
    }

    @Transactional(readOnly = true)
    public BindingRow activeBinding(String tenant, String bindingId) {
        if (bindingId == null || bindingId.isBlank()) return null;
        var rows = jdbc.query("SELECT b.*,u.enabled AS user_enabled FROM notification_wechat_bindings b JOIN app_users u ON u.id=b.user_id "
                + "WHERE b.id=? AND b.tenant_id=? AND b.status='ACTIVE'", (rs, n) -> bindingRow(rs), bindingId, tenant);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Provider subscribe/unsubscribe events update real follow state; no binding is created or removed. */
    @Transactional
    public void markFollow(String appId, String openidFingerprint, boolean subscribed) {
        jdbc.update("UPDATE notification_wechat_bindings SET follow_state=?,last_seen_at=?,revision=revision+1 "
                + "WHERE app_id=? AND openid_fingerprint=? AND status='ACTIVE'",
            subscribed ? "SUBSCRIBED" : "UNSUBSCRIBED", time.now(), appId, openidFingerprint);
    }

    public String currentAppId() {
        String appId = config.currentSnapshot().appId();
        return appId == null || appId.isBlank() ? null : appId;
    }

    public static String openidFingerprint(String appId, String openid) {
        return WechatOfficialAccountSecretStorage.sha256(appId + "\u0000" + openid);
    }

    public static String maskOpenid(String openid) {
        if (openid == null) return null;
        if (openid.length() <= 8) return "****";
        return openid.substring(0, 4) + "****" + openid.substring(openid.length() - 4);
    }

    private CallbackOutcome reject(OAuthRow row, String code, String message, OffsetDateTime now) {
        jdbc.update("UPDATE notification_wechat_oauth_states SET status='REJECTED',failure_code=?,revision=revision+1,finished_at=? WHERE id=? AND status='PENDING'",
            code, now, row.id());
        audit.record(null, row.tenantId(), "WECHAT_OAUTH_CALLBACK", row.id(), "DENIED", null);
        return new CallbackOutcome(false, code, message);
    }

    private BindingView latestBinding(String userId, String appId) {
        BindingView current = appId == null ? null : activeBindingView(userId, appId);
        if (current != null) return current;
        var rows = jdbc.query("SELECT b.*,u.enabled AS user_enabled FROM notification_wechat_bindings b JOIN app_users u ON u.id=b.user_id "
                + "WHERE b.user_id=? AND b.status='ACTIVE' ORDER BY b.bound_at DESC LIMIT 1",
            (rs, n) -> bindingRow(rs), userId);
        if (rows.isEmpty()) return null;
        BindingRow row = rows.getFirst();
        return view(row, !Objects.equals(row.appId(), appId));
    }

    private BindingView activeBindingView(String userId, String appId) {
        var rows = jdbc.query("SELECT b.*,u.enabled AS user_enabled FROM notification_wechat_bindings b JOIN app_users u ON u.id=b.user_id "
                + "WHERE b.user_id=? AND b.app_id=? AND b.status='ACTIVE' ORDER BY b.bound_at DESC LIMIT 1",
            (rs, n) -> bindingRow(rs), userId, appId);
        return rows.isEmpty() ? null : view(rows.getFirst(), false);
    }

    private static BindingView view(BindingRow row, boolean stale) {
        return new BindingView(row.id(), row.openidMasked(), maskAppId(row.appId()), row.status(), row.followState(),
            row.revision(), row.boundAt(), row.lastSeenAt(), stale);
    }

    private static BindingRow bindingRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BindingRow(rs.getString("id"), rs.getString("tenant_id"), rs.getString("user_id"), rs.getString("app_id"),
            rs.getString("openid_cipher"), rs.getString("openid_nonce"), rs.getString("openid_masked"), rs.getString("openid_fingerprint"),
            rs.getString("status"), rs.getString("follow_state"), rs.getLong("revision"), rs.getObject("bound_at", OffsetDateTime.class),
            rs.getObject("last_seen_at", OffsetDateTime.class), rs.getBoolean("user_enabled"));
    }

    private static OAuthRow oauthRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OAuthRow(rs.getString("id"), rs.getString("state_hash"), rs.getString("audience"), rs.getString("user_id"),
            rs.getString("tenant_id"), rs.getString("session_token_hash"), rs.getLong("account_revision"), rs.getString("app_id"),
            rs.getString("app_fingerprint"), rs.getLong("config_revision"), rs.getLong("credentials_revision"), rs.getString("status"),
            rs.getLong("revision"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
            rs.getString("openid_cipher"), rs.getString("openid_nonce"), rs.getString("openid_masked"), rs.getString("openid_fingerprint"),
            rs.getString("unionid_cipher"), rs.getString("unionid_nonce"), rs.getBoolean("is_snapshot_user"));
    }

    private static String maskAppId(String value) {
        if (value == null) return null;
        if (value.length() <= 8) return "****";
        return value.substring(0, 6) + "****" + value.substring(value.length() - 2);
    }
}
