package com.fireplatform.legacyingest.facts;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.legacyingest.LegacyNormalizedEvent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LegacyFactApplyTest {
    final JdbcTemplate jdbc = LegacyFactSchema.migrate("legacy-facts");
    final LegacyFactApplier applier = new LegacyFactApplier(jdbc, new ObjectMapper(), org.mockito.Mockito.mock(com.fireplatform.identity.CoordinationFence.class), new LegacyAlarmLifecycleFixture(jdbc), org.mockito.Mockito.mock(com.fireplatform.configuration.AlarmBaseConfigurationEventConsumer.class), org.mockito.Mockito.mock(com.fireplatform.integration.BusinessFactPublisher.class));
    final OffsetDateTime at = OffsetDateTime.parse("2026-10-30T14:44:16Z");
    int sequence;

    @BeforeEach void schema() {
        LegacyFactSchema.seed(jdbc);
    }

    void apply(BigDecimal battery, String kind, String params) {
        OffsetDateTime eventAt = at.plusSeconds(sequence++);
        String message = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,vendor_device_id,device_id,payload_raw,payload_sha256,payload_bytes,received_at,observed_at,state,duplicate_count) VALUES (?,'tenant','HTTP_NOTIFY','aep_turbo',?,'vendor','device','{}',?,2,?,?,'NORMALIZED',0)", message, UUID.randomUUID().toString(), "0".repeat(64), eventAt, eventAt);
        applier.apply(message, new LegacyNormalizedEvent("tenant", "device", "vendor", "aep_turbo", kind, eventAt, battery, false, List.of(new LegacyNormalizedEvent.LegacyEvent(kind, "method", params, eventAt))));
    }
    @Test void laterParameterActivitySupersedesOfflineAsCurrentEvidenceButPreservesHistoryAndFire() {
        OffsetDateTime oldOffline = at.minusHours(2);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,message_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('historical-offline','tenant',NULL,'device','OFFLINE','offline.scan','{}',?,?)",oldOffline,oldOffline);
        jdbc.update("UPDATE devices SET physical_state='FIRE',last_message_at=? WHERE id='device'",oldOffline.minusMinutes(1));
        apply(null,"PARAM","{\"temperature\":21}");
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id='device'",OffsetDateTime.class)).isAfter(oldOffline);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE id='historical-offline'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT observed_at>(SELECT observed_at FROM legacy_ingest_events WHERE id='historical-offline') FROM legacy_ingest_events WHERE kind='PARAM'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id='device'",String.class)).isEqualTo("FIRE");
    }


    @Test void preservesZeroAndNullBatteryAndHeartbeatDoesNotClearFire() {
        apply(BigDecimal.ZERO, "HEARTBEAT", "{}");
        assertThat(jdbc.queryForObject("SELECT battery_percent FROM devices WHERE id='device'", BigDecimal.class)).isEqualByComparingTo(BigDecimal.ZERO);
        apply(null, "ALARM", "{\"eventType\":\"FIRE\"}"); apply(null, "HEARTBEAT", "{}");
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id='device'", String.class)).isEqualTo("FIRE");
        assertThat(jdbc.queryForObject("SELECT battery_percent FROM devices WHERE id='device'", BigDecimal.class)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test void repeatAlarmReusesUnhandledSessionAndRecoveryAllowsNewSession() {
        apply(null, "ALARM", "{\"eventType\":\"FIRE\"}"); apply(null, "ALARM", "{\"eventType\":\"FIRE\"}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms", Integer.class)).isEqualTo(1);
        var originalOccurred = jdbc.queryForObject("SELECT occurred_at FROM alarms", OffsetDateTime.class);
        assertThat(jdbc.queryForObject("SELECT occurrence_count FROM alarms", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM alarms", OffsetDateTime.class)).isEqualTo(originalOccurred);
        assertThat(jdbc.queryForObject("SELECT revision FROM alarms", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_event_at FROM alarms", OffsetDateTime.class)).isAfter(originalOccurred);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms", Integer.class)).isEqualTo(1);
        apply(null, "RECOVERY", "{}");
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id='device'", String.class)).isEqualTo("NORMAL");
        apply(null, "ALARM", "{\"eventType\":\"FIRE\"}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms", Integer.class)).isEqualTo(2);
    }
}
