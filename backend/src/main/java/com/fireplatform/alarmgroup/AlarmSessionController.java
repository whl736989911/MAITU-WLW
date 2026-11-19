package com.fireplatform.alarmgroup;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AlarmSessionController {
    private static final Set<String> PARAMETERS=Set.of("page","size","device_id","event_type","scope_selected","scope_tenant_ids","scope_place_ids");
    private final RequestSecurity security;
    private final AlarmSessionQueries queries;

    public AlarmSessionController(RequestSecurity security,AlarmSessionQueries queries) { this.security=security; this.queries=queries; }

    @GetMapping("/api/{audience:management|customer}/alarm-sessions")
    public Page<AlarmSessionQueries.SessionRow> sessions(@PathVariable String audience,
            @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,
            @RequestParam(name="device_id",required=false) String deviceId,
            @RequestParam(name="event_type",required=false) String eventType,HttpServletRequest request) {
        var actor=security.require(request,"alarm:read",audience);
        for(var entry:request.getParameterMap().entrySet()) {
            if(!PARAMETERS.contains(entry.getKey())||entry.getValue().length!=1) throw com.fireplatform.shared.ApiError.bad("会合组查询参数无效或重复");
        }
        if(deviceId!=null&&(deviceId.isBlank()||deviceId.length()>64)) throw com.fireplatform.shared.ApiError.bad("设备筛选无效");
        if(eventType!=null&&!eventType.isEmpty()&&!"FIRE".equals(eventType)) throw com.fireplatform.shared.ApiError.bad("事件类型筛选无效");
        return queries.sessions(actor.user().id(),security.optionalTenant(request),page,size,security.readSelection(request),deviceId==null||deviceId.isBlank()?null:deviceId,eventType==null||eventType.isEmpty()?null:eventType);
    }

    @GetMapping("/api/{audience:management|customer}/alarm-sessions/{id}")
    public AlarmSessionQueries.SessionDetail session(@PathVariable String audience,@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"alarm:read",audience);
        return queries.session(actor.user().id(),security.optionalTenant(request),id);
    }

    @GetMapping("/api/{audience:management|customer}/alarm-sessions/{id}/members")
    public Page<AlarmSessionQueries.MemberRow> members(@PathVariable String audience,@PathVariable String id,
            @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,HttpServletRequest request) {
        var actor=security.require(request,"alarm:read",audience);
        return queries.members(actor.user().id(),security.optionalTenant(request),id,page,size);
    }
}
