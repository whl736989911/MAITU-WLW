package com.fireplatform.alarm;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ScopeFilter;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController
public class AlarmWorkbenchController {
    private static final Set<String> PARAMETERS=Set.of("page","size","keyword","physical_state","response_state","conclusion","occurred_from","occurred_to","scope_selected","scope_tenant_ids","scope_place_ids");
    private static final Set<String> DEVICE_PARAMETERS=Set.of("page","size","keyword","physical_state","response_state","conclusion","occurred_from","occurred_to");
    private final RequestSecurity security; private final AlarmWorkbenchQueries workbench; private final ScopeFilter scopeFilter;
    public AlarmWorkbenchController(RequestSecurity security,AlarmWorkbenchQueries workbench,ScopeFilter scopeFilter) { this.security=security; this.workbench=workbench; this.scopeFilter=scopeFilter; }
    @GetMapping("/api/{audience:management|customer}/devices/{id}/alarms")
    public AlarmWorkbenchQueries.DeviceResult device(@PathVariable String audience,@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(required=false) String keyword,@RequestParam(name="physical_state",required=false) String physical,@RequestParam(name="response_state",required=false) String response,@RequestParam(required=false) String conclusion,@RequestParam(name="occurred_from",required=false) String from,@RequestParam(name="occurred_to",required=false) String to,HttpServletRequest request) {
        var actor=security.require(request,"device:read",audience); security.require(request,"alarm:read",audience); String tenant=security.tenant(request);
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!DEVICE_PARAMETERS.contains(e.getKey()) || e.getValue().length!=1))throw ApiError.bad("筛选参数无效或重复");
        return workbench.device(actor.user().id(),tenant,id,page,size,AlarmWorkbenchFilter.parse(keyword,physical,response,conclusion,from,to));
    }
    @GetMapping("/api/{audience:management|customer}/alarm-workbench")
    public AlarmWorkbenchQueries.Result query(@PathVariable String audience,@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(required=false) String keyword,@RequestParam(name="physical_state",required=false) String physical,@RequestParam(name="response_state",required=false) String response,@RequestParam(required=false) String conclusion,@RequestParam(name="occurred_from",required=false) String from,@RequestParam(name="occurred_to",required=false) String to,HttpServletRequest request) {
        var actor=security.require(request,"alarm:read",audience); String tenant=security.optionalTenant(request);
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!PARAMETERS.contains(e.getKey()) || e.getValue().length!=1)) throw ApiError.bad("筛选参数无效或重复");
        var selection=security.readSelection(request);
        var extra=selection.empty()?new ScopeFilter.Clause("1=0",java.util.List.of()):scopeFilter.alarmDevices("alarms",selection.tenantIds(),selection.placeIds());
        return workbench.query(actor.user().id(),tenant,page,size,AlarmWorkbenchFilter.parse(keyword,physical,response,conclusion,from,to),extra.sql(),extra.bindings());
    }
}
