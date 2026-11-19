package com.fireplatform.notification;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/notification-deliveries")
public class NotificationDeliveryReceiptController {
    private final RequestSecurity security;
    private final NotificationDeliveryReceiptQueries queries;
    private final NotificationDeliveryReceiptPolicy policy;
    private final NotificationDeliveryReceiptCommands commands;
    private final NotificationDeliveryReceiptService service;

    public NotificationDeliveryReceiptController(RequestSecurity security,NotificationDeliveryReceiptQueries queries,
                                                 NotificationDeliveryReceiptPolicy policy,NotificationDeliveryReceiptCommands commands,
                                                 NotificationDeliveryReceiptService service) {
        this.security=security;this.queries=queries;this.policy=policy;this.commands=commands;this.service=service;
    }

    @GetMapping
    public Page<NotificationDeliveryReceiptQueries.Delivery> list(@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Integer size,HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:read","management");
        return queries.list(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size);
    }

    @GetMapping("/config")
    public NotificationDeliveryReceiptPolicy.View receiptConfig(HttpServletRequest request) {
        security.require(request,"notification-resources:read","management");
        return policy.view();
    }

    @PutMapping("/config")
    public NotificationDeliveryReceiptPolicy.View updateConfig(@RequestHeader(value="Idempotency-Key",required=false)String key,
            @Valid @RequestBody NotificationDeliveryReceiptInputs.ConfigUpdate body,HttpServletRequest request) {
        var actor=security.require(request,"notification-config:write","management");
        if(request.getHeader("X-Tenant-Id")!=null) throw com.fireplatform.shared.ApiError.bad("回执查询策略是全局配置，不接受单位上下文");
        return commands.updateConfig(actor,body,key,RequestContext.requestId(request));
    }

    @GetMapping("/{id}")
    public NotificationDeliveryReceiptQueries.Detail detail(@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:read","management");
        return queries.detail(actor.user().id(),security.optionalTenant(request),security.readSelection(request),id);
    }

    @PostMapping("/{id}/reconcile")
    public NotificationDeliveryReceiptCommands.ReconcileResult reconcile(@PathVariable String id,
            @RequestHeader(value="Idempotency-Key",required=false)String key,
            @Valid @RequestBody NotificationDeliveryReceiptInputs.Reconcile body,HttpServletRequest request) {
        var actor=security.require(request,NotificationDeliveryReceiptCommands.PERMISSION,"management");
        return service.reconcile(actor,security.optionalTenant(request),security.readSelection(request),id,body,key,RequestContext.requestId(request));
    }
}
