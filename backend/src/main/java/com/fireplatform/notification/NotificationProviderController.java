package com.fireplatform.notification;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/notification-provider")
public class NotificationProviderController {
    private final RequestSecurity security;
    private final NotificationProviderService service;
    public NotificationProviderController(RequestSecurity security,NotificationProviderService service) { this.security=security; this.service=service; }
    private void global(HttpServletRequest request) { if(request.getHeader("X-Tenant-Id")!=null) throw com.fireplatform.shared.ApiError.bad("通知通道是全局配置，不接受单位上下文"); }
    @GetMapping public NotificationProviderService.View get(HttpServletRequest request) { security.require(request,"notification-config:read","management"); global(request); return service.get(); }
    @PutMapping public NotificationProviderService.View update(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationProviderInputs.Provider body,HttpServletRequest request) {
        var actor=security.require(request,"notification-config:write","management"); global(request); return service.update(actor,body,RequestContext.requestId(request));
    }
    @GetMapping("/status") public NotificationProviderService.Status status(HttpServletRequest request) { security.require(request,"notification-config:read","management"); global(request); return service.status(); }
    @PostMapping("/test") public NotificationProviderService.TestResult test(@RequestBody NotificationProviderInputs.ProviderTest body,HttpServletRequest request) {
        var actor=security.require(request,"notification-config:write","management"); global(request); return service.test(actor,body,RequestContext.requestId(request));
    }
}
