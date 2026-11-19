package com.fireplatform.duty;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController
public class DutyController {
    private static final Set<String> LIST_PARAMS=Set.of("page","size","keyword","from","to","scope_selected","scope_tenant_ids","scope_place_ids");
    public record HandleFault(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long expected_revision,@jakarta.validation.constraints.NotNull Boolean handled,@jakarta.validation.constraints.Size(max=1000) String note) { }
    private final RequestSecurity security; private final DutyQueries queries; private final DutyTelemetryQueries telemetry; private final FaultHandlingService handling; private final DutyAnalyticsQueries analytics;
    public DutyController(RequestSecurity security,DutyQueries queries,DutyTelemetryQueries telemetry,FaultHandlingService handling,DutyAnalyticsQueries analytics){this.security=security;this.queries=queries;this.telemetry=telemetry;this.handling=handling;this.analytics=analytics;}
    @GetMapping("/api/{audience:management|customer}/duty/faults")
    public com.fireplatform.shared.Page<DutyQueries.FaultEvent> faults(@PathVariable String audience,
        @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) String from,@RequestParam(required=false) String to,
        @RequestParam(name="handling_state",required=false) String handlingState,HttpServletRequest request) {
        var identity=security.require(request,"device:read",audience);
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!(LIST_PARAMS.contains(e.getKey())||e.getKey().equals("handling_state"))||e.getValue().length!=1)) throw ApiError.bad("故障查询参数无效或重复");
        OffsetDateTime start=parse(from),end=parse(to); if(start!=null&&end!=null&&!start.isBefore(end))throw ApiError.bad("结束时间必须晚于开始时间");
        var selection=security.readSelection(request);
        return queries.faults(identity.user().id(),security.optionalTenant(request),selection,keyword,start,end,handlingState,page,size);
    }
    @GetMapping("/api/{audience:management|customer}/duty/device-faults")
    public com.fireplatform.shared.Page<DutyQueries.DeviceFaultSummary> deviceFaults(@PathVariable String audience,
        @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) String from,@RequestParam(required=false) String to,
        HttpServletRequest request) {
        var identity=security.require(request,"device:read",audience);
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!LIST_PARAMS.contains(e.getKey())||e.getValue().length!=1))throw ApiError.bad("设备故障查询参数无效或重复");
        OffsetDateTime start=parse(from),end=parse(to);if(start!=null&&end!=null&&!start.isBefore(end))throw ApiError.bad("结束时间必须晚于开始时间");
        return queries.deviceFaults(identity.user().id(),security.optionalTenant(request),security.readSelection(request),keyword,start,end,page,size);
    }
    @GetMapping("/api/{audience:management|customer}/duty/device-faults/{deviceId}/events")
    public com.fireplatform.shared.Page<DutyQueries.FaultEvent> deviceFaultEvents(@PathVariable String audience,@PathVariable String deviceId,
        @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,
        @RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(name="handling_state",required=false) String handlingState,HttpServletRequest request) {
        var identity=security.require(request,"device:read",audience);
        var allowed=Set.of("page","size","from","to","handling_state","scope_selected","scope_tenant_ids","scope_place_ids");
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!allowed.contains(e.getKey())||e.getValue().length!=1))throw ApiError.bad("设备故障记录查询参数无效或重复");
        OffsetDateTime start=parse(from),end=parse(to);if(start!=null&&end!=null&&!start.isBefore(end))throw ApiError.bad("结束时间必须晚于开始时间");
        return queries.faultsForDevice(identity.user().id(),security.optionalTenant(request),security.readSelection(request),deviceId,null,start,end,handlingState,page,size);
    }
    @GetMapping("/api/{audience:management|customer}/duty/faults/{id}")
    public DutyQueries.FaultEvent fault(@PathVariable String audience,@PathVariable String id,HttpServletRequest request) {
        var identity=security.require(request,"device:read",audience);
        return queries.fault(identity.user().id(),security.optionalTenant(request),security.readSelection(request),id);
    }
    @GetMapping("/api/{audience:management|customer}/duty/faults/{id}/handling-history")
    public java.util.List<FaultHandlingService.Handling> handlingHistory(@PathVariable String audience,@PathVariable String id,HttpServletRequest request){
        var identity=security.require(request,"device:read",audience);
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!Set.of("scope_selected","scope_tenant_ids","scope_place_ids").contains(e.getKey())||e.getValue().length!=1))throw ApiError.bad("故障处置历史范围参数无效");
        var event=queries.fault(identity.user().id(),security.optionalTenant(request),security.readSelection(request),id);
        return handling.history(identity.user().id(),event.tenantId(),id);
    }
    @PostMapping("/api/{audience:management|customer}/duty/faults/{id}/handling")
    public FaultHandlingService.Handling handle(@PathVariable String audience,@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@jakarta.validation.Valid @RequestBody HandleFault body,HttpServletRequest request){
        var identity=security.require(request,"faults:handle",audience);
        return handling.set(identity,audience,security.tenant(request),id,key,body.handled(),body.expected_revision(),body.note(),com.fireplatform.shared.RequestContext.requestId(request));
    }
    @GetMapping("/api/{audience:management|customer}/duty/analytics")
    public DutyAnalyticsQueries.Analytics analytics(@PathVariable String audience,@RequestParam(defaultValue="7") int days,HttpServletRequest request){
        var identity=security.require(request,"summary:read",audience);
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!Set.of("days","scope_selected","scope_tenant_ids","scope_place_ids").contains(e.getKey())||e.getValue().length!=1))throw ApiError.bad("运维分析范围参数无效");
        return analytics.read(identity.user().id(),security.optionalTenant(request),security.readSelection(request),days);
    }
    @GetMapping("/api/{audience:management|customer}/duty/telemetry/metrics")
    public java.util.List<DutyTelemetryQueries.Metric> metrics(@PathVariable String audience,HttpServletRequest request){
        var identity=security.require(request,"device:read",audience);
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!Set.of("scope_selected","scope_tenant_ids","scope_place_ids").contains(e.getKey())||e.getValue().length!=1))throw ApiError.bad("监测指标范围参数无效");
        return telemetry.metrics(identity.user().id(),security.optionalTenant(request),security.readSelection(request));
    }
    @GetMapping("/api/{audience:management|customer}/duty/parameters")
    public com.fireplatform.shared.Page<DutyTelemetryQueries.Sample> parameters(@PathVariable String audience,
        @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(required=false) String metric,
        @RequestParam(required=false) String from,@RequestParam(required=false) String to,HttpServletRequest request) {
        var identity=security.require(request,"device:read",audience);
        var allowed=Set.of("page","size","metric","from","to","scope_selected","scope_tenant_ids","scope_place_ids");
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!allowed.contains(e.getKey())||e.getValue().length!=1))throw ApiError.bad("监测查询参数无效或重复");
        OffsetDateTime start=parse(from),end=parse(to);if(start!=null&&end!=null&&!start.isBefore(end))throw ApiError.bad("结束时间必须晚于开始时间");
        return telemetry.history(identity.user().id(),security.optionalTenant(request),security.readSelection(request),metric,start,end,page,size);
    }
    @GetMapping("/api/{audience:management|customer}/duty/parameters/current")
    public com.fireplatform.shared.Page<DutyTelemetryQueries.Sample> currentParameters(@PathVariable String audience,
        @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(required=false) String metric,HttpServletRequest request) {
        var identity=security.require(request,"device:read",audience);
        var allowed=Set.of("page","size","metric","scope_selected","scope_tenant_ids","scope_place_ids");
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!allowed.contains(e.getKey())||e.getValue().length!=1))throw ApiError.bad("当前监测查询参数无效或重复");
        return telemetry.current(identity.user().id(),security.optionalTenant(request),security.readSelection(request),metric,page,size);
    }
    @GetMapping("/api/{audience:management|customer}/duty/parameters/analytics")
    public DutyTelemetryQueries.Analytics parameterAnalytics(@PathVariable String audience,@RequestParam String metric,@RequestParam(defaultValue="7") int days,HttpServletRequest request){
        var identity=security.require(request,"device:read",audience);
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!Set.of("metric","days","scope_selected","scope_tenant_ids","scope_place_ids").contains(e.getKey())||e.getValue().length!=1))throw ApiError.bad("监测分析参数无效或重复");
        return telemetry.analytics(identity.user().id(),security.optionalTenant(request),security.readSelection(request),metric,days);
    }
    private static OffsetDateTime parse(String value){if(value==null||value.isBlank())return null;try{return OffsetDateTime.parse(value);}catch(RuntimeException e){throw ApiError.bad("时间必须为带时区的ISO-8601时间");}}
    
}
