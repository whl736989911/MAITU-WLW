package com.fireplatform.maintenance;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/management/maintenance/field-records")
public class FieldMaintenanceController {
    private final RequestSecurity security;
    private final FieldMaintenance maintenance;

    public FieldMaintenanceController(RequestSecurity security,FieldMaintenance maintenance) {
        this.security=security; this.maintenance=maintenance;
    }

    @GetMapping
    public Page<FieldMaintenance.Entry> list(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,
                                             @RequestParam(name="device_id",required=false) String deviceId,HttpServletRequest request) {
        var actor=security.require(request,"maintenance:read","management");
        security.require(request,"device:read","management");
        return maintenance.list(actor.user().id(),security.optionalTenant(request),security.readSelection(request),deviceId,page,size);
    }

    @PostMapping
    public FieldMaintenance.Entry create(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody FieldMaintenance.Draft draft,HttpServletRequest request) {
        var actor=security.require(request,"maintenance:write","management");
        return maintenance.create(actor,security.tenant(request),key,draft,RequestContext.requestId(request));
    }

    @PostMapping("/{id}/finish")
    public FieldMaintenance.Entry finish(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        var actor=security.require(request,"maintenance:write","management");
        return maintenance.finish(actor,security.tenant(request),id,key,RequestContext.requestId(request));
    }
}
