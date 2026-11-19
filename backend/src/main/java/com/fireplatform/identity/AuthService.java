package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.Limits;
import com.fireplatform.shared.Digests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService implements WriteAuthorization {
    public record LoginRequest(@NotBlank @Size(max=100) String username, @NotBlank @Size(max=128) String password,
        @NotBlank @Pattern(regexp="management|customer") String audience) { }
    public record ChangePasswordRequest(@NotBlank @Size(max=128) String currentPassword,
        @NotBlank @Size(min=12,max=128) String newPassword) { }
    public record UserView(String id, String username, String name, String userType, String tenantId, boolean mustChangePassword) { }
    public record Identity(UserView user, List<String> permissions, String audience, String tokenHash) { }
    public record SessionView(UserView user, List<String> permissions) { }
    public record LoginResult(String accessToken, UserView user, List<String> permissions) { }
    private record Credential(UserView user, String passwordHash, boolean enabled) { }
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final AuditService audit;
    private final IdentityExpiryService expiry;
    private final SecureRandom random = new SecureRandom();
    // Used to keep unknown-user verification comparable to a wrong-password request.
    private final String dummyHash;
    public AuthService(JdbcTemplate jdbc, PasswordEncoder encoder, Clock clock, AuditService audit, IdentityExpiryService expiry) {
        this.jdbc = jdbc; this.encoder = encoder; this.clock = clock; this.audit = audit; this.expiry = expiry;
        dummyHash = encoder.encode(UUID.randomUUID().toString());
    }
    static UserView user(ResultSet rs) throws SQLException {
        return new UserView(rs.getString("id"), rs.getString("username"), rs.getString("display_name"),
            rs.getString("user_type"), rs.getString("tenant_id"), rs.getBoolean("must_change_password"));
    }
    List<String> permissions(String userId) {
        // Effective authority: direct permissions UNION enabled, non-deleted assigned-role permissions.
        return jdbc.queryForList("SELECT permission FROM effective_user_permissions WHERE user_id=? ORDER BY permission", String.class, userId);
    }
    @Transactional(noRollbackFor=ApiError.class)
    public LoginResult login(LoginRequest input, String requestId) {
        List<Credential> rows = jdbc.query("SELECT * FROM app_users WHERE username=? FOR UPDATE",
            (rs,n) -> new Credential(user(rs), rs.getString("password_hash"), rs.getBoolean("enabled")), input.username());
        Credential c = rows.isEmpty() ? null : rows.getFirst();
        boolean matches = encoder.matches(input.password(), c == null ? dummyHash : c.passwordHash());
        boolean audienceMatches = c != null && (c.user().userType().equals("PLATFORM") ? "management" : "customer").equals(input.audience());
        if (!matches || c == null || !c.enabled() || !audienceMatches || (c.user().tenantId() != null && !tenantEnabled(c.user().tenantId()))) {
            audit.record(null, null, "LOGIN", null, "DENIED", requestId);
            throw new ApiError(401, "INVALID_CREDENTIALS", "账号、密码或登录入口错误");
        }
        expiry.requireCurrent(c.user().id());
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO sessions(token_hash,user_id,audience,expires_at,revoked,created_at) VALUES (?,?,?,?,FALSE,?)",
            hash(token), c.user().id(), input.audience(), OffsetDateTime.now(clock).plus(Limits.SESSION_TTL), OffsetDateTime.now(clock));
        audit.record(c.user().id(), c.user().tenantId(), "LOGIN", null, "SUCCESS", requestId);
        return new LoginResult(token, c.user(), permissions(c.user().id()));
    }
    public Identity authenticate(String bearer) {
        if (bearer == null || !bearer.matches("[A-Za-z0-9_-]{43}")) throw unauthorized();
        return refreshKnownIdentity(hash(bearer));
    }
    /** Internal session reference refresh; no transport accepts this hash as a credential. */
    public Identity refreshKnownIdentity(String tokenHash) {
        if(tokenHash==null||!tokenHash.matches("[0-9a-f]{64}"))throw unauthorized();
        var rows = jdbc.query("SELECT u.*,s.audience FROM sessions s JOIN app_users u ON u.id=s.user_id WHERE s.token_hash=? AND s.revoked=FALSE AND s.expires_at>? AND u.enabled=TRUE",
            (rs,n) -> new Identity(user(rs), List.of(), rs.getString("audience"), tokenHash), tokenHash, OffsetDateTime.now(clock));
        if (rows.isEmpty()) throw unauthorized();
        Identity found = rows.getFirst();
        String expected = found.user().userType().equals("PLATFORM") ? "management" : "customer";
        if (!expected.equals(found.audience()) || (found.user().tenantId()!=null && !tenantEnabled(found.user().tenantId()))) throw unauthorized();
        expiry.requireCurrent(found.user().id());
        return new Identity(found.user(), permissions(found.user().id()), found.audience(), tokenHash);
    }
    static ApiError unauthorized() { return new ApiError(401, "UNAUTHENTICATED", "请重新登录"); }
    /** Serialize identity-scoped writes before reading persistent idempotency records. */
    private void lockAndCheck(Identity identity) {
        var rows = jdbc.queryForList("SELECT id FROM app_users WHERE id=? AND enabled=TRUE FOR UPDATE", String.class, identity.user().id());
        if (rows.isEmpty() || jdbc.queryForList("SELECT token_hash FROM sessions WHERE token_hash=? AND user_id=? AND revoked=FALSE AND expires_at>? FOR UPDATE",
            String.class, identity.tokenHash(), identity.user().id(), OffsetDateTime.now(clock)).size() != 1) throw unauthorized();
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT must_change_password FROM app_users WHERE id=?", Boolean.class, identity.user().id())))
            throw new ApiError(403, "PASSWORD_CHANGE_REQUIRED", "首次登录请先修改密码");
        expiry.requireCurrent(identity.user().id());
    }
    /** Repeat authorization inside writes; row locks keep revocations ordered with the commit. */
    public void lockAndAuthorize(Identity identity, String permission, String tenant, String requiredAudience) {
        authorizeWrite(identity,permission,tenant,requiredAudience,true);
    }
    /** Management's singleton lock already serializes unit state/grants; do not take a tenant row before a target user. */
    void lockForManagement(Identity identity,String permission,String tenant,String audience) {
        authorizeWrite(identity,permission,tenant,audience,false);
    }
    private void authorizeWrite(Identity identity,String permission,String tenant,String requiredAudience,boolean lockTenant) {
        lockAndCheck(identity);
        UserView current = jdbc.queryForObject("SELECT * FROM app_users WHERE id=?", (rs,n) -> user(rs), identity.user().id());
        String audience = jdbc.queryForObject("SELECT audience FROM sessions WHERE token_hash=?", String.class, identity.tokenHash());
        String expected = current.userType().equals("PLATFORM") ? "management" : "customer";
        if (!expected.equals(audience) || !audience.equals(identity.audience()) || (requiredAudience != null && !requiredAudience.equals(audience)))
            throw ApiError.forbidden("登录入口与操作受众不匹配");
        if (!permissions(current.id()).contains(permission)) throw ApiError.forbidden("操作权限已撤销");
        if (tenant != null) {
            if (current.userType().equals("CUSTOMER")) {
                if (!tenant.equals(current.tenantId())) throw ApiError.forbidden("客户租户不匹配");
            } else if (jdbc.queryForList("SELECT tenant_id FROM user_tenant_grants WHERE user_id=? AND tenant_id=? FOR UPDATE", String.class, current.id(), tenant).isEmpty()) {
                throw ApiError.forbidden("租户授权已撤销");
            }
            var enabled=jdbc.queryForList("SELECT enabled FROM tenants WHERE id=?"+(lockTenant ? " FOR UPDATE" : ""),Boolean.class,tenant);
            if(enabled.isEmpty() || !enabled.getFirst()) throw new ApiError(403,"UNIT_DISABLED","单位已停用");
        }
        if(current.tenantId()!=null && !tenantEnabled(current.tenantId())) throw new ApiError(403,"UNIT_DISABLED","单位已停用");
    }
    public boolean tenantEnabled(String tenant) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM tenants WHERE id=?",Boolean.class,tenant));
    }
    @Transactional(noRollbackFor=ApiError.class)
    public void changePassword(Identity identity, ChangePasswordRequest input, String requestId) {
        Credential c = jdbc.queryForObject("SELECT * FROM app_users WHERE id=? FOR UPDATE",
            (rs,n) -> new Credential(user(rs), rs.getString("password_hash"), rs.getBoolean("enabled")), identity.user().id());
        Long current = jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE token_hash=? AND revoked=FALSE AND expires_at>?",
            Long.class, identity.tokenHash(), OffsetDateTime.now(clock));
        if (current != 1L || !c.enabled()) throw unauthorized();
        if (!encoder.matches(input.currentPassword(), c.passwordHash()) || !Limits.validPassword(input.newPassword())
            || input.currentPassword().equals(input.newPassword())) {
            audit.record(identity.user().id(), identity.user().tenantId(), "CHANGE_PASSWORD", identity.user().id(), "DENIED", requestId);
            throw ApiError.bad("原密码不正确或新密码不符合策略（12至128字符且不同于原密码）");
        }
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=FALSE,revision=revision+1 WHERE id=?", encoder.encode(input.newPassword()), identity.user().id());
        jdbc.update("UPDATE sessions SET revoked=TRUE WHERE user_id=?", identity.user().id());
        audit.record(identity.user().id(), identity.user().tenantId(), "CHANGE_PASSWORD", identity.user().id(), "SUCCESS", requestId);
    }
    @Transactional
    public void logout(Identity identity, String requestId) {
        jdbc.queryForObject("SELECT id FROM app_users WHERE id=? FOR UPDATE", String.class, identity.user().id());
        jdbc.update("UPDATE sessions SET revoked=TRUE WHERE token_hash=?", identity.tokenHash());
        audit.record(identity.user().id(), identity.user().tenantId(), "LOGOUT", null, "SUCCESS", requestId);
    }
    public static String hash(String value) {
        return Digests.sha256(value);
    }
}
