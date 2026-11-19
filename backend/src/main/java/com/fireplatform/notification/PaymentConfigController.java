package com.fireplatform.notification;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** Global WeChat Pay merchant configuration; never accepts a tenant context. */
@RestController
@RequestMapping("/api/management/payment-config")
public class PaymentConfigController {
    private final RequestSecurity security;
    private final PaymentConfigService service;

    public PaymentConfigController(RequestSecurity security, PaymentConfigService service) {
        this.security = security; this.service = service;
    }

    private static void global(HttpServletRequest request) {
        if (request.getHeader("X-Tenant-Id") != null) throw ApiError.bad("支付配置是全局配置，不接受单位上下文");
    }

    @GetMapping
    public PaymentConfigService.View get(HttpServletRequest request) {
        security.require(request, "payments:read", "management");
        global(request);
        return service.view();
    }

    @PutMapping
    public PaymentConfigService.View update(@RequestHeader(value="Idempotency-Key",required=false) String key,
                                            @RequestBody PaymentConfigInputs.Config body, HttpServletRequest request) {
        var actor = security.require(request, "payments:write", "management");
        global(request);
        return service.update(actor, key, body, RequestContext.requestId(request));
    }

    @GetMapping("/status")
    public PaymentConfigService.Status status(HttpServletRequest request) {
        security.require(request, "payments:read", "management");
        global(request);
        return service.status();
    }
}
