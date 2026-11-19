package com.fireplatform.notification;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** Read access and explicit manual runs for the daily WeChat Pay bill reconciliation. */
@RestController
@RequestMapping("/api/management/payment-reconciliation")
public class PaymentReconciliationController {
    private final RequestSecurity security;
    private final PaymentReconciliationService service;

    public PaymentReconciliationController(RequestSecurity security, PaymentReconciliationService service) {
        this.security = security; this.service = service;
    }

    private static void global(HttpServletRequest request) {
        if (request.getHeader("X-Tenant-Id") != null) throw ApiError.bad("对账是全局商户操作，不接受单位上下文");
    }

    @GetMapping
    public Page<PaymentReconciliationService.RunView> list(@RequestParam(defaultValue="1") int page,
                                                           @RequestParam(required=false) Integer size, HttpServletRequest request) {
        security.require(request, "payments:read", "management");
        global(request);
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public PaymentReconciliationService.RunDetail detail(@PathVariable String id, HttpServletRequest request) {
        security.require(request, "payments:read", "management");
        global(request);
        return service.detail(id);
    }

    @PostMapping
    public PaymentReconciliationService.RunDetail run(@RequestHeader(value="Idempotency-Key",required=false) String key,
                                                      @RequestBody PaymentReconciliationInputs.Run body, HttpServletRequest request) {
        var actor = security.require(request, "payments:write", "management");
        global(request);
        return service.run(actor, key, body, RequestContext.requestId(request));
    }
}
