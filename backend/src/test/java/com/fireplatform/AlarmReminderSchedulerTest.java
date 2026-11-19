package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fireplatform.configuration.AlarmBaseConfigurationQueries;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.notification.AlarmReminderScheduler;
import com.fireplatform.notification.RealNotificationService;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class AlarmReminderSchedulerTest {
    private void handledDueAlarmIsCancelledButFutureScheduleIsUnchanged(DriverManagerDataSource source) {
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE alarms(tenant_id VARCHAR(64) NOT NULL,id VARCHAR(64) NOT NULL,response_state VARCHAR(16) NOT NULL,occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,PRIMARY KEY(tenant_id,id))");
        jdbc.execute("CREATE TABLE alarm_reminder_schedule(tenant_id VARCHAR(64) NOT NULL,alarm_id VARCHAR(64) NOT NULL,cycle_number BIGINT NOT NULL,cycle_started_at TIMESTAMP WITH TIME ZONE NOT NULL,period_hours INTEGER NOT NULL,next_due_at TIMESTAMP WITH TIME ZONE NOT NULL,status VARCHAR(16) NOT NULL,revision BIGINT NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL,PRIMARY KEY(tenant_id,alarm_id),FOREIGN KEY(tenant_id,alarm_id) REFERENCES alarms(tenant_id,id))");
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        for (String id : new String[]{"handled-due", "handled-future"}) {
            jdbc.update("INSERT INTO alarms(tenant_id,id,response_state,occurred_at) VALUES('unit',?,'HANDLED',?)", id, now.minusHours(2));
            jdbc.update("INSERT INTO alarm_reminder_schedule(tenant_id,alarm_id,cycle_number,cycle_started_at,period_hours,next_due_at,status,revision,updated_at) VALUES('unit',?,0,?,1,?,'WAITING',1,?)", id, now.minusHours(1), id.equals("handled-due") ? now.minusHours(1) : now.plusHours(1), now);
        }
        var time = mock(DatabaseTime.class);
        when(time.expression()).thenReturn("CURRENT_TIMESTAMP");
        when(time.now()).thenReturn(now);
        var scheduler = new AlarmReminderScheduler(jdbc, mock(AlarmBaseConfigurationQueries.class), mock(RealNotificationService.class), time);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        Integer accepted = transaction.execute(status -> scheduler.processDue());
        assertThat(accepted).isZero();
        assertThat(jdbc.queryForMap("SELECT status,cycle_number,revision FROM alarm_reminder_schedule WHERE alarm_id='handled-due'"))
            .containsEntry("status", "CANCELLED");
        assertThat(jdbc.queryForObject("SELECT revision FROM alarm_reminder_schedule WHERE alarm_id='handled-due'", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT status FROM alarm_reminder_schedule WHERE alarm_id='handled-future'", String.class)).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT revision FROM alarm_reminder_schedule WHERE alarm_id='handled-future'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE response_state='HANDLED'", Integer.class)).isEqualTo(2);
    }

    @Test void h2CancelsOnlyDueHandledAlarm() {
        handledDueAlarmIsCancelledButFutureScheduleIsUnchanged(new DriverManagerDataSource("jdbc:h2:mem:reminder-lock-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "IOT_MIGRATION_PG_URL", matches = ".+")
    void postgres17CancelsOnlyDueHandledAlarmWithoutLockingNullableJoin() throws SQLException {
        var source = new DriverManagerDataSource(System.getenv("IOT_MIGRATION_PG_URL"), System.getenv("IOT_MIGRATION_PG_USER"), System.getenv("IOT_MIGRATION_PG_PASSWORD"));
        String schema = "reminder_lock_" + UUID.randomUUID().toString().replace("-", "");
        var root = new JdbcTemplate(source);
        root.execute("CREATE SCHEMA " + schema);
        source.setSchema(schema);
        try {
            handledDueAlarmIsCancelledButFutureScheduleIsUnchanged(source);
        } finally {
            source.setSchema("public");
            root.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
