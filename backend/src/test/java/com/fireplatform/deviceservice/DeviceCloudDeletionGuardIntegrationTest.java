package com.fireplatform.deviceservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** Real authorization/database/queue guards; the only external transport is an isolated test driver. */
@SpringBootTest(properties = {
    "app.roles=api", "app.storage=h2-dev",
    "app.data-transfer-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "spring.datasource.url=jdbc:h2:mem:clouddeleteguards;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DeviceCloudDeletionGuardIntegrationTest.ExternalFixture.class)
class DeviceCloudDeletionGuardIntegrationTest {
    private static final String INITIAL = "Initial-" + UUID.randomUUID();
    private static final String PASSWORD = "Changed-" + UUID.randomUUID();

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

    String token, tenant, device, external, serial, binding;

    @BeforeEach
    void prepare() {
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=FALSE WHERE id='bootstrap-admin'", encoder.encode(PASSWORD));
        token = auth.login(new AuthService.LoginRequest("admin", PASSWORD, "management"), "delete-guard-fixture").accessToken();
        String suffix = UUID.randomUUID().toString();
        tenant = "guard-unit-" + suffix;
        device = "guard-device-" + suffix;
        external = "guard-external-" + suffix;
        serial = "guard-serial-" + suffix;
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", tenant, "Isolated deletion guard");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin',?)", tenant);
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES ('bootstrap-admin',?,'ALL_TENANT',0)", tenant);
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state) VALUES (?,?,?,?,?,?,'UNKNOWN',FALSE,'PENDING_INTEGRATION')",
            device, tenant, "Isolated guard asset", serial, "guard-model", "Isolated address");
        driver.account = "fixture-account-A";
        driver.submits = 0;
        driver.queryOutcome = IntegrationDriver.DriverOutcome.unknown("FIXTURE_NO_RECEIPT", null, "No physical receipt exists in this test");
        driver.supported = true;
        binding = transactions.execute(status -> bindings.bind(tenant, device, driver.key(), external, serial,
            driver.account, "bootstrap-admin").id());
    }

    @Test
    void sameAccountCanExplicitlyCleanUpItsReleasedBindingWithoutClaimingPhysicalSuccess() throws Exception {
        release();
        String command = createdCommand();
        assertThat(commands.dispatchOne()).isTrue();
        assertThat(driver.submits).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM device_service_commands WHERE id=?", String.class, command)).isEqualTo("ACCEPTED");
        driver.queryOutcome = IntegrationDriver.DriverOutcome.succeeded("LEDGER_ABSENT",
            json.createObjectNode().put("ledger_absence_confirmed", true), "Isolated account-scoped ledger absence only");
        mvc.perform(post("/api/management/device-service-commands/" + command + "/reconcile")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenant)
            .header("Idempotency-Key", "reconcile-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                "reason", "Explicit isolated ledger reconciliation"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.command.state").value("SUCCEEDED"))
            .andExpect(jsonPath("$.result.ledger_absence_confirmed").value(true));
        assertThat(driver.submits).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_service_command_attempts WHERE command_id=?", Long.class, command)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT state FROM device_source_bindings WHERE id=?", String.class, binding)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT archived_at FROM devices WHERE id=?", Object.class, device)).isNull();
    }

    @Test
    void rotatingAccountCannotQueueDeletionOfTheOldAccountsHistoricalResource() throws Exception {
        release();
        driver.account = "fixture-account-B";
        deletion().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_CHANGED"));
        assertNoCommandOrExternalCall();
    }

    @Test
    void missingOriginalAccountCannotBeReplacedByTheCurrentAccount() throws Exception {
        release();
        jdbc.update("UPDATE device_source_bindings SET account_fingerprint=NULL WHERE id=?", binding);
        deletion().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOURCE_ACCOUNT_UNCONFIRMED"));
        assertNoCommandOrExternalCall();
    }

    @Test
    void oldQueuedIntentCannotSendWithANewAccountAndAnOldBinding() throws Exception {
        release();
        String command = createdCommand();
        ObjectNode snapshot = (ObjectNode) json.readTree(jdbc.queryForObject("SELECT snapshot_json FROM device_service_commands WHERE id=?", String.class, command));
        snapshot.put("account_fingerprint", "fixture-account-B");
        jdbc.update("UPDATE device_service_commands SET snapshot_json=? WHERE id=?", snapshot.toString(), command);
        driver.account = "fixture-account-B";
        commands.dispatchOne();
        assertNotSent(command, "ACCOUNT_CHANGED");
    }

    @Test
    void queuedDeletionCannotFollowAChangedSourceIdentity() throws Exception {
        String command = createdCommand();
        jdbc.update("UPDATE device_source_bindings SET source_external_id=? WHERE id=?", "different-" + external, binding);
        commands.dispatchOne();
        assertNotSent(command, "SOURCE_IDENTITY_CHANGED");
    }

    @Test
    void deviceModelDriftCannotSendAPreviouslyQueuedService() throws Exception {
        String command = createdReadCommand();
        jdbc.update("UPDATE devices SET model=?,revision=revision+1 WHERE id=?", "different-model", device);
        commands.dispatchOne();
        assertNotSent(command, "DEVICE_MODEL_CHANGED");
    }

    @Test
    void revokedCapabilityCannotSendAPreviouslyQueuedService() throws Exception {
        String command = createdReadCommand();
        driver.supported = false;
        commands.dispatchOne();
        assertNotSent(command, "CAPABILITY_UNVERIFIED");
    }

    @Test
    void editingTheDisplayNameDoesNotRetargetOrInvalidateTheQueuedService() throws Exception {
        String command = createdReadCommand();
        jdbc.update("UPDATE devices SET name=?,revision=revision+1 WHERE id=?", "Renamed isolated asset", device);
        assertThat(commands.dispatchOne()).isTrue();
        assertThat(driver.submits).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM device_service_commands WHERE id=?", String.class, command)).isEqualTo("ACCEPTED");
    }

    private void release() {
        transactions.executeWithoutResult(status -> bindings.release(binding, "bootstrap-admin", "Explicit fixture release", json.createObjectNode()));
    }

    private ResultActions deletion() throws Exception {
        return mvc.perform(post("/api/management/devices/" + device + "/cloud-delete")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenant)
            .header("Idempotency-Key", "delete-guard-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                "expected_device_revision", 0, "external_device_id", external, "external_serial", serial,
                "reason", "Explicit isolated cloud-ledger cleanup", "risk_confirmed", true))));
    }

    private String createdCommand() throws Exception {
        return json.readTree(deletion().andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("command").path("id").asText();
    }

    private String createdReadCommand() throws Exception {
        return json.readTree(mvc.perform(post("/api/management/devices/" + device + "/service-commands")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenant)
            .header("Idempotency-Key", "guard-read-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                "service_key", LegacyTtlDriver.DETAIL_READ, "arguments", Map.of(),
                "expected_device_revision", 0, "capability_revision", 0, "source_binding_id", binding,
                "reason", "Explicit isolated service read", "risk_confirmed", false))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("command").path("id").asText();
    }

    private void assertNoCommandOrExternalCall() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_service_commands WHERE device_id=?", Long.class, device)).isZero();
        assertThat(driver.submits).isZero();
    }

    private void assertNotSent(String command, String code) {
        assertThat(jdbc.queryForObject("SELECT state FROM device_service_commands WHERE id=?", String.class, command)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT vendor_code FROM device_service_commands WHERE id=?", String.class, command)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT outcome FROM device_service_command_attempts WHERE command_id=?", String.class, command)).isEqualTo("ABORTED");
        assertThat(driver.submits).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalFixture {
        @Bean FixtureDriver deletionFixtureDriver(LegacyTtlDriver templates) {
            return new FixtureDriver(templates.services().stream().filter(service ->
                "cloud.delete".equals(service.key()) || LegacyTtlDriver.DETAIL_READ.equals(service.key())).toList());
        }
    }

    static final class FixtureDriver implements IntegrationDriver {
        private final List<DriverServiceDescriptor> definitions;
        private final List<DriverServiceDescriptor> unverifiedDefinitions;
        boolean supported;
        String account;
        int submits;
        DriverOutcome queryOutcome;
        FixtureDriver(List<DriverServiceDescriptor> definitions) {
            this.definitions = definitions;
            this.unverifiedDefinitions = definitions.stream().map(service -> new DriverServiceDescriptor(
                service.key(), service.displayName(), service.category(), service.riskLevel(), service.resultKind(),
                service.inputSchema(), service.applicability(), service.evidence(), "UNVERIFIED", service.typedNormalAllowed())).toList();
        }
        @Override public String key() { return "ISOLATED_DELETE_GUARD"; }
        @Override public String mode() { return "VENDOR_PLATFORM"; }
        @Override public String displayName() { return "Isolated test-only external transport"; }
        @Override public DriverConfiguration configuration() {
            return new DriverConfiguration(true, true, "READY", "https://fixture.invalid", null, account, 1);
        }
        @Override public List<DriverServiceDescriptor> services() { return supported ? definitions : unverifiedDefinitions; }
        @Override public String accountFingerprint() { return account; }
        @Override public DriverOutcome submit(DriverCall call) {
            submits++;
            return DriverOutcome.accepted("FIXTURE_ACCEPTED", null, null, "Isolated transport acceptance; no physical device effect");
        }
        @Override public DriverOutcome query(DriverCall call) {
            return queryOutcome;
        }
    }
}
