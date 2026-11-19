package com.fireplatform.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PermissionCatalogController {
    private final RequestSecurity security; private final PermissionCatalog catalog;
    public PermissionCatalogController(RequestSecurity security,PermissionCatalog catalog) { this.security=security; this.catalog=catalog; }
    @GetMapping("/api/auth/permission-catalog") public PermissionCatalog.Catalog catalog(HttpServletRequest request) { return catalog.forIdentity(security.identity(request)); }
}
