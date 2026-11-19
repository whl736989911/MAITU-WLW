package com.fireplatform.alarm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fireplatform.configuration.*;
import com.fireplatform.identity.*;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.availability.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AlarmChangeChannelController {
    public record Scope(String tenantId,String audience,String session) { }
    public record Bootstrap(int v,Scope scope,String ticket,int connectTimeoutMs,int silenceTimeoutMs) { @Override public String toString(){return "Bootstrap[credential withheld]";} }
    private final RequestSecurity security;private final ChannelScopeAuthorization authority;private final AlarmChangeChannelTickets tickets;private final AlarmChangeChannelPolicy policy;private final RuntimeConfig runtime;private final ApplicationAvailability availability;
    public AlarmChangeChannelController(RequestSecurity security,ChannelScopeAuthorization authority,AlarmChangeChannelTickets tickets,AlarmChangeChannelPolicy policy,RuntimeConfig runtime,ApplicationAvailability availability){this.security=security;this.authority=authority;this.tickets=tickets;this.policy=policy;this.runtime=runtime;this.availability=availability;}
    @PostMapping("/api/{audience:management|customer}/alarm-change-channel")
    public Bootstrap create(@PathVariable String audience,@RequestBody JsonNode body,HttpServletRequest request){
        var identity=security.require(request,"alarm:read",audience);String tenant=security.tenant(request);
        if(request.getQueryString()!=null||!body.isObject()||!body.isEmpty())throw ApiError.bad("通道初始化只接受空对象且没有查询参数");
        requireAvailable(policy,runtime,availability);String origin=origin(request,policy);authority.require(identity.tokenHash(),audience,tenant);
        var issued=tickets.issue(identity.tokenHash(),tenant,audience,origin,identity.user().id());
        return new Bootstrap(1,new Scope(tenant,audience,AlarmChangeCursor.session(identity.tokenHash())),issued.ticket(),policy.connectTimeoutMs(),policy.silenceTimeoutMs());
    }
    static void requireAvailable(AlarmChangeChannelPolicy policy,RuntimeConfig runtime,ApplicationAvailability availability){if(!policy.enabled()||!runtime.alarmChangeReadEnabled()||availability.getReadinessState()!=ReadinessState.ACCEPTING_TRAFFIC)throw new ApiError(503,"CHANNEL_UNAVAILABLE","通道暂不可用");}
    static String origin(HttpServletRequest request,AlarmChangeChannelPolicy policy){var values=Collections.list(request.getHeaders("Origin"));if(values.size()!=1||!policy.acceptsOrigin(values.getFirst()))throw ApiError.forbidden("通道来源不被允许");return values.getFirst();}
}
