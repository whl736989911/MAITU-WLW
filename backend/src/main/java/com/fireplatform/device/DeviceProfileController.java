package com.fireplatform.device;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

/** N11 画像 API：规则、当前快照、历史、重算。 */
@RestController @RequestMapping("/api/management")
public class DeviceProfileController {
    private final RequestSecurity security; private final DeviceProfileService profiles;
    public DeviceProfileController(RequestSecurity security,DeviceProfileService profiles) { this.security=security; this.profiles=profiles; }

    public record ConfigureRule(@NotNull @Min(1) @Max(365) Integer window_days,@NotNull @Min(1) @Max(1000) Integer min_samples,@NotNull Boolean enabled,@NotBlank @Size(max=1000) String reason) { }
    public record Recompute(@NotBlank @Size(max=1000) String reason) { }

    @GetMapping("/profile-rules") public DeviceProfileService.RulePage rules(HttpServletRequest request) {
        var actor=security.require(request,"profiles:read","management");
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!java.util.Set.of("scope_selected","scope_tenant_ids","scope_place_ids").contains(e.getKey())||e.getValue().length!=1)) throw ApiError.bad("画像规则范围参数无效或重复");
        return profiles.currentRules(actor.user().id(),security.optionalTenant(request),security.readSelection(request));
    }
    @GetMapping("/profile-rule") public DeviceProfileService.Rule rule(HttpServletRequest request) {
        var actor=security.require(request,"profiles:read","management");
        return profiles.currentRule(security.tenant(request));
    }

    @PostMapping("/profile-rule") public DeviceProfileService.Rule configure(@Valid @RequestBody ConfigureRule body,HttpServletRequest request) {
        var actor=security.require(request,"profiles:write","management");
        return profiles.configure(actor,security.tenant(request),body.window_days(),body.min_samples(),body.enabled(),body.reason());
    }

    @GetMapping("/devices/{id}/profile") public org.springframework.http.ResponseEntity<DeviceProfileService.Snapshot> current(@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"profiles:read","management");
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!java.util.Set.of("scope_selected","scope_tenant_ids","scope_place_ids").contains(e.getKey())||e.getValue().length!=1)) throw ApiError.bad("画像范围参数无效或重复");
        var snapshot=profiles.latestSnapshot(actor.user().id(),security.tenant(request),id,security.readSelection(request));
        return snapshot==null ? org.springframework.http.ResponseEntity.status(404).build() : org.springframework.http.ResponseEntity.ok(snapshot);
    }

    @GetMapping("/devices/{id}/profile/history") public java.util.List<DeviceProfileService.Snapshot> history(@PathVariable String id,@RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        var actor=security.require(request,"profiles:read","management");
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!java.util.Set.of("limit","scope_selected","scope_tenant_ids","scope_place_ids").contains(e.getKey())||e.getValue().length!=1)) throw ApiError.bad("画像范围参数无效或重复");
        return profiles.history(actor.user().id(),security.tenant(request),id,limit,security.readSelection(request));
    }

    @PostMapping("/devices/{id}/profile/recompute") public DeviceProfileService.Snapshot recompute(@PathVariable String id,@Valid @RequestBody Recompute body,HttpServletRequest request) {
        var actor=security.require(request,"profiles:write","management");
        return profiles.recompute(actor,security.tenant(request),id,body.reason());
    }
}
