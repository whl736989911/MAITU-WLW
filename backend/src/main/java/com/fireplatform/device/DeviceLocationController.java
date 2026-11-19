package com.fireplatform.device;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.*;

/** N18 坐标维护与无底图分布 API（管理端）。 */
@RestController @RequestMapping("/api/management")
public class DeviceLocationController {
    private final RequestSecurity security; private final DeviceLocationService locations;
    public DeviceLocationController(RequestSecurity security,DeviceLocationService locations) { this.security=security; this.locations=locations; }

    public record SetLocation(@NotNull BigDecimal latitude,@NotNull BigDecimal longitude,@NotNull @Min(0) Long expected_revision) { }
    public record ClearLocation(@NotNull @Min(0) Long expected_revision) { }

    @PutMapping("/devices/{id}/location") public DeviceQueryService.DeviceRow set(@PathVariable String id,@Valid @RequestBody SetLocation body,HttpServletRequest request) {
        var actor=security.require(request,"device:write","management");
        return locations.set(actor,security.tenant(request),id,body.latitude(),body.longitude(),body.expected_revision());
    }

    @PostMapping("/devices/{id}/location/clear") public DeviceQueryService.DeviceRow clear(@PathVariable String id,@Valid @RequestBody ClearLocation body,HttpServletRequest request) {
        var actor=security.require(request,"device:write","management");
        return locations.clear(actor,security.tenant(request),id,body.expected_revision());
    }

    @GetMapping("/devices/distribution") public DeviceLocationService.Distribution distribution(HttpServletRequest request) {
        var actor=security.require(request,"device:read","management");
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!java.util.Set.of("scope_selected","scope_tenant_ids","scope_place_ids").contains(e.getKey())||e.getValue().length!=1)) throw ApiError.bad("范围筛选参数无效或重复");
        return locations.distribution(actor.user().id(),security.optionalTenant(request),security.readSelection(request));
    }
}
