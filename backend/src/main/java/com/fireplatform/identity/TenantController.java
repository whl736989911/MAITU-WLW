package com.fireplatform.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TenantController {
    private final TenantService tenants; private final RequestSecurity security;
    public TenantController(TenantService tenants,RequestSecurity security) { this.tenants=tenants; this.security=security; }
    @GetMapping("/api/management/tenants") public Map<String,List<TenantService.TenantRow>> list(HttpServletRequest request) {
        var identity=security.require(request,"tenants:read","management"); return Map.of("items",tenants.authorizedTenants(identity.user().id()));
    }
}
