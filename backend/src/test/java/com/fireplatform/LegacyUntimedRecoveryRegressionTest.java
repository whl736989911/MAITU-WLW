package com.fireplatform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.bootstrap.Bootstrap;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.product.JsonPointerParser;
import java.time.OffsetDateTime;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={
    "app.roles=all", "app.generic-ingest-enabled=true", "app.storage=h2-dev",
    "fire.legacy.ingest-token=untimed-recovery-token",
    "spring.datasource.url=jdbc:h2:mem:legacy-untimed-recovery;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class LegacyUntimedRecoveryRegressionTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry r) {
        IngestionIntegrationTest.environment(r);
    }

    private static final String DEVICE = "legacy-untimed-device";
    private static final String UNTIMED_FIRE = "legacy-untimed-fire";
    private static final String LATER_KNOWN_FIRE = "legacy-later-known-fire";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Bootstrap bootstrap;
    @Autowired JsonPointerParser parser;
    @Autowired DatabaseTime time;

    @BeforeEach
    void setup() throws Exception {
        IngestionIntegrationTest f = new IngestionIntegrationTest();
        f.mvc = mvc;
        f.json = json;
        f.jdbc = jdbc;
        f.bootstrap = bootstrap;
        f.parser = parser;
        f.reset();

        // First registration has ownership_from = NULL (LegacyRealtimeDeviceService), so the
        // ownership boundary deliberately does not reject an untimed packet for this device.
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated) "
            + "VALUES (?,'tenant-a','Legacy smoke','vendor-untimed','legacy-model','A区','FIRE',FALSE)", DEVICE);

        OffsetDateTime now = time.now();
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,last_event_at,"
            + "physical_state,response_state,revision,is_simulated,source_time_known,platform_recorded_at) "
            + "VALUES (?, 'tenant-a', ?, 'Legacy smoke','A区','FIRE', ?, ?, 'FIRE','UNHANDLED',0,FALSE,FALSE,?)",
            UNTIMED_FIRE, DEVICE, now.minusHours(2), now.minusHours(2), now.minusHours(2));
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,last_event_at,"
            + "physical_state,response_state,revision,is_simulated,source_time_known,platform_recorded_at) "
            + "VALUES (?, 'tenant-a', ?, 'Legacy smoke','A区','FIRE', ?, ?, 'FIRE','UNHANDLED',0,FALSE,TRUE,?)",
            LATER_KNOWN_FIRE, DEVICE, now.minusHours(1), now.minusHours(1), now.minusHours(1));
    }

    @Test
    void untimedLegacyRecoveryMustNotCloseANewerKnownTimeFire() throws Exception {
        String body = "{\"deviceId\":\"vendor-untimed\",\"parseMsg\":[{\"method\":\"thing.event.alarmRecovery\",\"params\":{}}]}";
        mvc.perform(post("/v1/formttl/alarm/telemetry")
                .header("X-Legacy-Token", "untimed-recovery-token")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("NORMALIZED"))
            .andExpect(jsonPath("$.duplicate").value(false))
            .andExpect(jsonPath("$.events").value(1));

        // Control: identical wire body is an exact duplicate, so a fresh untimed recovery body is required.
        mvc.perform(post("/v1/formttl/alarm/telemetry")
                .header("X-Legacy-Token", "untimed-recovery-token")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.duplicate").value(true));

        String laterState = jdbc.queryForObject(
            "SELECT physical_state FROM alarms WHERE id=?", String.class, LATER_KNOWN_FIRE);
        OffsetDateTime laterOccurred = jdbc.queryForObject(
            "SELECT occurred_at FROM alarms WHERE id=?", OffsetDateTime.class, LATER_KNOWN_FIRE);
        String laterResponse = jdbc.queryForObject(
            "SELECT response_state FROM alarms WHERE id=?", String.class, LATER_KNOWN_FIRE);
        String deviceState = jdbc.queryForObject(
            "SELECT physical_state FROM devices WHERE id=?", String.class, DEVICE);

        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(laterState)
            .as("a recovery with no source time cannot close a newer known-time fire")
            .isEqualTo("FIRE");
        soft.assertThat(deviceState)
            .as("device stays FIRE while the newer known fire remains active")
            .isEqualTo("FIRE");
        soft.assertThat(laterOccurred)
            .as("the newer fire keeps its original occurrence time")
            .isNotNull();
        soft.assertThat(laterResponse)
            .as("physical recovery never fabricates human handling")
            .isEqualTo("UNHANDLED");
        soft.assertAll();
    }
}
