package com.fireplatform.alarm;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class AlarmController {
    private final AlarmQueryService reads; private final AlarmReviewService reviews; private final RequestSecurity security; private final AlarmHistoryQueries history;
    public AlarmController(AlarmQueryService reads,AlarmReviewService reviews,RequestSecurity security,AlarmHistoryQueries history) { this.reads=reads; this.reviews=reviews; this.security=security; this.history=history; }
    @GetMapping("/api/{audience:management|customer}/alarms/{id}") public AlarmQueryService.AlarmRow current(@PathVariable String audience,@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"alarm:read",audience); return reads.alarm(actor.user().id(),security.tenant(request),id);
    }
    @GetMapping("/api/{audience:management|customer}/alarms/{id}/history") public AlarmTraceModels.History history(@PathVariable String audience,@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,HttpServletRequest request) {
        var identity=security.require(request,"alarm:read",audience); security.require(request,"alarm:history",audience); return history.history(identity.user().id(),security.tenant(request),id,page,size,identity.user().id(),audience.equals("management"));
    }
    @GetMapping("/api/{audience:management|customer}/alarms") public Page<AlarmQueryService.AlarmRow> list(@PathVariable String audience,@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(name="physical_state",required=false) String physicalState,HttpServletRequest request) {
        var actor=security.require(request,"alarm:read",audience);
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!java.util.Set.of("page","size","physical_state","scope_selected","scope_tenant_ids","scope_place_ids").contains(e.getKey())||e.getValue().length!=1)) throw com.fireplatform.shared.ApiError.bad("报警查询参数无效或重复");
        if(physicalState!=null&&!physicalState.isEmpty()&&!java.util.Set.of("FIRE","NORMAL").contains(physicalState)) throw com.fireplatform.shared.ApiError.bad("报警状态筛选无效");
        return reads.alarms(actor.user().id(),security.optionalTenant(request),page,size,security.readSelection(request),physicalState);
    }
    @PostMapping("/api/{audience:management|customer}/alarms/{id}/review") public AlarmQueryService.AlarmRow review(@PathVariable String audience,@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody AlarmReviewService.ReviewRequest body,HttpServletRequest request) {
        var identity=security.require(request,"alarm:review",audience); return reviews.review(identity,security.tenant(request),id,key,body,RequestContext.requestId(request));
    }
}
