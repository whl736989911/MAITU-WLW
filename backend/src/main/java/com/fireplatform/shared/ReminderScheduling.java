package com.fireplatform.shared;

import java.time.OffsetDateTime;

/** Port for the local reminder schedule. Implemented by the notification slice so alarm/configuration stay independent of it. */
public interface ReminderScheduling {
    void schedule(String tenantId, String alarmId, OffsetDateTime occurredAt);
    void cancelForHandled(String tenantId, String alarmId);
    void reconcileTenant(String tenantId);
}
