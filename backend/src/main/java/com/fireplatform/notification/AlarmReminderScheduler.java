package com.fireplatform.notification;

import com.fireplatform.configuration.AlarmBaseConfigurationQueries;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.shared.ReminderScheduling;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Schedules periodic reminders for still-unhandled local alarms; it never changes alarm state. */
@Service
public class AlarmReminderScheduler implements ReminderScheduling {
    private static final int SCAN_PAGE_SIZE = 100;
    private final JdbcTemplate jdbc;
    private final AlarmBaseConfigurationQueries configuration;
    private final RealNotificationService notifications;
    private final DatabaseTime time;
    // Scheduled traversal only; changing pages does not change any reminder's due time or cycle.
    private DuePosition dueAfter;
    private record DuePosition(OffsetDateTime dueAt,String tenantId,String alarmId) { }

    public AlarmReminderScheduler(JdbcTemplate jdbc, AlarmBaseConfigurationQueries configuration,
            RealNotificationService notifications, DatabaseTime time) {
        this.jdbc = jdbc;
        this.configuration = configuration;
        this.notifications = notifications;
        this.time = time;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(String tenantId, String alarmId, OffsetDateTime occurredAt) {
        var identity=jdbc.queryForMap("""
            SELECT a.device_id,d.model FROM alarms a JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id
            WHERE a.tenant_id=? AND a.id=?
            """,tenantId,alarmId);
        var delivery=reminderPolicy(tenantId,(String)identity.get("device_id"),(String)identity.get("model"));
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
        var rows = jdbc.queryForList("""
            SELECT s.alarm_id,s.period_hours,a.occurred_at,a.device_id,d.model
            FROM (SELECT tenant_id,alarm_id,period_hours FROM alarm_reminder_schedule
                  WHERE tenant_id=? AND status='WAITING' ORDER BY alarm_id FOR UPDATE) s
            JOIN alarms a ON a.id=s.alarm_id AND a.tenant_id=s.tenant_id
            JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id
            """, tenantId);
        for (var row : rows) {
            String alarmId = String.valueOf(row.get("alarm_id"));
            var delivery = reminderPolicy(tenantId,(String)row.get("device_id"),(String)row.get("model"));
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
        var due=duePage(dueAfter);
        if (due.isEmpty() && dueAfter!=null) due=duePage(null);
        // Use the selected keys, not the due dates after processing/rescheduling the rows.
        if (due.size()==SCAN_PAGE_SIZE) {
            var last=due.getLast();
            dueAfter=new DuePosition((OffsetDateTime)last.get("next_due_at"),(String)last.get("tenant_id"),(String)last.get("alarm_id"));
        } else {
            dueAfter=null;
        }
        int accepted = 0;
        for (var row : due) {
            String tenantId = String.valueOf(row.get("tenant_id"));
            String alarmId = String.valueOf(row.get("alarm_id"));
            if (row.get("response_state") == null || "HANDLED".equals(row.get("response_state")) || row.get("model")==null) {
                cancelForAlarm(tenantId, alarmId);
                continue;
            }
            var policy = reminderPolicy(tenantId,(String)row.get("device_id"),(String)row.get("model"));
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

    private List<Map<String,Object>> duePage(DuePosition after) {
        String select="""
            SELECT s.tenant_id,s.alarm_id,s.cycle_number,s.cycle_started_at,s.period_hours,s.next_due_at,
                   a.response_state,a.occurred_at,a.device_id,d.model
            FROM (SELECT tenant_id,alarm_id,cycle_number,cycle_started_at,period_hours,next_due_at
                  FROM alarm_reminder_schedule WHERE status='WAITING' AND next_due_at<=
            """+time.expression();
        String orderAndLock="""
             ORDER BY next_due_at,tenant_id,alarm_id LIMIT ? FOR UPDATE SKIP LOCKED) s
             LEFT JOIN alarms a ON a.id=s.alarm_id AND a.tenant_id=s.tenant_id
             LEFT JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id
             ORDER BY s.next_due_at,s.tenant_id,s.alarm_id
            """;
        return after==null
            ? jdbc.queryForList(select+orderAndLock,SCAN_PAGE_SIZE)
            : jdbc.queryForList(select+" AND (next_due_at,tenant_id,alarm_id)>(?,?,?)"+orderAndLock,
                after.dueAt(),after.tenantId(),after.alarmId(),SCAN_PAGE_SIZE);
    }

    private AlarmBaseConfigurationQueries.DeliveryPolicy reminderPolicy(String tenantId,String deviceId,String model) {
        var rule=configuration.effectiveForDevice(tenantId,deviceId,"FIRE");
        if (!rule.subscribed(model,"FIRE")) return new AlarmBaseConfigurationQueries.DeliveryPolicy(false,false,false,0);
        return rule.deliveryPolicy("SMS");
    }

    private void cancelForAlarm(String tenantId, String alarmId) {
        jdbc.update("UPDATE alarm_reminder_schedule SET status='CANCELLED',revision=revision+1,updated_at=? WHERE tenant_id=? AND alarm_id=? AND status='WAITING'", time.now(), tenantId, alarmId);
    }
}
