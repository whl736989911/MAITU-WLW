package com.fireplatform.ruleflow;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management-only rule flow surface. Reads need {@code rules:read}; draft writes {@code rules:write};
 * preview/publish/enable need {@code rules:publish} plus the underlying consumer write permissions.
 */
@RestController
@RequestMapping("/api/management/rule-flows")
public class RuleFlowController {
    private final RequestSecurity security;
    private final RuleFlowQueries queries;
    private final RuleFlowCommands commands;

    public RuleFlowController(RequestSecurity security, RuleFlowQueries queries, RuleFlowCommands commands) {
        this.security = security; this.queries = queries; this.commands = commands;
    }

    @GetMapping
    public Page<RuleFlowModels.Flow> page(@RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status, HttpServletRequest request) {
        var actor = security.require(request, "rules:read", "management");
        return queries.page(actor.user().id(), security.optionalTenant(request), security.readSelection(request),
            page, size, keyword, status);
    }

    @GetMapping("/catalog")
    public RuleFlowModels.Catalog catalog(HttpServletRequest request) {
        var actor = security.require(request, "rules:read", "management");
        return queries.catalog(actor, security.tenant(request));
    }

    @GetMapping("/{id}")
    public RuleFlowModels.FlowDetail detail(@PathVariable String id, HttpServletRequest request) {
        security.require(request, "rules:read", "management");
        return queries.detail(security.tenant(request), id);
    }

    @GetMapping("/{id}/revisions")
    public List<RuleFlowModels.Revision> revisions(@PathVariable String id, HttpServletRequest request) {
        security.require(request, "rules:read", "management");
        return queries.revisions(security.tenant(request), id);
    }

    @GetMapping("/{id}/revisions/{revision}")
    public RuleFlowModels.RevisionDetail revision(@PathVariable String id, @PathVariable long revision, HttpServletRequest request) {
        security.require(request, "rules:read", "management");
        return queries.revision(security.tenant(request), id, revision);
    }

    @GetMapping("/{id}/compiled")
    public RuleFlowModels.CompiledView compiled(@PathVariable String id, HttpServletRequest request) {
        security.require(request, "rules:read", "management");
        return queries.compiled(security.tenant(request), id);
    }

    @GetMapping("/{id}/reports")
    public List<RuleFlowModels.CompileReport> reports(@PathVariable String id, HttpServletRequest request) {
        security.require(request, "rules:read", "management");
        return queries.reports(security.tenant(request), id);
    }

    @GetMapping("/{id}/events")
    public List<RuleFlowModels.Event> events(@PathVariable String id, HttpServletRequest request) {
        security.require(request, "rules:read", "management");
        return queries.events(security.tenant(request), id);
    }

    /** Revisions and bindings are the compiled consumer readback the UI compares against live state. */
    @GetMapping("/{id}/bindings")
    public List<RuleFlowModels.Binding> bindings(@PathVariable String id, HttpServletRequest request) {
        security.require(request, "rules:read", "management");
        String tenant = security.tenant(request);
        RuleFlowModels.Flow flow = queries.flow(tenant, id);
        if (flow.publishedRevision() == null) return List.of();
        return List.copyOf(queries.bindings(tenant, id, flow.publishedRevision()).values());
    }

    @PostMapping
    public RuleFlowModels.FlowDetail create(@Valid @RequestBody RuleFlowInputs.Create body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        var actor = security.require(request, "rules:write", "management");
        return commands.create(actor, security.tenant(request), body.name(), body.description(), body.definition(),
            body.reason(), RequestContext.requestId(request), key);
    }

    @PutMapping("/{id}")
    public RuleFlowModels.FlowDetail save(@PathVariable String id, @Valid @RequestBody RuleFlowInputs.Save body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        var actor = security.require(request, "rules:write", "management");
        return commands.save(actor, security.tenant(request), id, body.expected_revision(), body.name(), body.description(),
            body.definition(), body.reason(), RequestContext.requestId(request), key);
    }

    @DeleteMapping("/{id}")
    public RuleFlowModels.FlowDetail archive(@PathVariable String id, @Valid @RequestBody RuleFlowInputs.Archive body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        var actor = security.require(request, "rules:write", "management");
        return commands.archive(actor, security.tenant(request), id, body.expected_revision(), body.reason(), RequestContext.requestId(request), key);
    }

    @PostMapping("/{id}/preview")
    public RuleFlowInputs.PreviewResponse preview(@PathVariable String id, @Valid @RequestBody RuleFlowInputs.Preview body,
            HttpServletRequest request) {
        var actor = security.require(request, "rules:publish", "management");
        return commands.preview(actor, security.tenant(request), id, body.expected_revision());
    }

    @PostMapping("/{id}/publish")
    public RuleFlowInputs.PublishResponse publish(@PathVariable String id, @Valid @RequestBody RuleFlowInputs.Publish body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        var actor = security.require(request, "rules:publish", "management");
        return commands.publish(actor, security.tenant(request), id, body.expected_revision(), body.preview_hash(),
            body.reason(), RequestContext.requestId(request), key);
    }

    @PostMapping("/{id}/enabled")
    public RuleFlowInputs.PublishResponse toggle(@PathVariable String id, @Valid @RequestBody RuleFlowInputs.Toggle body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        var actor = security.require(request, "rules:publish", "management");
        return commands.setEnabled(actor, security.tenant(request), id, body.expected_revision(), body.enabled(),
            body.reason(), RequestContext.requestId(request), key);
    }

    @GetMapping("/{id}/summary")
    public Map<String, Object> summary(@PathVariable String id, HttpServletRequest request) {
        security.require(request, "rules:read", "management");
        String tenant = security.tenant(request);
        RuleFlowModels.Flow flow = queries.flow(tenant, id);
        return Map.of("flow", flow, "compiled", queries.compiled(tenant, id));
    }
}
