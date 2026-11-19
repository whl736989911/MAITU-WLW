package com.fireplatform.maintenance;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController
public class MaintenanceController {
    private static final String BASE = "/api/{audience:management|customer}/maintenance/work-orders";
    private static final Set<String> LIST_PARAMS = Set.of("page", "size", "status", "keyword", "device_id",
        "scope_selected", "scope_tenant_ids", "scope_place_ids");

    private final RequestSecurity security;
    private final MaintenanceQueries queries;
    private final MaintenanceCommands commands;

    public MaintenanceController(RequestSecurity security, MaintenanceQueries queries, MaintenanceCommands commands) {
        this.security = security; this.queries = queries; this.commands = commands;
    }

    @GetMapping(BASE)
    public MaintenanceModels.OrderPage page(@PathVariable String audience,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String device_id,
                                            HttpServletRequest request) {
        var actor = security.require(request, "maintenance:read", audience);
        only(request, LIST_PARAMS);
        return queries.page(actor.user().id(), security.optionalTenant(request), security.readSelection(request),
            status, keyword, device_id, page, size);
    }

    @GetMapping(BASE + "/{id}")
    public MaintenanceModels.OrderDetail detail(@PathVariable String audience, @PathVariable String id,
                                                HttpServletRequest request) {
        var actor = security.require(request, "maintenance:read", audience);
        only(request, Set.of("scope_selected", "scope_tenant_ids", "scope_place_ids"));
        return queries.detail(actor.user().id(), security.optionalTenant(request), security.readSelection(request), id);
    }

    @GetMapping(BASE + "/{id}/assignee-candidates")
    public List<MaintenanceModels.Assignee> assigneeCandidates(@PathVariable String audience, @PathVariable String id,
                                                               @RequestParam(required = false) String keyword,
                                                               HttpServletRequest request) {
        var actor = security.require(request, "maintenance:read", audience);
        security.require(request, "maintenance:assign", audience);
        only(request, Set.of("keyword", "scope_selected", "scope_tenant_ids", "scope_place_ids"));
        var detail = queries.detail(actor.user().id(), security.optionalTenant(request), security.readSelection(request), id);
        return queries.assigneeCandidates(detail.order().tenantId(), detail.order().deviceId(), keyword);
    }

    @PostMapping(BASE)
    public MaintenanceModels.OrderDetail create(@PathVariable String audience,
                                                @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody MaintenanceInputs.CreateBody body,
                                                HttpServletRequest request) {
        var actor = security.require(request, "maintenance:write", audience);
        String tenant = security.tenant(request);
        return commands.create(actor, tenant, key, MaintenanceInputs.create(body), RequestContext.requestId(request), false);
    }

    @PostMapping(BASE + "/{id}/open")
    public MaintenanceModels.OrderDetail open(@PathVariable String audience, @PathVariable String id,
                                              @RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody MaintenanceInputs.VersionedBody body,
                                              HttpServletRequest request) {
        var actor = security.require(request, "maintenance:write", audience);
        String tenant = security.tenant(request);
        return commands.open(actor, tenant, id, key, MaintenanceInputs.versioned(body), RequestContext.requestId(request));
    }

    @PostMapping(BASE + "/{id}/assign")
    public MaintenanceModels.OrderDetail assign(@PathVariable String audience, @PathVariable String id,
                                                @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody MaintenanceInputs.AssignBody body,
                                                HttpServletRequest request) {
        var actor = security.require(request, "maintenance:assign", audience);
        String tenant = security.tenant(request);
        return commands.assign(actor, tenant, id, key, MaintenanceInputs.assign(body), RequestContext.requestId(request));
    }

    @PostMapping(BASE + "/{id}/work-logs")
    public MaintenanceModels.OrderDetail workLog(@PathVariable String audience, @PathVariable String id,
                                                 @RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody MaintenanceInputs.WorkLogBody body,
                                                 HttpServletRequest request) {
        var actor = security.require(request, "maintenance:write", audience);
        String tenant = security.tenant(request);
        return commands.workLog(actor, tenant, id, key, MaintenanceInputs.workLog(body), RequestContext.requestId(request));
    }

    @PostMapping(BASE + "/{id}/submit-review")
    public MaintenanceModels.OrderDetail submitReview(@PathVariable String audience, @PathVariable String id,
                                                      @RequestHeader("Idempotency-Key") String key,
                                                      @Valid @RequestBody MaintenanceInputs.VersionedBody body,
                                                      HttpServletRequest request) {
        var actor = security.require(request, "maintenance:write", audience);
        String tenant = security.tenant(request);
        return commands.submitReview(actor, tenant, id, key, MaintenanceInputs.versioned(body), RequestContext.requestId(request));
    }

    @PostMapping(BASE + "/{id}/review")
    public MaintenanceModels.OrderDetail review(@PathVariable String audience, @PathVariable String id,
                                                @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody MaintenanceInputs.ReviewBody body,
                                                HttpServletRequest request) {
        var actor = security.require(request, "maintenance:review", audience);
        String tenant = security.tenant(request);
        return commands.review(actor, tenant, id, key, MaintenanceInputs.review(body), RequestContext.requestId(request));
    }

    @PostMapping(BASE + "/{id}/reopen")
    public MaintenanceModels.OrderDetail reopen(@PathVariable String audience, @PathVariable String id,
                                                @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody MaintenanceInputs.VersionedBody body,
                                                HttpServletRequest request) {
        var actor = security.require(request, "maintenance:write", audience);
        String tenant = security.tenant(request);
        return commands.reopen(actor, tenant, id, key, MaintenanceInputs.versioned(body), RequestContext.requestId(request));
    }

    @PostMapping(BASE + "/{id}/cancel")
    public MaintenanceModels.OrderDetail cancel(@PathVariable String audience, @PathVariable String id,
                                                @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody MaintenanceInputs.VersionedBody body,
                                                HttpServletRequest request) {
        var actor = security.require(request, "maintenance:write", audience);
        String tenant = security.tenant(request);
        return commands.cancel(actor, tenant, id, key, MaintenanceInputs.versioned(body), RequestContext.requestId(request));
    }

    private static void only(HttpServletRequest request, Set<String> allowed) {
        for (var entry : request.getParameterMap().entrySet())
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
                throw ApiError.bad("不支持的查询参数");
    }
}
