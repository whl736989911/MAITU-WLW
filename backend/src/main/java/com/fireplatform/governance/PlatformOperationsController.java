package com.fireplatform.governance;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** Platform-only read-only operations evidence. No write, shell, restart, delete or arbitrary path. */
@RestController @RequestMapping("/api/management/platform-operations")
public class PlatformOperationsController {
    private final RequestSecurity security; private final PlatformOperationsService service;
    public PlatformOperationsController(RequestSecurity security,PlatformOperationsService service) {
        this.security=security; this.service=service;
    }

    @GetMapping public PlatformOperationsService.Overview overview(HttpServletRequest request) {
        requirePlatform(request);
        return service.overview();
    }

    @GetMapping("/schema") public PlatformOperationsService.SchemaHistory schema(
        @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,HttpServletRequest request) {
        requirePlatform(request);
        return service.schemaHistory(page,size);
    }

    private AuthService.Identity requirePlatform(HttpServletRequest request) {
        var identity=security.require(request,"platform-ops:read","management");
        if(!identity.user().userType().equals("PLATFORM"))
            throw ApiError.forbidden("平台运维证据仅平台账号可读");
        return identity;
    }
}
