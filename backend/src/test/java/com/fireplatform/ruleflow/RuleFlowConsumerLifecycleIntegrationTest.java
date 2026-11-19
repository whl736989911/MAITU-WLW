package com.fireplatform.ruleflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual local consumer configuration only; no alarms, provider sends or device operations are produced. */
@SpringBootTest(properties = {
    "app.roles=api", "app.storage=h2-dev", "app.task-poll-ms=60000",
    "spring.datasource.url=jdbc:h2:mem:ruleflow-consumer-lifecycle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuleFlowConsumerLifecycleIntegrationTest {
    private static final String PASSWORD = "Ruleflow-Lifecycle-Confirmation-2026";
    private static final String BASE = "/api/management/rule-flows";
    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry registry) {
        registry.add("APP_BOOTSTRAP_USERNAME", () -> "admin");
        registry.add("APP_BOOTSTRAP_PASSWORD", () -> "Initial-Ruleflow-Lifecycle-2026");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    String tenant;
    String token;

    @BeforeEach
    void seedOwnedUnit() throws Exception {
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=FALSE,enabled=TRUE WHERE id='bootstrap-admin'",
            passwords.encode(PASSWORD));
        tenant = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tenants(id,name) VALUES(?,?)", tenant, "Isolated ruleflow unit");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin',?)", tenant);
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES('bootstrap-admin',?,'ALL_TENANT',1)", tenant);
        token = body(call(HttpMethod.POST, "/api/auth/login", Map.of("username", "admin", "password", PASSWORD,
            "audience", "management"), null)).get("access_token").asText();
    }

    @Test
    void publishStagesDisabledThenRepeatedTogglesChangeTheExistingConsumerExactlyOnce() throws Exception {
        JsonNode created = create(120);
        String id = created.get("flow").get("id").asText();
        long revision = created.get("flow").get("revision").asLong();
        JsonNode preview = body(call(HttpMethod.POST, BASE + "/" + id + "/preview", Map.of("expected_revision", revision), null));
        assertThat(preview.get("plan").get("ok").asBoolean()).isTrue();
        JsonNode published = body(call(HttpMethod.POST, BASE + "/" + id + "/publish", Map.of(
            "expected_revision", revision, "preview_hash", preview.get("plan").get("plan_hash").asText(), "reason", "Isolated reviewed publication"), UUID.randomUUID().toString()));
        assertThat(published.get("published").asBoolean()).isTrue();
        assertPolicy(false, 120);
        long policyRevision = policyRevision();
        String enableKey = UUID.randomUUID().toString();
        toggle(id, revision, true, enableKey);
        assertPolicy(true, 120);
        assertThat(policyRevision()).isEqualTo(policyRevision + 1);
        toggle(id, revision, true, enableKey);
        assertThat(policyRevision()).isEqualTo(policyRevision + 1);
        toggle(id, revision, false, UUID.randomUUID().toString());
        assertPolicy(false, 120);
        assertThat(policyRevision()).isEqualTo(policyRevision + 2);
        toggle(id, revision, true, UUID.randomUUID().toString());
        assertPolicy(true, 120);
        assertThat(policyRevision()).isEqualTo(policyRevision + 3);
    }

    @Test
    void changedDraftCannotPublishAnOldReviewedPlanOrWriteItsConsumer() throws Exception {
        JsonNode created = create(120);
        String id = created.get("flow").get("id").asText();
        JsonNode preview = body(call(HttpMethod.POST, BASE + "/" + id + "/preview", Map.of("expected_revision", 0), null));
        JsonNode saved = body(call(HttpMethod.PUT, BASE + "/" + id, Map.of("expected_revision", 0,
            "name", "Isolated reviewed merge", "description", "No external effects", "definition", definition(180),
            "reason", "Change the actual target after preview"), UUID.randomUUID().toString()));
        call(HttpMethod.POST, BASE + "/" + id + "/publish", Map.of(
            "expected_revision", saved.get("flow").get("revision").asLong(),
            "preview_hash", preview.get("plan").get("plan_hash").asText(), "reason", "Old review must not authorize changed output"), UUID.randomUUID().toString())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PREVIEW_CHANGED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_merge_policies WHERE tenant_id=?", Long.class, tenant)).isZero();
        JsonNode current = body(call(HttpMethod.GET, BASE + "/" + id, null, null));
        assertThat(current.get("flow").get("published_revision").isNull()).isTrue();
    }

    private JsonNode create(int seconds) throws Exception {
        return body(call(HttpMethod.POST, BASE, Map.of("name", "Isolated reviewed merge", "description", "No external effects",
            "definition", definition(seconds), "reason", "Isolated software lifecycle regression"), UUID.randomUUID().toString()));
    }
    private Map<String, Object> definition(int seconds) {
        return Map.of("nodes", List.of(
            Map.of("id", "source", "type", "device.message", "config", Map.of("scope", Map.of("mode", "ALL", "placeIds", List.of(), "deviceIds", List.of()), "systemTypes", List.of()), "x", 40, "y", 60),
            Map.of("id", "persist", "type", "persist", "config", Map.of(), "x", 300, "y", 60),
            Map.of("id", "alarm", "type", "createalarm", "config", Map.of(), "x", 560, "y", 60),
            Map.of("id", "merge", "type", "merge", "config", Map.of("enabled", true, "windowSeconds", seconds), "x", 800, "y", 60)),
            "edges", List.of(Map.of("from", "source", "to", "persist"), Map.of("from", "persist", "to", "alarm"), Map.of("from", "alarm", "to", "merge")));
    }
    private void toggle(String id, long revision, boolean enabled, String key) throws Exception {
        JsonNode result = body(call(HttpMethod.POST, BASE + "/" + id + "/enabled", Map.of("expected_revision", revision,
            "enabled", enabled, "reason", "Isolated explicit consumer activation"), key));
        assertThat(result.get("flow").get("enabled").asBoolean()).isEqualTo(enabled);
    }
    private void assertPolicy(boolean enabled, int seconds) {
        var policy = jdbc.queryForMap("SELECT enabled,window_seconds FROM alarm_merge_policies WHERE tenant_id=?", tenant);
        assertThat(policy.get("enabled")).isEqualTo(enabled);
        assertThat(((Number) policy.get("window_seconds")).intValue()).isEqualTo(seconds);
    }
    private long policyRevision() {
        return jdbc.queryForObject("SELECT revision FROM alarm_merge_policies WHERE tenant_id=?", Long.class, tenant);
    }
    private ResultActions call(HttpMethod method, String path, Object input, String key) throws Exception {
        var builder = request(method, path);
        if (token != null) builder.header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenant);
        if (key != null) builder.header("Idempotency-Key", key);
        if (input != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input));
        return mvc.perform(builder);
    }
    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
