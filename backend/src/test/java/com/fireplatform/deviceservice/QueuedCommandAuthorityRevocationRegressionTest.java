package com.fireplatform.deviceservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.deviceservice.driver.LegacyTtlDriver;
import com.fireplatform.identity.AuthService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"app.roles=api", "app.storage=h2-dev", "app.device-service-poll-ms=3600000",
    "spring.datasource.url=jdbc:h2:mem:queuedauthorityregression;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(QueuedCommandAuthorityRevocationRegressionTest.ExternalFixture.class)
class QueuedCommandAuthorityRevocationRegressionTest {
    private static final String PASSWORD = "Changed-" + UUID.randomUUID();
    private static final String INITIAL = "Initial-" + UUID.randomUUID();

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
    @Autowired DeviceSourceBindingService bindings;
    @Autowired DeviceServiceCommandService commands;
    @Autowired TransactionTemplate transactions;
    @Autowired FixtureDriver driver;
    private String adminToken, operatorToken, operatorId, tenant, device, binding;

    @BeforeEach
    void prepare() {
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=FALSE WHERE id='bootstrap-admin'", encoder.encode(PASSWORD));
        adminToken = auth.login(new AuthService.LoginRequest("admin", PASSWORD, "management"), "fixture-admin").accessToken();
        jdbc.update("DELETE FROM device_service_automation_intents");
        jdbc.update("DELETE FROM device_service_command_attempts");
        jdbc.update("DELETE FROM device_service_commands");
        String suffix = UUID.randomUUID().toString();
        tenant = "queued-authority-unit-" + suffix;
        device = "queued-authority-device-" + suffix;
        operatorId = "queued-authority-operator-" + suffix;
        String username = "queued-authority-op-" + suffix;
        String external = "queued-authority-external-" + suffix;
        String serial = "queued-authority-serial-" + suffix;
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", tenant, "Queued authority unit");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin',?)", tenant);
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES ('bootstrap-admin',?,'ALL_TENANT',1)", tenant);
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state)"
            + " VALUES (?,?,?,?,?,?,'UNKNOWN',FALSE,'PENDING_INTEGRATION')",
            device, tenant, "Queued authority asset", serial, "queued-authority-model", "fixture-address");
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled,is_protected)"
            + " VALUES (?,?,?,'PLATFORM',NULL,?,FALSE,TRUE,FALSE)", operatorId, username, "Fixture operator", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES (?,?),(?,?)",
            operatorId, "device-services:execute", operatorId, "device-services:read");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES (?,?)", operatorId, tenant);
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES (?,?,'ALL_TENANT',1)", operatorId, tenant);
        operatorToken = auth.login(new AuthService.LoginRequest(username, PASSWORD, "management"), "fixture-operator").accessToken();
        driver.account = "fixture-account";
        driver.submits = 0;
        binding = transactions.execute(status -> bindings.bind(tenant, device, driver.key(), external, serial,
            driver.account, "bootstrap-admin").id());
    }

    @Test
    void permissionRevocationBlocksPreviouslyQueuedManualCommand() throws Exception {
        String command = queue(operatorToken);
        jdbc.update("DELETE FROM user_permissions WHERE user_id=? AND permission='device-services:execute'", operatorId);
        commands.dispatchOne();
        assertNoSubmit(command);
    }

    @Test
    void tenantDisableBlocksPreviouslyQueuedManualCommand() throws Exception {
        String command = queue(adminToken);
        mvc.perform(put("/api/management/units/" + tenant).header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", "disable-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "Queued authority unit", "enabled", false, "expected_revision", 0))))
            .andExpect(status().isOk());
        commands.dispatchOne();
        assertNoSubmit(command);
    }

    private String queue(String token) throws Exception {
        var response = mvc.perform(post("/api/management/devices/" + device + "/service-commands")
                .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", "queued-authority-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("service_key", LegacyTtlDriver.DETAIL_READ, "arguments", Map.of(),
                    "expected_device_revision", 0, "capability_revision", 0, "source_binding_id", binding,
                    "reason", "Isolated queued authority regression", "risk_confirmed", false))))
            .andExpect(status().isOk()).andReturn();
        String id = json.readTree(response.getResponse().getContentAsString()).path("command").path("id").asText();
        assertThat(jdbc.queryForObject("SELECT state FROM device_service_commands WHERE id=?", String.class, id)).isEqualTo("QUEUED");
        return id;
    }

    private void assertNoSubmit(String id) {
        assertThat(driver.submits).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM device_service_commands WHERE id=?", String.class, id))
            .isNotIn("ACCEPTED", "UNKNOWN", "IN_FLIGHT", "SUCCEEDED");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalFixture {
        @Bean FixtureDriver queuedAuthorityFixtureDriver(LegacyTtlDriver templates) {
            return new FixtureDriver(templates.services().stream()
                .filter(service -> LegacyTtlDriver.DETAIL_READ.equals(service.key())).toList());
        }
    }

    static final class FixtureDriver implements IntegrationDriver {
        private final List<DriverServiceDescriptor> definitions;
        String account;
        int submits;
        FixtureDriver(List<DriverServiceDescriptor> definitions) { this.definitions = definitions; }
        @Override public String key() { return "ISOLATED_QUEUED_AUTHORITY"; }
        @Override public String mode() { return "VENDOR_PLATFORM"; }
        @Override public String displayName() { return "Isolated queued-authority transport"; }
        @Override public DriverConfiguration configuration() {
            return new DriverConfiguration(true, true, "READY", "https://fixture.invalid", null, account, 1);
        }
        @Override public List<DriverServiceDescriptor> services() { return definitions; }
        @Override public String accountFingerprint() { return account; }
        @Override public DriverOutcome submit(DriverCall call) {
            submits++;
            return DriverOutcome.accepted("FIXTURE_ACCEPTED", null, null, "Fixture acceptance only");
        }
        @Override public DriverOutcome query(DriverCall call) {
            return DriverOutcome.unknown("FIXTURE_NO_RECEIPT", null, "No fixture receipt");
        }
    }
}
