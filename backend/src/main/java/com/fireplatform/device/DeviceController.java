package com.fireplatform.device;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class DeviceController {
    private final DeviceQueryService reads; private final RequestSecurity security;
    public DeviceController(DeviceQueryService reads,RequestSecurity security) { this.reads=reads; this.security=security; }
    @GetMapping("/api/{audience:management|customer}/device-workbench") public DeviceQueryService.WorkbenchResult workbench(@PathVariable String audience,@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(required=false) String keyword,@RequestParam(required=false) String connectivity,@RequestParam(name="physical_state",required=false) String physical,@RequestParam(name="integration_state",required=false) String integration,@RequestParam(name="system_type",required=false) String systemType,@RequestParam(name="device_category",required=false) String deviceCategory,HttpServletRequest request) {
        var actor=security.require(request,"device:read",audience);String tenant=security.optionalTenant(request);
        var allowed=java.util.Set.of("page","size","keyword","connectivity","physical_state","integration_state","system_type","device_category","scope_selected","scope_tenant_ids","scope_place_ids");
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!allowed.contains(e.getKey())||e.getValue().length!=1))throw com.fireplatform.shared.ApiError.bad("筛选参数无效或重复");
        return reads.workbench(actor.user().id(),tenant,page,size,DeviceWorkbenchFilter.parse(keyword,connectivity,physical,integration,systemType,deviceCategory),security.readSelection(request));
    }
    @GetMapping("/api/{audience:management|customer}/summary") public DeviceQueryService.Summary summary(@PathVariable String audience,HttpServletRequest request) {
        var actor=security.require(request,"summary:read",audience);
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!java.util.Set.of("scope_selected","scope_tenant_ids","scope_place_ids").contains(e.getKey())||e.getValue().length!=1)) throw com.fireplatform.shared.ApiError.bad("范围筛选参数无效或重复");
        return reads.summary(actor.user().id(),security.optionalTenant(request),security.readSelection(request));
    }
    @GetMapping("/api/{audience:management|customer}/devices/{id}") public DeviceQueryService.Detail detail(@PathVariable String audience,@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"device:read",audience); String tenant=security.tenant(request);
        if(!request.getParameterMap().isEmpty())throw com.fireplatform.shared.ApiError.bad("设备详情不接受查询参数");
        return reads.detail(actor.user().id(),tenant,id);
    }
    @GetMapping("/api/management/devices/{id}/place-history")
    public Page<DeviceQueryService.PlaceHistory> placeHistory(@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,HttpServletRequest request) {
        var actor=security.require(request,"device:read","management");
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!java.util.Set.of("page","size").contains(e.getKey())||e.getValue().length!=1)) throw com.fireplatform.shared.ApiError.bad("历史分页参数无效或重复");
        return reads.placeHistory(actor.user().id(),security.optionalTenant(request),id,page,size);
    }
    @GetMapping("/api/{audience:management|customer}/devices/{id}/effective-parameters") public com.fireplatform.configuration.ParameterModels.DeviceEffective effective(@PathVariable String audience,@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"device:read",audience); return reads.effective(actor.user().id(),security.tenant(request),id);
    }
    @GetMapping("/api/{audience:management|customer}/devices") public Page<DeviceQueryService.DeviceRow> devices(@PathVariable String audience,@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String keyword,@RequestParam(value="battery_status",required=false) String batteryStatus,@RequestParam(required=false) String field,@RequestParam(name="place_id",required=false) String placeId,@RequestParam(name="tenant_id",required=false) String tenantId,@RequestParam(required=false) String connectivity,@RequestParam(name="physical_state",required=false) String physical,@RequestParam(name="integration_state",required=false) String integration,@RequestParam(name="system_type",required=false) String systemType,@RequestParam(name="device_category",required=false) String deviceCategory,HttpServletRequest request) {
        var actor=security.require(request,"device:read",audience); String tenant=security.optionalTenant(request); String filter=security.tenantFilter(request,tenantId);
        var allowed=java.util.Set.of("page","size","keyword","battery_status","field","place_id","tenant_id","connectivity","physical_state","integration_state","system_type","device_category","scope_selected","scope_tenant_ids","scope_place_ids");
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!allowed.contains(e.getKey())||e.getValue().length!=1)) throw com.fireplatform.shared.ApiError.bad("设备查询参数无效或重复");
        var statuses=DeviceListFilter.parse(connectivity,physical,integration,systemType,deviceCategory);
        var selection=security.readSelection(request);
        return reads.devices(actor.user().id(),tenant,filter,page,size,keyword,batteryStatus,field,placeId,selection,statuses);
    }
}
