package com.fireplatform.device;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/**
 * Narrow customer self-service for ordinary own-unit asset metadata, device creation, within-unit moves
 * and lifecycle history. The tenant is always the server-side customer session unit, never a header or
 * request body; vendor credentials, product publishing, engineering controls and cross-unit transfers
 * are not reachable here.
 */
@RestController
@RequestMapping("/api/customer/devices")
public class CustomerDeviceAssetController {
    private final RequestSecurity security;
    private final DeviceManagementService commands;
    private final DeviceQueryService queries;

    public CustomerDeviceAssetController(RequestSecurity security, DeviceManagementService commands, DeviceQueryService queries) {
        this.security = security; this.commands = commands; this.queries = queries;
    }

    private static void only(HttpServletRequest request, String... allowed) {
        var names = java.util.Set.of(allowed);
        if (request.getParameterMap().entrySet().stream().anyMatch(entry -> !names.contains(entry.getKey()) || entry.getValue().length != 1))
            throw ApiError.bad("设备操作参数无效或重复");
    }

    @PostMapping
    public DeviceQueryService.DeviceRow create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody DeviceInputs.Register body, HttpServletRequest request) {
        var actor = security.require(request, "device:write", "customer");
        only(request);
        return commands.register(actor, security.tenant(request), key, body, RequestContext.requestId(request), "customer");
    }

    @PutMapping("/{id}")
    public DeviceQueryService.DeviceRow edit(@PathVariable String id,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                             @Valid @RequestBody DeviceInputs.Edit body, HttpServletRequest request) {
        var actor = security.require(request, "device:write", "customer");
        only(request);
        return commands.edit(actor, security.tenant(request), id, key, body, RequestContext.requestId(request), "customer");
    }

    @PostMapping("/batch-move")
    public DeviceManagementService.BatchResult batchMove(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                         @Valid @RequestBody DeviceInputs.BatchMove body, HttpServletRequest request) {
        var actor = security.require(request, "device:write", "customer");
        only(request);
        return commands.batchMove(actor, security.tenant(request), key, body, RequestContext.requestId(request), "customer");
    }

    @GetMapping("/{id}/version-history")
    public Map<String, List<DeviceManagementService.History>> versionHistory(@PathVariable String id, HttpServletRequest request) {
        var actor = security.require(request, "device:read", "customer");
        only(request);
        return Map.of("items", commands.history(actor, security.tenant(request), id, "customer"));
    }

    @GetMapping("/{id}/place-history")
    public Page<DeviceQueryService.PlaceHistory> placeHistory(@PathVariable String id,
                                                              @RequestParam(defaultValue = "1") int page,
                                                              @RequestParam(required = false) Integer size,
                                                              HttpServletRequest request) {
        var actor = security.require(request, "device:read", "customer");
        only(request, "page", "size");
        return queries.placeHistory(actor.user().id(), security.tenant(request), id, page, size);
    }
}
