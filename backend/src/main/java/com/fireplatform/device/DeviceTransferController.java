package com.fireplatform.device;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/devices")
public class DeviceTransferController {
    private final RequestSecurity security;
    private final DeviceTransferService transfers;
    public DeviceTransferController(RequestSecurity security, DeviceTransferService transfers) { this.security=security; this.transfers=transfers; }

    @PostMapping("/{id}/transfer")
    public DeviceTransferService.Result transfer(@PathVariable String id,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @Valid @RequestBody DeviceTransferInput body, HttpServletRequest request) {
        var actor=security.require(request,"device:write","management");
        return transfers.transfer(actor,security.tenant(request),id,key,body,RequestContext.requestId(request));
    }
    @PostMapping("/{id}/archive")
    public DeviceQueryService.DeviceRow archive(@PathVariable String id,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @Valid @RequestBody DeviceArchiveInput body, HttpServletRequest request) {
        var actor=security.require(request,"device:write","management");
        return transfers.archive(actor,security.tenant(request),id,key,body,RequestContext.requestId(request));
    }
}
