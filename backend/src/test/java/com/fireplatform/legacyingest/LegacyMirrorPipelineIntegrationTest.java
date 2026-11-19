package com.fireplatform.legacyingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.N04TestScopes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
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
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 端到端：旧平台报文 → 原始报文落库 → 认设备 → 事件 → 事实。
 * 只依赖 HTTP 路径与数据库表（契约 §4），不依赖各并行线的类名。
 */
@SpringBootTest(properties = {
    "app.roles=all", "app.storage=h2-dev", "fire.legacy.ingest-token=vector-token",
    "spring.datasource.url=jdbc:h2:mem:legacymirror;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class LegacyMirrorPipelineIntegrationTest {
    static final String INITIAL = "Initial-" + UUID.randomUUID(), PASSWORD = "Changed-" + UUID.randomUUID();
    static final String LORAWAN = """
        {"app":"backend","devaddr":"00000001","deveui":"0000000000000001","data":"B2B000130000E1","fcnt":327,
         "port":1,"datetime":"2026-10-30T07:07:22Z","datr":"SF12BW125","freq":473.3,"codr":"4/5",
         "mac":"0200000000000001","lsnr":-10.5,"rssi":-145,"best_gw":"0200000000000001","battery":100,
         "appargs":"","pid":334,"netid":"470同频"}""";
    static final String FORMTTL = """
        {"deviceId":"0000000000000001","deviceName":"0000000000000001","platfromType":"lorawan_turbo",
         "productCategory":1,"productManufacturer":"Turboes","productName":"TBS-110底座式无线感烟报警器",
         "deviceStateCode":"1002045","deviceState":"心跳上报",
         "parseMsg":[{"method":"thing.event.heartbeat","params":[{"rssi":"-116","lsnr":"-5.3",
           "smoke_value":3,"temperature":24,"battery":100,"deviceState":"心跳上报","fcnt":778,
           "gateway":"00000001","time":"2026-10-30 14:44:16"}]}]}""";
    @DynamicPropertySource static void env(DynamicPropertyRegistry r) {
        r.add("APP_BOOTSTRAP_USERNAME", () -> "admin");
        r.add("APP_BOOTSTRAP_PASSWORD", () -> INITIAL);
        r.add("APP_DEMO_CUSTOMER_PASSWORD", () -> INITIAL);
        r.add("APP_DEMO_CUSTOMER_B_PASSWORD", () -> INITIAL);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired Bootstrap bootstrap;
    String root;

    @BeforeEach void reset() throws Exception {
        N04TestScopes.clear(jdbc);
        for (String table : List.of("legacy_ingest_quarantines","legacy_ingest_events", "legacy_ingest_messages", "device_aliases",
            "notification_attempts", "notification_records", "notification_dispatches",
            "notification_automation_intents", "alarm_configuration_outcomes","alarm_reminder_schedule","resource_linkage_outcomes","alarm_trace_entries", "alarms", "device_profile_snapshots","profile_rules","device_battery_observations","device_events", "device_place_history", "device_version_history", "devices", "places",
        "product_version_attributes", "legacy_product_decoders", "product_versions", "products",
        "identity_command_records", "idempotency_records", "sessions", "user_permissions",
        "user_tenant_grants", "app_users", "tenants", "audit_log")) jdbc.update("DELETE FROM " + table);
        bootstrap.run(new DefaultApplicationArguments());
        root = login("admin", INITIAL, "management");
        mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + root)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("current_password", INITIAL, "new_password", PASSWORD))))
            .andExpect(status().isNoContent());
        root = login("admin", PASSWORD, "management");
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('t1','合成测试单位')");
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('UNASSIGNED','未分配')");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) " +
            "VALUES ('d1','t1','试点烟感','0000000000000001','TBS-110','1号楼','UNKNOWN',FALSE)");
    }

    @Test void lorawanUplinkIsStoredVerbatimAndNeedsADecoder() throws Exception {
        mvc.perform(post("/api/notify/v1/vector-token/lorawan_turbo/telemetry")
                .contentType(MediaType.APPLICATION_JSON).content(LORAWAN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("PENDING_DECODER"))
            .andExpect(jsonPath("$.duplicate").value(false));
        var row = jdbc.queryForMap("SELECT tenant_id,device_id,payload_raw,payload_sha256,observed_at,state FROM legacy_ingest_messages");
        assertThat(row.get("tenant_id")).isEqualTo("t1");
        assertThat(row.get("device_id")).isEqualTo("d1");
        assertThat((String) row.get("payload_raw")).isEqualTo(LORAWAN);
        assertThat((String) row.get("payload_sha256")).hasSize(64);
        assertThat(row.get("observed_at")).isNotNull();
    }

    @Test void sameFrameArrivingTwiceIsRecordedOnceWithADuplicateCount() throws Exception {
        sendUplink(LORAWAN).andExpect(status().isOk());
        mvc.perform(post("/api/notify/v1/vector-token/lorawan_turbo/telemetry")
                .contentType(MediaType.APPLICATION_JSON).content(LORAWAN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_messages", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT duplicate_count FROM legacy_ingest_messages", Long.class)).isEqualTo(1);
    }

    @Test void formttlHeartbeatBecomesANormalizedEventAndTouchesTheDevice() throws Exception {
        mvc.perform(post("/v1/formttl/heartbeat/telemetry").header("X-Legacy-Token", "vector-token")
                .contentType(MediaType.APPLICATION_JSON).content(FORMTTL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("NORMALIZED"))
            .andExpect(jsonPath("$.events").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='HEARTBEAT' AND device_id='d1'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id='d1'", java.time.OffsetDateTime.class)).isNotNull();
        // 电能值来自报文，0 与 null 不得混用
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id='d1' AND params_json LIKE '%\"battery\":100%'", Long.class)).isEqualTo(1);
    }

    @Test void formttlAlarmCodeCreatesOneFireAlarmAndRecoveryClearsTheDevice() throws Exception {
        String alarm = """
            {"deviceId":"0000000000000001","deviceName":"0000000000000001","platfromType":"lorawan_turbo",
             "productCategory":1,"productManufacturer":"Turboes","productName":"TBS-110底座式无线感烟报警器",
             "deviceStateCode":"1001001","deviceState":"火灾报警",
             "parseMsg":[{"method":"thing.event.alarm","params":[{"deviceStateCode":"1001001","deviceState":"火灾报警","time":"2026-10-30 14:44:16"}]}]}""";
        mvc.perform(post("/v1/formttl/alarm/telemetry").header("X-Legacy-Token", "vector-token")
                .contentType(MediaType.APPLICATION_JSON).content(alarm))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NORMALIZED"));
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id='d1'", String.class)).isEqualTo("FIRE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id='d1' AND physical_state='FIRE' AND response_state='UNHANDLED'", Long.class)).isEqualTo(1);
        // 同一火灾码重复到达：复用未处理告警并累计次数，不新建第二条。
        String repeated = alarm.replace("2026-10-30 14:44:16", "2026-10-30 14:44:17");
        mvc.perform(post("/v1/formttl/alarm/telemetry").header("X-Legacy-Token", "vector-token")
                .contentType(MediaType.APPLICATION_JSON).content(repeated))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE device_id='d1'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT occurrence_count FROM alarms WHERE device_id='d1'", Integer.class)).isEqualTo(2);
        String recovery = alarm.replace("\"thing.event.alarm\"", "\"thing.event.alarmRecovery\"")
            .replace("\"火灾报警\"", "\"恢复\"").replace("2026-10-30 14:44:16","2026-10-30 14:44:18");
        mvc.perform(post("/v1/formttl/alarm/telemetry").header("X-Legacy-Token", "vector-token")
                .contentType(MediaType.APPLICATION_JSON).content(recovery))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id='d1'", String.class)).isEqualTo("NORMAL");
    }

    @Test void channelStatsExposeEveryDeliveredPlatform() throws Exception {
        sendUplink(LORAWAN).andExpect(status().isOk());
        mvc.perform(post("/v1/formttl/heartbeat/telemetry").header("X-Legacy-Token", "vector-token")
                .contentType(MediaType.APPLICATION_JSON).content(FORMTTL))
            .andExpect(status().isOk());
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) SELECT 'bootstrap-admin','t1' WHERE NOT EXISTS(SELECT 1 FROM user_tenant_grants WHERE user_id='bootstrap-admin' AND tenant_id='t1')");
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) SELECT 'bootstrap-admin','t1','ALL_TENANT',1 WHERE NOT EXISTS(SELECT 1 FROM user_device_scopes WHERE user_id='bootstrap-admin' AND tenant_id='t1')");
        jdbc.update("INSERT INTO user_permissions(user_id,permission) SELECT 'bootstrap-admin','legacy-ingest:read' WHERE NOT EXISTS(SELECT 1 FROM user_permissions WHERE user_id='bootstrap-admin' AND permission='legacy-ingest:read')");
        String body = mvc.perform(get("/api/management/legacy-ingest/channels")
                .header("Authorization", "Bearer " + root).header("X-Tenant-Id", "t1"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var rows = json.readTree(body);
        java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> byPlatform = new java.util.HashMap<>();
        rows.forEach(row -> byPlatform.put(row.get("platform").asText(), row));
        assertThat(byPlatform).containsKeys("lorawan_turbo", "formttl");
        assertThat(byPlatform.get("lorawan_turbo").get("messages").asLong()).isEqualTo(1);
        assertThat(byPlatform.get("lorawan_turbo").get("pending_decoder").asLong()).isEqualTo(1);
        assertThat(byPlatform.get("formttl").get("messages").asLong()).isEqualTo(1);
        assertThat(byPlatform.get("formttl").get("normalized").asLong()).isEqualTo(1);
        assertThat(byPlatform.get("formttl").get("last_received_at").isNull()).isFalse();
    }

    @Test void unknownDeviceIsKeptRatherThanDropped() throws Exception {
        var body = LORAWAN.replace("0000000000000001", "FFFFFFFFFFFFFFFF");
        mvc.perform(post("/api/notify/v1/vector-token/lorawan_turbo/telemetry")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("FAILED"))
            .andExpect(jsonPath("$.failure_code").value("UNKNOWN_DEVICE"));
        var retained = jdbc.queryForMap("SELECT m.tenant_id,m.device_id,m.payload_raw,q.reason FROM legacy_ingest_messages m"
            + " JOIN legacy_ingest_quarantines q ON q.message_id=m.id WHERE m.payload_sha256=?",
            com.fireplatform.shared.Digests.sha256(body));
        assertThat(retained.get("tenant_id")).isNull();
        assertThat(retained.get("device_id")).isNull();
        assertThat(retained.get("payload_raw")).isEqualTo(body);
        assertThat(retained.get("reason")).isEqualTo("UNKNOWN_DEVICE");
    }

    @Test void wrongOrMissingTokenNeverIngests() throws Exception {
        mvc.perform(post("/api/notify/v1/other-token/lorawan_turbo/telemetry")
                .contentType(MediaType.APPLICATION_JSON).content(LORAWAN))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LEGACY_INGEST_DENIED"));
        mvc.perform(post("/v1/formttl/heartbeat/telemetry").contentType(MediaType.APPLICATION_JSON).content(FORMTTL))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_messages", Long.class)).isZero();
    }

    @Test void heartbeatNeverClearsAnOpenFireAlarm() throws Exception {
        jdbc.update("UPDATE devices SET physical_state='FIRE' WHERE id='d1'");
        mvc.perform(post("/v1/formttl/heartbeat/telemetry").header("X-Legacy-Token", "vector-token")
                .contentType(MediaType.APPLICATION_JSON).content(FORMTTL))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id='d1'", String.class)).isEqualTo("FIRE");
    }

    private ResultActions sendUplink(String body) throws Exception {
        return mvc.perform(post("/api/notify/v1/vector-token/lorawan_turbo/telemetry")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    private String login(String name, String password, String audience) throws Exception {
        var response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("username", name, "password", password, "audience", audience))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("access_token").asText();
    }
}
