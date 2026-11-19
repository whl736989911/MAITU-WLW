package com.fireplatform.legacyingest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.legacyingest.mirror.LegacyMirrorIntakeService;
import java.nio.file.*;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 镜像入口：鉴权、原文持久化、异步解析、死信和来源身份边界。 */
@SpringBootTest(properties = {
    "app.roles=all", "app.storage=h2-dev", "fire.legacy.mirror.token=mirror-secret",
    "fire.legacy.mirror.token-previous=mirror-old", "fire.legacy.mirror-scan-ms=3600000",
    "spring.datasource.url=jdbc:h2:mem:legacy_mirror_ingest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class LegacyMirrorIngestTest {
    private static final String INITIAL = "Mirror-" + UUID.randomUUID();
    private static final Path SPOOL_DIR = Path.of("target", "mirror-spool-test-" + UUID.randomUUID());
    private static final String LORAWAN = """
        {"app":"backend","devaddr":"00000001","deveui":"0000000000000001","data":"B2B000130000E1","fcnt":327,
         "port":1,"datetime":"2026-10-30T07:07:22Z","datr":"SF12BW125","freq":473.3,"codr":"4/5",
         "mac":"0200000000000001","lsnr":-10.5,"rssi":-145,"best_gw":"0200000000000001","battery":100}""";
    private static final String LWM2M = """
        {"upPacketSN":-1,"IMEI":"000000000000001","deviceId":"00000000000000000000000000000001",
         "protocol":"lwm2m","messageType":"dataReport","timestamp":1793564579861,
         "payload":{"battery_value":100,"smoke_concentration":0},"serviceId":2}""";
    private static final String OCEAN = """
        {"notifyType":"deviceDataChanged","service":{"serviceType":"ServiceAPI",
         "data":{"payload":"7e016ae7ac2807fa004001006508"},"eventTime":"20261101T211632Z"},
         "deviceId":"ocean-1"}""";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired LegacyMirrorIntakeService intake;

    @DynamicPropertySource static void env(DynamicPropertyRegistry registry) {
        registry.add("APP_BOOTSTRAP_USERNAME", () -> "admin");
        registry.add("APP_BOOTSTRAP_PASSWORD", () -> INITIAL);
        registry.add("APP_DEMO_CUSTOMER_PASSWORD", () -> INITIAL);
        registry.add("APP_DEMO_CUSTOMER_B_PASSWORD", () -> INITIAL);
        registry.add("fire.legacy.mirror.spool-dir", () -> SPOOL_DIR.toString());
    }

    @BeforeEach void reset() throws Exception {
        // Finish the previous request's asynchronous intake before removing its spool and database rows.
        intake.drain();
        Path spool=SPOOL_DIR;
        Files.createDirectories(spool);
        try (var files = Files.walk(spool)) {
            files.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(spool)).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) { } });
        }
        for (String table : new String[]{"legacy_ingest_quarantines","legacy_ingest_events", "legacy_ingest_messages", "device_aliases",
            "notification_attempts", "notification_records", "notification_dispatches", "notification_automation_intents",
            "alarm_configuration_outcomes", "alarm_trace_entries", "alarms", "devices", "product_version_attributes", "legacy_product_decoders",
            "product_versions", "products", "identity_command_records", "idempotency_records", "sessions",
            "user_permissions", "user_device_scopes", "user_tenant_grants", "app_users", "tenants", "audit_log"})
            jdbc.update("DELETE FROM " + table);
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('t1','镜像租户')");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) " +
            "VALUES ('d1','t1','镜像烟感','0000000000000001','TBS-110','1号楼','UNKNOWN',FALSE)");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) " +
            "VALUES ('d2','t1','镜像CAT1','0000000000000002','JTY-GD-TCN2010-C','2号楼','UNKNOWN',FALSE)");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) " +
            "VALUES ('d3','t1','LwM2M','00000000000000000000000000000001','JTY-GD-TCN1010','3号楼','UNKNOWN',FALSE)");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) " +
            "VALUES ('d4','t1','Ocean','ocean-1','TBS-N101','4号楼','UNKNOWN',FALSE)");
    }

    @Test void acceptedRawRemainsDurableAfterImmediateParsing() throws Exception {
        mvc.perform(post("/api/ingest/ttl/api/api/notify/v1/x/lorawan_turbo/telemetry")
                .header("X-Ingest-Token", "mirror-secret")
                .header("X-Original-Uri", "/api/api/notify/v1/x/lorawan_turbo/telemetry")
                .contentType(MediaType.APPLICATION_JSON).content(LORAWAN))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.duplicate").value(false));
        intake.drain();
        Path queued = onlyFile("done", ".json");
        var stored = json.readTree(Files.readString(queued));
        assertThat(stored.path("body").asText()).isEqualTo(LORAWAN);
        assertThat(stored.path("original_uri").asText()).isEqualTo("/api/api/notify/v1/x/lorawan_turbo/telemetry");
        intake.drain();
        assertThat(count("queue", ".json")).isZero();
        assertThat(count("done", ".json")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM legacy_ingest_messages", String.class)).isEqualTo("PENDING_DECODER");
    }

    @Test void sameBodyIsDeduplicatedBySha256() throws Exception {
        String path = "/ingest/ttl/api/api/notify/v1/x/lorawan_turbo/telemetry";
        mvc.perform(post(path).header("X-Ingest-Token", "mirror-secret")
                .contentType(MediaType.APPLICATION_JSON).content(LORAWAN)).andExpect(status().isAccepted());
        mvc.perform(post(path).header("X-Ingest-Token", "mirror-secret")
                .contentType(MediaType.APPLICATION_JSON).content(LORAWAN))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.duplicate").value(true));
        intake.drain();
        assertThat(count("queue", ".json") + count("done", ".json")).isEqualTo(1);
    }

    @Test void wrongTokenReturns401AndNothingIsStored() throws Exception {
        mvc.perform(post("/ingest/ttl/api/api/notify/v1/x/lorawan_turbo/telemetry")
                .header("X-Ingest-Token", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(LORAWAN))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MIRROR_INGEST_UNAUTHORIZED"));
        assertThat(count("queue", ".json") + count("done", ".json") + count("dead", ".json")).isZero();
    }

    @Test void previousTokenIsAcceptedDuringRotation() throws Exception {
        mvc.perform(post("/ingest/ttl/api/api/notify/v1/x/lorawan_turbo/telemetry")
                .header("X-Ingest-Token", "mirror-old")
                .contentType(MediaType.APPLICATION_JSON).content(LORAWAN))
            .andExpect(status().isAccepted());
    }

    @Test void malformedBodyIsKeptAsDeadLetter() throws Exception {
        mvc.perform(post("/ingest/ttl/api/api/notify/v1/x/lorawan_turbo/telemetry")
                .header("X-Ingest-Token", "mirror-secret")
                .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
            .andExpect(status().isAccepted());
        intake.drain();
        Path deadFile = onlyFile("dead", ".json");
        assertThat(Files.readString(deadFile)).contains("{not-json");
        assertThat(Files.readString(SPOOL_DIR.resolve("dead").resolve(deadFile.getFileName() + ".error"))).isNotBlank();
    }

    @Test void cat1AlarmAndFaultUseBodyMessageType() throws Exception {
        String alarm = """
            {"deviceName":"0000000000000002","deviceId":"0000000000000002","messageType":"Alarm",
             "deviceStateCode":"1001001","params":"{\\"deviceStateCode\\":\\"1001001\\",\\"deviceState\\":\\"火灾报警\\",\\"battery\\":75}",
             "ts":1793568000000}""";
        mvc.perform(post("/ingest/cat1/api/v1/formttl/alarms/telemetry")
                .header("X-Ingest-Token", "mirror-secret")
                .contentType(MediaType.APPLICATION_JSON).content(alarm)).andExpect(status().isAccepted());
        intake.drain();
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id='d2'", String.class)).isEqualTo("FIRE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id='d2'", Integer.class)).isEqualTo(1);

        String fault = alarm.replace("\"Alarm\"", "\"Fault\"")
            .replace("1793568000000", "1793568060000")
            .replace("1001001", "1002006");
        mvc.perform(post("/ingest/cat1/api/v1/formttl/alarms/telemetry")
                .header("X-Ingest-Token", "mirror-secret")
                .contentType(MediaType.APPLICATION_JSON).content(fault)).andExpect(status().isAccepted());
        intake.drain();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='FAULT' AND device_id='d2'", Integer.class)).isEqualTo(1);
    }

    @Test void lwm2mPrefersBusinessDeviceIdToModemImei() throws Exception {
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) VALUES ('modem','t1','Not the business identity','000000000000001','Other','Other room','UNKNOWN',FALSE)");
        mvc.perform(post("/ingest/ttl/api/api/notify/v1/x/aep_turbo/telemetry")
                .header("X-Ingest-Token", "mirror-secret")
                .contentType(MediaType.APPLICATION_JSON).content(LWM2M)).andExpect(status().isAccepted());
        intake.drain();
        var row = jdbc.queryForMap("SELECT vendor_device_id,device_id,observed_at FROM legacy_ingest_messages");
        assertThat(row.get("vendor_device_id")).isEqualTo("d632df53dd3e4a488530cb7efcff945a");
        assertThat(row.get("device_id")).isEqualTo("d3");
        assertThat(row.get("observed_at").toString()).startsWith("2026-11-01T20:22:59");
    }

    @Test void oceanConnectIsAcceptedAsServicePayload() throws Exception {
        mvc.perform(post("/ingest/ttl/api/api/notify/v1/x/oceanconnect_turbo/telemetry")
                .header("X-Ingest-Token", "mirror-secret")
                .contentType(MediaType.APPLICATION_JSON).content(OCEAN)).andExpect(status().isAccepted());
        intake.drain();
        var row = jdbc.queryForMap("SELECT vendor_platform,state,device_id FROM legacy_ingest_messages");
        assertThat(row.get("vendor_platform")).isEqualTo("oceanconnect_turbo");
        assertThat(row.get("state")).isEqualTo("PENDING_DECODER");
        assertThat(row.get("device_id")).isEqualTo("d4");
    }

    private int count(String subdir, String suffix) {
        try {
            Files.createDirectories(SPOOL_DIR.resolve(subdir));
        } catch (Exception ignored) { }
        try (var files = Files.list(SPOOL_DIR.resolve(subdir))) {
            return (int) files.filter(path -> path.getFileName().toString().endsWith(suffix)).count();
        } catch (Exception e) { return 0; }
    }

    private Path onlyFile(String subdir, String suffix) throws Exception {
        Files.createDirectories(SPOOL_DIR.resolve(subdir));
        try (var files = Files.list(SPOOL_DIR.resolve(subdir))) {
            return files.filter(path -> path.getFileName().toString().endsWith(suffix)).findFirst().orElseThrow();
        }
    }
}
