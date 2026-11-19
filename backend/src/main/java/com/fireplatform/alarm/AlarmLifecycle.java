package com.fireplatform.alarm;

import java.time.OffsetDateTime;

/** Public inbound device-event port. Joins the existing transaction; it never commits separately. */
public interface AlarmLifecycle {
    String fire(String tenant,String deviceId,String deviceName,String address,OffsetDateTime occurredAt);
    boolean recoverAlarm(String tenant,String deviceId,String alarmId,OffsetDateTime occurredAt);
    void recoverDevice(String tenant,String deviceId);
    boolean hasActiveFire(String tenant,String deviceId);
}
