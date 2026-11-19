package com.fireplatform.notification;

import com.fireplatform.configuration.AlarmBaseConfigurationQueries;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.shared.ReminderScheduling;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Schedules periodic reminders for still-unhandled local alarms; it never changes alarm state. */
@Service
public class AlarmReminderScheduler implements ReminderScheduling {
    private final JdbcTemplate jdbc;
    private final AlarmBaseConfigurationQueries configuration;
    private final RealNotificationService notifications;
    private final DatabaseTime time;

    public AlarmReminderScheduler(JdbcTemplate jdbc, AlarmBaseConfigurationQueries configuration,
            RealNotificationService notifications, DatabaseTime time) {
        this.jdbc = jdbc;
        this.configuration = configuration;
        this.notifications = notifications;
        this.time = time;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(String tenantId, String alarmId, OffsetDateTime occurredAt) {
        var delivery = configuration.deliveryPolicy(tenantId, "FIRE", "SMS");
        if (!delivery.enabled() || !delivery.receiveAlarm() || !delivery.pushMessage() || delivery.pushPeriodHours() < 1) {
            cancelForAlarm(tenantId, alarmId);
            return;
        }
        OffsetDateTime firstDue = occurredAt.plusHours(delivery.pushPeriodHours());
        var existing = jdbc.queryForList("SELECT alarm_id FROM alarm_reminder_schedule WHERE tenant_id=? AND alarm_id=? FOR UPDATE", tenantId, alarmId);
        if (existing.isEmpty()) jdbc.update("INSERT INTO alarm_reminder_schedule(tenant_id,alarm_id,cycle_number,period_hours,cycle_started_at,next_due_at,last_accepted_at,status,revision,updated_at) VALUES(?,?,1,?,?,?,NULL,'WAITING',1,?)", tenantId, alarmId, delivery.pushPeriodHours(), firstDue, firstDue, time.now());
        else jdbc.update("UPDATE alarm_reminder_schedule SET cycle_number=1,period_hours=?,cycle_started_at=?,next_due_at=?,last_accepted_at=NULL,status='WAITING',revision=revision+1,updated_at=? WHERE tenant_id=? AND alarm_id=?", delivery.pushPeriodHours(), firstDue, firstDue, time.now(), tenantId, alarmId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForHandled(String tenantId, String alarmId) {
        cancelForAlarm(tenantId, alarmId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileTenant(String tenantId) {
        var rows = jdbc.queryForList("SELECT s.alarm_id,s.period_hours,a.occurred_at FROM alarm_reminder_schedule s JOIN alarms a ON a.id=s.alarm_id AND a.tenant_id=s.tenant_id WHERE s.tenant_id=? AND s.status='WAITING' FOR UPDATE", tenantId);
        for (var row : rows) {
            String alarmId = String.valueOf(row.get("alarm_id"));
            var delivery = configuration.deliveryPolicy(tenantId, "FIRE", "SMS");
            int period = delivery.pushPeriodHours();
            if (!delivery.enabled() || !delivery.receiveAlarm() || !delivery.pushMessage() || period < 1) {
                cancelForAlarm(tenantId, alarmId);
                continue;
            }
            if (period != ((Number) row.get("period_hours")).intValue()) {
                OffsetDateTime occurred = (OffsetDateTime) row.get("occurred_at");
                OffsetDateTime next = occurred.plusHours(period);
                jdbc.update("UPDATE alarm_reminder_schedule SET period_hours=?,cycle_started_at=?,next_due_at=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND alarm_id=? AND status='WAITING'",
                        period, next, next, time.now(), tenantId, alarmId);
            }
        }
    }

    @Scheduled(initialDelayString = "${fire.alarm-reminder-initial-delay-ms:30000}", fixedDelayString = "${fire.alarm-reminder-scan-ms:15000}")
    @Transactional
    public int processDue() {
        List<java.util.Map<String, Object>> due = jdbc.queryForList("SELECT s.tenant_id,s.alarm_id,s.cycle_number,s.cycle_started_at,s.period_hours,a.response_state,a.occurred_at FROM (SELECT tenant_id,alarm_id,cycle_number,cycle_started_at,period_hours,next_due_at FROM alarm_reminder_schedule WHERE status='WAITING' AND next_due_at<=" + time.expression() + " ORDER BY next_due_at,tenant_id,alarm_id LIMIT 100 FOR UPDATE SKIP LOCKED) s LEFT JOIN alarms a ON a.id=s.alarm_id AND a.tenant_id=s.tenant_id ORDER BY s.next_due_at,s.tenant_id,s.alarm_id");
        int accepted = 0;
        for (var row : due) {
            String tenantId = String.valueOf(row.get("tenant_id"));
            String alarmId = String.valueOf(row.get("alarm_id"));
            if (row.get("response_state") == null || "HANDLED".equals(row.get("response_state"))) {
                cancelForAlarm(tenantId, alarmId);
                continue;
            }
            var policy = configuration.deliveryPolicy(tenantId, "FIRE", "SMS");
            int period = policy.pushPeriodHours();
            if (!policy.enabled() || !policy.receiveAlarm() || !policy.pushMessage() || period < 1) {
                cancelForAlarm(tenantId, alarmId);
                continue;
            }
            if (period != ((Number) row.get("period_hours")).intValue()) {
                OffsetDateTime occurred = (OffsetDateTime) row.get("occurred_at");
                OffsetDateTime rescheduled = occurred.plusHours(period);
                jdbc.update("UPDATE alarm_reminder_schedule SET period_hours=?,cycle_started_at=?,next_due_at=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND alarm_id=? AND status='WAITING'",
                        period, rescheduled, rescheduled, time.now(), tenantId, alarmId);
                continue;
            }
            long cycle = ((Number) row.get("cycle_number")).longValue();
            OffsetDateTime cycleStarted = (OffsetDateTime) row.get("cycle_started_at");
            var outcome = notifications.runDueReminder(tenantId, alarmId, cycle, cycleStarted);
            if (outcome.complete()) {
                var now = time.now();
                OffsetDateTime nextDue = now.plusHours(period);
                int changed = jdbc.update("UPDATE alarm_reminder_schedule SET cycle_number=cycle_number+1,last_accepted_at=COALESCE(?,last_accepted_at),cycle_started_at=?,next_due_at=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND alarm_id=? AND status='WAITING' AND cycle_number=?",
                        outcome.lastAcceptedAt(), nextDue, nextDue, now, tenantId, alarmId, cycle);
                if (changed == 1 && outcome.lastAcceptedAt() != null) accepted++;
            }
        }
        return accepted;
    }

    private void cancelForAlarm(String tenantId, String alarmId) {
        jdbc.update("UPDATE alarm_reminder_schedule SET status='CANCELLED',revision=revision+1,updated_at=? WHERE tenant_id=? AND alarm_id=? AND status='WAITING'", time.now(), tenantId, alarmId);
    }
}
