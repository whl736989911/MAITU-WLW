package com.fireplatform.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.Digests;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Consumer-visible authorization for the bounded video proxy and capture ledger.
 *
 * <p>All media authorization is re-evaluated against the database on every media GET. The tests below
 * drive real controllers/services over MockMvc and a disposable H2 database. No test opens a socket to
 * an upstream camera: valid-session plays are only attempted after the authorization path stops before
 * media I/O, and the capture worker is delayed for an hour while a disabled sentinel job absorbs any
 * late startup poll before it can reach a manually queued job.
 */
@SpringBootTest(properties = {
    "app.roles=api",
    "app.storage=h2-dev",
    "app.task-poll-ms=60000",
    "app.video.capture-poll-ms=3600000",
    "spring.datasource.url=jdbc:h2:mem:videoauthorityregression;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoAuthorityIntegrationTest {
    private static final String INITIAL = "Initial-Video-" + UUID.randomUUID();
    private static final String PASSWORD = "Changed-Video-" + UUID.randomUUID();
    /** Test-only key. Never a deployment key. */
    private static final String VIDEO_KEY = Base64.getEncoder()
        .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private static final String TENANT_A = "video-tenant-a";
    private static final String TENANT_B = "video-tenant-b";
    private static final String DEVICE_A = "video-device-a";
    private static final String DEVICE_B = "video-device-b";
    private static final String CUSTOMER_A = "video-customer-a";
    private static final String CUSTOMER_USERNAME = "video-customer-a";
    private static final String COOKIE = "fp_video_media";
    private static final String ENDPOINT_SIGNATURE = "endpoint-signature-a";
    private static final String ENDPOINT_USER = "upstream-user-a";
    private static final String ENDPOINT_PASSWORD = "upstream-password-a";
    private static final String SENTINEL_RESOURCE = "video-capture-sentinel-resource";

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("APP_BOOTSTRAP_USERNAME", () -> "admin");
        registry.add("APP_BOOTSTRAP_PASSWORD", () -> INITIAL);
        registry.add("APP_VIDEO_KEY", () -> VIDEO_KEY);
        registry.add("APP_VIDEO_ALLOWED_HOSTS", () -> "localhost");
        registry.add("APP_VIDEO_COOKIE_SECURE", () -> "true");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    String root;
    String customer;
    String resourceA;
    String resourceAUnassociated;
    String resourceB;
    String associationA;
    long revisionA;
    long associationARevision;

    @BeforeEach
    void resetAndSeed() throws Exception {
        reset();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE id='bootstrap-admin'", Long.class))
            .isEqualTo(1L);
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=FALSE,enabled=TRUE WHERE id='bootstrap-admin'",
            passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO tenants(id,name) VALUES(?,?),(?,?)",
            TENANT_A, "Video authority A", TENANT_B, "Video authority B");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin',?),('bootstrap-admin',?)",
            TENANT_A, TENANT_B);
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES"
            + "('bootstrap-admin',?,'ALL_TENANT',1),('bootstrap-admin',?,'ALL_TENANT',1)", TENANT_A, TENANT_B);
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state)"
            + " VALUES(?,?,?,?,?,?,?,FALSE,'LEGACY_UNVERIFIED'),(?,?,?,?,?,?,?,FALSE,'LEGACY_UNVERIFIED')",
            DEVICE_A, TENANT_A, "Video device A", "video-fixture-a", "legacy-model", "A", "NORMAL",
            DEVICE_B, TENANT_B, "Video device B", "video-fixture-b", "legacy-model", "B", "NORMAL");
        String adminHash = jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id='bootstrap-admin'", String.class);
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled,is_protected)"
                + " VALUES(?,?,?,'CUSTOMER',?,?,FALSE,TRUE,FALSE)",
            CUSTOMER_A, CUSTOMER_USERNAME, "Video customer A", TENANT_A, adminHash);
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES(?,?),(?,?)",
            CUSTOMER_A, "video:read", CUSTOMER_A, "video:play");
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES(?,?,'ALL_TENANT',1)",
            CUSTOMER_A, TENANT_A);

        // The capture worker's first poll runs at startup and then waits an hour. Seed a cheap QUEUED job
        // whose resource is disabled, so an unlucky first poll claims that job and never performs media I/O.
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO video_resources(id,tenant_id,name,resource_kind,protocol,enabled,revision,"
                + "created_by,created_at,updated_by,updated_at) VALUES(?,?,?,'CAMERA','HTTPS_MP4',FALSE,0,?,?,?,?)",
            SENTINEL_RESOURCE, TENANT_A, "Capture poll sentinel", "bootstrap-admin", now, "bootstrap-admin", now);
        jdbc.update("INSERT INTO video_capture_jobs(id,tenant_id,video_resource_id,device_id,association_id,"
                + "requested_by,source_kind,source_id,rule_id,idem_key,request_hash,protocol,state,created_at)"
                + " VALUES(?,?,?,?,NULL,?,'MANUAL','sentinel',NULL,'manual.sentinel',?,'HTTPS_MP4','QUEUED',?)",
            "video-capture-sentinel", TENANT_A, SENTINEL_RESOURCE, DEVICE_A, "bootstrap-admin",
            Digests.sha256("sentinel"), now.minusDays(1));

        root = login("admin", PASSWORD, "management");
        customer = login(CUSTOMER_USERNAME, PASSWORD, "customer");

        JsonNode createdA = createResource(TENANT_A, "Video A");
        resourceA = createdA.get("id").asText();
        revisionA = createdA.get("revision").asLong();
        revisionA = saveEndpoint(TENANT_A, resourceA, revisionA, "https://localhost/live/a.mp4?signature=" + ENDPOINT_SIGNATURE, true);
        revisionA = enable(TENANT_A, resourceA, revisionA);
        JsonNode association = associate(TENANT_A, resourceA, DEVICE_A);
        associationA = association.get("id").asText();
        associationARevision = association.get("revision").asLong();

        JsonNode createdUnassociated = createResource(TENANT_A, "Video A unassociated");
        resourceAUnassociated = createdUnassociated.get("id").asText();
        long revision = saveEndpoint(TENANT_A, resourceAUnassociated,
            createdUnassociated.get("revision").asLong(), "https://localhost/live/unassociated.mp4", false);
        enable(TENANT_A, resourceAUnassociated, revision);

        JsonNode createdB = createResource(TENANT_B, "Video B");
        resourceB = createdB.get("id").asText();
        long revisionB = saveEndpoint(TENANT_B, resourceB, createdB.get("revision").asLong(),
            "https://localhost/live/b.mp4", false);
        revisionB = enable(TENANT_B, resourceB, revisionB);
        associate(TENANT_B, resourceB, DEVICE_B);
    }

    // ---------------------------------------------------------------- scenarios

    @Test
    void noSessionCannotPlayAndForeignOrUnassociatedResourcesStayHidden() throws Exception {
        expectError(playback("customer", resourceA, null), 401, "VIDEO_MEDIA_SESSION_REQUIRED");
        expectError(playback("management", resourceA, null), 401, "VIDEO_MEDIA_SESSION_REQUIRED");
        expectError(playback("customer", resourceA, "forged-session-token"), 401, "VIDEO_MEDIA_SESSION_REQUIRED");
        // A normal bearer login is not a media credential; the proxy only accepts the scoped HttpOnly cookie.
        expectError(mvc.perform(get(mediaPath("customer", resourceA, "play"))
            .header("Authorization", "Bearer " + customer)), 401, "VIDEO_MEDIA_SESSION_REQUIRED");
        expectError(mvc.perform(get(mediaPath("customer", resourceA, "snapshot"))), 401,
            "VIDEO_MEDIA_SESSION_REQUIRED");
        expectError(mvc.perform(get(mediaPath("customer", resourceA, "hls/0"))), 401,
            "VIDEO_MEDIA_SESSION_REQUIRED");

        // A customer session is tenant-bound; it cannot read another unit's resource or an unassociated one.
        call(HttpMethod.GET, "/api/customer/video-resources", customer, TENANT_B, null, null)
            .andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/customer/video-resources/" + resourceB, customer, null, null, null)
            .andExpect(status().isNotFound());
        call(HttpMethod.GET, "/api/customer/video-resources/" + resourceAUnassociated, customer, null, null, null)
            .andExpect(status().isNotFound());
        call(HttpMethod.GET, "/api/customer/video-resources", customer, null, null, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value(resourceA));

        assertThat(sessionCount()).isZero();
        expectError(call(HttpMethod.POST, "/api/customer/video-resources/" + resourceB + "/media-sessions",
            customer, null, null, sessionBody(DEVICE_B)), 404, "NOT_FOUND");
        expectError(call(HttpMethod.POST, "/api/customer/video-resources/" + resourceAUnassociated + "/media-sessions",
            customer, null, null, sessionBody(DEVICE_A)), 409, "VIDEO_ASSOCIATION_REQUIRED");
        expectError(call(HttpMethod.POST, "/api/customer/video-resources/" + resourceA + "/media-sessions",
            customer, null, null, sessionBody(DEVICE_B)), 409, "VIDEO_ASSOCIATION_REQUIRED");
        // Platform media sessions require an explicit authorized unit, not an implicit first tenant.
        expectError(call(HttpMethod.POST, "/api/management/video-resources/" + resourceA + "/media-sessions",
            root, null, null, sessionBody(DEVICE_A)), 400, "INVALID_REQUEST");
        assertThat(sessionCount()).isZero();

        ResultActions managementSession = call(HttpMethod.POST,
            "/api/management/video-resources/" + resourceA + "/media-sessions", root, TENANT_A, null, sessionBody(DEVICE_A))
            .andExpect(status().isOk());
        assertThat(mediaCookie(managementSession)).isNotBlank();

        ResultActions customerSession = call(HttpMethod.POST,
            "/api/customer/video-resources/" + resourceA + "/media-sessions", customer, null, null, sessionBody(DEVICE_A))
            .andExpect(status().isOk());
        String cookie = mediaCookie(customerSession);

        // A session is bound to one resource and one audience; reusing it elsewhere is rejected before I/O.
        expectError(playback("customer", resourceAUnassociated, cookie), 401, "VIDEO_MEDIA_SESSION_REQUIRED");
        expectError(playback("management", resourceA, cookie), 401, "VIDEO_MEDIA_SESSION_REQUIRED");
    }

    @Test
    void mediaSessionAndResourceReadsNeverExposeTokensOrEndpointSecrets() throws Exception {
        ResultActions detail = call(HttpMethod.GET, "/api/management/video-resources/" + resourceA,
            root, TENANT_A, null, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resource.endpoint_host").value("localhost"))
            .andExpect(jsonPath("$.resource.endpoint_configured").value(true))
            .andExpect(jsonPath("$.resource.credentials_configured").value(true));
        assertNoSecrets(detail);

        ResultActions customerDetail = call(HttpMethod.GET, "/api/customer/video-resources/" + resourceA,
            customer, null, null, null)
            .andExpect(status().isOk());
        assertNoSecrets(customerDetail);

        ResultActions list = call(HttpMethod.GET, "/api/management/video-resources", root, TENANT_A, null, null)
            .andExpect(status().isOk());
        assertNoSecrets(list);

        ResultActions issued = call(HttpMethod.POST,
            "/api/customer/video-resources/" + resourceA + "/media-sessions", customer, null, null, sessionBody(DEVICE_A))
            .andExpect(status().isOk());
        String token = mediaCookie(issued);
        String setCookie = issued.andReturn().getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(value -> value.startsWith(COOKIE + "=")).findFirst().orElseThrow();
        assertThat(setCookie)
            .contains("HttpOnly")
            .contains("SameSite=Strict")
            .contains("Secure")
            .contains("Path=/api/customer/video-resources/" + resourceA + "/media");

        String responseBody = issued.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(responseBody)
            .doesNotContain(token)
            .doesNotContain(ENDPOINT_SIGNATURE)
            .doesNotContain(ENDPOINT_USER)
            .doesNotContain(ENDPOINT_PASSWORD)
            .doesNotContain(PASSWORD);
        JsonNode session = json.readTree(responseBody);
        assertThat(session.get("purpose").asText()).isEqualTo("PLAYBACK");
        assertThat(session.get("device_id").asText()).isEqualTo(DEVICE_A);
        assertThat(session.get("resource_id").asText()).isEqualTo(resourceA);
        assertThat(session.get("media_path").asText())
            .isEqualTo("/api/customer/video-resources/" + resourceA + "/media/primary");

        String tokenHash = jdbc.queryForObject("SELECT token_hash FROM video_media_sessions WHERE id=?",
            String.class, session.get("id").asText());
        assertThat(tokenHash).isEqualTo(Digests.sha256(token)).isNotEqualTo(token);
    }

    @Test
    void mediaSessionStopsAuthorizingAfterDeviceScopeOrPlayPermissionRevocation() throws Exception {
        String cookie = openCustomerSession();
        assertThat(sessionState(cookie)).isEqualTo("ACTIVE");

        jdbc.update("UPDATE user_device_scopes SET mode='DEVICE_SET',revision=revision+1 WHERE user_id=? AND tenant_id=?",
            CUSTOMER_A, TENANT_A);
        expectError(playback("customer", resourceA, cookie), 403, "FORBIDDEN");
        assertThat(sessionState(cookie)).isEqualTo("ACTIVE");

        jdbc.update("UPDATE user_device_scopes SET mode='ALL_TENANT',revision=revision+1 WHERE user_id=? AND tenant_id=?",
            CUSTOMER_A, TENANT_A);
        jdbc.update("DELETE FROM user_permissions WHERE user_id=? AND permission='video:play'", CUSTOMER_A);
        expectError(playback("customer", resourceA, cookie), 403, "FORBIDDEN");
        assertThat(sessionState(cookie)).isEqualTo("ACTIVE");
    }

    @Test
    void mediaSessionStopsAuthorizingAfterAssociationRevocation() throws Exception {
        String cookie = openCustomerSession();

        call(HttpMethod.POST, "/api/management/video-resources/" + resourceA + "/associations/"
                + associationA + "/revoke", root, TENANT_A, UUID.randomUUID().toString(),
            Map.of("expected_revision", associationARevision, "revoke_reason", "Association no longer authorizes video", "reason", "Video authority regression"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("REVOKED"));

        expectError(playback("customer", resourceA, cookie), 401, "VIDEO_MEDIA_SESSION_EXPIRED");
        assertThat(sessionState(cookie)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT state FROM video_associations WHERE id=?", String.class, associationA))
            .isEqualTo("REVOKED");
    }

    @Test
    void mediaSessionStopsAuthorizingAfterResourceIsDisabled() throws Exception {
        String cookie = openCustomerSession();

        call(HttpMethod.POST, "/api/management/video-resources/" + resourceA + "/enabled", root, TENANT_A,
            UUID.randomUUID().toString(), Map.of("expected_revision", revisionA, "enabled", false, "reason", "Video authority regression disable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));

        expectError(playback("customer", resourceA, cookie), 409, "VIDEO_MEDIA_UNAVAILABLE");
        assertThat(sessionState(cookie)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT enabled FROM video_resources WHERE id=?", Boolean.class, resourceA))
            .isFalse();
    }

    @Test
    void captureLedgerNeverClaimsSuccessWithoutImageEvidence() throws Exception {
        JsonNode queued = queueCapture();
        String jobId = queued.get("id").asText();
        assertThat(queued.get("state").asText()).isEqualTo("QUEUED");
        assertThat(queued.get("protocol").asText()).isEqualTo("HTTPS_MP4");
        assertThat(queued.path("image_sha256").isNull() || queued.path("image_sha256").isMissingNode()).isTrue();
        assertThat(queued.path("capture_at").isNull() || queued.path("capture_at").isMissingNode()).isTrue();

        JsonNode read = body(call(HttpMethod.GET, capturePath(resourceA) + "/" + jobId,
            customer, null, null, null).andExpect(status().isOk()));
        assertThat(read.get("state").asText()).isEqualTo("QUEUED");
        assertThat(read.path("image_sha256").isNull() || read.path("image_sha256").isMissingNode()).isTrue();
        assertThat(read.path("image_bytes").isNull() || read.path("image_bytes").isMissingNode()).isTrue();

        expectError(mvc.perform(get(capturePath(resourceA) + "/" + jobId + "/image").header("Authorization",
            "Bearer " + customer)), 409, "CAPTURE_NOT_SUCCEEDED");

        // There is no public transition API that can forge SUCCEEDED; the durable schema itself is the
        // ledger guard. A state-only success update must be rejected by the image-evidence constraint.
        assertThatThrownBy(() -> jdbc.update("UPDATE video_capture_jobs SET state='SUCCEEDED' WHERE id=?", jobId))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT state FROM video_capture_jobs WHERE id=?", String.class, jobId))
            .isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_capture_jobs WHERE state='SUCCEEDED'", Long.class))
            .isZero();
        expectError(mvc.perform(get(capturePath(resourceA) + "/" + jobId + "/image").header("Authorization",
            "Bearer " + customer)), 409, "CAPTURE_NOT_SUCCEEDED");
    }

    @Test
    void captureImageRequiresPlayPermission() throws Exception {
        JsonNode queued = queueCapture();

        // The controller reads with video:read, so the service must additionally confirm video:play before
        // releasing any capture bytes. A read-only principal must not receive the image.
        jdbc.update("DELETE FROM user_permissions WHERE user_id=? AND permission='video:play'", CUSTOMER_A);
        expectError(mvc.perform(get(capturePath(resourceA) + "/" + queued.get("id").asText() + "/image")
            .header("Authorization", "Bearer " + customer)), 403, "FORBIDDEN");
    }

    // ---------------------------------------------------------------- fixture helpers

    private void reset() {
        for (String table : List.of("video_media_subresources", "video_media_sessions", "video_capture_jobs",
            "video_view_intents", "video_associations", "video_resources"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM user_device_scope_devices WHERE tenant_id IN (?,?)", TENANT_A, TENANT_B);
        jdbc.update("DELETE FROM devices WHERE tenant_id IN (?,?)", TENANT_A, TENANT_B);
        jdbc.update("DELETE FROM user_device_scopes WHERE tenant_id IN (?,?)", TENANT_A, TENANT_B);
        jdbc.update("DELETE FROM user_tenant_grants WHERE tenant_id IN (?,?)", TENANT_A, TENANT_B);
        jdbc.update("DELETE FROM user_permissions WHERE user_id=?", CUSTOMER_A);
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM app_users WHERE id=?", CUSTOMER_A);
        jdbc.update("DELETE FROM tenants WHERE id IN (?,?)", TENANT_A, TENANT_B);
        jdbc.update("DELETE FROM idempotency_records");
    }

    private JsonNode createResource(String tenant, String name) throws Exception {
        return body(call(HttpMethod.POST, "/api/management/video-resources", root, tenant,
            UUID.randomUUID().toString(), Map.of("name", name, "resource_kind", "CAMERA", "protocol", "HTTPS_MP4", "reason", "Video authority regression"))
            .andExpect(status().isOk()));
    }

    private long saveEndpoint(String tenant, String resourceId, long revision, String url, boolean credentials)
            throws Exception {
        Map<String, Object> input = credentials
            ? Map.of("expected_revision", revision, "endpoint_url", url, "username", ENDPOINT_USER,
                "password", ENDPOINT_PASSWORD, "reason", "Video authority regression")
            : Map.of("expected_revision", revision, "endpoint_url", url,
                "reason", "Video authority regression");
        return body(call(HttpMethod.PUT, "/api/management/video-resources/" + resourceId + "/endpoint", root,
            tenant, UUID.randomUUID().toString(), input).andExpect(status().isOk())).get("revision").asLong();
    }

    private long enable(String tenant, String resourceId, long revision) throws Exception {
        return body(call(HttpMethod.POST, "/api/management/video-resources/" + resourceId + "/enabled", root,
            tenant, UUID.randomUUID().toString(), Map.of("expected_revision", revision, "enabled", true, "reason", "Video authority regression"))
            .andExpect(status().isOk())).get("revision").asLong();
    }

    private JsonNode associate(String tenant, String resourceId, String deviceId) throws Exception {
        return body(call(HttpMethod.POST, "/api/management/video-resources/" + resourceId + "/associations", root,
            tenant, UUID.randomUUID().toString(), Map.of("device_id", deviceId,
                "reason", "Video authority regression")).andExpect(status().isOk()));
    }

    private String openCustomerSession() throws Exception {
        ResultActions issued = call(HttpMethod.POST,
            "/api/customer/video-resources/" + resourceA + "/media-sessions", customer, null, null,
            sessionBody(DEVICE_A)).andExpect(status().isOk());
        return mediaCookie(issued);
    }

    private JsonNode queueCapture() throws Exception {
        return body(call(HttpMethod.POST, capturePath(resourceA), customer, null, null, Map.of("device_id", DEVICE_A))
            .andExpect(status().isOk()));
    }

    private static Map<String, Object> sessionBody(String deviceId) {
        return Map.of("device_id", deviceId, "purpose", "PLAYBACK");
    }

    private static String capturePath(String resourceId) {
        return "/api/customer/video-resources/" + resourceId + "/capture-jobs";
    }

    private static String mediaPath(String audience, String resourceId, String suffix) {
        return "/api/" + audience + "/video-resources/" + resourceId + "/media/" + suffix;
    }

    private ResultActions playback(String audience, String resourceId, String cookie) throws Exception {
        MockHttpServletRequestBuilder builder = get(mediaPath(audience, resourceId, "play"));
        if (cookie != null) builder.cookie(new Cookie(COOKIE, cookie));
        return mvc.perform(builder);
    }

    private long sessionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_media_sessions", Long.class);
    }

    private String sessionState(String token) {
        return jdbc.queryForObject("SELECT state FROM video_media_sessions WHERE token_hash=?", String.class,
            Digests.sha256(token));
    }

    private static String mediaCookie(ResultActions result) {
        return result.andReturn().getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(value -> value.startsWith(COOKIE + "="))
            .findFirst()
            .map(value -> {
                int end = value.indexOf(';');
                return value.substring(COOKIE.length() + 1, end < 0 ? value.length() : end);
            })
            .orElseThrow(() -> new AssertionError("media session cookie was not issued"));
    }

    private void assertNoSecrets(ResultActions result) throws Exception {
        String text = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text)
            .doesNotContain(ENDPOINT_SIGNATURE)
            .doesNotContain(ENDPOINT_USER)
            .doesNotContain(ENDPOINT_PASSWORD)
            .doesNotContain("endpoint_url")
            .doesNotContain("endpoint_cipher")
            .doesNotContain("credentials_cipher")
            .doesNotContain("\"password\"")
            .doesNotContain("\"username\"")
            .doesNotContain(PASSWORD);
    }

    private void expectError(ResultActions result, int statusCode, String code) throws Exception {
        result.andExpect(status().is(statusCode))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value(code));
    }

    private String login(String username, String password, String audience) throws Exception {
        return body(call(HttpMethod.POST, "/api/auth/login", null, null, null,
            Map.of("username", username, "password", password, "audience", audience)))
            .get("access_token").asText();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions call(HttpMethod method, String path, String token, String tenant, String idempotencyKey,
                               Object input) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, path);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (tenant != null) builder.header("X-Tenant-Id", tenant);
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        if (input != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input));
        return mvc.perform(builder);
    }
}
