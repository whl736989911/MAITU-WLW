package com.fireplatform.controller;

import com.fireplatform.configuration.LinkageControllerPort;
import com.fireplatform.shared.ApiError;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Implements the configuration-owned linkage seam. Alarm-policy evaluation stays unaware of controller
 * models; this adapter resolves the live association and delegates every capability/risk check to
 * {@link ControllerAutomation}. A queued command is never reported as a physical effect.
 */
@Component
public class ControllerLinkagePort implements LinkageControllerPort {
    private final ControllerAutomation automation;
    private final ControllerResourceQueries queries;

    public ControllerLinkagePort(ControllerAutomation automation, ControllerResourceQueries queries) {
        this.automation = automation;
        this.queries = queries;
    }

    @Override
    public List<Target> controllersFor(String tenant, String action, List<String> controllerIds) {
        return automation.controllersFor(tenant, action, controllerIds).stream()
            .map(ControllerLinkagePort::target).toList();
    }

    @Override
    public List<Target> enabledControllersOn(String tenant, String deviceId) {
        return automation.enabledControllersOn(tenant, deviceId).stream()
            .map(ControllerLinkagePort::target).toList();
    }

    @Override
    public Enqueue enqueue(String tenant, Target controller, String action, String reason, String sourceKey) {
        ControllerModels.Resource resource;
        try {
            resource = queries.byId(tenant, controller.id());
        } catch (ApiError missing) {
            if (missing.status == 404)
                return new Enqueue(null, controller.deviceId(), controller.serviceKey(), null,
                    "CONTROLLER_NOT_FOUND", "控制器关联不存在");
            throw missing;
        }
        var result = automation.enqueue(tenant, resource, action, reason, sourceKey);
        return new Enqueue(result.commandId(), result.deviceId(), result.serviceKey(), result.state(),
            result.blockedCode(), result.blockedReason());
    }

    private static Target target(ControllerModels.Resource resource) {
        return new Target(resource.id(), resource.action(), resource.deviceId(), resource.serviceKey());
    }
}
