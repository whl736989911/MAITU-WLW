package com.fireplatform.legacyingest.facts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.configuration.OfflinePolicySql;
import com.fireplatform.configuration.RuntimeConfig;

class OfflineEventTaskTest {
    final JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:legacy-offline-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
    final RuntimeConfig runtime=runtimeConfig();
    final DatabaseTime time=new DatabaseTime(jdbc,runtime);
    final OfflineEventTask task=new OfflineEventTask(jdbc,policy(),time);

    private static RuntimeConfig runtimeConfig() {
        RuntimeConfig runtime=mock(RuntimeConfig.class);when(runtime.storage()).thenReturn("h2-dev");return runtime;
    }

    private OfflinePolicySql policy() { return new OfflinePolicySql(runtime); }

    /** Scan timestamps come from the database clock, not this test: compare instants, never host-formatted strings. */
    private static OffsetDateTime jsonTime(String json,String field) {
        String marker="\""+field+"\":\"";
        int start=json.indexOf(marker);
        assertThat(start).as("params field %s",field).isNotNegative();
        return OffsetDateTime.parse(json.substring(start+marker.length(),json.indexOf('"',start+marker.length())));
    }

    @BeforeEach void schema() {
        jdbc.execute("CREATE TABLE numeric_settings(setting_key VARCHAR(64) PRIMARY KEY,setting_value BIGINT,revision BIGINT)");
        jdbc.update("INSERT INTO numeric_settings VALUES ('offline_threshold_minutes',120,0)");
        jdbc.execute("CREATE TABLE parameter_overrides(tenant_id VARCHAR(64),scope VARCHAR(16),scope_id VARCHAR(64),parameter_key VARCHAR(64),override_value BIGINT,revision BIGINT)");
        jdbc.execute("CREATE TABLE devices(id VARCHAR(64) PRIMARY KEY,tenant_id VARCHAR(64),place_id VARCHAR(64),last_message_at TIMESTAMP WITH TIME ZONE,is_simulated BOOLEAN DEFAULT FALSE,archived_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE places(id VARCHAR(64) PRIMARY KEY,tenant_id VARCHAR(64),parent_place_id VARCHAR(64),archived_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE legacy_ingest_events(id VARCHAR(64) PRIMARY KEY,tenant_id VARCHAR(64),message_id VARCHAR(64),device_id VARCHAR(64),kind VARCHAR(16),method VARCHAR(64),params_json TEXT,observed_at TIMESTAMP WITH TIME ZONE,created_at TIMESTAMP WITH TIME ZONE,business_key VARCHAR(64),business_code VARCHAR(64))");
        OffsetDateTime old=time.now().minusHours(2);
        jdbc.update("INSERT INTO devices(id,tenant_id,last_message_at) VALUES ('reported','tenant',?)",old);
        jdbc.update("INSERT INTO devices(id,tenant_id,last_message_at) VALUES ('unactivated','tenant',NULL)");
    }

    @Test void noHeartbeatEvidenceDoesNotInventOfflineForAReportedDevice() {
        assertThat(task.scan()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='OFFLINE'",Integer.class)).isZero();
    }
    @Test void untimedHeartbeatDoesNotCreateOfflineEvidence() {
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('untimed-heartbeat','tenant','reported','HEARTBEAT','source.heartbeat','{}',NULL,CURRENT_TIMESTAMP)");
        assertThat(task.scan()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='OFFLINE'",Integer.class)).isZero();
    }

    @Test void noReportRemainsUnactivatedWithoutAnOfflineEvent() {
        assertThat(task.scan()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='OFFLINE'",Integer.class)).isZero();
    }


    @Test void delayedActivityWithOldSourceTimeDoesNotReopenAnOfflineRound() {
        OffsetDateTime now=time.now(),last=now.minusHours(3);
        jdbc.update("UPDATE numeric_settings SET setting_value=120 WHERE setting_key='offline_threshold_minutes'");
        jdbc.update("UPDATE devices SET last_message_at=? WHERE id='reported'",last);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('heartbeat','tenant','reported','HEARTBEAT','source.heartbeat','{}',?,?)",last,last);
        OffsetDateTime before=time.now();
        assertThat(task.scan()).isEqualTo(1);
        OffsetDateTime after=time.now();
        assertThat(jdbc.queryForObject("SELECT observed_at FROM legacy_ingest_events WHERE kind='OFFLINE'",OffsetDateTime.class).toInstant()).isBetween(before.toInstant(),after.toInstant());
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('late-old-other','tenant','reported','OTHER','source.other','{}',?,?)",now.minusMinutes(30),after.plusMinutes(5));
        assertThat(task.scan()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='OFFLINE'",Integer.class)).isEqualTo(1);
    }
    @Test void effectiveDeviceTenantGlobalThresholdsRequireTimedHeartbeatEvidenceAndActivityReopensRounds() {
        OffsetDateTime now=time.now(),heartbeat=now.minusMinutes(45);
        jdbc.update("INSERT INTO places(id,tenant_id,parent_place_id) VALUES ('building','tenant',NULL),('room','tenant','building')");
        jdbc.update("INSERT INTO parameter_overrides VALUES ('tenant','TENANT','tenant','offline_threshold_minutes',120,1)");
        jdbc.update("INSERT INTO parameter_overrides VALUES ('tenant','PLACE','building','offline_threshold_minutes',60,1)");
        jdbc.update("INSERT INTO parameter_overrides VALUES ('tenant','DEVICE','reported','offline_threshold_minutes',30,1)");
        jdbc.update("UPDATE devices SET last_message_at=? WHERE id='reported'",heartbeat);
        jdbc.update("INSERT INTO devices(id,tenant_id,place_id,last_message_at) VALUES ('tenant-only','tenant','room',?)",now.minusMinutes(90));
        jdbc.update("INSERT INTO devices(id,tenant_id,last_message_at) VALUES ('global-only','other',?)",now.minusMinutes(90));
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('heartbeat','tenant','reported','HEARTBEAT','source.heartbeat','{}',?,?)",heartbeat,heartbeat);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('tenant-heartbeat','tenant','tenant-only','HEARTBEAT','source.heartbeat','{}',?,?)",now.minusMinutes(90),now.minusMinutes(90));
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('global-heartbeat','other','global-only','HEARTBEAT','source.heartbeat','{}',?,?)",now.minusMinutes(90),now.minusMinutes(90));
        OffsetDateTime before=time.now();
        assertThat(task.scan()).isEqualTo(2);
        OffsetDateTime after=time.now();
        assertThat(task.scan()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='OFFLINE'",Integer.class)).isEqualTo(2);
        String params=jdbc.queryForObject("SELECT params_json FROM legacy_ingest_events WHERE device_id='reported' AND kind='OFFLINE'",String.class);
        assertThat(params).contains("\"thresholdMinutes\":30","\"evidence\":\"accepted_heartbeat\"",
            "\"thresholdSourceScope\":\"DEVICE\"","\"thresholdSourceRevision\":1").doesNotContain("reported");
        assertThat(jsonTime(params,"lastMessageAt").toInstant()).isEqualTo(heartbeat.toInstant());
        OffsetDateTime reportedOfflineAt=jdbc.queryForObject("SELECT observed_at FROM legacy_ingest_events WHERE device_id='reported' AND kind='OFFLINE'",OffsetDateTime.class);
        assertThat(reportedOfflineAt.toInstant()).isBetween(before.toInstant(),after.toInstant());
        assertThat(jsonTime(params,"evaluatedAt").toInstant()).isEqualTo(reportedOfflineAt.toInstant());
        assertThat(jdbc.queryForObject("SELECT params_json FROM legacy_ingest_events WHERE device_id='tenant-only' AND kind='OFFLINE'",String.class))
            .contains("\"thresholdMinutes\":60","\"thresholdSourceScope\":\"PLACE\"","\"thresholdSourceRevision\":1")
            .doesNotContain("tenant-only","building");
        OffsetDateTime activity=now.plusMinutes(1);
        jdbc.update("UPDATE devices SET last_message_at=? WHERE id='reported'",activity);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('param-after-offline','tenant','reported','PARAM','source.param','{}',?,?)",activity,activity);
        assertThat(task.scan()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='OFFLINE'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE device_id='global-only' AND kind='OFFLINE'",Integer.class)).isZero();
    }
    @Test void laterTimedActivityClosesAnOldOfflineAndAllowsASeparateStaleRound() {
        OffsetDateTime now=time.now();
        jdbc.update("UPDATE numeric_settings SET setting_value=30 WHERE setting_key='offline_threshold_minutes'");
        OffsetDateTime old=now.minusHours(2),activity=now.minusMinutes(40),received=now.minusMinutes(30);
        jdbc.update("UPDATE devices SET last_message_at=? WHERE id='reported'",activity);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('heartbeat','tenant','reported','HEARTBEAT','source.heartbeat','{}',?,?)",old,old);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('prior-offline','tenant','reported','OFFLINE','offline.scan','{}',?,?)",old,old);
        jdbc.update("INSERT INTO legacy_ingest_events(id,tenant_id,device_id,kind,method,params_json,observed_at,created_at) VALUES ('later-param','tenant','reported','PARAM','source.param','{}',?,?)",activity,received);
        OffsetDateTime before=time.now();
        assertThat(task.scan()).isEqualTo(1);
        OffsetDateTime after=time.now();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE kind='OFFLINE'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT observed_at FROM legacy_ingest_events WHERE kind='OFFLINE' AND id<>'prior-offline'",OffsetDateTime.class).toInstant()).isBetween(before.toInstant(),after.toInstant());
        assertThat(task.scan()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE id='prior-offline'",Integer.class)).isEqualTo(1);
    }
}
