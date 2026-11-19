package com.fireplatform.place;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * Customer-safe own-unit position management. Every route fixes the tenant to the customer session
 * unit and requires explicit current {@code units:read}/{@code units:write}; cross-unit transfer,
 * archive restore and management-only relations are not exposed.
 */
@RestController
public class CustomerPlaceController {
    public record ScopePlaceNode(String id, String tenantId, String tenantName, String name, String parentId,
                                 String legacyPlaceId, long deviceCount, int level, long revision,
                                 ScopeTreeQueries.ParentContext parentContext) { }
    public record UnitNode(String id, String name, List<ScopePlaceNode> places) { }
    private final RequestSecurity security;
    private final PlaceQueries queries;
    private final PlaceCommands commands;
    private final JdbcTemplate jdbc;

    public CustomerPlaceController(RequestSecurity security, PlaceQueries queries, PlaceCommands commands, JdbcTemplate jdbc) {
        this.security = security; this.queries = queries; this.commands = commands; this.jdbc = jdbc;
    }

    private static void only(HttpServletRequest request, String... allowed) {
        var names = java.util.Set.of(allowed);
        if (request.getParameterMap().entrySet().stream().anyMatch(entry -> !names.contains(entry.getKey()) || entry.getValue().length != 1))
            throw ApiError.bad("位置操作参数无效或重复");
    }

    @GetMapping("/api/customer/scope-tree")
    public List<UnitNode> scopeTree(HttpServletRequest request) {
        var actor = security.require(request, "units:read", "customer");
        String tenant = security.tenant(request);
        only(request);
        String name = jdbc.queryForObject("SELECT name FROM tenants WHERE id=?", String.class, tenant);
        var places = queries.placesForTenant(actor.user().id(), tenant).stream()
            .map(place -> new ScopePlaceNode(place.id(), place.tenantId(), place.tenantName(), place.name(), place.parentId(),
                place.legacyPlaceId(), place.deviceCount(), place.level(), place.revision(), null))
            .toList();
        return List.of(new UnitNode(tenant, name, places));
    }

    @GetMapping("/api/customer/places")
    public List<PlaceQueries.PlaceNode> list(HttpServletRequest request) {
        var actor = security.require(request, "units:read", "customer");
        String tenant = security.tenant(request);
        only(request);
        return queries.placesForTenant(actor.user().id(), tenant);
    }

    @PostMapping("/api/customer/places")
    public PlaceQueries.PlaceNode create(@Valid @RequestBody PlaceInputs.Create body, HttpServletRequest request) {
        security.require(request, "units:read", "customer");
        var actor = security.require(request, "units:write", "customer");
        only(request);
        return commands.create(actor, security.tenant(request), body, RequestContext.requestId(request), "customer");
    }

    @PutMapping("/api/customer/places/{id}")
    public PlaceQueries.PlaceNode update(@PathVariable String id, @Valid @RequestBody PlaceInputs.Update body, HttpServletRequest request) {
        security.require(request, "units:read", "customer");
        var actor = security.require(request, "units:write", "customer");
        only(request);
        return commands.update(actor, security.tenant(request), id, body, RequestContext.requestId(request), "customer");
    }

    @DeleteMapping("/api/customer/places/{id}")
    public void delete(@PathVariable String id, @Valid @RequestBody PlaceInputs.Delete body, HttpServletRequest request) {
        security.require(request, "units:read", "customer");
        var actor = security.require(request, "units:write", "customer");
        only(request);
        commands.delete(actor, security.tenant(request), id, body, RequestContext.requestId(request), "customer");
    }

    @GetMapping("/api/customer/places/{id}/move-preview")
    public PlaceCommands.MovePreview movePreview(@PathVariable String id,
                                                 @RequestParam(name = "target_parent_id", required = false) String target,
                                                 HttpServletRequest request) {
        security.require(request, "units:write", "customer");
        var actor = security.require(request, "units:read", "customer");
        only(request, "target_parent_id");
        return commands.movePreview(actor, security.tenant(request), id, target, "customer");
    }

    @PostMapping("/api/customer/places/{id}/move")
    public PlaceCommands.MoveResult move(@PathVariable String id,
                                         @RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody PlaceInputs.Move body,
                                         HttpServletRequest request) {
        security.require(request, "units:read", "customer");
        var actor = security.require(request, "units:write", "customer");
        only(request);
        return commands.move(actor, security.tenant(request), id, body, key, RequestContext.requestId(request), "customer");
    }
}
