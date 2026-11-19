package com.fireplatform.alarm;

/** Durable alarm/change data committed; consumers must treat this only as an invalidation/dispatch hint. */
public record AlarmApplicationChangeCommitted(String tenantId, String alarmId, String traceId) { }
