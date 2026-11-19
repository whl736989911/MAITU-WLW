package com.fireplatform.device;

import com.fireplatform.deviceservice.DeviceServiceInputs;
import com.fireplatform.deviceservice.DeviceServiceModels;
import com.fireplatform.deviceservice.HikvisionDeviceCreateService;
import com.fireplatform.hikvision.HikvisionService;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/vendor-devices")
public class VendorDeviceController {
    private final RequestSecurity security;
    private final LegacyRealtimeDeviceService legacy;
    private final HikvisionService hikvision;
    private final DeviceQueryService devices;
    private final HikvisionDeviceCreateService hikvisionCreate;

    public VendorDeviceController(RequestSecurity security,LegacyRealtimeDeviceService legacy,
            HikvisionService hikvision,DeviceQueryService devices,HikvisionDeviceCreateService hikvisionCreate) {
        this.security=security;this.legacy=legacy;this.hikvision=hikvision;this.devices=devices;
        this.hikvisionCreate=hikvisionCreate;
    }
    @PostMapping("/hikvision-create")
    public DeviceServiceModels.RegistrationDetail createHikvision(
            @RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody DeviceServiceInputs.CreateRegistration input, HttpServletRequest request) {
        var actor=security.require(request,"device:write","management");
        security.require(request,"integrations:write","management");
        return hikvisionCreate.create(actor,tenant,key,input,RequestContext.requestId(request));
    }


    @GetMapping("/legacy-profiles")
    public Page<LegacyRealtimeDeviceService.LegacyProfile> profiles(@RequestParam(defaultValue="1") int page,
            @RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String keyword,HttpServletRequest request) {
        security.require(request,"device:write","management");
        return legacy.profiles(page,size,keyword);
    }

    @PostMapping
    public DeviceQueryService.DeviceRow register(@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody VendorDeviceInputs.Register input,HttpServletRequest request) {
        var actor=security.require(request,"device:write","management");
        String tenant=security.tenant(request),requestId=RequestContext.requestId(request);
        String id;
        if("LEGACY_REALTIME".equals(input.source())) {
            id=legacy.register(actor,tenant,key,input,requestId);
        } else {
            if(input.legacyProfileId()!=null || input.upstreamDeviceId()!=null)
                throw ApiError.bad("海康云来源不使用旧接口解析配置或旧上报别名");
            id=hikvision.registerBySerial(actor,tenant,key,input.name(),input.serial(),input.placeId(),input.address(),requestId);
        }
        return devices.device(actor.user().id(),tenant,id,false);
    }

    @GetMapping("/{id}/aep-onboarding")
    public LegacyRealtimeDeviceService.AepOnboarding aepOnboarding(@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"device:write","management");
        security.require(request,"integrations:write","management");
        return legacy.aepOnboarding(actor,security.tenant(request),id);
    }

    @PostMapping("/{id}/aep-onboarding")
    public LegacyRealtimeDeviceService.AepOnboarding confirmAep(@PathVariable String id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody VendorDeviceInputs.ConfirmAep input,HttpServletRequest request) {
        var actor=security.require(request,"device:write","management");
        security.require(request,"integrations:write","management");
        return legacy.confirmAep(actor,security.tenant(request),id,key,input,RequestContext.requestId(request));
    }
}
