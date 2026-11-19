package com.fireplatform.maintenance;

import java.time.OffsetDateTime;
import java.util.List;

/** Immutable maintenance projections. Status vocabulary is local work-order review, not device repair truth. */
public final class MaintenanceModels {
    private MaintenanceModels() { }

    public static final long MAX_SAFE = 9_007_199_254_740_991L;

    public record OrderSummary(String id, String tenantId, String deviceId, String deviceName, String deviceAddress,
                               String faultEventId, String title, String description, String priority,
                               OffsetDateTime dueAt, String status, String assigneeUserId, String assigneeName,
                               long revision, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                               OffsetDateTime closedAt, OffsetDateTime cancelledAt) { }

    public record Transition(String id, String action, String fromStatus, String toStatus, String actorId,
                             String actorName, String reason, String assigneeUserId, Long revisionBefore,
                             Long revisionAfter, String workLogId, OffsetDateTime createdAt) { }

    public record WorkLog(String id, String actorId, String actorName, String note, String result,
                          String replacementDeviceId, String replacementDeviceName, OffsetDateTime createdAt) { }

    public record Replacement(String id, String workLogId, String sourceDeviceId, String replacementDeviceId,
                              String replacementDeviceName, String createdBy, OffsetDateTime createdAt) { }

    public record OrderDetail(OrderSummary order, List<Transition> timeline, List<WorkLog> workLogs,
                              List<Replacement> replacements) {
        public OrderDetail {
            timeline = List.copyOf(timeline);
            workLogs = List.copyOf(workLogs);
            replacements = List.copyOf(replacements);
        }
    }

    public record Assignee(String userId, String username, String name) { }

    public record OrderPage(List<OrderSummary> items, long total, int page, int size) { }
}
