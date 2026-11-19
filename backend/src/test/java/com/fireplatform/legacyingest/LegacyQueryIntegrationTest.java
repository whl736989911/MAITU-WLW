package com.fireplatform.legacyingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.N04TestScopes;
import com.fireplatform.bootstrap.Bootstrap;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "app.roles=all", "app.storage=h2-dev",
    "spring.datasource.url=jdbc:h2:mem:legacyquery;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegacyQueryIntegrationTest {
    private static final String INITIAL = "Initial-" + UUID.randomUUID();
    private static final String PASSWORD = "Changed-" + UUID.randomUUID();

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) {
        registry.add("APP_BOOTSTRAP_USERNAME", () -> "admin");
        registry.add("APP_BOOTSTRAP_PASSWORD", () -> INITIAL);
        registry.add("APP_DEMO_CUSTOMER_PASSWORD", () -> INITIAL);
        registry.add("APP_DEMO_CUSTOMER_B_PASSWORD", () -> INITIAL);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    private String root;

    @BeforeEach
    void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        for (String table : List.of("legacy_ingest_quarantines", "legacy_ingest_events", "legacy_ingest_messages", "legacy_reconcile_differences",
            "device_aliases", "alarms", "device_profile_snapshots","profile_rules","device_battery_observations","device_events", "device_place_history", "device_version_history", "devices", "places",
        "product_version_attributes", "legacy_product_decoders", "product_versions", "products",
        "identity_command_records", "idempotency_records", "sessions", "user_permissions",
        "user_tenant_grants", "app_users", "tenants", "audit_log")) {
            jdbc.update("DELETE FROM " + table);
        }
        bootstrap.run(new DefaultApplicationArguments());
        root = login("admin", INITIAL, "management");
        mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + root)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("current_password", INITIAL, "new_password", PASSWORD))))
            .andExpect(status().isNoContent());
        root = login("admin", PASSWORD, "management");

        jdbc.update("INSERT INTO tenants(id,name) VALUES ('t1','Tenant One'),('t2','Tenant Two')");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin','t1'),('bootstrap-admin','t2')");
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES ('bootstrap-admin','t1','ALL_TENANT',1),('bootstrap-admin','t2','ALL_TENANT',1)");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) " +
            "VALUES ('d1','t1','Device One','eui-1','TBS-110','address','UNKNOWN',FALSE)," +
            "('d2','t1','Device Two','eui-2','TBS-110','address','UNKNOWN',FALSE)," +
            "('d3','t2','Device Three','eui-3','TBS-110','address','UNKNOWN',FALSE)");
    }

    @Test
    void messagesArePagedAndCanBeFilteredByState() throws Exception {
        for (int i = 1; i <= 5; i++) insertMessage("m" + i, "t1", "d1", i == 3 ? "FAILED" : "NORMALIZED", i);

        readMessages("t1", "size", "2")
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(5))
            .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(2));
        readMessages("t1", "state", "FAILED")
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value("m3"));
    }

    @Test
    void readsDefaultToAllAuthorizedUnitsAndTreeSelectionsNarrowIncludingEmpty() throws Exception {
        insertMessage("scope-one", "t1", "d1", "NORMALIZED", 1);
        insertMessage("scope-two", "t2", "d3", "NORMALIZED", 2);
        mvc.perform(get("/api/management/legacy-ingest/messages").header("Authorization", "Bearer " + root))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/management/legacy-ingest/messages").header("Authorization", "Bearer " + root)
                .param("scope_selected", "true").param("scope_tenant_ids", "t1").param("scope_place_ids", ""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].tenant_id").value("t1"));
        mvc.perform(get("/api/management/legacy-ingest/messages").header("Authorization", "Bearer " + root)
                .param("scope_selected", "true").param("scope_tenant_ids", "").param("scope_place_ids", ""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.items.length()").value(0));
    }
    @Test
    void messageListAndDetailAreIsolatedByTenantHeader() throws Exception {
        insertMessage("tenant-one-message", "t1", "d1", "NORMALIZED", 1);
        insertMessage("tenant-two-message", "t2", "d3", "NORMALIZED", 2);

        readMessages("t1").andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value("tenant-one-message"));
        readMessages("t2").andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value("tenant-two-message"));
        mvc.perform(get("/api/management/legacy-ingest/messages/tenant-one-message")
                .header("Authorization", "Bearer " + root).header("X-Tenant-Id", "t2"))
            .andExpect(status().isNotFound());
    }

    @Test
    void devicesArePagedWithinTheSelectedTenant() throws Exception {
        mvc.perform(get("/api/management/legacy-ingest/devices").header("Authorization", "Bearer " + root)
                .header("X-Tenant-Id", "t1").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.size").value(1));
        mvc.perform(get("/api/management/legacy-ingest/devices").header("Authorization", "Bearer " + root)
                .header("X-Tenant-Id", "t2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].tenant_id").value("t2"));
    }

    @Test
    void deviceSetScopeHidesMessagesFromUnselectedDevices() throws Exception {
        insertMessage("visible-message", "t1", "d1", "NORMALIZED", 1);
        insertMessage("hidden-message", "t1", "d2", "NORMALIZED", 2);
        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET' WHERE user_id='bootstrap-admin' AND tenant_id='t1'");
        jdbc.update("INSERT INTO user_device_scope_devices(user_id,tenant_id,device_id) VALUES ('bootstrap-admin','t1','d1')");

        readMessages("t1").andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value("visible-message"));
        mvc.perform(get("/api/management/legacy-ingest/messages/hidden-message")
                .header("Authorization", "Bearer " + root).header("X-Tenant-Id", "t1"))
            .andExpect(status().isNotFound());
    }

    @Test
    void detailIncludesEventsAndMissingIdReturnsNotFound() throws Exception {
        insertMessage("with-events", "t1", "d1", "NORMALIZED", 1);
        insertEvent("e1", "with-events", "t1", "d1");
        insertEvent("e2", "with-events", "t1", "d1");

        mvc.perform(get("/api/management/legacy-ingest/messages/with-events")
                .header("Authorization", "Bearer " + root).header("X-Tenant-Id", "t1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.events.length()").value(2))
            .andExpect(jsonPath("$.summary.event_count").value(2));
        mvc.perform(get("/api/management/legacy-ingest/messages/missing")
                .header("Authorization", "Bearer " + root).header("X-Tenant-Id", "t1"))
            .andExpect(status().isNotFound());
    }

    @Test
    void reconcileDifferencesCanBeFilteredByRunId() throws Exception {
        insertDifference("diff-a", "run-a", "legacy-a");
        insertDifference("diff-b", "run-b", "legacy-b");

        mvc.perform(get("/api/management/legacy-ingest/reconcile").header("Authorization", "Bearer " + root)
                .header("X-Tenant-Id", "t1").param("runId", "run-b"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value("diff-b"))
            .andExpect(jsonPath("$.items[0].run_id").value("run-b"));
    }

    @Test
    void detailRedactsRawPayloadWithoutIndependentRawPermission() throws Exception {
        insertMessage("raw-message", "t1", "d1", "NORMALIZED", 1);
        jdbc.update("DELETE FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='messages:raw'");
        mvc.perform(get("/api/management/legacy-ingest/messages/raw-message")
                .header("Authorization", "Bearer " + root).header("X-Tenant-Id", "t1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.payload_raw").value(nullValue()))
            .andExpect(jsonPath("$.payload_redacted").value(true));
    }

    @Test
    void exportRequiresExplicitTargetAndRevalidatesCurrentScope() throws Exception {
        insertMessage("export-one", "t1", "d1", "NORMALIZED", 1);
        insertMessage("export-two", "t2", "d3", "NORMALIZED", 2);

        mvc.perform(post("/api/management/legacy-ingest/exports").header("Authorization", "Bearer " + root)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", "MESSAGES", "target", "TENANT", "tenant_id", "t1",
                    "reason", "scope test"))))
            .andExpect(status().isOk());

        var scoped = mvc.perform(post("/api/management/legacy-ingest/exports").header("Authorization", "Bearer " + root)
                .param("scope_selected", "true").param("scope_tenant_ids", "t1").param("scope_place_ids", "")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", "MESSAGES", "target", "ALL_AUTHORIZED",
                    "reason", "scope test"))))
            .andExpect(status().isOk()).andReturn();
        String csv = new String(java.util.Base64.getDecoder().decode(
            json.readTree(scoped.getResponse().getContentAsString()).get("content_base64").asText()), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("export-one").doesNotContain("export-two");

        mvc.perform(post("/api/management/legacy-ingest/exports").header("Authorization", "Bearer " + root)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", "MESSAGES", "target", "TENANT",
                    "reason", "scope test"))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/management/legacy-ingest/exports").header("Authorization", "Bearer " + root)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", "MESSAGES", "target", "ALL_AUTHORIZED",
                    "tenant_id", "t1", "reason", "scope test"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void reconcileExportOnlyIncludesVisibleDevices() throws Exception {
        jdbc.update("INSERT INTO legacy_reconcile_differences(id,run_id,kind,legacy_id,new_id,detail,created_at) " +
            "VALUES ('visible-diff','run-1','DEVICE_MISSING_IN_TTL','legacy-a','d1','visible',CURRENT_TIMESTAMP)," +
            "('hidden-diff','run-1','DEVICE_MISSING_IN_TTL','legacy-b','d3','hidden',CURRENT_TIMESTAMP)");
        var result = mvc.perform(post("/api/management/legacy-ingest/exports").header("Authorization", "Bearer " + root)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", "RECONCILE", "target", "TENANT", "tenant_id", "t1",
                    "reason", "scope test"))))
            .andExpect(status().isOk()).andReturn();
        String csv = new String(java.util.Base64.getDecoder().decode(
            json.readTree(result.getResponse().getContentAsString()).get("content_base64").asText()), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("\"legacy-a\",\"d1\",\"visible\"").doesNotContain("\"legacy-b\"").doesNotContain("\"d3\"").doesNotContain("\"hidden\"");
    }

    private org.springframework.test.web.servlet.ResultActions readMessages(String tenant) throws Exception {
        return readMessages(tenant, null, null);
    }

    private org.springframework.test.web.servlet.ResultActions readMessages(String tenant, String parameter, String value) throws Exception {
        var request = get("/api/management/legacy-ingest/messages").header("Authorization", "Bearer " + root)
            .header("X-Tenant-Id", tenant);
        if (parameter != null) request.param(parameter, value);
        return mvc.perform(request);
    }

    private void insertMessage(String id, String tenant, String deviceId, String state, int second) {
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-10-30T00:00:00Z").plusSeconds(second);
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,vendor_device_id,device_id," +
                "payload_raw,payload_sha256,payload_bytes,received_at,observed_at,state,failure_code,duplicate_count) " +
                "VALUES (?,?,'HTTP_NOTIFY','aep_turbo',?,?,?,'{}',?,2,?,?,?, ?,0)",
            id, tenant, "external-" + id, "vendor-" + id, deviceId, "0".repeat(64), receivedAt, receivedAt, state,
            "FAILED".equals(state) ? "UNKNOWN_DEVICE" : null);
    }

    private void insertEvent(String id, String messageId, String tenant, String deviceId) {
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at) " +
                "VALUES (?,?,?,?,'HEARTBEAT','thing.event.heartbeat','{}',?,?)",
            id, tenant, messageId, deviceId, OffsetDateTime.parse("2026-10-30T00:00:00Z"), OffsetDateTime.parse("2026-10-30T00:00:00Z"));
    }

    private void insertDifference(String id, String runId, String legacyId) {
        jdbc.update("INSERT INTO legacy_reconcile_differences(id,run_id,kind,legacy_id,new_id,detail,created_at) " +
                "VALUES (?,?,'DEVICE_MISSING_IN_TTL',?,'d1','fixture',?)",
            id, runId, legacyId, OffsetDateTime.parse("2026-10-30T00:00:00Z"));
    }

    private String login(String username, String password, String audience) throws Exception {
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password, "audience", audience))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("access_token").asText();
    }
}
