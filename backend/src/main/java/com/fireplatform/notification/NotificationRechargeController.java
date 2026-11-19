package com.fireplatform.notification;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/notification-recharge")
public class NotificationRechargeController {
    private final RequestSecurity security;
    private final NotificationResourceService service;
    public NotificationRechargeController(RequestSecurity security,NotificationResourceService service) { this.security=security; this.service=service; }

    @GetMapping("/policies") public Page<NotificationResourceService.PolicyOverview> policies(@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Integer size,HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:read","management"); return service.policyOverview(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size);
    }
    @GetMapping("/policy") public NotificationResourceService.Policy policy(HttpServletRequest request) {
        security.require(request,"notification-resources:read","management"); return service.policy(security.tenant(request));
    }
    @PutMapping("/policy") public NotificationResourceService.Policy policy(@RequestBody NotificationRechargeInputs.Policy body,HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:write","management"); return service.updatePolicy(actor,security.tenant(request),body,RequestContext.requestId(request));
    }
    @GetMapping("/grants") public List<NotificationResourceService.Grant> grants(HttpServletRequest request) {
        security.require(request,"notification-resources:read","management"); return service.grants(security.tenant(request));
    }
    @GetMapping("/grants/overview") public Page<NotificationResourceService.GrantOverview> grantOverview(@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Integer size,HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:read","management"); return service.grantOverview(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size);
    }
    @PostMapping("/grants") public NotificationResourceService.Grant grant(@RequestBody NotificationRechargeInputs.Grant body,HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:write","management"); return service.addGrant(actor,security.tenant(request),body,RequestContext.requestId(request));
    }
    @PostMapping("/grants/revoke") public void revoke(@RequestParam String userId,@RequestParam String ownerType,@RequestParam String ownerId,HttpServletRequest request) {
        var actor=security.require(request,"notification-resources:write","management"); service.revokeGrant(actor,security.tenant(request),userId,ownerType,ownerId,RequestContext.requestId(request));
    }
}
