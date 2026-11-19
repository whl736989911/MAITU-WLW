package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class IoTPlatformMigrationUpgradeTest {
    private record Database(DriverManagerDataSource dataSource, String databaseName) { }

    private Database h2Database() {
        String url = "jdbc:h2:mem:iot-migration-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        return new Database(new DriverManagerDataSource(url, "sa", ""), null);
    }

    private Database postgresDatabase() throws SQLException {
        String adminUrl = System.getenv("IOT_MIGRATION_PG_URL");
        String user = System.getenv("IOT_MIGRATION_PG_USER");
        String password = System.getenv("IOT_MIGRATION_PG_PASSWORD");
        String databaseName = "iot_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = new DriverManagerDataSource(adminUrl, user, password).getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + databaseName + "\"");
        }
        return new Database(new DriverManagerDataSource(withDatabase(adminUrl, databaseName), user, password), databaseName);
    }

    private String withDatabase(String jdbcUrl, String databaseName) {
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        return "jdbc:" + uri.getScheme() + "://" + uri.getRawAuthority() + "/" + databaseName
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    private void migrateTo58(Database db) {
        Flyway.configure().dataSource(db.dataSource()).target("58").load().migrate();
    }

    private void migrateToLatest(Database db) {
        Flyway.configure().dataSource(db.dataSource()).load().migrate();
    }

    private void verifyUpgrade(Database db) {
        migrateTo58(db);


        JdbcTemplate jdbc = new JdbcTemplate(db.dataSource());
        String oldUnit = UUID.randomUUID().toString();
        String newUnit = UUID.randomUUID().toString();
        String oldDevice = UUID.randomUUID().toString();
        String otherDevice = UUID.randomUUID().toString();
        String alarm = UUID.randomUUID().toString();
        String actor = UUID.randomUUID().toString();
        String legacyRule = UUID.randomUUID().toString();
        String faultRule = UUID.randomUUID().toString();
        String sharedEui = "eui-" + UUID.randomUUID();

        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?),(?,?)", oldUnit, "Original unit", newUnit, "New unit");
        jdbc.update("INSERT INTO notification_rules(id,tenant_id,name,enabled,event_scope,escalation_interval_seconds,sms_escalation_interval_seconds,voice_escalation_interval_seconds,created_at) VALUES (?,?,?,TRUE,'FIRE',NULL,0,0,CURRENT_TIMESTAMP)",
                legacyRule, oldUnit, "Historical FIRE notification rule");
        var legacyRuleBefore = jdbc.queryForMap("SELECT id,tenant_id,name,event_scope FROM notification_rules WHERE id=?", legacyRule);
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled,is_protected) VALUES (?,?,?,'PLATFORM','test-hash',FALSE,TRUE,FALSE)",
                actor, "migration-actor-" + actor, "Migration fixture actor");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state) VALUES (?,?,?,?,'model-v58','Original address','FIRE'),(?,?,?,?,'model-v58','Other address','NORMAL')",
                oldDevice, oldUnit, "Original asset", sharedEui, otherDevice, newUnit, "Other unit asset", "other-eui-" + UUID.randomUUID());
        jdbc.update("INSERT INTO alarms(id,tenant_id,device_id,device_name,address,event_type,occurred_at,physical_state,response_state,conclusion,remark,revision,is_simulated) VALUES (?,?,?,?,?,'FIRE',CURRENT_TIMESTAMP,'FIRE','HANDLED','TEST','Historical disposition',7,FALSE)",
                alarm, oldUnit, oldDevice, "Alarm-time device name", "Alarm-time address");
        var deviceBefore = jdbc.queryForMap("SELECT id,tenant_id,name,device_eui,physical_state FROM devices WHERE id=?", oldDevice);
        var alarmBefore = jdbc.queryForMap("SELECT id,tenant_id,device_id,device_name,address,physical_state,response_state,conclusion,remark,revision FROM alarms WHERE id=?", alarm);

        migrateToLatest(db);
        assertThat(jdbc.queryForMap("SELECT id,tenant_id,name,event_scope FROM notification_rules WHERE id=?", legacyRule))
                .isEqualTo(legacyRuleBefore);
        jdbc.update("INSERT INTO notification_rules(id,tenant_id,name,enabled,event_scope,escalation_interval_seconds,sms_escalation_interval_seconds,voice_escalation_interval_seconds,created_at) VALUES (?,?,?,TRUE,'FAULT',NULL,0,0,CURRENT_TIMESTAMP)",
                faultRule, oldUnit, "Fault notification rule");
        assertThat(jdbc.queryForObject("SELECT event_scope FROM notification_rules WHERE id=?", String.class, faultRule))
                .isEqualTo("FAULT");

        assertThat(jdbc.queryForMap("SELECT id,tenant_id,name,device_eui,physical_state FROM devices WHERE id=?", oldDevice))
                .isEqualTo(deviceBefore);
        assertThat(jdbc.queryForMap("SELECT id,tenant_id,device_id,device_name,address,physical_state,response_state,conclusion,remark,revision FROM alarms WHERE id=?", alarm))
                .isEqualTo(alarmBefore);
        assertThatThrownBy(() -> insertAsset(jdbc, UUID.randomUUID().toString(), oldUnit, sharedEui))
                .isInstanceOf(DataAccessException.class);

        jdbc.update("UPDATE devices SET archived_at=CURRENT_TIMESTAMP,archived_reason='Migration identity fixture',archived_by=?,identity_slot=? WHERE id=?",
                actor, "HISTORY:" + oldDevice, oldDevice);
        String newDevice = UUID.randomUUID().toString();
        insertAsset(jdbc, newDevice, newUnit, sharedEui);

        assertThat(jdbc.queryForMap("SELECT id,tenant_id,name,device_eui,physical_state FROM devices WHERE id=?", oldDevice))
                .containsEntry("id", oldDevice).containsEntry("tenant_id", oldUnit).containsEntry("device_eui", sharedEui)
                .containsEntry("physical_state", "FIRE");
        assertThat(jdbc.queryForObject("SELECT identity_slot FROM devices WHERE id=?", String.class, newDevice)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=? AND identity_slot='ACTIVE'", Long.class, sharedEui)).isEqualTo(1L);
        assertThat(jdbc.queryForMap("SELECT tenant_id,device_id,physical_state,response_state,conclusion,remark,revision FROM alarms WHERE id=?", alarm))
                .containsEntry("tenant_id", oldUnit).containsEntry("device_id", oldDevice)
                .containsEntry("physical_state", "FIRE").containsEntry("response_state", "HANDLED")
                .containsEntry("conclusion", "TEST").containsEntry("remark", "Historical disposition");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE tenant_id=? AND device_id=?", Long.class, newUnit, newDevice)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE tenant_id=? AND device_id=?", Long.class, oldUnit, oldDevice)).isEqualTo(1L);
        assertThatThrownBy(() -> insertAsset(jdbc, UUID.randomUUID().toString(), oldUnit, sharedEui))
                .isInstanceOf(DataAccessException.class);
    }

    private void insertAsset(JdbcTemplate jdbc, String id, String tenant, String eui) {
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state) VALUES (?,?,?,?,'model-v58','New asset address','NORMAL')",
                id, tenant, "Replacement asset", eui);
    }

    private void dropDatabase(Database db) throws SQLException {
        if (db.databaseName() == null) return;
        String adminUrl = System.getenv("IOT_MIGRATION_PG_URL");
        String user = System.getenv("IOT_MIGRATION_PG_USER");
        String password = System.getenv("IOT_MIGRATION_PG_PASSWORD");
        try (Connection connection = new DriverManagerDataSource(adminUrl, user, password).getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS \"" + db.databaseName() + "\" WITH (FORCE)");
        }
    }

    @Test
    void h2UpgradePreservesOldIdentityAndAlarmAndAllowsArchivedEuiReuse() {
        verifyUpgrade(h2Database());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "IOT_MIGRATION_PG_URL", matches = ".+")
    void postgres17UpgradePreservesOldIdentityAndAlarmAndAllowsArchivedEuiReuse() throws SQLException {
        Database db = postgresDatabase();
        try {
            verifyUpgrade(db);
        } finally {
            dropDatabase(db);
        }
    }
}
