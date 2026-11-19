package com.fireplatform.configuration;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/management")
public class BatteryThresholdController {
    private final RequestSecurity security;
    private final BatteryThresholdQueries queries;
    private final BatteryThresholdCommands commands;

    public BatteryThresholdController(RequestSecurity security, BatteryThresholdQueries queries, BatteryThresholdCommands commands) {
        this.security = security;
        this.queries = queries;
        this.commands = commands;
    }

    @GetMapping("/metric-parameter-definitions")
    public Map<String, List<BatteryThresholdModels.Definition>> definitions(HttpServletRequest request) {
        AuthService.Identity actor = security.identity(request);
        if (!"management".equals(actor.audience()) || !"PLATFORM".equals(actor.user().userType()) ||
            request.getHeader("X-Tenant-Id") != null ||
            !(actor.permissions().contains("parameters:read") || actor.permissions().contains("products:read"))) {
            throw ApiError.forbidden("没有此操作权限");
        }
        return Map.of("items", queries.definitions());
    }

    @GetMapping("/devices/{id}/battery-low-threshold")
    public BatteryThresholdModels.View view(@PathVariable String id, HttpServletRequest request) {
        AuthService.Identity actor = security.require(request, "parameters:read", "management");
        return queries.view(actor.user().id(), security.tenant(request), id);
    }

    @GetMapping("/devices/{id}/battery-low-threshold/history")
    public com.fireplatform.shared.Page<BatteryThresholdModels.Change> history(
        @PathVariable String id, @RequestParam(defaultValue="1") int page,
        @RequestParam(required=false) Integer size, HttpServletRequest request) {
        AuthService.Identity actor = security.require(request, "parameters:read", "management");
        return queries.history(actor.user().id(), security.tenant(request), id, page, size);
    }

    @PutMapping("/devices/{id}/battery-low-threshold")
    public BatteryThresholdModels.Mutation put(@PathVariable String id,
        @RequestHeader(value="Idempotency-Key", required=false) String key,
        @Valid @RequestBody BatteryThresholdInputs.SetOverride body, HttpServletRequest request) {
        security.require(request, "parameters:read", "management");
        AuthService.Identity actor = security.require(request, "parameters:write", "management");
        return commands.put(actor, security.tenant(request), id, key, body, RequestContext.requestId(request));
    }
}
