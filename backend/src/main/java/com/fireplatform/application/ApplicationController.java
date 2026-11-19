package com.fireplatform.application;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

/**
 * Open-application management API. Management audience only: applications are platform-configured unit-owned
 * records, not a customer self-service surface. Reads require {@code applications:read}; every write requires
 * {@code applications:write}. Raw output additionally requires
 * {@code applications:forward-raw} and {@code messages:raw}, rechecked at execution time.
 */
@RestController
public class ApplicationController {
    private static final String BASE = "/api/management/applications";
    private static final Set<String> LIST_PARAMS = Set.of("page", "size", "status", "keyword", "scope_selected",
        "scope_tenant_ids", "scope_place_ids");
    private static final Set<String> DELIVERY_PARAMS = Set.of("page", "size", "status", "output_id", "scope_selected",
        "scope_tenant_ids", "scope_place_ids");
    private static final Set<String> PAGE_PARAMS = Set.of("page", "size", "scope_selected", "scope_tenant_ids",
        "scope_place_ids");

    private final RequestSecurity security;
    private final ApplicationQueries queries;
    private final ApplicationCommands commands;
    private final ApplicationSecretStorage secrets;
    private final ApplicationEndpointPolicy endpoints;

    public ApplicationController(RequestSecurity security, ApplicationQueries queries, ApplicationCommands commands,
                                 ApplicationSecretStorage secrets, ApplicationEndpointPolicy endpoints) {
        this.security = security;
        this.queries = queries;
        this.commands = commands;
        this.secrets = secrets;
        this.endpoints = endpoints;
    }

    /** Deployment readiness and the caller's raw capability; never a secret and never an enabled default. */
    @GetMapping(BASE + "/capabilities")
    public Capabilities capabilities(HttpServletRequest request) {
        var actor = security.require(request, "applications:read", "management");
        only(request, Set.of());
        var permissions = actor.permissions();
        return new Capabilities(secrets.ready(), endpoints.allowlistConfigured(),
            permissions.contains("applications:forward-raw") && permissions.contains("messages:raw"));
    }

    /** Authoritative picker options for the three identity kinds; management unit context is explicit. */
    @GetMapping(BASE + "/reference-options")
    public ApplicationModels.ReferenceOptions referenceOptions(@RequestParam String kind,
                                                               @RequestParam(required = false) String source_platform,
                                                               @RequestParam(defaultValue = "1") int page,
                                                               @RequestParam(required = false) Integer size,
                                                               @RequestParam(defaultValue = "") String keyword,
                                                               HttpServletRequest request) {
        var actor = security.require(request, "applications:read", "management");
        only(request, Set.of("kind", "source_platform", "page", "size", "keyword"));
        return queries.referenceOptions(actor.user().id(), security.tenant(request), kind, source_platform, keyword,
            page, size);
    }

    @GetMapping(BASE)
    public ApplicationModels.ApplicationPage page(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(required = false) Integer size,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String keyword,
                                                  HttpServletRequest request) {
        var actor = security.require(request, "applications:read", "management");
        only(request, LIST_PARAMS);
        return queries.page(actor.user().id(), security.optionalTenant(request), security.readSelection(request),
            status, keyword, page, size);
    }

    @GetMapping(BASE + "/{id}")
    public ApplicationModels.ApplicationDetail detail(@PathVariable String id, HttpServletRequest request) {
        var actor = security.require(request, "applications:read", "management");
        only(request, Set.of("scope_selected", "scope_tenant_ids", "scope_place_ids"));
        return queries.detail(actor.user().id(), security.optionalTenant(request), ApplicationInputs.applicationId(id));
    }

    @GetMapping(BASE + "/{id}/events")
    public ApplicationModels.EventPage events(@PathVariable String id,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(required = false) Integer size,
                                              HttpServletRequest request) {
        var actor = security.require(request, "applications:read", "management");
        only(request, PAGE_PARAMS);
        return queries.events(actor.user().id(), security.optionalTenant(request),
            ApplicationInputs.applicationId(id), page, size);
    }

    @GetMapping(BASE + "/{id}/deliveries")
    public ApplicationModels.DeliveryPage deliveries(@PathVariable String id,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(required = false) Integer size,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String output_id,
                                                     HttpServletRequest request) {
        var actor = security.require(request, "applications:read", "management");
        only(request, DELIVERY_PARAMS);
        return queries.deliveries(actor.user().id(), security.optionalTenant(request),
            ApplicationInputs.applicationId(id), status, output_id, page, size);
    }

    @GetMapping(BASE + "/{id}/deliveries/{deliveryId}")
    public ApplicationModels.DeliveryDetail delivery(@PathVariable String id, @PathVariable String deliveryId,
                                                     HttpServletRequest request) {
        var actor = security.require(request, "applications:read", "management");
        only(request, Set.of("scope_selected", "scope_tenant_ids", "scope_place_ids"));
        return queries.delivery(actor.user().id(), security.optionalTenant(request),
            ApplicationInputs.applicationId(id), ApplicationInputs.deliveryId(deliveryId));
    }

    @PostMapping(BASE)
    public ApplicationModels.ApplicationDetail create(@RequestHeader("Idempotency-Key") String key,
                                                      @Valid @RequestBody ApplicationInputs.CreateBody body,
                                                      HttpServletRequest request) {
        var actor = security.require(request, "applications:write", "management");
        return commands.create(actor, security.tenant(request), key, ApplicationInputs.create(body),
            RequestContext.requestId(request));
    }

    @PutMapping(BASE + "/{id}")
    public ApplicationModels.ApplicationDetail update(@PathVariable String id,
                                                      @RequestHeader("Idempotency-Key") String key,
                                                      @Valid @RequestBody ApplicationInputs.UpdateBody body,
                                                      HttpServletRequest request) {
        var actor = security.require(request, "applications:write", "management");
        return commands.update(actor, security.tenant(request), ApplicationInputs.applicationId(id), key,
            ApplicationInputs.update(body), RequestContext.requestId(request));
    }

    @PostMapping(BASE + "/{id}/archive")
    public ApplicationModels.ApplicationDetail archive(@PathVariable String id,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody ApplicationInputs.ArchiveBody body,
                                                       HttpServletRequest request) {
        var actor = security.require(request, "applications:write", "management");
        return commands.archive(actor, security.tenant(request), ApplicationInputs.applicationId(id), key,
            ApplicationInputs.archive(body), RequestContext.requestId(request));
    }

    @PostMapping(BASE + "/{id}/authorizations")
    public ApplicationModels.ApplicationDetail grant(@PathVariable String id,
                                                     @RequestHeader("Idempotency-Key") String key,
                                                     @Valid @RequestBody ApplicationInputs.GrantBody body,
                                                     HttpServletRequest request) {
        var actor = security.require(request, "applications:write", "management");
        return commands.grant(actor, security.tenant(request), ApplicationInputs.applicationId(id), key,
            ApplicationInputs.grant(body), RequestContext.requestId(request));
    }

    @PostMapping(BASE + "/{id}/authorizations/{authorizationId}/revoke")
    public ApplicationModels.ApplicationDetail revoke(@PathVariable String id, @PathVariable String authorizationId,
                                                      @RequestHeader("Idempotency-Key") String key,
                                                      @Valid @RequestBody ApplicationInputs.RevokeBody body,
                                                      HttpServletRequest request) {
        var actor = security.require(request, "applications:write", "management");
        return commands.revoke(actor, security.tenant(request), ApplicationInputs.applicationId(id),
            ApplicationInputs.applicationId(authorizationId), key, ApplicationInputs.revoke(body),
            RequestContext.requestId(request));
    }

    @PostMapping(BASE + "/{id}/outputs")
    public ApplicationModels.ApplicationDetail createOutput(@PathVariable String id,
                                                            @RequestHeader("Idempotency-Key") String key,
                                                            @Valid @RequestBody ApplicationInputs.CreateOutputBody body,
                                                            HttpServletRequest request) {
        var actor = security.require(request, "applications:write", "management");
        return commands.createOutput(actor, security.tenant(request), ApplicationInputs.applicationId(id), key,
            ApplicationInputs.createOutput(body), RequestContext.requestId(request));
    }

    @PutMapping(BASE + "/{id}/outputs/{outputId}")
    public ApplicationModels.ApplicationDetail updateOutput(@PathVariable String id, @PathVariable String outputId,
                                                            @RequestHeader("Idempotency-Key") String key,
                                                            @Valid @RequestBody ApplicationInputs.UpdateOutputBody body,
                                                            HttpServletRequest request) {
        var actor = security.require(request, "applications:write", "management");
        String tenant = security.tenant(request);
        String applicationId = ApplicationInputs.applicationId(id);
        String fixedMode = queries.outputMode(tenant, applicationId, ApplicationInputs.outputId(outputId));
        String fixedProtocol = queries.outputProtocol(tenant, applicationId, ApplicationInputs.outputId(outputId));
        return commands.updateOutput(actor, tenant, applicationId, ApplicationInputs.outputId(outputId), key,
            ApplicationInputs.updateOutput(body, fixedMode, fixedProtocol), RequestContext.requestId(request));
    }

    public record Capabilities(boolean encryptionReady, boolean allowlistConfigured, boolean rawForwardPermitted) { }

    private static void only(HttpServletRequest request, Set<String> allowed) {
        for (var entry : request.getParameterMap().entrySet())
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
                throw ApiError.bad("不支持的查询参数");
    }
}
