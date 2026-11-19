package com.fireplatform.device;

import com.fireplatform.duty.DutyTelemetryQueries;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/{audience:management|customer}/devices/{id}/telemetry")
public class DeviceTelemetryController {
    private final RequestSecurity security;
    private final DutyTelemetryQueries telemetry;

    public DeviceTelemetryController(RequestSecurity security,DutyTelemetryQueries telemetry) {
        this.security=security;this.telemetry=telemetry;
    }

    @GetMapping
    public Page<DutyTelemetryQueries.Sample> current(@PathVariable String audience,@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"device:read",audience);
        if(!request.getParameterMap().isEmpty())throw ApiError.bad("设备监测范围由设备路径确定，不接受查询参数");
        return telemetry.currentForDevice(actor.user().id(),security.tenant(request),id);
    }

    @GetMapping("/history")
    public Page<DutyTelemetryQueries.Sample> history(@PathVariable String audience,@PathVariable String id,
            @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,
            @RequestParam(required=false) String from,@RequestParam(required=false) String to,HttpServletRequest request) {
        var actor=security.require(request,"device:read",audience);
        var allowed=Set.of("page","size","from","to");
        if(request.getParameterMap().entrySet().stream().anyMatch(entry->!allowed.contains(entry.getKey())||entry.getValue().length!=1))throw ApiError.bad("设备监测历史参数无效或重复");
        OffsetDateTime start=parseTime(from),end=parseTime(to);
        if(start!=null&&end!=null&&!start.isBefore(end))throw ApiError.bad("结束时间必须晚于开始时间");
        return telemetry.historyForDevice(actor.user().id(),security.tenant(request),id,start,end,page,size);
    }

    private static OffsetDateTime parseTime(String value) {
        if(value==null||value.isBlank())return null;
        try{return OffsetDateTime.parse(value);}catch(RuntimeException invalid){throw ApiError.bad("时间必须为带时区的ISO-8601时间");}
    }
}
