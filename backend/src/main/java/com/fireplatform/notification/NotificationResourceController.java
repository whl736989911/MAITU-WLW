package com.fireplatform.notification;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/notification-resources")
public class NotificationResourceController {
    private final RequestSecurity security;
    private final NotificationResourceService service;
    private final NotificationProviderAttemptService attempts;
    public NotificationResourceController(RequestSecurity security,NotificationResourceService service,NotificationProviderAttemptService attempts) { this.security=security; this.service=service; this.attempts=attempts; }

    @GetMapping public List<java.util.Map<String,Object>> accounts(HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:read","management"); return service.accountOverview(actor.user().id(),security.optionalTenant(request),security.readSelection(request));
    }
    @GetMapping("/pricing") public NotificationResourcePricing pricing(HttpServletRequest request) {
        security.require(request,"notification-resources:read","management"); return service.pricing();
    }
    @PutMapping("/pricing") public NotificationResourcePricing pricing(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationResourceInputs.Pricing body,HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:write","management"); return service.updatePricing(actor,security.tenant(request),body,RequestContext.requestId(request));
    }
    @GetMapping("/{accountId}/ledger") public Page<NotificationResourceService.Ledger> ledger(@PathVariable String accountId,@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Integer size,HttpServletRequest request) {
        security.require(request,"notification-resources:read","management"); return service.ledger(security.tenant(request),accountId,page,size);
    }
    @PostMapping("/recharge") public NotificationResourceService.Account recharge(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationResourceInputs.Recharge body,HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:write","management"); return service.recharge(actor,security.tenant(request),key,body,RequestContext.requestId(request));
    }
    @GetMapping("/orders") public Page<NotificationResourceService.Order> orders(@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Integer size,HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:read","management"); return service.orders(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size);
    }
    @PostMapping("/orders") public NotificationResourceService.Order createOrder(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationResourceInputs.OrderCreate body,HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:write","management"); String ip=request.getHeader("X-Real-IP"); if(ip==null||ip.isBlank())ip=request.getRemoteAddr(); return service.createOrder(actor,security.tenant(request),key,body,ip,RequestContext.requestId(request));
    }
    @GetMapping("/provider-attempts") public Page<NotificationProviderAttemptService.Attempt> providerAttempts(@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Integer size,HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:read","management"); return attempts.list(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size);
    }
    @GetMapping("/orders/{id}") public NotificationResourceService.Order order(@PathVariable String id,HttpServletRequest request) {
        security.require(request,"notification-resources:read","management"); return service.order(security.tenant(request),id);
    }
    @PostMapping("/orders/{id}/confirm") public NotificationResourceService.Order confirmOrder(@PathVariable String id,@RequestBody NotificationResourceInputs.OrderConfirm body,HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:write","management"); return service.confirmOrder(actor,security.tenant(request),id,body.reference(),RequestContext.requestId(request));
    }
}
