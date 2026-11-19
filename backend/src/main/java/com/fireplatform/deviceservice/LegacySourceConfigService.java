package com.fireplatform.deviceservice;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.DataTransferPayloadCipher;
import com.fireplatform.shared.Digests;
import com.fireplatform.shared.IdempotencyStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-controlled legacy TTL/TSF management origin and account. Username and password are
 * encrypted with the shared payload cipher and are write-only through the API; only a stable
 * account fingerprint is returned in clear text. An HTTP origin requires both an explicit operator
 * acknowledgement and a deployment-approved origin. The stored scheme is never downgraded.
 */
@Service
public class LegacySourceConfigService {
    public record Config(boolean configured, boolean enabled, String code, String origin, String username,
                         String password, boolean allowInsecureHttp, String usernameMasked,
                         String accountFingerprint, long revision) { }
    public record View(String driverKey, String displayName, String origin, String usernameMasked,
                       boolean credentialsStored, boolean allowInsecureHttp, boolean enabled, boolean configured,
                       String accountFingerprint, long revision, OffsetDateTime updatedAt) { }
    private record Row(String origin, String usernameCipher, String usernameNonce, String passwordCipher,
                       String passwordNonce, String accountFingerprint, boolean allowInsecureHttp,
                       boolean enabled, long revision, OffsetDateTime updatedAt) { }

    private final JdbcTemplate jdbc;
    private final DataTransferPayloadCipher cipher;
    private final ManagementGuard guard;
    private final AuditService audit;
    private final IdempotencyStore idem;
    private final Clock clock;
    private final Set<String> approvedOrigins;

    public LegacySourceConfigService(JdbcTemplate jdbc, DataTransferPayloadCipher cipher, ManagementGuard guard,
                                     AuditService audit, IdempotencyStore idem, Clock clock,
                                     @Value("${app.device-service.legacy-approved-origins:${APP_LEGACY_SOURCE_APPROVED_ORIGINS:}}") String approvedOrigins) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.guard = guard;
        this.audit = audit;
        this.idem = idem;
        this.clock = clock;
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : Arrays.stream(approvedOrigins.split("[,\\s]+")).filter(item -> !item.isBlank()).toList()) {
            try { normalized.add(normalizeOrigin(value)); }
            catch (ApiError invalid) { throw new IllegalStateException("部署审批的旧平台来源地址无效", invalid); }
        }
        this.approvedOrigins = Set.copyOf(normalized);
    }

    public boolean hasApprovedOrigins() { return !approvedOrigins.isEmpty(); }

    public Config load(String driverKey) {
        List<Row> rows = rows(driverKey, false);
        if (rows.isEmpty()) return new Config(false, false, "NOT_CONFIGURED", null, null, null, false, null, null, 0);
        Row row = rows.getFirst();
        String originCode = originFault(row);
        if (originCode != null) return new Config(false, row.enabled(), originCode, row.origin(), null, null,
            row.allowInsecureHttp(), null, row.accountFingerprint(), row.revision());
        String username = null, password = null;
        try {
            username = decrypt("username", driverKey, row.usernameCipher(), row.usernameNonce());
            password = decrypt("password", driverKey, row.passwordCipher(), row.passwordNonce());
        } catch (ApiError unreadable) {
            return new Config(false, row.enabled(), "CREDENTIAL_UNREADABLE", row.origin(), null, null,
                row.allowInsecureHttp(), null, row.accountFingerprint(), row.revision());
        }
        boolean readable = username != null && !username.isBlank() && password != null && !password.isBlank();
        String code = !readable ? "CREDENTIAL_UNAVAILABLE" : !row.enabled() ? "DISABLED" : "READY";
        return new Config(readable, row.enabled(), code, row.origin(), username, password,
            row.allowInsecureHttp(), DeviceServiceSecrets.mask(username), row.accountFingerprint(), row.revision());
    }

    /**
     * Re-checks the deployment allowlist and the recorded HTTP risk acknowledgement every time a stored
     * origin is used. A tampered or legacy row can never become an outbound target merely by existing.
     */
    private String originFault(Row row) {
        String normalized;
        try { normalized = normalizeOrigin(row.origin()); }
        catch (ApiError invalid) { return "ORIGIN_INVALID"; }
        if (!normalized.equals(row.origin())) return "ORIGIN_INVALID";
        if (!approvedOrigins.contains(normalized)) return "ORIGIN_NOT_APPROVED";
        if (normalized.startsWith("http://") && !row.allowInsecureHttp()) return "HTTP_RISK_NOT_ACKNOWLEDGED";
        if (normalized.startsWith("https://") && row.allowInsecureHttp()) return "HTTP_RISK_NOT_APPLICABLE";
        return null;
    }

    public List<View> views(List<String> driverKeys, java.util.function.Function<String, String> displayName) {
        List<View> result = new java.util.ArrayList<>();
        for (String key : driverKeys) {
            Config config = load(key);
            List<Row> rows = rows(key, false);
            result.add(new View(key, displayName.apply(key), config.origin(), config.usernameMasked(),
                config.configured(), config.allowInsecureHttp(), config.enabled(), config.configured(),
                config.accountFingerprint(), config.revision(), rows.isEmpty() ? null : rows.getFirst().updatedAt()));
        }
        return List.copyOf(result);
    }

    @Transactional
    public View update(AuthService.Identity actor, String driverKey, String displayName,
                       DeviceServiceInputs.UpdateLegacySource input, String key, String requestId) {
        guard.begin(actor, "integrations:write", null, "management");
        idem.validateKey(key);
        if (!driverKey.matches("[A-Z][A-Z0-9_]{1,63}"))
            throw ApiError.bad("接入驱动标识无效");
        if (!hasApprovedOrigins())
            throw ApiError.conflict("LEGACY_ORIGINS_REQUIRED", "部署未配置旧平台来源白名单（APP_LEGACY_SOURCE_APPROVED_ORIGINS），不能保存真实外发配置");
        String origin = normalizeOrigin(input.origin());
        if (!approvedOrigins.contains(origin))
            throw new ApiError(400, "LEGACY_ORIGIN_NOT_APPROVED", "旧平台来源不在部署审批白名单中");
        boolean http = origin.startsWith("http://");
        if (http && !input.allowInsecureHttp())
            throw ApiError.conflict("HTTP_RISK_ACK_REQUIRED", "明文HTTP来源必须由操作者显式确认风险；不会自动降级HTTPS");
        if (!http && input.allowInsecureHttp())
            throw new ApiError(400, "HTTP_RISK_NOT_APPLICABLE", "HTTPS来源不需要明文HTTP风险确认");
        String username = input.username() == null ? "" : input.username().trim();
        if (username.isEmpty() || username.length() > 200)
            throw ApiError.bad("账号名称必须是1-200字符");
        String password = input.password() == null ? "" : input.password();
        if (password.length() > 500) throw ApiError.bad("账号密码超过允许长度");
        if (!cipher.ready()) throw new ApiError(503, "SERVICE_SECRET_STORAGE_UNAVAILABLE", "未配置应用加密主密钥，不能保存旧平台账号");
        String fingerprint = Digests.sha256(origin + "\n" + username);
        String hash = idem.fingerprint(List.of(driverKey, input.expectedRevision(), origin, username, password,
            input.allowInsecureHttp(), input.enabled()));
        View replay = idem.replay(actor.user().id(), "legacy-source-config", key, hash, View.class);
        if (replay != null) return replay;
        List<Row> rows = rows(driverKey, true);
        Row old = rows.isEmpty() ? null : rows.getFirst();
        long revision = old == null ? 0 : old.revision();
        if (input.expectedRevision() != revision)
            throw ApiError.conflict("REVISION_CONFLICT", "旧平台来源配置已更新，请刷新后重试");
        if (password.isEmpty()) {
            if (old == null) throw ApiError.bad("首次保存必须填写账号密码");
            password = decrypt("password", driverKey, old.passwordCipher(), old.passwordNonce());
        }
        var usernameCipher = cipher.encryptScope(scope(driverKey) + "username", username.getBytes(StandardCharsets.UTF_8));
        var passwordCipher = cipher.encryptScope(scope(driverKey) + "password", password.getBytes(StandardCharsets.UTF_8));
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (old == null) {
            jdbc.update("INSERT INTO legacy_source_config(driver_key,origin,username_cipher,username_nonce,password_cipher,password_nonce,account_fingerprint,allow_insecure_http,enabled,revision,updated_at,updated_by) VALUES(?,?,?,?,?,?,?,?,?,1,?,?)",
                driverKey, origin, usernameCipher.ciphertext(), usernameCipher.nonce(), passwordCipher.ciphertext(),
                passwordCipher.nonce(), fingerprint, input.allowInsecureHttp(), input.enabled(), now, actor.user().id());
        } else {
            jdbc.update("UPDATE legacy_source_config SET origin=?,username_cipher=?,username_nonce=?,password_cipher=?,password_nonce=?,account_fingerprint=?,allow_insecure_http=?,enabled=?,revision=revision+1,updated_at=?,updated_by=? WHERE driver_key=?",
                origin, usernameCipher.ciphertext(), usernameCipher.nonce(), passwordCipher.ciphertext(),
                passwordCipher.nonce(), fingerprint, input.allowInsecureHttp(), input.enabled(), now,
                actor.user().id(), driverKey);
        }
        View view = new View(driverKey, displayName, origin, DeviceServiceSecrets.mask(username), true,
            input.allowInsecureHttp(), input.enabled(), true, fingerprint, revision + 1, now);
        audit.recordReason(actor.user().id(), null, "LEGACY_SOURCE_CONFIG", driverKey, "SUCCESS", requestId,
            "origin=" + origin + "; enabled=" + input.enabled() + "; allow_insecure_http=" + input.allowInsecureHttp()
                + "; account_fingerprint=" + fingerprint);
        idem.save(actor.user().id(), "legacy-source-config", key, hash, view);
        return view;
    }

    public String normalizeOrigin(String raw) {
        URI uri;
        try { uri = URI.create(raw == null ? "" : raw.trim()); }
        catch (RuntimeException invalid) { throw ApiError.bad("旧平台来源必须是合法URL"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https"))
            throw ApiError.bad("旧平台来源只支持HTTP或HTTPS，且必须显式保留协议，不能自动降级");
        String host = uri.getHost();
        if (host == null || host.isBlank() || host.length() > 253)
            throw ApiError.bad("旧平台来源主机无效");
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw ApiError.bad("旧平台来源不能包含用户信息、查询串或片段");
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/"))
            throw ApiError.bad("旧平台来源只接受协议、主机和端口，不能带路径");
        int port = uri.getPort();
        boolean defaultPort = (scheme.equals("http") && (port == -1 || port == 80))
            || (scheme.equals("https") && (port == -1 || port == 443));
        if (port != -1 && (port < 1 || port > 65535)) throw ApiError.bad("旧平台来源端口无效");
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.indexOf(':') >= 0)
            throw ApiError.bad("旧平台来源主机不接受IPv6字面量；请使用部署审批的DNS名称或IPv4地址");
        if (normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost") || normalizedHost.equals("::1")
            || normalizedHost.equals("[::1]") || isPrivateLiteral(normalizedHost))
            throw ApiError.bad("旧平台来源不能指向本机或私有地址");
        return scheme + "://" + normalizedHost + (defaultPort ? "" : ":" + port);
    }

    private List<Row> rows(String driverKey, boolean lock) {
        return jdbc.query("SELECT origin,username_cipher,username_nonce,password_cipher,password_nonce,account_fingerprint,allow_insecure_http,enabled,revision,updated_at FROM legacy_source_config WHERE driver_key=?" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getBoolean(7), rs.getBoolean(8), rs.getLong(9),
                rs.getObject(10, OffsetDateTime.class)), driverKey);
    }

    private String decrypt(String field, String driverKey, String ciphertext, String nonce) {
        if (ciphertext == null || nonce == null) throw new ApiError(409, "CREDENTIAL_UNREADABLE", "旧平台账号字段缺失");
        try {
            return new String(cipher.decryptScopeBytes(scope(driverKey) + field, ciphertext, nonce), StandardCharsets.UTF_8);
        } catch (RuntimeException failure) {
            throw new ApiError(409, "CREDENTIAL_UNREADABLE", "旧平台账号密文无法解密");
        }
    }

    private static boolean isPrivateLiteral(String host) {
        if (!host.matches("[0-9.]+")) return false;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return true;
        try {
            int first = Integer.parseInt(parts[0]), second = Integer.parseInt(parts[1]);
            if (first == 0 || first == 10 || first == 127) return true;
            if (first == 169 && second == 254) return true;
            if (first == 172 && second >= 16 && second <= 31) return true;
            return first == 192 && second == 168;
        } catch (NumberFormatException invalid) { return true; }
    }

    private static String scope(String driverKey) { return "legacy-source-v1\u0000" + driverKey + "\u0000"; }
}
