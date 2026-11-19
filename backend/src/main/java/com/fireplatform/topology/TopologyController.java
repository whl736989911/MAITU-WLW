package com.fireplatform.topology;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

/** Gateway topology reads for both audiences; all writes are management-only and session-authorized. */
@RestController
public class TopologyController {
    private static final String READ = "/api/{audience:management|customer}/topology";
    private static final String WRITE = "/api/management/topology";
    private static final Set<String> PAGE_PARAMS = Set.of("page", "size", "keyword", "gateway_id", "child_kind", "state",
        "scope_selected", "scope_tenant_ids", "scope_place_ids");
    private static final Set<String> TREE_PARAMS = Set.of("scope_selected", "scope_tenant_ids", "scope_place_ids");

    public record CreateGatewayBody(@NotBlank @Size(max = 64) String address, @NotBlank @Size(max = 200) String name,
                                    @NotBlank @Size(max = 32) String protocol, @Size(max = 64) String device_id,
                                    @NotBlank @Size(max = 1000) String reason) { }
    public record UpdateGatewayBody(@NotNull @jakarta.validation.constraints.Min(0) Long expected_revision,
                                    @NotBlank @Size(max = 64) String address, @NotBlank @Size(max = 200) String name,
                                    @NotBlank @Size(max = 32) String protocol, @Size(max = 64) String device_id,
                                    @NotBlank @Size(max = 1000) String reason) { }
    public record RetireGatewayBody(@NotNull @jakarta.validation.constraints.Min(0) Long expected_revision,
                                    @NotBlank @Size(max = 1000) String reason) { }
    public record CreateLinkBody(@NotBlank @Size(max = 64) String gateway_id,
                                 @NotBlank @Size(max = 16) String child_kind,
                                 @NotBlank @Size(max = 64) String device_id,
                                 @Size(max = 64) String channel_id,
                                 @NotBlank @Size(max = 16) String provenance,
                                 @Size(max = 32) String observation_source,
                                 @Size(max = 64) String observation_event_id,
                                 @Size(max = 64) String observed_at,
                                 @NotBlank @Size(max = 1000) String reason) { }
    public record VerifyLinkBody(@NotNull @jakarta.validation.constraints.Min(0) Long expected_revision,
                                 @NotBlank @Size(max = 1000) String verification_note,
                                 @NotBlank @Size(max = 1000) String reason) { }
    public record RevokeLinkBody(@NotNull @jakarta.validation.constraints.Min(0) Long expected_revision,
                                 @NotBlank @Size(max = 1000) String revoke_reason,
                                 @NotBlank @Size(max = 1000) String reason) { }

    private final RequestSecurity security;
    private final GatewayTopologyQueries queries;
    private final GatewayTopologyCommands commands;

    public TopologyController(RequestSecurity security, GatewayTopologyQueries queries, GatewayTopologyCommands commands) {
        this.security = security; this.queries = queries; this.commands = commands;
    }

    @GetMapping(READ + "/gateways")
    public TopologyModels.GatewayPage gateways(@PathVariable String audience, @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String keyword,
                                               HttpServletRequest request) {
        var actor = security.require(request, "topology:read", audience);
        only(request, PAGE_PARAMS);
        return queries.gateways(actor.user().id(), security.tenant(request), security.readSelection(request),
            keyword, page, size);
    }

    @GetMapping(READ + "/gateways/{id}/tree")
    public TopologyModels.GatewayTree tree(@PathVariable String audience, @PathVariable String id,
                                           HttpServletRequest request) {
        var actor = security.require(request, "topology:read", audience);
        only(request, TREE_PARAMS);
        return queries.gatewayTree(actor.user().id(), security.tenant(request), security.readSelection(request), id);
    }

    @GetMapping(READ + "/links")
    public TopologyModels.LinkPage links(@PathVariable String audience, @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(required = false) Integer size,
                                         @RequestParam(required = false) String gateway_id,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String child_kind,
                                         @RequestParam(required = false) String state,
                                         HttpServletRequest request) {
        var actor = security.require(request, "topology:read", audience);
        only(request, PAGE_PARAMS);
        return queries.links(actor.user().id(), security.tenant(request), security.readSelection(request),
            gateway_id, keyword, child_kind, state, page, size);
    }

    @PostMapping(WRITE + "/gateways")
    public TopologyModels.Gateway createGateway(@RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody CreateGatewayBody body,
                                                HttpServletRequest request) {
        var actor = security.require(request, "topology:write", "management");
        return commands.createGateway(actor, security.tenant(request), key,
            TopologyInputs.createGateway(new TopologyInputs.CreateGatewayBody(body.address(), body.name(),
                body.protocol(), body.device_id(), body.reason())),
            RequestContext.requestId(request));
    }

    @PutMapping(WRITE + "/gateways/{id}")
    public TopologyModels.Gateway updateGateway(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody UpdateGatewayBody body,
                                                HttpServletRequest request) {
        var actor = security.require(request, "topology:write", "management");
        return commands.updateGateway(actor, security.tenant(request), id, key,
            TopologyInputs.updateGateway(new TopologyInputs.UpdateGatewayBody(body.expected_revision(), body.address(),
                body.name(), body.protocol(), body.device_id(), body.reason())),
            RequestContext.requestId(request));
    }

    @PostMapping(WRITE + "/gateways/{id}/retire")
    public TopologyModels.Gateway retireGateway(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody RetireGatewayBody body,
                                                HttpServletRequest request) {
        var actor = security.require(request, "topology:write", "management");
        return commands.retireGateway(actor, security.tenant(request), id, key,
            TopologyInputs.retireGateway(new TopologyInputs.RetireGatewayBody(body.expected_revision(),
                body.reason())),
            RequestContext.requestId(request));
    }

    @PostMapping(WRITE + "/links")
    public TopologyModels.Link createLink(@RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody CreateLinkBody body, HttpServletRequest request) {
        var actor = security.require(request, "topology:write", "management");
        return commands.createLink(actor, security.tenant(request), key,
            TopologyInputs.createLink(new TopologyInputs.CreateLinkBody(body.gateway_id(), body.child_kind(),
                body.device_id(), body.channel_id(), body.provenance(), body.observation_source(),
                body.observation_event_id(), body.observed_at(), body.reason())),
            RequestContext.requestId(request));
    }

    @PostMapping(WRITE + "/links/{id}/verify")
    public TopologyModels.Link verifyLink(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody VerifyLinkBody body, HttpServletRequest request) {
        var actor = security.require(request, "topology:write", "management");
        return commands.verifyLink(actor, security.tenant(request), id, key,
            TopologyInputs.verifyLink(new TopologyInputs.VerifyLinkBody(body.expected_revision(),
                body.verification_note(), body.reason())),
            RequestContext.requestId(request));
    }

    @PostMapping(WRITE + "/links/{id}/revoke")
    public TopologyModels.Link revokeLink(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody RevokeLinkBody body, HttpServletRequest request) {
        var actor = security.require(request, "topology:write", "management");
        return commands.revokeLink(actor, security.tenant(request), id, key,
            TopologyInputs.revokeLink(new TopologyInputs.RevokeLinkBody(body.expected_revision(),
                body.revoke_reason(), body.reason())),
            RequestContext.requestId(request));
    }

    private static void only(HttpServletRequest request, Set<String> allowed) {
        for (var entry : request.getParameterMap().entrySet())
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
                throw ApiError.bad("不支持的查询参数");
    }
}
