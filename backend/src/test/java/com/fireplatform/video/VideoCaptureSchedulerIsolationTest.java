package com.fireplatform.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
    "app.roles=all",
    "app.storage=h2-dev",
    "app.task-poll-ms=60000",
    "app.video.capture-poll-ms=150",
    "app.video.session-seconds=60",
    "app.video.connect-timeout-seconds=1",
    "app.video.read-timeout-seconds=1",
    "app.video.max-primary-bytes=1048576",
    "spring.datasource.url=jdbc:h2:mem:captureSchedulerIsolation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(VideoCaptureSchedulerIsolationTest.TransportFixture.class)
class VideoCaptureSchedulerIsolationTest {
    private static final String INITIAL = "Initial-" + UUID.randomUUID();
    private static final String PASSWORD = "Changed-" + UUID.randomUUID();
    private static final String VIDEO_KEY = Base64.getEncoder()
        .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private static final String TENANT = "capture-deadline-unit";
    private static final String DEVICE = "capture-deadline-device";
    private static final String CUSTOMER = "capture-deadline-customer";
    private static final String CUSTOMER_USERNAME = "capture-deadline-customer";

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("APP_BOOTSTRAP_USERNAME", () -> "admin");
        registry.add("APP_BOOTSTRAP_PASSWORD", () -> INITIAL);
        registry.add("APP_VIDEO_KEY", () -> VIDEO_KEY);
        registry.add("APP_VIDEO_ALLOWED_HOSTS", () -> StallingUpstreamClient.HOST);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired StallingUpstreamClient upstream;
    @Autowired @Qualifier("taskScheduler") TaskScheduler taskScheduler;

    String admin;
    String customer;
    String resource;
    long revision;

    @BeforeEach
    void resetAndSeed() throws Exception {
        reset();
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=FALSE,enabled=TRUE WHERE id='bootstrap-admin'",
            passwords.encode(PASSWORD));
        String adminHash = jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id='bootstrap-admin'", String.class);

        jdbc.update("INSERT INTO tenants(id,name) VALUES(?,?)", TENANT, "Capture deadline tenant");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin',?)", TENANT);
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES('bootstrap-admin',?,'ALL_TENANT',1)", TENANT);
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state)"
                + " VALUES(?,?,?,?,?,?,'NORMAL',FALSE,'LEGACY_UNVERIFIED')",
            DEVICE, TENANT, "Capture deadline device", "capture-deadline-eui", "legacy-model", "Capture address");
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,"
                + "must_change_password,enabled,is_protected) VALUES(?,?,?,'CUSTOMER',?,?,FALSE,TRUE,FALSE)",
            CUSTOMER, CUSTOMER_USERNAME, "Capture deadline customer", TENANT, adminHash);
        jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES(?,?),(?,?)",
            CUSTOMER, "video:read", CUSTOMER, "video:play");
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES(?,?,'ALL_TENANT',1)",
            CUSTOMER, TENANT);

        admin = login("admin", PASSWORD, "management");
        customer = login(CUSTOMER_USERNAME, PASSWORD, "customer");

        JsonNode created = body(call(HttpMethod.POST, "/api/management/video-resources", admin, TENANT,
            UUID.randomUUID().toString(), Map.of("name", "Capture deadline resource", "resource_kind", "CAMERA",
                "protocol", "HTTPS_JPEG", "reason", "Capture scheduler deadline regression"))
            .andExpect(status().isOk()));
        resource = created.get("id").asText();
        revision = saveEndpoint(created.get("revision").asLong());
        revision = enable(revision);
        associate();
        upstream.reset();
    }

    @Test
    void stalledCaptureLeavesAlarmSchedulerAvailable() throws Exception {
        JsonNode queued = body(call(HttpMethod.POST,
            "/api/customer/video-resources/" + resource + "/capture-jobs", customer, null, null,
            Map.of("device_id", DEVICE)).andExpect(status().isOk()));
        assertThat(queued.get("state").asText()).isEqualTo("QUEUED");

        try {
            assertThat(upstream.opened.await(10, TimeUnit.SECONDS))
                .as("capture worker reached the fake upstream body")
                .isTrue();

            CountDownLatch sentinel = new CountDownLatch(1);
            taskScheduler.schedule(sentinel::countDown, Instant.now());
            assertThat(sentinel.await(5, TimeUnit.SECONDS))
                .as("alarm scheduler must remain available while a capture is waiting on its peer")
                .isTrue();
        } finally {
            upstream.release.countDown();
        }
    }

    private void reset() {
        jdbc.update("DELETE FROM video_media_subresources");
        jdbc.update("DELETE FROM video_media_sessions");
        jdbc.update("DELETE FROM video_capture_jobs");
        jdbc.update("DELETE FROM video_view_intents");
        jdbc.update("DELETE FROM video_associations");
        jdbc.update("DELETE FROM video_resources");
        jdbc.update("DELETE FROM user_device_scope_devices WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM devices WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM user_device_scopes WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM user_tenant_grants WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM user_permissions WHERE user_id=?", CUSTOMER);
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM app_users WHERE id=?", CUSTOMER);
        jdbc.update("DELETE FROM tenants WHERE id=?", TENANT);
        jdbc.update("DELETE FROM idempotency_records");
    }

    private long saveEndpoint(long currentRevision) throws Exception {
        return body(call(HttpMethod.PUT, "/api/management/video-resources/" + resource + "/endpoint", admin,
            TENANT, UUID.randomUUID().toString(), Map.of(
                "expected_revision", currentRevision,
                "endpoint_url", "https://" + StallingUpstreamClient.HOST + "/live/capture.jpg",
                "reason", "Capture scheduler deadline regression"))
            .andExpect(status().isOk())).get("revision").asLong();
    }

    private long enable(long currentRevision) throws Exception {
        return body(call(HttpMethod.POST, "/api/management/video-resources/" + resource + "/enabled", admin,
            TENANT, UUID.randomUUID().toString(), Map.of("expected_revision", currentRevision, "enabled", true,
                "reason", "Capture scheduler deadline regression"))
            .andExpect(status().isOk())).get("revision").asLong();
    }

    private void associate() throws Exception {
        body(call(HttpMethod.POST, "/api/management/video-resources/" + resource + "/associations", admin,
            TENANT, UUID.randomUUID().toString(), Map.of("device_id", DEVICE,
                "reason", "Capture scheduler deadline regression"))
            .andExpect(status().isOk()));
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TransportFixture {
        @Bean @Primary StallingUpstreamClient stallingUpstreamClient(VideoPolicy policy) {
            return new StallingUpstreamClient(policy);
        }
    }

    static final class StallingUpstreamClient extends VideoUpstreamClient {
        static final String HOST = "stalled.example";
        volatile CountDownLatch opened = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);

        StallingUpstreamClient(VideoPolicy policy) { super(policy); }

        void reset() {
            opened = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        @Override public Target resolve(String rawUrl) {
            return new Target(URI.create(rawUrl), HOST);
        }

        @Override public Upstream open(Target target, String range, String authorization, long maxBytes) {
            opened.countDown();
            InputStream body = new InputStream() {
                private boolean finished;
                @Override public int read() throws IOException {
                    if (finished) return -1;
                    try {
                        release.await(20, TimeUnit.SECONDS);
                        finished = true;
                        return -1;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException("capture exchange interrupted");
                    }
                }
            };
            return new Upstream(new Socket(), 200, "image/jpeg", -1, null, false, body);
        }
    }
}
