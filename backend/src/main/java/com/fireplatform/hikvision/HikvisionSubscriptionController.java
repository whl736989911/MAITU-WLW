package com.fireplatform.hikvision;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

/** Explicit Hik subscription management; no automatic registration and no raw URL exposure. */
@RestController
@RequestMapping("/api/management/hikvision/subscriptions")
public class HikvisionSubscriptionController {
    private final RequestSecurity security;
    private final HikvisionSubscriptionService subscriptions;

    public HikvisionSubscriptionController(RequestSecurity security, HikvisionSubscriptionService subscriptions) {
        this.security = security;
        this.subscriptions = subscriptions;
    }

    @PostMapping("/inspect")
    public HikvisionSubscriptionService.Inspect inspect(HttpServletRequest request) {
        var actor = security.require(request, "integrations:write", "management");
        return subscriptions.inspect(actor, RequestContext.requestId(request));
    }

    @PostMapping("/previews")
    public HikvisionSubscriptionService.PreviewView preview(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody HikvisionSubscriptionService.Inputs.Preview body, HttpServletRequest request) {
        var actor = security.require(request, "integrations:write", "management");
        return subscriptions.preview(actor, key, body, RequestContext.requestId(request));
    }

    @PostMapping("/{id}/execute")
    public HikvisionSubscriptionService.OperationView execute(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody HikvisionSubscriptionService.Inputs.Execute body, HttpServletRequest request) {
        var actor = security.require(request, "integrations:write", "management");
        return subscriptions.execute(actor, id, key, body, RequestContext.requestId(request));
    }

    @PostMapping("/{id}/reconcile")
    public HikvisionSubscriptionService.OperationView reconcile(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest request) {
        var actor = security.require(request, "integrations:write", "management");
        return subscriptions.reconcile(actor, id, key, RequestContext.requestId(request));
    }

    @PostMapping("/{id}/cancel")
    public HikvisionSubscriptionService.OperationView cancel(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody HikvisionSubscriptionService.Inputs.Cancel body, HttpServletRequest request) {
        var actor = security.require(request, "integrations:write", "management");
        return subscriptions.cancel(actor, id, key, body, RequestContext.requestId(request));
    }

    @GetMapping("/operations")
    public Page<HikvisionSubscriptionService.OperationSummary> operations(
            @RequestParam(defaultValue = "1") int page, @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        security.require(request, "integrations:read", "management");
        return subscriptions.operations(page, size);
    }

    @GetMapping("/{id}")
    public HikvisionSubscriptionService.OperationView view(@PathVariable String id, HttpServletRequest request) {
        security.require(request, "integrations:read", "management");
        return subscriptions.view(id);
    }
}
