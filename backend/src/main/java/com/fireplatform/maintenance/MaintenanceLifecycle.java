package com.fireplatform.maintenance;

import java.util.Set;

/** Explicit local work-order transitions. Nothing here changes the source fault, device or vendor state. */
public final class MaintenanceLifecycle {
    private MaintenanceLifecycle() { }

    public static final Set<String> STATUSES = Set.of("DRAFT", "OPEN", "ASSIGNED", "IN_PROGRESS", "PENDING_REVIEW", "CLOSED", "CANCELLED");

    public static boolean canOpen(String status) { return "DRAFT".equals(status); }
    public static boolean canAssign(String status) { return Set.of("OPEN", "ASSIGNED", "IN_PROGRESS").contains(status); }
    public static boolean canWorkLog(String status) { return Set.of("OPEN", "ASSIGNED", "IN_PROGRESS").contains(status); }
    public static boolean canSubmitReview(String status) { return "IN_PROGRESS".equals(status); }
    public static boolean canReview(String status) { return "PENDING_REVIEW".equals(status); }
    public static boolean canReopen(String status) { return "CLOSED".equals(status); }
    public static boolean canCancel(String status) { return Set.of("DRAFT", "OPEN", "ASSIGNED", "IN_PROGRESS", "PENDING_REVIEW").contains(status); }

    /** Assignment preserves an order already in progress; otherwise it becomes assigned. */
    public static String afterAssign(String status) { return "IN_PROGRESS".equals(status) ? "IN_PROGRESS" : "ASSIGNED"; }
    public static String afterWorkLog() { return "IN_PROGRESS"; }
    public static String afterReview(boolean pass) { return pass ? "CLOSED" : "IN_PROGRESS"; }
    public static String afterOpen() { return "OPEN"; }
    public static String afterReopen() { return "OPEN"; }
    public static String afterCancel() { return "CANCELLED"; }

    public static boolean terminal(String status) { return "CLOSED".equals(status) || "CANCELLED".equals(status); }
    public static boolean active(String status) { return !terminal(status); }
}
