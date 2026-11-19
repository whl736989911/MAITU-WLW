package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fireplatform.application.ApplicationDeliveryProcessor;
import com.fireplatform.application.ApplicationDeliveryTransport;
import com.fireplatform.application.ApplicationEndpointPolicy;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.integration.BusinessFactPublisher;
import com.fireplatform.integration.NormalizedBusinessFact;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumer-visible contract of the open-application output capture and claim path.
 *
 * <p>The publisher only captures a durable outbox row; it never proves the fact satisfies the frozen reference
 * identity. The claim worker rechecks that identity and the frozen configuration, so a missing decoder/adapter
 * digest or a different one is recorded as BLOCKED evidence and never as an accepted delivery. A refused capture
 * SQL statement must also leave the producer's own applied business fact committed.
 */
@SpringBootTest(properties={"app.roles=api","app.storage=h2-dev","app.task-poll-ms=60000",
    "spring.datasource.url=jdbc:h2:mem:application-output-identity;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class ApplicationOutputIntegrationTest {
    private static final String TENANT = "output-unit";
    private static final String DEVICE = "output-device";
    private static final String ACTOR = "output-actor";
    private static final String PLATFORM = "aep_turbo";
    private static final String VENDOR_PLATFORM = "hikvision";
    private static final String DRIVER = "hikvision.isapi";
    private static final String DIGEST = "a".repeat(64);
    private static final String OTHER_DIGEST = "b".repeat(64);
    private static final String MQTT_ORIGIN = "mqtts://broker.example.test:8883";
    private static final String MQTT_TOPIC = "fire/alarm";
    private static final OffsetDateTime EVENT_AT = OffsetDateTime.parse("2026-10-30T14:44:16Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessFactPublisher publisher;
    @Autowired ApplicationDeliveryProcessor processor;
    @Autowired PlatformTransactionManager transactions;

    private record ProductVersion(String productId, String versionId) { }
    private record ProxyDatabase(DriverManagerDataSource dataSource, String databaseName) { }

    @BeforeEach
    void resetFixture() {
        for (String table : List.of("application_delivery_attempts", "application_delivery_outbox",
            "application_authorization_devices", "application_outputs", "application_product_authorizations",
            "output_application_events", "output_applications", "hikvision_discovered_devices", "device_events",
            "alarms", "devices", "product_versions", "products", "sessions", "user_permissions",
            "user_tenant_grants", "app_users", "tenants")) {
            jdbc.update("DELETE FROM " + table);
        }
        seedBase(jdbc);
    }

    private static void seedBase(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", TENANT, "Application output unit");
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,"
            + "enabled,is_protected) VALUES (?,?,?,'PLATFORM','fixture-hash',FALSE,TRUE,FALSE)",
            ACTOR, ACTOR, "Output fixture actor");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,"
                + "integration_state) VALUES (?,?,?,?,'fixture-model','Fixture address','NORMAL',FALSE,"
                + "'PENDING_INTEGRATION')",
            DEVICE, TENANT, "Output device", "eui-" + DEVICE);
    }

    private static String createApplication(JdbcTemplate jdbc, String name) {
        String id = "application-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO output_applications(id,tenant_id,name,description,status,revision,created_by,"
                + "created_at,updated_by,updated_at) VALUES (?,?,?,?,'ACTIVE',0,?,?,?,?)",
            id, TENANT, name, "Identity fixture application", ACTOR, now, ACTOR, now);
        return id;
    }

    private static String createOutput(JdbcTemplate jdbc, String applicationId, String name) {
        String id = "output-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO application_outputs(id,tenant_id,application_id,name,mode,enabled,"
                + "endpoint_url_cipher,endpoint_url_nonce,endpoint_origin,endpoint_digest,endpoint_masked,"
                + "auth_scheme,timeout_ms,revision,created_by,created_at,updated_by,updated_at) "
                + "VALUES (?,?,?,?,'NORMALIZED',TRUE,'fixture-url-cipher','fixture-url-nonce',?,?,?,"
                + "'NONE',5000,0,?,?,?,?)",
            id, TENANT, applicationId, name, "https://output.example.test",
            "c".repeat(64), "https://output.example.test/***", ACTOR, now, ACTOR, now);
        return id;
    }

    /** An enabled MQTT output: mqtts origin, frozen topic digest and encrypted topic/username/password. */
    private static String createMqttOutput(JdbcTemplate jdbc, String applicationId, String name, String origin,
                                           String topic) {
        String id = "output-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String digest = ApplicationEndpointPolicy.mqttDigest(origin, topic);
        jdbc.update("INSERT INTO application_outputs(id,tenant_id,application_id,name,mode,protocol,enabled,"
                + "endpoint_url_cipher,endpoint_url_nonce,endpoint_origin,endpoint_digest,endpoint_masked,"
                + "auth_scheme,timeout_ms,mqtt_auth_scheme,mqtt_topic_cipher,mqtt_topic_nonce,mqtt_topic_masked,"
                + "mqtt_topic_digest,mqtt_username_cipher,mqtt_username_nonce,mqtt_username_masked,"
                + "mqtt_username_digest,mqtt_password_cipher,mqtt_password_nonce,mqtt_password_masked,"
                + "mqtt_password_digest,revision,created_by,created_at,updated_by,updated_at) "
                + "VALUES (?,?,?,?,'NORMALIZED','MQTT_TLS_3_1_1',TRUE,'url-cipher','url-nonce',?,?,?,"
                + "'NONE',5000,'USERNAME_PASSWORD','topic-cipher','topic-nonce','fire/***',?,"
                + "'username-cipher','username-nonce','****user',?,'password-cipher','password-nonce','****pass',?,"
                + "0,?,?,?,?)",
            id, TENANT, applicationId, name, origin, digest, origin + "/***", "d".repeat(64),
            "e".repeat(64), "f".repeat(64), ACTOR, now, ACTOR, now);
        return id;
    }

    private static ProductVersion createProductVersion(JdbcTemplate jdbc) {
        String suffix = UUID.randomUUID().toString();
        String productId = "product-" + suffix;
        String versionId = "version-" + suffix;
        jdbc.update("INSERT INTO products(id,name,manufacturer,model,description,revision) "
                + "VALUES (?,?,?,?,?,0)", productId, "Output product " + suffix,
            "Output manufacturer " + suffix, "model-" + suffix, "Identity fixture product");
        jdbc.update("INSERT INTO product_versions(id,product_id,version_number,status,revision,schema_json,"
                + "capabilities_json,parser_tested,created_at) VALUES (?,?,1,'PUBLISHED',0,'{}','{}',FALSE,?)",
            versionId, productId, OffsetDateTime.now());
        return new ProductVersion(productId, versionId);
    }

    private static String authorizeFormal(JdbcTemplate jdbc, String applicationId, ProductVersion version) {
        String id = "authorization-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO application_product_authorizations(id,tenant_id,application_id,reference_kind,"
                + "reference_key,product_id,product_version_id,scope_mode,status,revision,created_by,created_at,"
                + "updated_by,updated_at) VALUES (?,?,?,'FORMAL_VERSION',?,?,?,'ALL_TENANT','ACTIVE',0,?,?,?,?)",
            id, TENANT, applicationId, "FORMAL:" + version.versionId(), version.productId(), version.versionId(),
            ACTOR, now, ACTOR, now);
        return id;
    }

    private static String authorizeLegacy(JdbcTemplate jdbc, String applicationId, String legacyProductId,
                                          String decoderDigest) {
        String id = "authorization-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO application_product_authorizations(id,tenant_id,application_id,reference_kind,"
                + "reference_key,source_platform,legacy_product_id,legacy_decoder_revision,legacy_decoder_sha256,"
                + "scope_mode,status,revision,created_by,created_at,updated_by,updated_at) "
                + "VALUES (?,?,?,'LEGACY_PRODUCT',?,?,?,1,?,'ALL_TENANT','ACTIVE',0,?,?,?,?)",
            id, TENANT, applicationId, "LEGACY:" + PLATFORM + ":" + legacyProductId, PLATFORM, legacyProductId,
            decoderDigest, ACTOR, now, ACTOR, now);
        return id;
    }

    private static String authorizeVendor(JdbcTemplate jdbc, String applicationId, String vendorModelId,
                                          String driverKey, String adapterDigest) {
        String id = "authorization-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO application_product_authorizations(id,tenant_id,application_id,reference_kind,"
                + "reference_key,source_platform,vendor_model_id,driver_key,adapter_digest,scope_mode,status,"
                + "revision,created_by,created_at,updated_by,updated_at) "
                + "VALUES (?,?,?,'VENDOR_MODEL',?,?,?,?,?,'ALL_TENANT','ACTIVE',0,?,?,?,?)",
            id, TENANT, applicationId, "VENDOR:" + VENDOR_PLATFORM + ":" + vendorModelId, VENDOR_PLATFORM,
            vendorModelId, driverKey, adapterDigest, ACTOR, now, ACTOR, now);
        return id;
    }

    private static NormalizedBusinessFact formalFact(String factId, String versionId) {
        return fact(factId, versionId, null, null, null, null, "generic");
    }

    private static NormalizedBusinessFact legacyFact(String factId, String legacyProductId) {
        return legacyFact(factId, legacyProductId, DIGEST);
    }

    private static NormalizedBusinessFact legacyFact(String factId, String legacyProductId, String digest) {
        return fact(factId, null, NormalizedBusinessFact.LEGACY_PRODUCT, legacyProductId, null, digest, PLATFORM);
    }

    private static NormalizedBusinessFact vendorFact(String factId, String vendorModelId, String driverKey,
                                                     String digest) {
        return fact(factId, null, NormalizedBusinessFact.VENDOR_MODEL, vendorModelId, driverKey, digest,
            VENDOR_PLATFORM);
    }

    private static NormalizedBusinessFact fact(String factId, String versionId, String kind, String sourceId,
                                               String driverKey, String digest, String platform) {
        return new NormalizedBusinessFact(factId, TENANT, DEVICE, versionId, null, platform, kind, sourceId,
            driverKey, digest, "message-" + factId, "ALARM", EVENT_AT, EVENT_AT, null,
            "{\"event\":\"FIRE\",\"device\":\"" + DEVICE + "\"}", null, null, false, true);
    }

    private static String newFactId() {
        return "fact-" + UUID.randomUUID();
    }

    private void publish(NormalizedBusinessFact fact) {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> publisher.publish(fact));
    }

    private Map<String, Object> outbox(String factId) {
        return jdbc.queryForMap("SELECT id,status,failure_code,attempt_count,payload_base64,payload_sha256,"
            + "payload_bytes,source_product_digest,authorization_reference_kind FROM application_delivery_outbox"
            + " WHERE fact_id=?", factId);
    }

    private List<Map<String, Object>> attempts(String factId) {
        return jdbc.queryForList("SELECT a.status,a.failure_code FROM application_delivery_attempts a"
            + " JOIN application_delivery_outbox d ON d.id=a.outbox_id WHERE d.fact_id=? ORDER BY a.attempt_no",
            factId);
    }

    private void assertNeverAccepted() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_delivery_attempts WHERE status='ACCEPTED'",
            Long.class)).isZero();
    }

    @Test
    void formalVersionIdentityIsClaimedWhenFactAndDeviceProveTheFrozenVersion() {
        ProductVersion version = createProductVersion(jdbc);
        String application = createApplication(jdbc, "Formal identity application");
        authorizeFormal(jdbc, application, version);
        createOutput(jdbc, application, "Formal identity output");
        jdbc.update("UPDATE devices SET product_version_id=? WHERE id=?", version.versionId(), DEVICE);

        NormalizedBusinessFact fact = formalFact(newFactId(), version.versionId());
        publish(fact);

        // A formal grant has no source platform, so the capture must select it by product version alone.
        assertThat(jdbc.queryForList("SELECT status FROM application_delivery_outbox WHERE fact_id=?",
            String.class, fact.factId())).containsExactly("PENDING");
        var claim = processor.claim();
        assertThat(claim).isNotNull();
        assertThat(claim.referenceKind()).isEqualTo("FORMAL_VERSION");
        assertThat(claim.payload()).isEqualTo(fact.normalizedJson().getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("SELECT status FROM application_delivery_outbox WHERE fact_id=?",
            String.class, fact.factId())).isEqualTo("IN_FLIGHT");
    }

    @Test
    void formalVersionMismatchOnTheDeviceBlocksWithoutDelivery() {
        ProductVersion authorized = createProductVersion(jdbc);
        ProductVersion installed = createProductVersion(jdbc);
        String application = createApplication(jdbc, "Formal mismatch application");
        authorizeFormal(jdbc, application, authorized);
        createOutput(jdbc, application, "Formal mismatch output");
        jdbc.update("UPDATE devices SET product_version_id=? WHERE id=?", installed.versionId(), DEVICE);

        NormalizedBusinessFact fact = formalFact(newFactId(), authorized.versionId());
        publish(fact);

        assertThat(jdbc.queryForList("SELECT status FROM application_delivery_outbox WHERE fact_id=?",
            String.class, fact.factId())).containsExactly("PENDING");
        assertThat(processor.claim()).isNull();
        assertThat(outbox(fact.factId())).containsEntry("status", "BLOCKED")
            .containsEntry("failure_code", "IDENTITY_MISMATCH");
        assertThat(attempts(fact.factId())).singleElement().satisfies(attempt ->
            assertThat(attempt).containsEntry("status", "BLOCKED")
                .containsEntry("failure_code", "IDENTITY_MISMATCH"));
        assertNeverAccepted();
    }

    @Test
    void formalFactProvingAnotherVersionIsNotCaptured() {
        ProductVersion authorized = createProductVersion(jdbc);
        ProductVersion other = createProductVersion(jdbc);
        String application = createApplication(jdbc, "Formal foreign version application");
        authorizeFormal(jdbc, application, authorized);
        createOutput(jdbc, application, "Formal foreign version output");
        jdbc.update("UPDATE devices SET product_version_id=? WHERE id=?", authorized.versionId(), DEVICE);

        NormalizedBusinessFact fact = formalFact(newFactId(), other.versionId());
        publish(fact);

        // A different proven version is a different identity, not a covered asset with missing proof.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_delivery_outbox WHERE fact_id=?",
            Long.class, fact.factId())).isZero();
        assertThat(processor.claim()).isNull();
        assertNeverAccepted();
    }

    @Test
    void formalFactWithoutProductVersionIsCapturedThenBlockedAsPrerequisiteMissing() {
        ProductVersion version = createProductVersion(jdbc);
        String application = createApplication(jdbc, "Formal missing version application");
        authorizeFormal(jdbc, application, version);
        createOutput(jdbc, application, "Formal missing version output");
        jdbc.update("UPDATE devices SET product_version_id=? WHERE id=?", version.versionId(), DEVICE);

        NormalizedBusinessFact fact = formalFact(newFactId(), null);
        publish(fact);

        assertThat(jdbc.queryForList("SELECT status FROM application_delivery_outbox WHERE fact_id=?",
            String.class, fact.factId())).containsExactly("PENDING");
        assertThat(processor.claim()).isNull();
        assertThat(outbox(fact.factId())).containsEntry("status", "BLOCKED")
            .containsEntry("failure_code", "IDENTITY_PREREQUISITE_MISSING");
        assertThat(attempts(fact.factId())).singleElement().satisfies(attempt ->
            assertThat(attempt).containsEntry("status", "BLOCKED")
                .containsEntry("failure_code", "IDENTITY_PREREQUISITE_MISSING"));
        assertNeverAccepted();
    }

    @Test
    void legacyExactDecoderDigestIsClaimed() {
        String application = createApplication(jdbc, "Legacy exact application");
        authorizeLegacy(jdbc, application, "legacy-exact", DIGEST);
        createOutput(jdbc, application, "Legacy exact output");

        NormalizedBusinessFact fact = legacyFact(newFactId(), "legacy-exact");
        publish(fact);

        var claim = processor.claim();
        assertThat(claim).isNotNull();
        assertThat(claim.referenceKind()).isEqualTo(NormalizedBusinessFact.LEGACY_PRODUCT);
        assertThat(claim.frozenDigest()).isEqualTo(DIGEST);
        assertThat(claim.payload()).isEqualTo(fact.normalizedJson().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void legacyFactWithoutDecoderDigestIsCapturedThenDurablyBlocked() {
        String application = createApplication(jdbc, "Legacy missing digest application");
        authorizeLegacy(jdbc, application, "legacy-missing", DIGEST);
        createOutput(jdbc, application, "Legacy missing digest output");

        NormalizedBusinessFact fact = legacyFact(newFactId(), "legacy-missing", null);
        publish(fact);

        // Capture is evidence, not delivery: the row exists so the worker can record the missing prerequisite.
        assertThat(outbox(fact.factId())).containsEntry("status", "PENDING").containsEntry("attempt_count", 0);
        assertThat(processor.claim()).isNull();
        assertThat(outbox(fact.factId())).containsEntry("status", "BLOCKED")
            .containsEntry("failure_code", "IDENTITY_PREREQUISITE_MISSING");
        assertThat(attempts(fact.factId())).singleElement().satisfies(attempt ->
            assertThat(attempt).containsEntry("status", "BLOCKED")
                .containsEntry("failure_code", "IDENTITY_PREREQUISITE_MISSING"));
        assertNeverAccepted();
    }

    @Test
    void legacyAuthorizationWithoutFrozenDecoderDigestIsNotSatisfiedByAFactDigest() {
        String application = createApplication(jdbc, "Legacy unfrozen digest application");
        authorizeLegacy(jdbc, application, "legacy-unfrozen", null);
        createOutput(jdbc, application, "Legacy unfrozen digest output");

        NormalizedBusinessFact fact = legacyFact(newFactId(), "legacy-unfrozen", DIGEST);
        publish(fact);

        assertThat(processor.claim()).isNull();
        assertThat(outbox(fact.factId())).containsEntry("status", "BLOCKED")
            .containsEntry("failure_code", "IDENTITY_PREREQUISITE_MISSING");
        assertThat(attempts(fact.factId())).singleElement().satisfies(attempt ->
            assertThat(attempt).containsEntry("status", "BLOCKED"));
        assertNeverAccepted();
    }

    @Test
    void legacyFactWithDifferentDecoderDigestIsBlockedAsMismatch() {
        String application = createApplication(jdbc, "Legacy mismatch application");
        authorizeLegacy(jdbc, application, "legacy-mismatch", DIGEST);
        createOutput(jdbc, application, "Legacy mismatch output");

        NormalizedBusinessFact fact = legacyFact(newFactId(), "legacy-mismatch", OTHER_DIGEST);
        publish(fact);

        assertThat(processor.claim()).isNull();
        assertThat(outbox(fact.factId())).containsEntry("status", "BLOCKED")
            .containsEntry("failure_code", "IDENTITY_MISMATCH");
        assertThat(attempts(fact.factId())).singleElement().satisfies(attempt ->
            assertThat(attempt).containsEntry("status", "BLOCKED")
                .containsEntry("failure_code", "IDENTITY_MISMATCH"));
        assertNeverAccepted();
    }

    @Test
    void vendorModelExactAdapterDigestAndDriverIsClaimed() {
        String application = createApplication(jdbc, "Vendor exact application");
        authorizeVendor(jdbc, application, "vendor-exact", DRIVER, DIGEST);
        createOutput(jdbc, application, "Vendor exact output");

        NormalizedBusinessFact fact = vendorFact(newFactId(), "vendor-exact", DRIVER, DIGEST);
        publish(fact);

        var claim = processor.claim();
        assertThat(claim).isNotNull();
        assertThat(claim.referenceKind()).isEqualTo(NormalizedBusinessFact.VENDOR_MODEL);
        assertThat(claim.frozenDigest()).isEqualTo(DIGEST);
        assertThat(claim.payload()).isEqualTo(fact.normalizedJson().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void vendorModelMissingDigestDifferentDigestAndDifferentDriverAreAllBlocked() {
        String application = createApplication(jdbc, "Vendor evidence application");
        authorizeVendor(jdbc, application, "vendor-model", DRIVER, DIGEST);
        createOutput(jdbc, application, "Vendor evidence output");

        NormalizedBusinessFact missing = vendorFact(newFactId(), "vendor-model", DRIVER, null);
        NormalizedBusinessFact different = vendorFact(newFactId(), "vendor-model", DRIVER, OTHER_DIGEST);
        NormalizedBusinessFact wrongDriver = vendorFact(newFactId(), "vendor-model", "other.driver", DIGEST);
        publish(missing);
        publish(different);
        publish(wrongDriver);

        assertThat(processor.claim()).isNull();
        assertThat(outbox(missing.factId())).containsEntry("status", "BLOCKED")
            .containsEntry("failure_code", "IDENTITY_PREREQUISITE_MISSING");
        assertThat(outbox(different.factId())).containsEntry("status", "BLOCKED")
            .containsEntry("failure_code", "IDENTITY_MISMATCH");
        assertThat(outbox(wrongDriver.factId())).containsEntry("status", "BLOCKED")
            .containsEntry("failure_code", "IDENTITY_MISMATCH");
        assertThat(attempts(missing.factId())).hasSize(1);
        assertThat(attempts(different.factId())).hasSize(1);
        assertThat(attempts(wrongDriver.factId())).hasSize(1);
        assertNeverAccepted();
    }

    @Test
    void factProvingAnotherIdentityIsNeverCapturedForAnAuthorization() {
        String application = createApplication(jdbc, "Foreign identity application");
        authorizeLegacy(jdbc, application, "legacy-only", DIGEST);
        createOutput(jdbc, application, "Foreign identity output");

        NormalizedBusinessFact fact = vendorFact(newFactId(), "vendor-other", DRIVER, DIGEST);
        publish(fact);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_delivery_outbox WHERE fact_id=?",
            Long.class, fact.factId())).isZero();
        assertThat(processor.claim()).isNull();
        assertNeverAccepted();
    }

    @Test
    void revokedAuthorizationBlocksAnAlreadyCapturedDelivery() {
        String application = createApplication(jdbc, "Revoked application");
        String authorization = authorizeLegacy(jdbc, application, "legacy-revoke", DIGEST);
        createOutput(jdbc, application, "Revoked output");

        NormalizedBusinessFact fact = legacyFact(newFactId(), "legacy-revoke");
        publish(fact);
        assertThat(outbox(fact.factId())).containsEntry("status", "PENDING");

        jdbc.update("UPDATE application_product_authorizations SET status='REVOKED',revoked_by=?,revoked_at=?,"
            + "revoked_reason='Fixture revocation',revision=revision+1,updated_by=?,updated_at=? WHERE id=?",
            ACTOR, OffsetDateTime.now(), ACTOR, OffsetDateTime.now(), authorization);

        assertThat(processor.claim()).isNull();
        assertThat(outbox(fact.factId())).containsEntry("status", "BLOCKED")
            .containsEntry("failure_code", "AUTHORIZATION_REVOKED");
        assertThat(attempts(fact.factId())).singleElement().satisfies(attempt ->
            assertThat(attempt).containsEntry("status", "BLOCKED"));
        assertNeverAccepted();
    }

    @Test
    void outputConfigurationDriftBlocksAnAlreadyCapturedDelivery() {
        String application = createApplication(jdbc, "Drifted application");
        authorizeLegacy(jdbc, application, "legacy-drift", DIGEST);
        String output = createOutput(jdbc, application, "Drifted output");

        NormalizedBusinessFact fact = legacyFact(newFactId(), "legacy-drift");
        publish(fact);
        assertThat(outbox(fact.factId())).containsEntry("status", "PENDING");

        jdbc.update("UPDATE application_outputs SET revision=revision+1,endpoint_digest=?,updated_by=?,"
            + "updated_at=? WHERE id=?", OTHER_DIGEST, ACTOR, OffsetDateTime.now(), output);

        assertThat(processor.claim()).isNull();
        assertThat(outbox(fact.factId())).containsEntry("status", "BLOCKED")
            .containsEntry("failure_code", "OUTPUT_CONFIGURATION_CHANGED");
        assertThat(attempts(fact.factId())).singleElement().satisfies(attempt ->
            assertThat(attempt).containsEntry("status", "BLOCKED"));
        assertNeverAccepted();
    }

    @Test
    void interruptedInFlightDeliveryBecomesUnknownAndIsNeverResent() {
        String application = createApplication(jdbc, "Unknown application");
        authorizeLegacy(jdbc, application, "legacy-unknown", DIGEST);
        createOutput(jdbc, application, "Unknown output");

        NormalizedBusinessFact fact = legacyFact(newFactId(), "legacy-unknown");
        publish(fact);
        assertThat(processor.claim()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM application_delivery_outbox WHERE fact_id=?",
            String.class, fact.factId())).isEqualTo("IN_FLIGHT");

        jdbc.update("UPDATE application_delivery_outbox SET claimed_at=? WHERE fact_id=?",
            OffsetDateTime.now().minusMinutes(5), fact.factId());
        assertThat(processor.recoverInterrupted()).isEqualTo(1);

        Map<String, Object> recovered = outbox(fact.factId());
        assertThat(recovered).containsEntry("status", "UNKNOWN")
            .containsEntry("failure_code", "RECOVERY_INTERRUPTED");
        assertThat(jdbc.queryForObject("SELECT finished_at FROM application_delivery_outbox WHERE fact_id=?",
            OffsetDateTime.class, fact.factId())).isNotNull();
        assertThat(attempts(fact.factId())).singleElement().satisfies(attempt ->
            assertThat(attempt).containsEntry("status", "UNKNOWN")
                .containsEntry("failure_code", "RECOVERY_INTERRUPTED"));

        // UNKNOWN is terminal: the next worker pass must not claim or resend it.
        assertThat(processor.claim()).isNull();
        assertThat(outbox(fact.factId())).containsEntry("status", "UNKNOWN")
            .containsEntry("attempt_count", 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_delivery_outbox WHERE fact_id=?"
            + " AND status IN ('PENDING','IN_FLIGHT')", Long.class, fact.factId())).isZero();
        assertNeverAccepted();
    }

    @Test
    void mqttOutputIsClaimedWithFrozenProtocolTopicDigestAndEncryptedMaterial() {
        ProductVersion version = createProductVersion(jdbc);
        String application = createApplication(jdbc, "MQTT output application");
        authorizeFormal(jdbc, application, version);
        createMqttOutput(jdbc, application, "MQTT output", MQTT_ORIGIN, MQTT_TOPIC);
        jdbc.update("UPDATE devices SET product_version_id=? WHERE id=?", version.versionId(), DEVICE);

        NormalizedBusinessFact fact = formalFact(newFactId(), version.versionId());
        publish(fact);
        assertThat(outbox(fact.factId())).containsEntry("status", "PENDING");

        var claim = processor.claim();
        assertThat(claim).isNotNull();
        assertThat(claim.protocol()).isEqualTo("MQTT_TLS_3_1_1");
        assertThat(claim.endpointOrigin()).isEqualTo(MQTT_ORIGIN);
        assertThat(claim.endpointDigest()).isEqualTo(ApplicationEndpointPolicy.mqttDigest(MQTT_ORIGIN, MQTT_TOPIC));
        assertThat(claim.mqttTopicCipher()).isEqualTo("topic-cipher");
        assertThat(claim.mqttTopicNonce()).isEqualTo("topic-nonce");
        assertThat(claim.mqttUsernameCipher()).isEqualTo("username-cipher");
        assertThat(claim.mqttPasswordCipher()).isEqualTo("password-cipher");
        assertThat(claim.payload()).isEqualTo(fact.normalizedJson().getBytes(StandardCharsets.UTF_8));

        // A matching PUBACK is transport acceptance only: no HTTP status exists and the row is terminal.
        processor.finish(claim, new ApplicationDeliveryTransport.Outcome("ACCEPTED", null, null));
        assertThat(outbox(fact.factId())).containsEntry("status", "ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT http_status FROM application_delivery_outbox WHERE fact_id=?",
            Integer.class, fact.factId())).isNull();
        assertThat(jdbc.queryForMap("SELECT status,http_status FROM application_delivery_attempts"
            + " WHERE outbox_id=?", claim.outboxId()))
            .containsEntry("status", "ACCEPTED").containsEntry("http_status", null);
        assertThat(processor.claim()).isNull();
    }

    @Test
    void mqttTopicChangeBlocksTheFrozenClaimBeforeAnyIo() {
        ProductVersion version = createProductVersion(jdbc);
        String application = createApplication(jdbc, "MQTT topic drift application");
        authorizeFormal(jdbc, application, version);
        String output = createMqttOutput(jdbc, application, "MQTT topic drift output", MQTT_ORIGIN, MQTT_TOPIC);
        jdbc.update("UPDATE devices SET product_version_id=? WHERE id=?", version.versionId(), DEVICE);

        NormalizedBusinessFact fact = formalFact(newFactId(), version.versionId());
        publish(fact);
        assertThat(outbox(fact.factId())).containsEntry("status", "PENDING");

        // Same protocol and revision, but the frozen digest now belongs to another topic: nothing may be sent.
        jdbc.update("UPDATE application_outputs SET endpoint_digest=?,mqtt_topic_digest=?,updated_by=?,updated_at=?"
                + " WHERE id=?", ApplicationEndpointPolicy.mqttDigest(MQTT_ORIGIN, "fire/other"), "d".repeat(64),
            ACTOR, OffsetDateTime.now(), output);

        assertThat(processor.claim()).isNull();
        assertThat(outbox(fact.factId())).containsEntry("status", "BLOCKED")
            .containsEntry("failure_code", "OUTPUT_CONFIGURATION_CHANGED");
        assertThat(attempts(fact.factId())).singleElement().satisfies(attempt ->
            assertThat(attempt).containsEntry("status", "BLOCKED"));
        assertNeverAccepted();
    }

    @Test
    void httpsOutputsKeepTheHistoricalUrlDigestAndProtocol() {
        ProductVersion version = createProductVersion(jdbc);
        String application = createApplication(jdbc, "HTTPS unchanged application");
        authorizeFormal(jdbc, application, version);
        createOutput(jdbc, application, "HTTPS unchanged output");
        jdbc.update("UPDATE devices SET product_version_id=? WHERE id=?", version.versionId(), DEVICE);

        NormalizedBusinessFact fact = formalFact(newFactId(), version.versionId());
        publish(fact);

        var claim = processor.claim();
        assertThat(claim).isNotNull();
        assertThat(claim.protocol()).isEqualTo("HTTPS");
        assertThat(claim.endpointOrigin()).isEqualTo("https://output.example.test");
        assertThat(claim.endpointDigest()).isEqualTo("c".repeat(64));
    }

    @Test
    void httpsRowsKeepTheirProtocolAndHaveNoMqttMaterial() {
        String application = createApplication(jdbc, "HTTPS default application");
        String output = createOutput(jdbc, application, "HTTPS default output");

        assertThat(jdbc.queryForMap("SELECT protocol,mqtt_auth_scheme,mqtt_topic_cipher,mqtt_username_cipher,"
            + "mqtt_password_cipher FROM application_outputs WHERE id=?", output))
            .containsEntry("protocol", "HTTPS").containsEntry("mqtt_auth_scheme", "NONE")
            .containsEntry("mqtt_topic_cipher", null).containsEntry("mqtt_username_cipher", null)
            .containsEntry("mqtt_password_cipher", null);
    }

    @Test
    void mqttOutputCannotCarryHttpAuthMaterialOrDropItsTopic() {
        String application = createApplication(jdbc, "MQTT separation application");
        String output = createMqttOutput(jdbc, application, "MQTT separation output", MQTT_ORIGIN, MQTT_TOPIC);

        assertThatThrownBy(() -> jdbc.update("UPDATE application_outputs SET auth_scheme='BEARER',"
            + "credential_cipher='credential-cipher',credential_nonce='credential-nonce' WHERE id=?", output))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE application_outputs SET mqtt_topic_cipher=NULL WHERE id=?",
            output)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusedCaptureDoesNotRollBackTheAppliedSourceFact() {
        refusedCaptureKeepsAppliedSourceFact(jdbc, transactions, publisher);
    }

    /**
     * A deterministic uniqueness refusal stands in for the real concurrent-capture race: two enabled outputs cover
     * the same fact, and a transient unique index makes the second capture SQL fail after the first one succeeded.
     * The savepoint must absorb only that statement; the producer's already-applied business fact and the first
     * captured row must still commit.
     */
    private static void refusedCaptureKeepsAppliedSourceFact(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                                             BusinessFactPublisher publisher) {
        String application = createApplication(jdbc, "Savepoint application " + UUID.randomUUID());
        authorizeLegacy(jdbc, application, "legacy-savepoint", DIGEST);
        createOutput(jdbc, application, "Savepoint output one");
        createOutput(jdbc, application, "Savepoint output two");

        String factId = newFactId();
        NormalizedBusinessFact fact = legacyFact(factId, "legacy-savepoint");
        String eventId = "event-" + UUID.randomUUID();
        jdbc.execute("DROP INDEX IF EXISTS test_capture_once_per_fact");
        jdbc.execute("CREATE UNIQUE INDEX test_capture_once_per_fact ON application_delivery_outbox(fact_id)");
        try {
            new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                // The producer's own applied business fact in the same transaction as the outbox capture.
                jdbc.update("INSERT INTO device_events(id,tenant_id,device_id,message_id,event_type,actor_id,"
                    + "occurred_at,result_json) VALUES (?,?,?,?,'FIRE',?,?,?)",
                    eventId, TENANT, DEVICE, "message-" + factId, ACTOR, EVENT_AT, "{\"event\":\"FIRE\"}");
                publisher.publish(fact);
            });
        } finally {
            jdbc.execute("DROP INDEX IF EXISTS test_capture_once_per_fact");
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_events WHERE id=? AND tenant_id=?",
            Long.class, eventId, TENANT)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_delivery_outbox WHERE fact_id=?",
            Long.class, factId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM application_delivery_outbox WHERE fact_id=?",
            String.class, factId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_outputs WHERE application_id=? AND enabled=TRUE",
            Long.class, application)).isEqualTo(2L);
    }

    /**
     * PostgreSQL aborts the whole transaction on a failed statement, so only a real PostgreSQL run proves the
     * nested savepoint containment. Uses the same external-database convention as the migration upgrade suite:
     * IOT_MIGRATION_PG_URL (an admin database URL), IOT_MIGRATION_PG_USER and IOT_MIGRATION_PG_PASSWORD.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "IOT_MIGRATION_PG_URL", matches = ".+")
    void postgresRefusedCaptureRollsBackOnlyTheSavepoint() throws SQLException {
        ProxyDatabase database = postgresDatabase();
        try {
            Flyway.configure().dataSource(database.dataSource()).load().migrate();
            JdbcTemplate pgJdbc = new JdbcTemplate(database.dataSource());
            seedBase(pgJdbc);
            var environment = new MockEnvironment()
                .withProperty("app.storage", "postgresql")
                .withProperty("spring.datasource.url", database.dataSource().getUrl());
            environment.setActiveProfiles("test");
            DataSourceTransactionManager pgTransactions = new DataSourceTransactionManager(database.dataSource());
            BusinessFactPublisher pgPublisher = new BusinessFactPublisher(pgJdbc,
                new DatabaseTime(pgJdbc, new RuntimeConfig(environment)), pgTransactions);
            refusedCaptureKeepsAppliedSourceFact(pgJdbc, pgTransactions, pgPublisher);
        } finally {
            dropDatabase(database);
        }
    }

    private ProxyDatabase postgresDatabase() throws SQLException {
        String adminUrl = System.getenv("IOT_MIGRATION_PG_URL");
        String user = System.getenv("IOT_MIGRATION_PG_USER");
        String password = System.getenv("IOT_MIGRATION_PG_PASSWORD");
        String databaseName = "application_output_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = new DriverManagerDataSource(adminUrl, user, password).getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + databaseName + "\"");
        }
        return new ProxyDatabase(new DriverManagerDataSource(withDatabase(adminUrl, databaseName), user, password),
            databaseName);
    }

    private String withDatabase(String jdbcUrl, String databaseName) {
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        return "jdbc:" + uri.getScheme() + "://" + uri.getRawAuthority() + "/" + databaseName
            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    private void dropDatabase(ProxyDatabase database) throws SQLException {
        String adminUrl = System.getenv("IOT_MIGRATION_PG_URL");
        String user = System.getenv("IOT_MIGRATION_PG_USER");
        String password = System.getenv("IOT_MIGRATION_PG_PASSWORD");
        try (Connection connection = new DriverManagerDataSource(adminUrl, user, password).getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS \"" + database.databaseName() + "\" WITH (FORCE)");
        }
    }
}
