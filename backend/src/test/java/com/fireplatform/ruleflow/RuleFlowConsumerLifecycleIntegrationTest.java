package com.fireplatform.ruleflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

    @Test
    void unitNotificationConsumerRemainsBoundThroughTogglesAndDeactivatesWhenRemoved() throws Exception {
        String contact = seedNotificationContact();
        String id = create(notificationDefinition(contact)).get("flow").get("id").asText();
        JsonNode staged = publish(id, 0, reviewedHash(id, 0));
        assertThat(staged.path("published").asBoolean()).isTrue();
        String rule = notificationConsumer(staged.path("bindings")).path("consumer_id").asText();
        var initial = notificationState(rule);
        assertThat(initial).containsEntry("scope_type", "UNIT").containsEntry("enabled", false);
        long revision = ((Number) initial.get("revision")).longValue();

        toggle(id, 0, true, UUID.randomUUID().toString());
        JsonNode active = notificationConsumer(body(call(HttpMethod.GET, BASE + "/" + id + "/compiled", null, null)).path("consumers"));
        assertThat(active.path("consumer_id").asText()).isEqualTo(rule);
        assertThat(active.path("consumer_revision").asLong()).isEqualTo(revision + 1);
        assertThat(active.path("drift_detected").asBoolean()).isFalse();
        assertThat(active.path("detail").path("enabled").asBoolean()).isTrue();
        assertThat(active.path("detail").path("receivers").size()).isEqualTo(2);

        toggle(id, 0, false, UUID.randomUUID().toString());
        assertThat(notificationState(rule)).containsEntry("enabled", false).containsEntry("revision", revision + 2);
        toggle(id, 0, true, UUID.randomUUID().toString());
        long draft = body(call(HttpMethod.PUT, BASE + "/" + id, Map.of("expected_revision", 0, "name", "Isolated reviewed merge",
            "description", "No external effects", "definition", definition(120), "reason", "Remove notification channels"),
            UUID.randomUUID().toString())).path("flow").path("revision").asLong();
        assertThat(publish(id, draft, reviewedHash(id, draft)).path("published").asBoolean()).isTrue();
        assertThat(notificationState(rule)).containsEntry("enabled", false).containsEntry("revision", revision + 4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLACE", "DEVICE"})
    void unitFlowRejectsAnOutOfScopeNotificationBindingEvenAtTheRecordedRevision(String scope) throws Exception {
        String id = create(notificationDefinition(seedNotificationContact())).get("flow").get("id").asText();
        JsonNode staged = publish(id, 0, reviewedHash(id, 0));
        assertThat(staged.path("published").asBoolean()).isTrue();
        String unitRule = notificationConsumer(staged.path("bindings")).path("consumer_id").asText();
        toggle(id, 0, true, UUID.randomUUID().toString());
        String hash = reviewedHash(id, 0);
        Map<String, Object> unitBefore = notificationState(unitRule);
        long mergeBefore = policyRevision();
        String scopedRule = seedScopedRule(scope, unitRule, ((Number) unitBefore.get("revision")).longValue());
        // The binding's exact CAS revision is unchanged; only the target belongs to a different layer.
        jdbc.update("UPDATE rule_flow_consumer_bindings SET consumer_id=? WHERE tenant_id=? AND flow_id=? AND consumer_kind='NOTIFICATION_RULE'",
            scopedRule, tenant, id);
        Map<String, Object> before = notificationState(scopedRule);
        var receiversBefore = notificationReceivers(scopedRule);
        assertThat(receiversBefore).hasSize(2);

        JsonNode compiled = notificationConsumer(body(call(HttpMethod.GET, BASE + "/" + id + "/compiled", null, null)).path("consumers"));
        assertThat(compiled.path("drift_detected").asBoolean()).isTrue();
        assertThat(compiled.path("detail").isEmpty()).isTrue();
        JsonNode plan = body(call(HttpMethod.POST, BASE + "/" + id + "/preview", Map.of("expected_revision", 0), null))
            .required("plan");
        assertThat(plan.required("ok").booleanValue()).isFalse();
        assertThat(plan.required("issues"))
            .extracting(issue -> issue.required("code").textValue())
            .containsExactly("DEPENDENCY_MISSING");
        assertThat(publish(id, 0, hash).path("published").asBoolean()).isFalse();
        for (boolean enabled : List.of(true, false)) {
            call(HttpMethod.POST, BASE + "/" + id + "/enabled", Map.of("expected_revision", 0,
                "enabled", enabled, "reason", "Reject an invalid notification consumer"), UUID.randomUUID().toString())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEPENDENCY_DRIFT"));
        }
        assertThat(notificationState(scopedRule)).isEqualTo(before);
        assertThat(notificationReceivers(scopedRule)).isEqualTo(receiversBefore);
        assertThat(notificationState(unitRule)).isEqualTo(unitBefore);
        assertThat(policyRevision()).isEqualTo(mergeBefore);
        assertThat(body(call(HttpMethod.GET, BASE + "/" + id, null, null)).path("flow").path("enabled").asBoolean()).isTrue();
    }

    private JsonNode notificationConsumer(JsonNode bindings) {
        for (JsonNode binding : bindings) {
            if ("NOTIFICATION_RULE".equals(binding.path("consumer_kind").asText())) return binding;
        }
        throw new AssertionError("Expected a notification consumer: " + bindings);
    }

    private String seedNotificationContact() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO notification_contacts(id,tenant_id,name,contact_type,phone_cipher,phone_nonce,phone_masked,phone_mac,enabled,created_at)
            VALUES(?,?,?,'OTHER','fixture','fixture','fixture',?,TRUE,CURRENT_TIMESTAMP)
            """, id, tenant, "Synthetic RuleFlow contact", id);
        return id;
    }

    private String seedScopedRule(String scope, String unitRule, long revision) {
        String rule = UUID.randomUUID().toString();
        String target = UUID.randomUUID().toString();
        if ("PLACE".equals(scope)) {
            jdbc.update("INSERT INTO places(id,tenant_id,name) VALUES(?,?,'Synthetic RuleFlow place')", target, tenant);
        } else {
            jdbc.update("""
                INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state)
                VALUES(?,?,'Synthetic RuleFlow device',?,'fixture','','NORMAL')
                """, target, tenant, target);
        }
        jdbc.update("""
            INSERT INTO notification_rules(id,tenant_id,name,enabled,revision,event_scope,created_at,scope_type,scope_place_id,scope_device_id)
            VALUES(?,?,'Independent scoped policy',TRUE,?,'FIRE',CURRENT_TIMESTAMP,?,?,?)
            """, rule, tenant, revision, scope, "PLACE".equals(scope) ? target : null, "DEVICE".equals(scope) ? target : null);
        jdbc.update("""
            INSERT INTO notification_rule_receivers(tenant_id,rule_id,contact_id,level,channel)
            SELECT tenant_id,?,contact_id,level,channel FROM notification_rule_receivers WHERE tenant_id=? AND rule_id=?
            """, rule, tenant, unitRule);
        return rule;
    }

    private Map<String, Object> notificationState(String id) {
        return jdbc.queryForMap("SELECT scope_type,enabled,revision,name FROM notification_rules WHERE tenant_id=? AND id=?", tenant, id);
    }

    private List<Map<String, Object>> notificationReceivers(String id) {
        return jdbc.queryForList("SELECT channel,contact_id,level FROM notification_rule_receivers WHERE tenant_id=? AND rule_id=? ORDER BY channel,contact_id", tenant, id);
    }

    private String reviewedHash(String id, long revision) throws Exception {
        JsonNode plan = body(call(HttpMethod.POST, BASE + "/" + id + "/preview", Map.of("expected_revision", revision), null)).path("plan");
        assertThat(plan.path("ok").asBoolean()).as("preview issues: %s", plan.path("issues")).isTrue();
        return plan.path("plan_hash").asText();
    }

    private JsonNode publish(String id, long revision, String hash) throws Exception {
        return body(call(HttpMethod.POST, BASE + "/" + id + "/publish", Map.of("expected_revision", revision,
            "preview_hash", hash, "reason", "Isolated reviewed notification publication"), UUID.randomUUID().toString()));
    }

    private Map<String, Object> notificationDefinition(String contact) {
        Map<String, Object> definition = definition(120);
        var nodes = new ArrayList<Object>((List<?>) definition.get("nodes"));
        var edges = new ArrayList<Object>((List<?>) definition.get("edges"));
        for (String channel : List.of("sms", "voice")) {
            nodes.add(Map.of("id", channel, "type", channel, "x", 800, "y", 180, "config", Map.of(
                "eventScopes", List.of("FIRE"), "escalationMinutes", 0, "receivers", List.of(Map.of("contactId", contact, "level", 1)))));
            edges.add(Map.of("from", "alarm", "to", channel));
        }
        return Map.of("nodes", nodes, "edges", edges);
    }

    private JsonNode create(Map<String, Object> definition) throws Exception {
        return body(call(HttpMethod.POST, BASE, Map.of("name", "Isolated reviewed merge", "description", "No external effects",
            "definition", definition, "reason", "Isolated software lifecycle regression"), UUID.randomUUID().toString()));
    }

    private JsonNode create(int seconds) throws Exception {
        return create(definition(seconds));
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
