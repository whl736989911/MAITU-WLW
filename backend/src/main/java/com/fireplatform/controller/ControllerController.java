package com.fireplatform.controller;

import com.fireplatform.deviceservice.DeviceServiceCommandService;
import com.fireplatform.deviceservice.DeviceServiceModels;
import com.fireplatform.deviceservice.DeviceServiceQueryService;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

/**
 * Controller association management. Reads expose the live device capabilities and durable command history;
 * writes only reference real published services and never execute a physical action by themselves.
 */
@RestController
public class ControllerController {
    private static final String READ = "/api/{audience:management|customer}/controller-resources";
    private static final String WRITE = "/api/management/controller-resources";
    private static final Set<String> LIST_PARAMS = Set.of("page", "size", "keyword", "include_retired",
        "scope_selected", "scope_tenant_ids", "scope_place_ids");
    private static final Set<String> DETAIL_PARAMS = Set.of("scope_selected", "scope_tenant_ids", "scope_place_ids");

    public record CreateBody(@NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 64) String device_id,
                             @NotBlank @Size(max = 16) String action, @NotBlank @Size(max = 64) String service_key,
                             @Size(max = 16384) String arguments_json,
                             @NotBlank @Size(max = 1000) String reason) { }
    public record UpdateBody(@NotNull @Min(0) Long expected_revision, @NotBlank @Size(max = 200) String name,
                             @NotBlank @Size(max = 64) String service_key, @Size(max = 16384) String arguments_json,
                             @NotBlank @Size(max = 1000) String reason) { }
    public record EnabledBody(@NotNull @Min(0) Long expected_revision, @NotNull Boolean enabled,
                              @NotBlank @Size(max = 1000) String reason) { }
    public record VersionedBody(@NotNull @Min(0) Long expected_revision,
                                @NotBlank @Size(max = 1000) String reason) { }

    private final RequestSecurity security;
    private final ControllerResourceQueries queries;
    private final ControllerResourceCommands commands;
    private final DeviceServiceQueryService deviceServices;
    private final DeviceServiceCommandService deviceCommands;

    public ControllerController(RequestSecurity security, ControllerResourceQueries queries,
                                ControllerResourceCommands commands, DeviceServiceQueryService deviceServices,
                                DeviceServiceCommandService deviceCommands) {
        this.security = security; this.queries = queries; this.commands = commands;
        this.deviceServices = deviceServices; this.deviceCommands = deviceCommands;
    }

    @GetMapping(READ)
    public Page<ControllerModels.Resource> page(@PathVariable String audience, @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(required = false) Integer size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String include_retired,
                                                HttpServletRequest request) {
        var actor = security.require(request, "controller:read", audience);
        only(request, LIST_PARAMS);
        boolean includeRetired = "true".equals(include_retired);
        if (include_retired != null && !include_retired.isEmpty() && !includeRetired && !"false".equals(include_retired))
            throw ApiError.bad("停用筛选参数无效");
        return queries.page(actor.user().id(), security.tenant(request), security.readSelection(request),
            keyword, includeRetired, page, size);
    }

    @GetMapping(READ + "/{id}")
    public ControllerModels.Detail detail(@PathVariable String audience, @PathVariable String id,
                                          HttpServletRequest request) {
        var actor = security.require(request, "controller:read", audience);
        only(request, DETAIL_PARAMS);
        String tenant = security.tenant(request);
        var resource = queries.visible(actor.user().id(), tenant, security.readSelection(request), id);
        var capabilities = deviceServices.capabilities(actor.user().id(), tenant, resource.deviceId());
        Page<DeviceServiceModels.CommandSummary> commands = deviceCommands.page(actor.user().id(), tenant,
            resource.deviceId(), 1, 20, null);
        String note = resource.enabled()
            ? "仅当该操作仍为已发布、已验证的NORMAL类型化COMMAND且当前授权有效时，联动才可入队；入队不等于设备已执行"
            : "关联未启用；联动评估不会向设备服务队列写入命令";
        return new ControllerModels.Detail(resource, capabilities, commands, note);
    }

    @PostMapping(WRITE)
    public ControllerModels.Resource create(@RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody CreateBody body, HttpServletRequest request) {
        var actor = security.require(request, "controller:write", "management");
        return commands.create(actor, security.tenant(request), key,
            ControllerInputs.create(new ControllerInputs.CreateBody(body.name(), body.device_id(), body.action(),
                body.service_key(), body.arguments_json(), body.reason())),
            RequestContext.requestId(request));
    }

    @PutMapping(WRITE + "/{id}")
    public ControllerModels.Resource update(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody UpdateBody body, HttpServletRequest request) {
        var actor = security.require(request, "controller:write", "management");
        return commands.update(actor, security.tenant(request), id, key,
            ControllerInputs.update(new ControllerInputs.UpdateBody(body.expected_revision(), body.name(),
                body.service_key(), body.arguments_json(), body.reason())),
            RequestContext.requestId(request));
    }

    @PostMapping(WRITE + "/{id}/enabled")
    public ControllerModels.Resource enabled(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody EnabledBody body, HttpServletRequest request) {
        var actor = security.require(request, "controller:write", "management");
        return commands.setEnabled(actor, security.tenant(request), id, key,
            ControllerInputs.enabled(new ControllerInputs.EnabledBody(body.expected_revision(), body.enabled(), body.reason())),
            RequestContext.requestId(request));
    }

    @PostMapping(WRITE + "/{id}/retire")
    public ControllerModels.Resource retire(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody VersionedBody body, HttpServletRequest request) {
        var actor = security.require(request, "controller:write", "management");
        return commands.retire(actor, security.tenant(request), id, key,
            ControllerInputs.versioned(new ControllerInputs.VersionedBody(body.expected_revision(), body.reason())),
            RequestContext.requestId(request));
    }

    private static void only(HttpServletRequest request, Set<String> allowed) {
        for (var entry : request.getParameterMap().entrySet())
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
                throw ApiError.bad("不支持的查询参数");
    }
}
