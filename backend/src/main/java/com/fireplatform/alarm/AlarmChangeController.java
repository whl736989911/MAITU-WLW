package com.fireplatform.alarm;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController
public class AlarmChangeController {
    private final RequestSecurity security;private final AlarmChangeQueries queries;
    public AlarmChangeController(RequestSecurity security,AlarmChangeQueries queries){this.security=security;this.queries=queries;}
    @GetMapping("/api/{audience:management|customer}/alarm-changes")
    public AlarmChangeQueries.Result changes(@PathVariable String audience,@RequestParam(required=false) String cursor,@RequestParam(required=false) String through,@RequestParam(required=false) String size,HttpServletRequest request){
        var identity=security.require(request,"alarm:read",audience);String tenant=security.tenant(request);
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!Set.of("cursor","through","size").contains(e.getKey())||e.getValue().length!=1))throw ApiError.bad("变更查询参数无效或重复");
        Integer limit=null;if(size!=null){if(!size.matches("[1-9][0-9]{0,2}"))throw ApiError.bad("变更size无效");try{limit=Integer.valueOf(size);}catch(NumberFormatException e){throw ApiError.bad("变更size无效");}}
        return queries.read(identity,tenant,cursor,through,limit);
    }
}
