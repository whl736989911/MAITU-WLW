package com.fireplatform.legacyingest.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "app.roles=all", "app.storage=h2-dev", "fire.legacy.ingest-token=vector-token",
    "spring.datasource.url=jdbc:h2:mem:legacyingestreject;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegacyIngestRejectTest {
    private static final String LORAWAN = """
        {"app":"backend","devaddr":"00000001","deveui":"0000000000000001","data":"B2B000130000E1","fcnt":327,
         "port":1,"datetime":"2026-10-30T07:07:22Z","datr":"SF12BW125","freq":473.3,"codr":"4/5",
         "mac":"0200000000000001","lsnr":-10.5,"rssi":-145,"best_gw":"0200000000000001","battery":100,
         "appargs":"","pid":334,"netid":"470同频"}
        """;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fireplatform.bootstrap.Bootstrap bootstrap;

    @BeforeEach
    void prepareDatabase() {
        bootstrap.run(new DefaultApplicationArguments());
        jdbc.update("DELETE FROM legacy_ingest_events");
        jdbc.update("DELETE FROM legacy_ingest_quarantines");
        jdbc.update("DELETE FROM legacy_ingest_messages");
        jdbc.update("DELETE FROM tenants WHERE id='UNASSIGNED'");
        jdbc.update("MERGE INTO tenants (id,name) KEY(id) VALUES ('t1','测试租户')");
        jdbc.update("MERGE INTO devices (id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) " +
            "KEY(id) VALUES ('d1','t1','测试烟感','0000000000000001','TBS-110','1号楼','UNKNOWN',FALSE)");
    }

    @Test
    void nonJsonBodyReturnsBadRequest() throws Exception {
        postBody("lorawan_turbo", "this is not json").andExpect(status().isBadRequest());
    }

    @Test
    void nonObjectJsonReturnsBadRequest() throws Exception {
        postBody("lorawan_turbo", "[1,2]").andExpect(status().isBadRequest());
        postBody("lorawan_turbo", "\"text\"").andExpect(status().isBadRequest());
    }

    @Test
    void missingDeviceIdentifierReturnsBadRequest() throws Exception {
        postBody("lorawan_turbo", "{\"data\":\"B2B000130000E1\",\"fcnt\":1}")
            .andExpect(status().isBadRequest());
        postBody("formttl", "{\"parseMsg\":[]}").andExpect(status().isBadRequest());
    }

    @Test
    void unknownPlatformReturnsBadRequest() throws Exception {
        postBody("not_a_platform", LORAWAN).andExpect(status().isBadRequest());
    }

    @Test
    void unknownDeviceIsStoredAsFailedWithRawPayload() throws Exception {
        jdbc.update("MERGE INTO tenants (id,name) KEY(id) VALUES ('UNASSIGNED','未分配')");
        String raw = LORAWAN.replace("0000000000000001", "FFFFFFFFFFFFFFFF");
        postBody("lorawan_turbo", raw).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("FAILED"))
            .andExpect(jsonPath("$.failure_code").value("UNKNOWN_DEVICE"));
        assertThat(jdbc.queryForObject("SELECT payload_raw FROM legacy_ingest_messages", String.class)).isEqualTo(raw);
    }

    @Test
    void unknownDeviceWithoutPlaceholderTenantIsQuarantinedWithRawPayload() throws Exception {
        String raw = LORAWAN.replace("0000000000000001", "FFFFFFFFFFFFFFFF");
        postBody("lorawan_turbo", raw).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("FAILED"))
            .andExpect(jsonPath("$.failure_code").value("UNKNOWN_DEVICE"));
        assertThat(jdbc.queryForObject("SELECT payload_raw FROM legacy_ingest_messages", String.class)).isEqualTo(raw);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM legacy_ingest_messages", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_quarantines", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id='UNASSIGNED'", Long.class)).isZero();
    }

    @Test
    void duplicateDeviceFrameIncrementsCounterWithoutAddingEvents() throws Exception {
        postBody("lorawan_turbo", LORAWAN).andExpect(status().isOk());
        long initialEvents = jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events", Long.class);
        postBody("lorawan_turbo", LORAWAN).andExpect(status().isOk())
            .andExpect(jsonPath("$.duplicate").value(true));
        assertThat(jdbc.queryForObject("SELECT duplicate_count FROM legacy_ingest_messages", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events", Long.class)).isEqualTo(initialEvents);
    }

    @Test
    void omittedObservedAtIsStoredAsNull() throws Exception {
        String raw = "{\"deveui\":\"0000000000000001\",\"data\":\"B2B000130000E1\",\"fcnt\":991}";
        postBody("lorawan_turbo", raw).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT observed_at FROM legacy_ingest_messages", Object.class)).isNull();
    }

    private org.springframework.test.web.servlet.ResultActions postBody(String platform, String body) throws Exception {
        return mvc.perform(post("/api/notify/v1/vector-token/{platform}/telemetry", platform)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
