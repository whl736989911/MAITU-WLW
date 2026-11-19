package com.fireplatform.deviceservice;

import com.fireplatform.shared.DataTransferPayloadCipher;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.Digests;
import com.fireplatform.shared.IdempotencyStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side configuration for the standard HTTPS product-model gateway. Tokens are encrypted with
 * the shared payload cipher and only a stable account fingerprint is persisted in clear text.
 */
@Service
public class IntegrationDriverConfigService {
    public record Config(boolean configured, boolean enabled, String code, String endpoint, String token,
                         String tokenMasked, String accountFingerprint, long revision) { }
    public record View(String driverKey, String endpoint, String tokenMasked, boolean enabled, boolean configured,
                       String accountFingerprint, long revision, OffsetDateTime updatedAt) { }
    private record Row(String endpoint, String tokenCipher, String tokenNonce, String accountFingerprint,
                       boolean enabled, long revision, OffsetDateTime updatedAt) { }

    private final JdbcTemplate jdbc;
    private final DataTransferPayloadCipher cipher;
    private final ManagementGuard guard;
    private final AuditService audit;
    private final IdempotencyStore idem;
    private final Clock clock;
    private final java.util.Set<String> approvedHosts;

    public IntegrationDriverConfigService(JdbcTemplate jdbc, DataTransferPayloadCipher cipher, ManagementGuard guard,
                                          AuditService audit, IdempotencyStore idem, Clock clock,
                                          @org.springframework.beans.factory.annotation.Value("${app.device-service.approved-hosts:${APP_DEVICE_SERVICE_APPROVED_HOSTS:}}") String approvedHosts) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.guard = guard;
        this.audit = audit;
        this.idem = idem;
        this.clock = clock;
        this.approvedHosts = java.util.Arrays.stream(approvedHosts.split("[,\\s]+"))
            .map(value -> value.trim().toLowerCase(java.util.Locale.ROOT)).filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean hasApprovedHosts() { return !approvedHosts.isEmpty(); }

    public Config load(String driverKey) {
        List<Row> rows = jdbc.query("SELECT endpoint,token_cipher,token_nonce,account_fingerprint,enabled,revision,updated_at FROM integration_driver_config WHERE driver_key=?",
            (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getLong(6), rs.getObject(7, OffsetDateTime.class)), driverKey);
        if (rows.isEmpty()) return new Config(false, false, "NOT_CONFIGURED", null, null, null, null, 0);
        Row row = rows.getFirst();
        String token = null;
        boolean readable = false;
        try {
            token = new String(cipher.decryptScopeBytes(scope(driverKey), row.tokenCipher(), row.tokenNonce()), StandardCharsets.UTF_8);
            readable = token != null && !token.isBlank();
        } catch (ApiError unavailable) {
            readable = false;
        }
        String code = !readable ? "TOKEN_UNAVAILABLE" : !row.enabled() ? "DISABLED" : "READY";
        return new Config(readable && row.enabled(), row.enabled(), code, row.endpoint(),
            readable ? token : null, DeviceServiceSecrets.mask(readable ? token : null), row.accountFingerprint(), row.revision());
    }

    public View view(String driverKey) {
        Config config = load(driverKey);
        List<Row> rows = jdbc.query("SELECT endpoint,token_cipher,token_nonce,account_fingerprint,enabled,revision,updated_at FROM integration_driver_config WHERE driver_key=?",
            (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getLong(6), rs.getObject(7, OffsetDateTime.class)), driverKey);
        return new View(driverKey, config.endpoint(), config.tokenMasked(), config.enabled(), config.configured(),
            config.accountFingerprint(), config.revision(), rows.isEmpty() ? null : rows.getFirst().updatedAt());
    }

    @Transactional
    public View update(AuthService.Identity actor, String driverKey, DeviceServiceInputs.UpdateDriverConfig input,
                       String key, String requestId) {
        guard.begin(actor, "integrations:write", null, "management");
        idem.validateKey(key);
        if (!hasApprovedHosts())
            throw ApiError.conflict("APPROVED_HOSTS_REQUIRED", "部署未配置允许的外发网关主机白名单（APP_DEVICE_SERVICE_APPROVED_HOSTS），不能保存真实外发配置");
        String endpoint = endpoint(input.endpoint());
        String suppliedToken = input.token() == null ? "" : input.token().trim();
        if (suppliedToken.length() > 4000) throw ApiError.bad("网关令牌超过允许长度");
        if (!cipher.ready()) throw new ApiError(503, "SERVICE_SECRET_STORAGE_UNAVAILABLE", "未配置应用加密主密钥，不能保存网关令牌");
        List<Row> rows = jdbc.query("SELECT endpoint,token_cipher,token_nonce,account_fingerprint,enabled,revision,updated_at FROM integration_driver_config WHERE driver_key=? FOR UPDATE",
            (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getLong(6), rs.getObject(7, OffsetDateTime.class)), driverKey);
        Row old = rows.isEmpty() ? null : rows.getFirst();
        long revision = old == null ? 0 : old.revision();
        String token = suppliedToken;
        if (token.isEmpty()) {
            if (old == null) throw ApiError.bad("首次保存必须填写网关访问令牌");
            token = new String(cipher.decryptScopeBytes(scope(driverKey), old.tokenCipher(), old.tokenNonce()), StandardCharsets.UTF_8);
        }
        String fingerprint = Digests.sha256(endpoint + "\n" + token);
        String hash = idem.fingerprint(List.of(driverKey, input.expectedRevision(), endpoint, token, input.enabled()));
        View replay = idem.replay(actor.user().id(), "integration-driver-config", key, hash, View.class);
        if (replay != null) return replay;
        if (input.expectedRevision() != revision) throw ApiError.conflict("REVISION_CONFLICT", "接入驱动配置已更新，请刷新后重试");
        var encrypted = cipher.encryptScope(scope(driverKey), token.getBytes(StandardCharsets.UTF_8));
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (old == null) {
            jdbc.update("INSERT INTO integration_driver_config(driver_key,endpoint,token_cipher,token_nonce,account_fingerprint,enabled,revision,updated_at,updated_by) VALUES(?,?,?,?,?,?,1,?,?)",
                driverKey, endpoint, encrypted.ciphertext(), encrypted.nonce(), fingerprint, input.enabled(), now, actor.user().id());
        } else {
            jdbc.update("UPDATE integration_driver_config SET endpoint=?,token_cipher=?,token_nonce=?,account_fingerprint=?,enabled=?,revision=revision+1,updated_at=?,updated_by=? WHERE driver_key=?",
                endpoint, encrypted.ciphertext(), encrypted.nonce(), fingerprint, input.enabled(), now, actor.user().id(), driverKey);
        }
        View result = new View(driverKey, endpoint, DeviceServiceSecrets.mask(token), input.enabled(), input.enabled(),
            fingerprint, revision + 1, now);
        audit.recordReason(actor.user().id(), null, "INTEGRATION_DRIVER_CONFIG", driverKey, "SUCCESS", requestId,
            "endpoint=" + endpoint + "; enabled=" + input.enabled());
        idem.save(actor.user().id(), "integration-driver-config", key, hash, result);
        return result;
    }

    /** Revalidates the persisted endpoint against the live deployment allowlist at send time. */
    public String approvedEndpoint(String raw) {
        return endpoint(raw);
    }
    /** Blocks trivially unsafe literal hosts; hosts must also be explicitly deployment-approved. */
    private String endpoint(String raw) {
        URI uri;
        try { uri = URI.create(raw == null ? "" : raw.trim()); }
        catch (RuntimeException error) { throw ApiError.bad("网关endpoint不是合法URL"); }
        String scheme = uri.getScheme(), host = uri.getHost(), path = uri.getRawPath();
        if (!"https".equalsIgnoreCase(scheme) || host == null || host.isBlank() || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443))
            throw ApiError.bad("网关endpoint必须是标准443端口HTTPS地址，且不能包含用户信息、查询串或片段");
        if (isBlockedLiteralHost(host)) throw ApiError.bad("网关endpoint不能指向本机或私有地址");
        if (!approvedHosts.contains(host.toLowerCase(java.util.Locale.ROOT)))
            throw ApiError.bad("网关endpoint主机不在部署审批白名单中");
        String normalizedPath = path == null || path.isEmpty() ? "" : path;
        if (normalizedPath.length() > 400) throw ApiError.bad("网关endpoint路径过长");
        String normalized = "https://" + host.toLowerCase(java.util.Locale.ROOT) + normalizedPath;
        if (normalized.length() > 500) throw ApiError.bad("网关endpoint超过允许长度");
        return normalized;
    }

    private static boolean isBlockedLiteralHost(String host) {
        String value = host.toLowerCase(java.util.Locale.ROOT);
        if (value.equals("localhost") || value.endsWith(".localhost") || value.equals("[::1]") || value.equals("::1")) return true;
        if (!value.matches("[0-9.]+")) return false;
        String[] parts = value.split("\\.");
        if (parts.length != 4) return true;
        try {
            int first = Integer.parseInt(parts[0]), second = Integer.parseInt(parts[1]);
            if (first == 0 || first == 10 || first == 127) return true;
            if (first == 169 && second == 254) return true;
            if (first == 172 && second >= 16 && second <= 31) return true;
            return first == 192 && second == 168;
        } catch (NumberFormatException error) { return true; }
    }

    private static String scope(String driverKey) { return "device-service-driver-v1\u0000" + driverKey; }
}
