package com.fireplatform.topology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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

/** Real metadata API checks only: no vendor connections, telemetry or physical gateway operations. */
@SpringBootTest(properties = {
    "app.roles=api", "app.storage=h2-dev", "app.task-poll-ms=60000",
    "spring.datasource.url=jdbc:h2:mem:gateway-cycle-regression;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GatewayTopologyCycleIntegrationTest {
    private static final String PASSWORD = "Topology-Regression-Confirmation-2026";
    private static final String BASE = "/api/management/topology";
    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry registry) {
        registry.add("APP_BOOTSTRAP_USERNAME", () -> "admin");
        registry.add("APP_BOOTSTRAP_PASSWORD", () -> "Initial-Topology-Regression-2026");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    String tenant;
    String token;
    String deviceA;
    String deviceB;
    String deviceC;
    String leaf;

    @BeforeEach
    void seedOwnedMetadata() throws Exception {
        tenant = "topology-" + UUID.randomUUID();
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=FALSE,enabled=TRUE WHERE id='bootstrap-admin'",
            passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO tenants(id,name) VALUES(?,?)", tenant, "Isolated topology regression");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin',?)", tenant);
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES('bootstrap-admin',?,'ALL_TENANT',1)", tenant);
        for (String permission : List.of("topology:read", "topology:write"))
            jdbc.update("INSERT INTO user_permissions(user_id,permission) SELECT 'bootstrap-admin',? WHERE NOT EXISTS"
                + " (SELECT 1 FROM user_permissions WHERE user_id='bootstrap-admin' AND permission=?)", permission, permission);
        deviceA = device("A"); deviceB = device("B"); deviceC = device("C"); leaf = device("Leaf");
        token = body(call(HttpMethod.POST, "/api/auth/login", Map.of("username", "admin", "password", PASSWORD,
            "audience", "management"), false)).get("access_token").asText();
    }

    @Test
    void gatewayCannotCarryItsOwnDeviceAsset() throws Exception {
        JsonNode gateway = gateway("Self", deviceA);
        link(gateway, deviceA).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOPOLOGY_CYCLE"));
        assertThat(activeEdges()).isZero();
        call(HttpMethod.GET, BASE + "/gateways/" + gateway.get("id").asText() + "/tree", null, true)
            .andExpect(status().isOk()).andExpect(jsonPath("$.links").isEmpty());
    }

    @Test
    void validHierarchyAcceptsLeafButRejectsAnAncestorAsChild() throws Exception {
        JsonNode a = gateway("A", deviceA), b = gateway("B", deviceB), c = gateway("C", deviceC);
        body(link(a, deviceB));
        body(link(b, deviceC));
        link(c, deviceA).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOPOLOGY_CYCLE"));
        JsonNode leafLink = body(link(c, leaf));
        assertThat(leafLink.get("device_id").asText()).isEqualTo(leaf);
        assertThat(activeEdges()).isEqualTo(3);
    }

    @Test
    void gatewayAssetReassignmentCannotTurnAnExistingChildIntoItsOwner() throws Exception {
        JsonNode gateway = gateway("Rebind", deviceA);
        body(link(gateway, deviceC));
        JsonNode rebound = body(update(gateway, leaf));
        update(rebound, deviceC).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOPOLOGY_CYCLE"));
        JsonNode tree = body(call(HttpMethod.GET, BASE + "/gateways/" + gateway.get("id").asText() + "/tree", null, true));
        assertThat(tree.get("gateway").get("device_id").asText()).isEqualTo(leaf);
        assertThat(tree.get("gateway").get("revision").asLong()).isEqualTo(1);
        assertThat(tree.get("links").get(0).get("device_id").asText()).isEqualTo(deviceC);
    }

    private String device(String suffix) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state)"
            + " VALUES(?,?,?,?,'metadata-fixture','Isolated metadata','NORMAL',FALSE,'PENDING_INTEGRATION')",
            id, tenant, "Topology " + suffix, "topology-" + id);
        return id;
    }

    private JsonNode gateway(String suffix, String owner) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("address", "QA-" + UUID.randomUUID()); input.put("name", "Topology " + suffix);
        input.put("protocol", "MANUAL"); input.put("device_id", owner); input.put("reason", "Isolated graph invariant regression");
        return body(call(HttpMethod.POST, BASE + "/gateways", input, true));
    }

    private ResultActions link(JsonNode gateway, String child) throws Exception {
        return call(HttpMethod.POST, BASE + "/links", Map.of("gateway_id", gateway.get("id").asText(),
            "child_kind", "DEVICE", "device_id", child, "provenance", "MANUAL", "reason", "Isolated graph invariant regression"), true);
    }

    private ResultActions update(JsonNode gateway, String owner) throws Exception {
        return call(HttpMethod.PUT, BASE + "/gateways/" + gateway.get("id").asText(), Map.of(
            "address", gateway.get("address").asText(), "name", gateway.get("name").asText(), "protocol", "MANUAL",
            "device_id", owner, "expected_revision", gateway.get("revision").asLong(), "reason", "Isolated asset reassignment regression"), true);
    }

    private long activeEdges() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM gateway_links WHERE tenant_id=? AND state='ACTIVE'", Long.class, tenant);
    }

    private ResultActions call(HttpMethod method, String path, Object input, boolean authenticated) throws Exception {
        var builder = request(method, path);
        if (authenticated) builder.header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenant);
        if (method != HttpMethod.GET) builder.header("Idempotency-Key", UUID.randomUUID().toString());
        if (input != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input));
        return mvc.perform(builder);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
