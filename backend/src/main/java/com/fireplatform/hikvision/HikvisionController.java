package com.fireplatform.hikvision;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/hikvision")
public class HikvisionController {
    public record Assignment(@NotNull @Min(0) Long expectedRevision,
        @NotBlank @Size(max=64) String tenantId,@Size(max=64) String placeId,@Size(max=64) String existingDeviceId) { }
    public record ValidateResult(String result) { }
    private final RequestSecurity security;
    private final HikvisionService service;
    private final HikvisionConfigService configuration;
    public HikvisionController(RequestSecurity security,HikvisionService service,HikvisionConfigService configuration){this.security=security;this.service=service;this.configuration=configuration;}
    @GetMapping("/status")
    public HikvisionService.Status status(HttpServletRequest request){security.require(request,"integrations:read","management");return service.status();}
    @GetMapping("/devices")
    public Page<HikvisionService.DeviceRow> devices(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,HttpServletRequest request){security.require(request,"integrations:read","management");return service.devices(page,size);}
    @GetMapping("/configuration")
    public HikvisionConfigService.View configuration(HttpServletRequest request){security.require(request,"integrations:read","management");return configuration.get();}
    @PutMapping("/configuration")
    public HikvisionConfigService.View update(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody HikvisionConfigInputs.Write body,HttpServletRequest request){
        var actor=security.require(request,"integrations:write","management");return configuration.update(actor,key,body,RequestContext.requestId(request));
    }
    @PostMapping("/configuration/validate")
    public ValidateResult validate(HttpServletRequest request){
        var actor=security.require(request,"integrations:write","management");return new ValidateResult(configuration.validateReadOnly(actor,RequestContext.requestId(request)));
    }
    @PostMapping("/sync")
    public HikvisionService.Status sync(HttpServletRequest request){var actor=security.require(request,"integrations:write","management");return service.manualSync(actor,RequestContext.requestId(request));}
    @PostMapping("/devices/{externalId}/assignment")
    public HikvisionService.DeviceRow assign(@PathVariable String externalId,@Valid @RequestBody Assignment body,HttpServletRequest request){var actor=security.require(request,"integrations:write","management");return service.assign(actor,externalId,body,RequestContext.requestId(request));}
}
