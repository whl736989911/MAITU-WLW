package com.fireplatform.notification;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** Explicit merchant-side compensation actions for one local notification resource order. */
@RestController
@RequestMapping("/api/management/notification-resources/orders")
public class PaymentOrderController {
    private final RequestSecurity security;
    private final PaymentOrderService payments;

    public PaymentOrderController(RequestSecurity security, PaymentOrderService payments) {
        this.security = security; this.payments = payments;
    }

    @PostMapping("/{id}/query")
    public PaymentOrderService.ActionResult query(@PathVariable String id, HttpServletRequest request) {
        var actor = security.require(request, "payments:write", "management");
        return payments.query(actor, security.tenant(request), id, RequestContext.requestId(request));
    }

    @PostMapping("/{id}/close")
    public PaymentOrderService.ActionResult close(@PathVariable String id, HttpServletRequest request) {
        var actor = security.require(request, "payments:write", "management");
        return payments.close(actor, security.tenant(request), id, RequestContext.requestId(request));
    }
}
