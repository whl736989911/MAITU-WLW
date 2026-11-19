package com.fireplatform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.deviceservice.DeviceServiceCommandService;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Digests;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only bridge from resource-linkage evaluation into the durable device-service queue. It requires an
 * enabled controller association and delegates every capability/gate risk check to the core seam; it never
 * creates a command itself and never treats QUEUED as a physical effect.
 */
@Service
public class ControllerAutomation {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ControllerResourceQueries queries;
    private final DeviceServiceCommandService commands;

    public ControllerAutomation(JdbcTemplate jdbc, ObjectMapper json, ControllerResourceQueries queries,
                                DeviceServiceCommandService commands) {
        this.jdbc = jdbc; this.json = json; this.queries = queries; this.commands = commands;
    }

    public List<ControllerModels.Resource> controllersFor(String tenant, String action, List<String> controllerIds) {
        return queries.activeForAction(tenant, action, controllerIds);
    }

    public List<ControllerModels.Resource> enabledControllersOn(String tenant, String deviceId) {
        return queries.enabledControllers(tenant, deviceId);
    }

    /**
     * Joins the caller's transaction. Any expected capability/risk refusal is returned as a specific blocked
     * result so the linkage outcome can record it; unexpected storage failures still roll back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ControllerModels.Enqueue enqueue(String tenant, ControllerModels.Resource controller, String action,
                                            String reason, String sourceKey) {
        if (!"ACTIVE".equals(controller.state()))
            return blocked(controller, action, "CONTROLLER_RETIRED", "控制器关联已停用");
        if (!controller.enabled())
            return blocked(controller, action, "CONTROLLER_DISABLED", "控制器关联未启用");
        if (!controller.action().equals(action))
            return blocked(controller, action, "CONTROLLER_ACTION_MISMATCH",
                "控制器关联动作与联动动作不一致");
        String deviceId = jdbc.query("SELECT device_id FROM controller_resources WHERE id=? AND tenant_id=?",
            (rs, n) -> rs.getString(1), controller.id(), tenant).stream().findFirst().orElse(null);
        if (deviceId == null)
            return blocked(controller, action, "CONTROLLER_NOT_FOUND", "控制器关联不存在");
        var device = jdbc.queryForList("SELECT archived_at FROM devices WHERE id=? AND tenant_id=?",
            java.time.OffsetDateTime.class, deviceId, tenant);
        if (device.isEmpty()) return blocked(controller, action, "DEVICE_NOT_IN_TENANT", "控制器设备不存在");
        if (device.getFirst() != null) return blocked(controller, action, "DEVICE_ARCHIVED", "控制器设备已归档");
        String argumentsJson = jdbc.queryForObject("SELECT arguments_json FROM controller_resources WHERE id=? AND tenant_id=?",
            String.class, controller.id(), tenant);
        com.fasterxml.jackson.databind.JsonNode arguments;
        try {
            arguments = json.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception unreadable) {
            return blocked(controller, action, "INVALID_ARGUMENTS", "控制器保存的参数无法读取");
        }
        String source = "RESOURCE_LINKAGE:" + sourceKey;
        if (source.length() > 200) source = source.substring(0, 200);
        String idemKey = "linkage." + Digests.sha256(tenant + "|" + sourceKey + "|" + controller.id() + "|" + action);
        try {
            var result = commands.enqueueAutomation(new DeviceServiceCommandService.AutomationEnqueue(
                tenant, deviceId, controller.serviceKey(), arguments, reason, source, idemKey));
            if (result.blockedCode() != null)
                return blocked(controller, action, result.blockedCode(), result.blockedReason());
            return new ControllerModels.Enqueue(controller.id(), action, deviceId, controller.serviceKey(),
                result.commandId(), result.state(), null, null);
        } catch (ApiError refused) {
            if (refused.status == 400 || refused.status == 409)
                return blocked(controller, action, refused.code, refused.getMessage());
            throw refused;
        }
    }

    private static ControllerModels.Enqueue blocked(ControllerModels.Resource controller, String action,
                                                    String code, String reason) {
        return new ControllerModels.Enqueue(controller.id(), action, controller.deviceId(), controller.serviceKey(),
            null, null, code, reason);
    }
}
