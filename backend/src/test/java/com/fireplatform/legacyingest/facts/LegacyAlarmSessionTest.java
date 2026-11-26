package com.fireplatform.legacyingest.facts;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.legacyingest.LegacyNormalizedEvent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LegacyAlarmSessionTest {
    private final JdbcTemplate jdbc = LegacyFactSchema.migrate("legacy-alarm-session");
    private final LegacyFactApplier applier = new LegacyFactApplier(jdbc, new ObjectMapper(), org.mockito.Mockito.mock(com.fireplatform.identity.CoordinationFence.class), new LegacyAlarmLifecycleFixture(jdbc), org.mockito.Mockito.mock(com.fireplatform.configuration.AlarmBaseConfigurationEventConsumer.class));
    private final com.fireplatform.configuration.RuntimeConfig runtime=runtimeConfig();
    private final com.fireplatform.configuration.DatabaseTime scannerTime=new com.fireplatform.configuration.DatabaseTime(jdbc,runtime);
    private final OfflineEventTask offlineTask=new OfflineEventTask(jdbc,new com.fireplatform.configuration.OfflinePolicySql(runtime),scannerTime);
    private static com.fireplatform.configuration.RuntimeConfig runtimeConfig() {
        var runtime=org.mockito.Mockito.mock(com.fireplatform.configuration.RuntimeConfig.class);
        org.mockito.Mockito.when(runtime.storage()).thenReturn("h2-dev");
        return runtime;
    }
    private final OffsetDateTime base = OffsetDateTime.parse("2026-10-30T14:44:16Z");

    @BeforeEach
    void schema() {
        LegacyFactSchema.seed(jdbc);
        jdbc.update("UPDATE devices SET last_message_at=? WHERE id='device'", base.minusHours(2));
        jdbc.update("UPDATE numeric_settings SET setting_value=1 WHERE setting_key='offline_threshold_minutes'");
    }

    @Test
    void repeatedFireReusesSessionAndRecoveryAllowsANewOne() {
        apply("ALARM", "{\"eventType\":\"FIRE\"}", base);
        apply("ALARM", "{\"eventType\":\"FIRE\"}", base.plusSeconds(1));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms", Integer.class)).isEqualTo(1);
        var originalOccurred = jdbc.queryForObject("SELECT occurred_at FROM alarms", OffsetDateTime.class);
        assertThat(jdbc.queryForObject("SELECT occurrence_count FROM alarms", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM alarms", OffsetDateTime.class)).isEqualTo(originalOccurred);
        assertThat(jdbc.queryForObject("SELECT revision FROM alarms", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_event_at FROM alarms", OffsetDateTime.class)).isAfter(originalOccurred);

        apply("RECOVERY", "{}", base.plusSeconds(2));
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id='device'", String.class)).isEqualTo("NORMAL");
        apply("ALARM", "{\"eventType\":\"FIRE\"}", base.plusSeconds(3));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms", Integer.class)).isEqualTo(2);
    }

    @Test
    void faultRecoveryFieldsNeverRecoverFireAndDeviceRecoveryClosesAllEligibleAlarms() {
        apply("ALARM", "{\"eventType\":\"FIRE\"}", base);
        apply("FAULT", "{\"eventType\":\"FAULT\",\"faultStopTime\":\"2026-10-30T14:44:18Z\",\"message\":\"normal recovery\"}", base.plusSeconds(2));
        assertThat(jdbc.queryForObject("SELECT physical_state FROM alarms",String.class)).isEqualTo("FIRE");
        jdbc.update("UPDATE alarms SET response_state='HANDLED',conclusion='FALSE_ALARM'");
        apply("ALARM", "{\"eventType\":\"FIRE\"}", base.plusSeconds(3));
        apply("RECOVERY", "{}", base.plusSeconds(4));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE physical_state='FIRE'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id='device'",String.class)).isEqualTo("NORMAL");
    }
    @Test
    void repeatedFaultEvidenceDoesNotDuplicateFaultsOrCountAsAnotherFire() {
        apply("ALARM","{\"eventType\":\"FIRE\"}",base);
        apply("FAULT","{\"code\":\"1002001\"}",base.plusSeconds(1));
        apply("FAULT","{\"code\":\"1002001\"}",base.plusSeconds(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='FAULT'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT occurrence_count FROM alarms",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT physical_state FROM alarms",String.class)).isEqualTo("FIRE");
    }

    @Test
    void heartbeatNeverClearsFire() {
        apply("ALARM", "{\"eventType\":\"FIRE\"}", base);
        apply("HEARTBEAT", "{}", base.plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT physical_state FROM devices WHERE id='device'", String.class)).isEqualTo("FIRE");
    }

    @Test
    void hikvisionPollingDoesNotRefreshLivenessAndStaleMonitoringCannotReplaceBattery() {
        var original=base.minusHours(2);
        jdbc.update("UPDATE devices SET last_message_at=?,battery_percent=70 WHERE id='device'",original);
        applyHik("FAULT","{\"eventType\":\"FAULT\",\"faultStopTime\":\"stop\"}",base,null);
        applyHik("ALARM","{\"eventType\":\"FIRE\"}",base.plusSeconds(1),null);
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id='device'",OffsetDateTime.class)).isEqualTo(original);
        var latest=base.plusSeconds(2);
        applyHik("PARAM","{\"battery\":40}",latest,new java.math.BigDecimal("40"));
        applyHik("PARAM","{\"battery\":20}",latest.minusSeconds(1),new java.math.BigDecimal("20"));
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM devices WHERE id='device'",OffsetDateTime.class)).isEqualTo(latest);
        assertThat(jdbc.queryForObject("SELECT battery_percent FROM devices WHERE id='device'",java.math.BigDecimal.class)).isEqualByComparingTo("40");
    }

    @Test
    void hikvisionDeduplicatesExactSourceIdsButNeverMergesDistinctFaultsByTimeWindow() {
        var at=base.plusSeconds(10);
        applyHik("FAULT","{\"deviceStateCode\":\"1002001\",\"source_event_id\":\"fault-A\"}",at,null);
        applyHik("FAULT","{\"deviceStateCode\":\"1002001\",\"source_event_id\":\"fault-A\"}",at,null);
        applyHik("FAULT","{\"deviceStateCode\":\"1002001\",\"source_event_id\":\"fault-B\"}",at,null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='FAULT'",Integer.class)).isEqualTo(2);
    }

    @Test
    void offlineScansUseAcceptedHeartbeatAndLaterActivityEndsTheHistoricalRound() {
        OffsetDateTime now=scannerTime.now();
        OffsetDateTime stale=now.minusHours(2);
        jdbc.update("UPDATE devices SET last_message_at=? WHERE id='device'",stale);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('old-heartbeat','tenant','device','HEARTBEAT','source.heartbeat','{}',?,?)",stale,stale);
        assertThat(offlineTask.scan()).isEqualTo(1);
        assertThat(offlineTask.scan()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='OFFLINE'",Integer.class)).isEqualTo(1);
        OffsetDateTime parameterAt=now.minusSeconds(5);
        apply("PARAM","{}",parameterAt);
        jdbc.update("UPDATE legacy_ingest_events SET created_at=? WHERE kind='PARAM'",now.plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='OFFLINE'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='PARAM' AND created_at>(SELECT created_at FROM legacy_ingest_events WHERE kind='OFFLINE')",Integer.class)).isEqualTo(1);
    }

    private void applyHik(String kind,String params,OffsetDateTime observedAt,java.math.BigDecimal battery) {
        String messageId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,vendor_device_id,device_id,payload_raw,payload_sha256,payload_bytes,received_at,observed_at,state,duplicate_count) VALUES (?,'tenant','HTTP_NOTIFY','hikvision_firecloud',?,'vendor','device','{}',?,2,?,?,'NORMALIZED',0)",messageId,UUID.randomUUID().toString(),"0".repeat(64),base.plusDays(1),observedAt);
        applier.apply(messageId,new LegacyNormalizedEvent("tenant","device","vendor","hikvision_firecloud",kind,observedAt,battery,false,List.of(new LegacyNormalizedEvent.LegacyEvent(kind,"hikvision.method",params,observedAt))));
    }
    private void apply(String kind, String params, OffsetDateTime observedAt) {
        String messageId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,vendor_device_id,device_id,payload_raw,payload_sha256,payload_bytes,received_at,observed_at,state,duplicate_count) VALUES (?,'tenant','HTTP_NOTIFY','aep_turbo',?,'vendor','device','{}',?,2,?,?,'NORMALIZED',0)",
            messageId, UUID.randomUUID().toString(), "0".repeat(64), observedAt, observedAt);
        applier.apply(messageId, new LegacyNormalizedEvent("tenant", "device", "vendor", "aep_turbo", kind,
            observedAt, null, false, List.of(new LegacyNormalizedEvent.LegacyEvent(kind, "method", params, observedAt))));
    }
}
