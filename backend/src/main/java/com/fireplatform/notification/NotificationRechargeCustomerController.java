package com.fireplatform.notification;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer/notification-recharge")
public class NotificationRechargeCustomerController {
    private final RequestSecurity security;
    private final NotificationResourceService service;
    private final CustomerNotificationResourceQueries balances;
    public NotificationRechargeCustomerController(RequestSecurity security,NotificationResourceService service,CustomerNotificationResourceQueries balances) { this.security=security; this.service=service; this.balances=balances; }

    @GetMapping("/options") public NotificationResourceService.CustomerOptions options(HttpServletRequest request) {
        var actor=security.require(request,"notification-recharge:read","customer"); return service.customerOptions(security.tenant(request),actor.user().id());
    }
    @GetMapping("/orders") public Page<NotificationResourceService.CustomerOrder> orders(@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Integer size,HttpServletRequest request) {
        var actor=security.require(request,"notification-recharge:read","customer"); return service.customerOrders(security.tenant(request),actor.user().id(),page,size);
    }
    @GetMapping("/accounts") public List<CustomerNotificationResourceQueries.Balance> accounts(HttpServletRequest request) {
        var actor=security.require(request,"notification-recharge:read","customer"); return balances.balances(security.tenant(request),actor.user().id());
    }
    @GetMapping("/accounts/{id}/ledger") public Page<CustomerNotificationResourceQueries.Entry> accountLedger(@PathVariable String id,@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Integer size,HttpServletRequest request) {
        var actor=security.require(request,"notification-recharge:read","customer"); return balances.ledger(security.tenant(request),actor.user().id(),id,page,size);
    }
    @GetMapping("/orders/{id}") public NotificationResourceService.CustomerOrder order(@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"notification-recharge:read","customer"); return service.customerOrder(security.tenant(request),id,actor.user().id());
    }
    @PostMapping("/orders") public NotificationResourceService.CustomerOrder create(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationRechargeInputs.CustomerOrder body,HttpServletRequest request) {
        var actor=security.require(request,"notification-recharge:write","customer"); String ip=request.getHeader("X-Real-IP"); if(ip==null||ip.isBlank())ip=request.getRemoteAddr(); return service.createCustomerOrder(actor,security.tenant(request),key,body,ip,RequestContext.requestId(request));
    }
}
