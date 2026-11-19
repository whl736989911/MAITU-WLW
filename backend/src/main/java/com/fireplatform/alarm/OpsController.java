package com.fireplatform.alarm;

import com.fireplatform.identity.RequestSecurity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** N14 值守概览 API（只读）。 */
@RestController @RequestMapping("/api/management")
public class OpsController {
    private final RequestSecurity security; private final OpsQueries ops;
    public OpsController(RequestSecurity security,OpsQueries ops) { this.security=security; this.ops=ops; }

    @GetMapping("/ops-overview") public OpsQueries.Overview overview(HttpServletRequest request) {
        var actor=security.require(request,"summary:read","management");
        return ops.read(actor.user().id(),security.optionalTenant(request));
    }
}
