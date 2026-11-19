package com.fireplatform.deviceservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.ApiError;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Real Flyway fixture for the legacy origin/account gate. No live legacy host is contacted; only the
 * configuration boundary, encryption at rest, and use-time allowlist re-checks are exercised.
 */
@SpringBootTest(properties = {
    "app.roles=all",
    "app.storage=h2-dev",
    "app.task-poll-ms=60000",
    "app.data-transfer-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "app.device-service.legacy-approved-origins=http://192.0.2.10:85,https://legacy.example.test",
    "spring.datasource.url=jdbc:h2:mem:legacysourcegate;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegacySourceConfigGateTest {
    private static final String INITIAL = "Initial-" + UUID.randomUUID();
    private static final String PASSWORD = "Changed-" + UUID.randomUUID();
    private static final String TTL_ORIGIN = "http://192.0.2.10:85";
    private static final String USERNAME = "legacy-reader";
    private static final String SECRET = "legacy-password-value";

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("APP_BOOTSTRAP_USERNAME", () -> "admin");
        registry.add("APP_BOOTSTRAP_PASSWORD", () -> INITIAL);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Bootstrap bootstrap;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired PasswordEncoder encoder;
    @Autowired LegacySourceConfigService configuration;

    String token;

    @BeforeEach
    void reset() {
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("DELETE FROM legacy_source_config");
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=FALSE WHERE id='bootstrap-admin'",
            encoder.encode(PASSWORD));
        token = auth.login(new AuthService.LoginRequest("admin", PASSWORD, "management"), "legacy-source-gate").accessToken();
    }

    @Test
    void httpOriginRequiresExplicitRiskAcknowledgement() throws Exception {
        update(TTL_ORIGIN, false, 0, null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("HTTP_RISK_ACK_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_source_config", Long.class)).isZero();
    }

    @Test
    void originMustBeInDeploymentAllowlist() throws Exception {
        update("http://legacy-not-approved.example.test", true, 0, null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("LEGACY_ORIGIN_NOT_APPROVED"));
    }

    @Test
    void httpsOriginCannotRecordPlainHttpRisk() throws Exception {
        update("https://legacy.example.test", true, 0, null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("HTTP_RISK_NOT_APPLICABLE"));
    }

    @Test
    void credentialsAreEncryptedWriteOnlyAndReloadUsesTheApprovedOrigin() throws Exception {
        update(TTL_ORIGIN, true, 0, SECRET)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(true))
            .andExpect(jsonPath("$.credentials_stored").value(true))
            .andExpect(jsonPath("$.username_masked").value(org.hamcrest.Matchers.not(SECRET)))
            .andExpect(jsonPath("$.username").doesNotExist())
            .andExpect(jsonPath("$.password").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM legacy_source_config WHERE driver_key='LEGACY_TTL'");
        assertThat(row.toString()).doesNotContain(USERNAME).doesNotContain(SECRET);
        assertThat((String) row.get("account_fingerprint")).hasSize(64);
        assertThat((String) row.get("username_cipher")).isNotBlank();
        assertThat((String) row.get("password_cipher")).isNotBlank();

        LegacySourceConfigService.Config loaded = configuration.load("LEGACY_TTL");
        assertThat(loaded.configured()).isTrue();
        assertThat(loaded.code()).isEqualTo("READY");
        assertThat(loaded.origin()).isEqualTo(TTL_ORIGIN);
        assertThat(loaded.username()).isEqualTo(USERNAME);
        assertThat(loaded.password()).isEqualTo(SECRET);

        mvc.perform(get("/api/management/legacy-sources").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.driver_key == 'LEGACY_TTL')].origin")
                .value(org.hamcrest.Matchers.hasItem(TTL_ORIGIN)))
            .andExpect(jsonPath("$.items[?(@.driver_key == 'LEGACY_TTL')].username_masked")
                .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.not(SECRET))));
    }

    @Test
    void tamperedStoredOriginNeverBecomesAnOutboundTarget() throws Exception {
        update(TTL_ORIGIN, true, 0, SECRET).andExpect(status().isOk());
        jdbc.update("UPDATE legacy_source_config SET origin='http://not-approved.example.test' WHERE driver_key='LEGACY_TTL'");

        LegacySourceConfigService.Config loaded = configuration.load("LEGACY_TTL");
        assertThat(loaded.configured()).isFalse();
        assertThat(loaded.code()).isEqualTo("ORIGIN_NOT_APPROVED");
        assertThat(loaded.username()).isNull();
        assertThat(loaded.password()).isNull();
    }

    @Test
    void tamperedHttpRiskFlagIsRecheckedAtUseTime() throws Exception {
        update(TTL_ORIGIN, true, 0, SECRET).andExpect(status().isOk());
        jdbc.update("UPDATE legacy_source_config SET allow_insecure_http=FALSE WHERE driver_key='LEGACY_TTL'");

        LegacySourceConfigService.Config loaded = configuration.load("LEGACY_TTL");
        assertThat(loaded.configured()).isFalse();
        assertThat(loaded.code()).isEqualTo("HTTP_RISK_NOT_ACKNOWLEDGED");
    }

    @Test
    void privateAndLoopbackOriginsAreRejectedBeforeTheAllowlist() {
        assertThatThrownBy(() -> configuration.normalizeOrigin("http://127.0.0.1:85"))
            .isInstanceOf(ApiError.class).hasMessageContaining("私有");
        assertThatThrownBy(() -> configuration.normalizeOrigin("http://192.168.1.10:85"))
            .isInstanceOf(ApiError.class).hasMessageContaining("私有");
        assertThatThrownBy(() -> configuration.normalizeOrigin("https://legacy.example.test/path"))
            .isInstanceOf(ApiError.class).hasMessageContaining("路径");
    }

    private ResultActions update(String origin, boolean allowInsecureHttp, long expectedRevision, String password)
            throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("origin", origin);
        body.put("username", USERNAME);
        body.put("password", password == null ? "" : password);
        body.put("allow_insecure_http", allowInsecureHttp);
        body.put("enabled", true);
        body.put("expected_revision", expectedRevision);
        return mvc.perform(put("/api/management/legacy-sources/LEGACY_TTL")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "legacy-source-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
    }
}
