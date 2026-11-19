package com.fireplatform.deviceservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.ApiError;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real Flyway fixture for local commissioning: imported assets keep their imported product identity
 * (including no version when the source was ambiguous), and a legacy NEW_VENDOR add binds under its
 * own ledger driver instead of fabricating a Hikvision discovery row. No remote call is made: the
 * cloud step is represented by its persisted terminal state.
 */
@SpringBootTest(properties = {
    "app.roles=all",
    "app.storage=h2-dev",
    "app.task-poll-ms=60000",
    "app.data-transfer-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "spring.datasource.url=jdbc:h2:mem:legacycommission;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class LegacyImportedAssetCommissionTest {
    private static final String INITIAL = "Initial-" + UUID.randomUUID();
    private static final String PASSWORD = "Changed-" + UUID.randomUUID();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("APP_BOOTSTRAP_USERNAME", () -> "admin");
        registry.add("APP_BOOTSTRAP_PASSWORD", () -> INITIAL);
    }

    @Autowired DeviceRegistrationService registrations;
    @Autowired ImportedLegacyAssetMatcher importedAssets;
    @Autowired Bootstrap bootstrap;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired PasswordEncoder encoder;

    AuthService.Identity actor;
    String tenant;
    String placeId;

    @BeforeEach
    void reset() {
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=FALSE WHERE id='bootstrap-admin'",
            encoder.encode(PASSWORD));
        actor = auth.authenticate(auth.login(new AuthService.LoginRequest("admin", PASSWORD, "management"),
            "legacy-commission").accessToken());
        String unique = UUID.randomUUID().toString().replace("-", "");
        tenant = "LEGACY-" + unique.substring(0, 8);
        placeId = "LEGACY-PLACE-" + unique.substring(0, 8);
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", tenant, "Legacy fixture " + unique.substring(0, 8));
        jdbc.update("INSERT INTO places(id,tenant_id,name,legacy_place_id) VALUES (?,?,?,?)",
            placeId, tenant, "Legacy place", unique.substring(0, 8));
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES ('bootstrap-admin',?)", tenant);
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES ('bootstrap-admin',?,'ALL_TENANT',0)", tenant);
    }

    @Test
    void importedAssociationKeepsNullProductIdentityAndBindsTheExactLedger() {
        String external = "9" + uniqueDigits();
        String serial = "EUI-" + uniqueDigits();
        String deviceId = "LEGACY-TSF-" + external;
        importedDevice(deviceId, external, serial, null);
        String registrationId = registration("MATCH_EXISTING", "LEGACY_TSF", external, serial, null, null,
            """
            {"outcome":"SUCCEEDED","result":{"remote_lookup":true,"identity_verified":true,"ledger_id":"%s"}}
            """
                .formatted(external));

        var detail = registrations.commission(actor, registrationId, "commission-imported", commission(null, deviceId, 0L),
            "commission-imported");

        assertThat(detail.registration().status()).isEqualTo("COMMISSIONED");
        assertThat(detail.registration().deviceId()).isEqualTo(deviceId);
        assertThat(detail.registration().productVersionId()).isNull();
        assertThat(jdbc.queryForObject("SELECT product_version_id FROM devices WHERE id=?", String.class, deviceId)).isNull();
        var binding = jdbc.queryForMap("SELECT * FROM device_source_bindings WHERE device_id=? AND state='ACTIVE'", deviceId);
        assertThat(binding.get("driver_key")).isEqualTo("LEGACY_TSF");
        assertThat(binding.get("source_external_id")).isEqualTo(external);
        assertThat(binding.get("source_serial")).isEqualTo(serial);
    }

    @Test
    void importedAssociationRequiresPriorVerifiedRemoteIdentity() {
        String external = "9" + uniqueDigits();
        String serial = "EUI-" + uniqueDigits();
        String deviceId = "LEGACY-TSF-" + external;
        importedDevice(deviceId, external, serial, null);
        String registrationId = registration("MATCH_EXISTING", "LEGACY_TSF", external, serial, null, null,
            """
            {"outcome":"SUCCEEDED","result":{"remote_lookup":false,"identity_verified":false,"ledger_id":"%s"}}
            """
                .formatted(external));

        assertThatThrownBy(() -> registrations.commission(actor, registrationId, "commission-unverified",
            commission(null, deviceId, 0L), "commission-unverified"))
            .isInstanceOfSatisfying(ApiError.class,
                error -> assertThat(error.code).isEqualTo("LEGACY_REMOTE_UNVERIFIED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_source_bindings WHERE device_id=?", Long.class, deviceId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM device_registrations WHERE id=?", String.class, registrationId))
            .isEqualTo("CLOUD_REGISTERED");
    }

    @Test
    void importedAssociationCannotFabricateAProductVersion() {
        String external = "9" + uniqueDigits();
        String serial = "EUI-" + uniqueDigits();
        String deviceId = "LEGACY-TSF-" + external;
        importedDevice(deviceId, external, serial, null);
        String registrationId = registration("MATCH_EXISTING", "LEGACY_TSF", external, serial, null, null,
            """
            {"outcome":"SUCCEEDED","result":{"remote_lookup":true,"identity_verified":true,"ledger_id":"%s"}}
            """
                .formatted(external));

        assertThatThrownBy(() -> registrations.commission(actor, registrationId, "commission-version",
            commission("fabricated-version", deviceId, 0L), "commission-version"))
            .isInstanceOfSatisfying(ApiError.class,
                error -> assertThat(error.code).isEqualTo("IMPORTED_VERSION_IMMUTABLE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_source_bindings WHERE device_id=?", Long.class, deviceId)).isZero();
    }

    @Test
    void newVendorCommissioningBindsLegacyDriverWithoutHikDiscoveryRow() {
        String external = "6" + uniqueDigits();
        String serial = "EUI-NEW-" + uniqueDigits();
        String model = "MODEL-" + uniqueDigits();
        String product = "product-" + UUID.randomUUID();
        String version = "version-" + UUID.randomUUID();
        jdbc.update("INSERT INTO products(id,name,manufacturer,model,description,revision) VALUES (?,?,?,?,?,0)",
            product, "Legacy product", "Legacy vendor", model, "Fixture product");
        jdbc.update("INSERT INTO product_versions(id,product_id,version_number,status,revision,schema_json,capabilities_json,parser_tested,created_at,published_at) VALUES (?,?,1,'PUBLISHED',0,'{}','{\"battery\":\"SUPPORTED\"}',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            version, product);
        String registrationId = registration("NEW_VENDOR", "LEGACY_TSF", external, serial, "New legacy smoke", model,
            "{\"outcome\":\"SUCCEEDED\"}");

        var detail = registrations.commission(actor, registrationId, "commission-new-vendor", commission(version, null, null),
            "commission-new-vendor");

        assertThat(detail.registration().status()).isEqualTo("COMMISSIONED");
        String deviceId = detail.registration().deviceId();
        assertThat(deviceId).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT device_eui FROM devices WHERE id=?", String.class, deviceId)).isEqualTo(serial);
        assertThat(jdbc.queryForObject("SELECT product_version_id FROM devices WHERE id=?", String.class, deviceId)).isEqualTo(version);
        var binding = jdbc.queryForMap("SELECT * FROM device_source_bindings WHERE device_id=? AND state='ACTIVE'", deviceId);
        assertThat(binding.get("driver_key")).isEqualTo("LEGACY_TSF");
        assertThat(binding.get("source_external_id")).isEqualTo(external);
        assertThat(binding.get("source_serial")).isEqualTo(serial);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices WHERE external_id=?", Long.class, external)).isZero();
    }

    @Test
    void matcherUsesOnlyTheExactDriverLedgerNamespace() {
        String external = "5" + uniqueDigits();
        String serial = "EUI-MATCH-" + uniqueDigits();
        String deviceId = "LEGACY-TSF-" + external;
        importedDevice(deviceId, external, serial, null);

        assertThat(importedAssets.match(tenant, "LEGACY_TSF", serial, external).state()).isEqualTo("FOUND");
        assertThat(importedAssets.match(tenant, "LEGACY_TTL", serial, external).state()).isEqualTo("NOT_FOUND");
        assertThat(importedAssets.match(tenant, "LEGACY_TSF", serial, external + "0").state()).isEqualTo("NOT_FOUND");
        assertThat(importedAssets.match(tenant, "LEGACY_TSF", serial + "-drift", external).state()).isEqualTo("NOT_FOUND");
    }

    @Test
    void anotherTenantDuplicateSerialDoesNotBecomeTheMatchedAsset() {
        String external = "4" + uniqueDigits();
        String serial = "EUI-DUP-" + uniqueDigits();
        importedDevice("LEGACY-TSF-" + external, external, serial, null);
        String otherTenant = "LEGACY-OTHER-" + uniqueDigits();
        String otherDevice = "other-device-" + UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", otherTenant, "Other legacy tenant");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state) VALUES (?,?,?,?,?,?,?,FALSE,'PENDING_INTEGRATION')",
            otherDevice, otherTenant, "Other tenant device", "OTHER-" + serial, "legacy-model", "address", "UNKNOWN");
        jdbc.update("INSERT INTO device_aliases(id,tenant_id,device_id,kind,alias_value,origin,created_at) VALUES (?,?,?,'EUI',?,'LEGACY_TSF',CURRENT_TIMESTAMP)",
            "alias-" + UUID.randomUUID(), otherTenant, otherDevice, serial);

        var matched = importedAssets.match(tenant, "LEGACY_TSF", serial, external);
        assertThat(matched.state()).isEqualTo("FOUND");
        assertThat(matched.deviceId()).isEqualTo("LEGACY-TSF-" + external);
        assertThat(importedAssets.match(tenant, "LEGACY_TSF", serial, external + "9").state()).isEqualTo("NOT_FOUND");
    }

    private void importedDevice(String deviceId, String external, String serial, String version) {
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,place_id,physical_state,is_simulated,product_version_id,integration_state) VALUES (?,?,?,?,?,?,?,?,FALSE,?,'PENDING_INTEGRATION')",
            deviceId, tenant, "Imported " + external, serial, "legacy-model", "Imported address", placeId, "UNKNOWN", version);
        jdbc.update("INSERT INTO device_aliases(id,tenant_id,device_id,kind,alias_value,origin,created_at) VALUES (?,?,?,'EUI',?,'LEGACY_TSF',CURRENT_TIMESTAMP)",
            "alias-" + UUID.randomUUID(), tenant, deviceId, serial);
        jdbc.update("INSERT INTO device_aliases(id,tenant_id,device_id,kind,alias_value,origin,created_at) VALUES (?,?,?,'VENDOR_ID',?,'LEGACY_TSF',CURRENT_TIMESTAMP)",
            "alias-" + UUID.randomUUID(), tenant, deviceId, "platform-" + external);
    }

    private String registration(String mode, String driver, String external, String serial, String name, String model,
                                String cloudResultJson) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO device_registrations(id,driver_key,mode,status,tenant_id,place_id,external_device_id,external_serial,device_name,device_model,cloud_state,cloud_result_json,local_state,created_by,created_at,updated_at) VALUES (?,?,?,'CLOUD_REGISTERED',?,?,?,?,?,?,'SUCCEEDED',?,'READY','bootstrap-admin',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            id, driver, mode, tenant, placeId, external, serial, name, model, cloudResultJson);
        return id;
    }

    private DeviceServiceInputs.CommissionRegistration commission(String version, String existingDeviceId, Long revision) {
        return new DeviceServiceInputs.CommissionRegistration(tenant, placeId, version, existingDeviceId, revision,
            "Explicit local acceptance", null, null, null, null);
    }

    private static String uniqueDigits() {
        return String.format("%010d", Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong() % 10_000_000_000L));
    }
}
