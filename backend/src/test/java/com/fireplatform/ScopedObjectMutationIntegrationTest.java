package com.fireplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.product.JsonPointerParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "app.roles=api",
    "app.generic-ingest-enabled=true",
    "app.storage=h2-dev",
    "app.task-poll-ms=60000",
    "app.data-transfer-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "spring.datasource.url=jdbc:h2:mem:scopedobjectmutations;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScopedObjectMutationIntegrationTest {
    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) { IngestionIntegrationTest.environment(registry); }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;

    private IngestionIntegrationTest fixture;
    private String root;

    @BeforeEach
    void seed() throws Exception {
        // Delete rows that the shared ingestion fixture does not know about, before it deletes their parents.
        for (String table : List.of("reparse_attempts", "reparse_jobs", "gateway_links", "gateway_nodes",
            "controller_resources", "data_transfer_control_requests", "data_transfer_job_rows",
            "data_transfer_jobs", "data_transfer_exports", "data_transfer_plans")) {
            jdbc.update("DELETE FROM " + table);
        }
        fixture = new IngestionIntegrationTest();
        fixture.mvc = mvc;
        fixture.json = json;
        fixture.jdbc = jdbc;
        fixture.bootstrap = bootstrap;
        fixture.parser = parser;
        fixture.reset(); // tenant-a/tenant-b, device-a/device-b, customers, bootstrap grants, root token
        root = fixture.root;
        jdbc.update("INSERT INTO places(id,tenant_id,name,parent_place_id,revision) VALUES"
            + " ('vs-place-a','tenant-a','vs place A',NULL,0),('vs-place-b','tenant-a','vs place B',NULL,0)");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state) VALUES"
            + " ('vs-device-a','tenant-a','vs device A','vs-eui-a','fixture','vs address A','NORMAL',FALSE,'LEGACY_UNVERIFIED'),"
            + " ('vs-device-b','tenant-a','vs device B','vs-eui-b','fixture','vs address B','NORMAL',FALSE,'LEGACY_UNVERIFIED')");
        jdbc.update("UPDATE devices SET place_id='vs-place-a' WHERE id='vs-device-a'");
        jdbc.update("UPDATE devices SET place_id='vs-place-b' WHERE id='vs-device-b'");
    }


    @Test
    void controllerChangesRequireExistingDeviceScope() throws Exception {
        jdbc.update("INSERT INTO controller_resources(id,tenant_id,name,device_id,action,service_key,driver_key,"
                + "driver_mode,service_definition_id,capability_revision,support_state,risk_level,result_kind,"
                + "arguments_json,argument_keys_json,capability_evidence_json,enabled,state,active_key,revision,"
                + "created_by,created_at,updated_by,updated_at) VALUES ('vs-controller-b','tenant-a','vs controller',"
                + "'vs-device-b','start','vs-service','vs-driver','VENDOR_PLATFORM',NULL,0,'VERIFIED','NORMAL',"
                + "'COMMAND','{}','','{}',TRUE,'ACTIVE','vs-device-b:start',0,'bootstrap-admin',CURRENT_TIMESTAMP,"
                + "'bootstrap-admin',CURRENT_TIMESTAMP)");
        JsonNode operator = createPlatform("vs-controller-operator",
            List.of("controller:read", "controller:write"), List.of("tenant-a"));
        String operatorId = operator.get("id").asText();
        assignScope("/api/management/platform-users/" + operatorId + "/scope", "tenant-a",
            scopeBody("DEVICE_SET", List.of("vs-device-a"), List.of()));
        String operatorToken = activate("vs-controller-operator", "management");

        call(HttpMethod.GET, "/api/management/controller-resources/vs-controller-b", operatorToken, "tenant-a", null, null)
            .andExpect(status().isNotFound());

        var before = jdbc.queryForMap("SELECT device_id,state,enabled,revision FROM controller_resources WHERE id='vs-controller-b'");
        denied(call(HttpMethod.POST, "/api/management/controller-resources/vs-controller-b/enabled", operatorToken,
            "tenant-a", "deny-disable-" + UUID.randomUUID(), Map.of("expected_revision", 0, "enabled", false, "reason", "fixture")));
        denied(call(HttpMethod.POST, "/api/management/controller-resources/vs-controller-b/retire", operatorToken,
            "tenant-a", "deny-retire-" + UUID.randomUUID(), Map.of("expected_revision", 0, "reason", "fixture")));
        assertThat(jdbc.queryForMap("SELECT device_id,state,enabled,revision FROM controller_resources WHERE id='vs-controller-b'"))
            .isEqualTo(before);
    }


    @Test
    void topologyChangesRequireOriginalDeviceScope() throws Exception {
        jdbc.update("INSERT INTO gateway_nodes(id,tenant_id,address,name,protocol,device_id,state,active_key,revision,"
                + "created_by,created_at,updated_by,updated_at) VALUES ('vs-gateway','tenant-a','VS-GW-ADDRESS',"
                + "'vs gateway','MANUAL','vs-device-b','ACTIVE','vs-device-b',0,'bootstrap-admin',CURRENT_TIMESTAMP,"
                + "'bootstrap-admin',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO gateway_links(id,tenant_id,gateway_id,child_kind,device_id,channel_id,provenance,"
                + "observation_source,observation_event_id,observed_at,verified,state,active_key,revision,created_by,"
                + "created_at,updated_at) VALUES ('vs-link-b','tenant-a','vs-gateway','DEVICE','vs-device-b',NULL,"
                + "'MANUAL',NULL,NULL,NULL,FALSE,'ACTIVE','DEVICE:vs-device-b:-',0,'bootstrap-admin',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        JsonNode operator = createPlatform("vs-topology-operator",
            List.of("topology:read", "topology:write"), List.of("tenant-a"));
        String operatorId = operator.get("id").asText();
        assignScope("/api/management/platform-users/" + operatorId + "/scope", "tenant-a",
            scopeBody("DEVICE_SET", List.of("vs-device-a"), List.of()));
        String operatorToken = activate("vs-topology-operator", "management");

        call(HttpMethod.GET, "/api/management/topology/links?gateway_id=vs-gateway&page=1",
            operatorToken, "tenant-a", null, null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));

        // Control: a *new* link to the same hidden device is refused.
        call(HttpMethod.POST, "/api/management/topology/links", operatorToken, "tenant-a",
            "vs-new-link-" + UUID.randomUUID(),
            Map.of("gateway_id", "vs-gateway", "child_kind", "DEVICE", "device_id", "vs-device-b",
                "provenance", "MANUAL", "reason", "fixture"))
            .andExpect(status().isForbidden());

        var beforeLink = jdbc.queryForMap("SELECT device_id,state,verified,revision FROM gateway_links WHERE id='vs-link-b'");
        var beforeGateway = jdbc.queryForMap("SELECT device_id,state,revision FROM gateway_nodes WHERE id='vs-gateway'");
        denied(call(HttpMethod.POST, "/api/management/topology/links/vs-link-b/verify", operatorToken, "tenant-a",
            "deny-verify-" + UUID.randomUUID(), Map.of("expected_revision", 0, "verification_note", "fixture", "reason", "fixture")));
        denied(call(HttpMethod.POST, "/api/management/topology/links/vs-link-b/revoke", operatorToken, "tenant-a",
            "deny-revoke-" + UUID.randomUUID(), Map.of("expected_revision", 0, "revoke_reason", "fixture", "reason", "fixture")));
        denied(call(HttpMethod.PUT, "/api/management/topology/gateways/vs-gateway", operatorToken, "tenant-a",
            "deny-detach-" + UUID.randomUUID(), Map.of("expected_revision", 0, "address", "VS-GW-ADDRESS", "name", "vs gateway", "protocol", "MANUAL", "reason", "fixture")));
        assertThat(jdbc.queryForMap("SELECT device_id,state,verified,revision FROM gateway_links WHERE id='vs-link-b'"))
            .isEqualTo(beforeLink);
        assertThat(jdbc.queryForMap("SELECT device_id,state,revision FROM gateway_nodes WHERE id='vs-gateway'"))
            .isEqualTo(beforeGateway);
    }


    @Test
    void gatewayProjectionDoesNotExposeHiddenBackingDevice() throws Exception {
        jdbc.update("INSERT INTO gateway_nodes(id,tenant_id,address,name,protocol,device_id,state,active_key,revision,"
            + "created_by,created_at,updated_by,updated_at) VALUES ('vs-gateway','tenant-a','VS-GW-ADDRESS',"
            + "'vs gateway','MANUAL','vs-device-b','ACTIVE','vs-device-b',0,'bootstrap-admin',CURRENT_TIMESTAMP,"
            + "'bootstrap-admin',CURRENT_TIMESTAMP)");
        for (String suffix : List.of("a", "b")) {
            jdbc.update("INSERT INTO gateway_links(id,tenant_id,gateway_id,child_kind,device_id,provenance,"
                + "verified,state,active_key,revision,created_by,created_at,updated_at) VALUES "
                + "(?,'tenant-a','vs-gateway','DEVICE',?,'MANUAL',FALSE,'ACTIVE',?,0,'bootstrap-admin',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "vs-link-" + suffix, "vs-device-" + suffix, "DEVICE:vs-device-" + suffix + ":-");
        }
        JsonNode operator = createPlatform("vs-topology-reader", List.of("topology:read"), List.of("tenant-a"));
        assignScope("/api/management/platform-users/" + operator.get("id").asText() + "/scope", "tenant-a",
            scopeBody("DEVICE_SET", List.of("vs-device-a"), List.of()));
        String token = activate("vs-topology-reader", "management");
        JsonNode page = body(call(HttpMethod.GET, "/api/management/topology/gateways?page=1",
            token, "tenant-a", null, null).andExpect(status().isOk()));
        assertThat(page.path("total").asInt()).isEqualTo(1);
        JsonNode gateway = page.path("items").get(0);
        assertThat(gateway.path("device_id").isNull()).isTrue();
        assertThat(gateway.path("device_name").isNull()).isTrue();
        assertThat(gateway.path("visible_children").asInt()).isEqualTo(1);
        JsonNode tree = body(call(HttpMethod.GET, "/api/management/topology/gateways/vs-gateway/tree",
            token, "tenant-a", null, null).andExpect(status().isOk()));
        assertThat(tree.path("gateway").path("device_id").isNull()).isTrue();
        assertThat(tree.path("gateway").path("device_name").isNull()).isTrue();
        assertThat(tree.path("links").size()).isEqualTo(1);
        assertThat(tree.path("links").get(0).path("device_id").asText()).isEqualTo("vs-device-a");
    }

    @Test
    void workOrderCancellationRequiresExistingDeviceScope() throws Exception {
        call(HttpMethod.POST, "/api/management/maintenance/work-orders", root, "tenant-a",
            "vs-order-" + UUID.randomUUID(),
            Map.of("device_id", "vs-device-b", "title", "vs work order", "description", "fixture",
                "priority", "NORMAL", "reason", "fixture"))
            .andExpect(status().isOk());
        String orderId = jdbc.queryForObject(
            "SELECT id FROM maintenance_work_orders WHERE device_id='vs-device-b'", String.class);

        JsonNode operator = createPlatform("vs-maintenance-operator",
            List.of("maintenance:read", "maintenance:write"), List.of("tenant-a"));
        String operatorId = operator.get("id").asText();
        assignScope("/api/management/platform-users/" + operatorId + "/scope", "tenant-a",
            scopeBody("DEVICE_SET", List.of("vs-device-a"), List.of()));
        String operatorToken = activate("vs-maintenance-operator", "management");

        call(HttpMethod.GET, "/api/management/maintenance/work-orders/" + orderId, operatorToken, "tenant-a", null, null)
            .andExpect(status().isNotFound());

        var before = jdbc.queryForMap("SELECT device_id,status,revision FROM maintenance_work_orders WHERE id=?", orderId);
        denied(call(HttpMethod.POST, "/api/management/maintenance/work-orders/" + orderId + "/cancel", operatorToken,
            "tenant-a", "deny-cancel-" + UUID.randomUUID(), Map.of("expected_revision", 0, "note", "fixture", "reason", "fixture")));
        assertThat(jdbc.queryForMap("SELECT device_id,status,revision FROM maintenance_work_orders WHERE id=?", orderId))
            .isEqualTo(before);
    }


    @Test
    void archiveAndAttachmentChangesRequireOriginalDeviceScope() throws Exception {
        createArchive("vs archive archive", "vs-device-b");
        createArchive("vs archive update", "vs-device-b");
        createArchive("vs archive upload", "vs-device-b");
        String archiveId = archiveId("vs archive archive");
        String updateId = archiveId("vs archive update");
        String uploadId = archiveId("vs archive upload");

        JsonNode operator = createPlatform("vs-archive-operator",
            List.of("fire-archives:read", "fire-archives:write"), List.of("tenant-a"));
        String operatorId = operator.get("id").asText();
        assignScope("/api/management/platform-users/" + operatorId + "/scope", "tenant-a",
            scopeBody("DEVICE_SET", List.of("vs-device-a"), List.of()));
        String operatorToken = activate("vs-archive-operator", "management");

        call(HttpMethod.GET, "/api/management/fire-archives/" + archiveId, operatorToken, "tenant-a", null, null)
            .andExpect(status().isNotFound());

        var before = jdbc.queryForList("SELECT id,device_id,status,revision FROM fire_archives ORDER BY id");
        denied(call(HttpMethod.POST, "/api/management/fire-archives/" + archiveId + "/archive", operatorToken,
            "tenant-a", "deny-archive-" + UUID.randomUUID(), Map.of("expected_revision", 0, "reason", "fixture")));
        ObjectNode update = json.createObjectNode();
        update.put("title", "vs archive update").put("category", "fixture").putNull("archive_number")
            .putNull("device_id").putNull("place_id").putNull("issued_on").putNull("expires_on")
            .putNull("description").put("expected_revision", 0).put("reason", "fixture");
        denied(call(HttpMethod.PUT, "/api/management/fire-archives/" + updateId, operatorToken, "tenant-a",
            "deny-update-" + UUID.randomUUID(), update));
        denied(call(HttpMethod.POST, "/api/management/fire-archives/" + uploadId + "/attachments", operatorToken,
            "tenant-a", "deny-upload-" + UUID.randomUUID(), Map.of("filename", "vs.txt", "mime_type", "text/plain",
                "content_base64", Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)), "expected_revision", 0, "reason", "fixture")));
        assertThat(jdbc.queryForList("SELECT id,device_id,status,revision FROM fire_archives ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fire_archive_attachments WHERE archive_id=?", Long.class, uploadId)).isZero();
    }

    private void denied(ResultActions action) throws Exception {
        JsonNode result = body(action.andExpect(status().is4xxClientError()));
        assertThat(result.path("code").asText()).isIn("FORBIDDEN", "NOT_FOUND");
    }

    private ResultActions call(HttpMethod method, String path, String token, String tenant, String key, Object input) throws Exception {
        var builder = request(method, path);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (tenant != null) builder.header("X-Tenant-Id", tenant);
        if (key != null) builder.header("Idempotency-Key", key);
        if (input != null) builder.contentType("application/json").content(json.writeValueAsString(input));
        return mvc.perform(builder);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private JsonNode createPlatform(String username, List<String> permissions, List<String> grants) throws Exception {
        return body(call(HttpMethod.POST, "/api/management/platform-users", root, null,
            "vs-platform-" + UUID.randomUUID(),
            Map.of("username", username, "name", username, "initial_password", IngestionIntegrationTest.INITIAL,
                "permissions", permissions, "tenant_ids", grants)).andExpect(status().isOk()));
    }

    private String login(String username, String password, String audience) throws Exception {
        return body(call(HttpMethod.POST, "/api/auth/login", null, null, null,
            Map.of("username", username, "password", password, "audience", audience)).andExpect(status().isOk()))
            .get("access_token").asText();
    }

    private String activate(String username, String audience) throws Exception {
        String initial = login(username, IngestionIntegrationTest.INITIAL, audience);
        call(HttpMethod.POST, "/api/auth/change-password", initial, null, null,
            Map.of("current_password", IngestionIntegrationTest.INITIAL, "new_password", IngestionIntegrationTest.PASSWORD))
            .andExpect(status().isNoContent());
        return login(username, IngestionIntegrationTest.PASSWORD, audience);
    }

    private void assignScope(String path, String tenant, Map<String, Object> body) throws Exception {
        call(HttpMethod.PUT, path, root, tenant, "vs-scope-" + UUID.randomUUID(), body).andExpect(status().isOk());
    }

    private static Map<String, Object> scopeBody(String mode, List<String> devices, List<String> places) {
        return Map.of("mode", mode, "device_ids", devices, "place_ids", places, "expected_revision", 0, "reason", "fixture");
    }

    private void createArchive(String title, String deviceId) throws Exception {
        call(HttpMethod.POST, "/api/management/fire-archives", root, "tenant-a", "vs-archive-" + UUID.randomUUID(),
            Map.of("title", title, "category", "fixture", "device_id", deviceId, "reason", "fixture")).andExpect(status().isOk());
    }

    private String archiveId(String title) {
        return jdbc.queryForObject("SELECT id FROM fire_archives WHERE title=? AND device_id='vs-device-b'", String.class, title);
    }
}
