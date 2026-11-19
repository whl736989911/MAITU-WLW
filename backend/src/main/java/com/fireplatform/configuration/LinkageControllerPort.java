package com.fireplatform.configuration;

import java.util.List;

/**
 * Outbound seam owned by alarm-policy evaluation. Configuration resolves enabled controller associations and
 * records durable device-service commands through this port; it never imports the controller hardware module.
 *
 * <p>The implementation joins the caller's transaction and reports an honest blocked outcome instead of
 * claiming a physical effect. A queued command is not a confirmed action.</p>
 */
public interface LinkageControllerPort {
    /** Minimal association identity needed to evaluate and record one linkage action. */
    record Target(String id, String action, String deviceId, String serviceKey) { }

    /** One durable enqueue attempt; a null {@code commandId} means nothing was queued. */
    record Enqueue(String commandId, String deviceId, String serviceKey, String state, String blockedCode,
                   String blockedReason) { }

    List<Target> controllersFor(String tenant, String action, List<String> controllerIds);

    List<Target> enabledControllersOn(String tenant, String deviceId);

    Enqueue enqueue(String tenant, Target controller, String action, String reason, String sourceKey);
}
