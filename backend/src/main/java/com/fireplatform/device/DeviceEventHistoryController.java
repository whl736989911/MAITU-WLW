package com.fireplatform.device;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.ApiError;
import java.time.OffsetDateTime;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/{audience:management|customer}/devices/{id}/events")
public class DeviceEventHistoryController {
    private static final Set<String> PARAMETERS=Set.of("page","size","kind","from","to");
    private final RequestSecurity security;
    private final DeviceEventHistoryService events;

    public DeviceEventHistoryController(RequestSecurity security,DeviceEventHistoryService events) {
        this.security=security; this.events=events;
    }

    @GetMapping
    public Page<DeviceEventHistoryService.EventRow> list(@PathVariable String audience,@PathVariable String id,
            @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,
            @RequestParam(required=false) String kind,@RequestParam(required=false) String from,@RequestParam(required=false) String to,HttpServletRequest request) {
        var actor=security.require(request,"device:read",audience);
        if(request.getParameterMap().entrySet().stream().anyMatch(entry->!PARAMETERS.contains(entry.getKey())||entry.getValue().length!=1))throw ApiError.bad("设备事件查询参数无效或重复");
        return events.list(actor.user().id(),security.tenant(request),id,page,size,kind,parseTime(from),parseTime(to));
    }

    private static OffsetDateTime parseTime(String value) {
        if(value==null||value.isBlank())return null;
        try{return OffsetDateTime.parse(value);}catch(RuntimeException invalid){throw ApiError.bad("时间必须为带时区的ISO-8601时间");}
    }
}
