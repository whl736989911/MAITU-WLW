package com.fireplatform.alarm;

import com.fireplatform.configuration.*;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.web.bind.annotation.*;

/** Read-only descriptor; a policy snapshot never issues a ticket or renews authority. */
@RestController
public class AlarmChangeReconnectController {
    public record Values(int maxRetries,int delayMs,int windowMs) { }
    public record Descriptor(int v,AlarmChangeChannelController.Scope scope,Values policy) { }
    private final RequestSecurity security;
    private final AlarmChangeReconnectPolicy policy;
    private final AlarmChangeChannelPolicy channel;
    private final RuntimeConfig runtime;
    private final ApplicationAvailability availability;
    public AlarmChangeReconnectController(RequestSecurity security,AlarmChangeReconnectPolicy policy,AlarmChangeChannelPolicy channel,RuntimeConfig runtime,ApplicationAvailability availability){
        this.security=security;this.policy=policy;this.channel=channel;this.runtime=runtime;this.availability=availability;
    }
    @GetMapping("/api/{audience:management|customer}/alarm-change-reconnect-policy")
    public Descriptor read(@PathVariable String audience,HttpServletRequest request){
        var identity=security.require(request,"alarm:read",audience);
        String tenant=security.tenant(request);
        // Current HTTP/1 profile: do not parse an unbounded GET entity or confuse absent length with a body.
        if(request.getQueryString()!=null||request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)
            throw ApiError.bad("重连策略读取不接受查询参数或请求体");
        AlarmChangeChannelController.requireAvailable(channel,runtime,availability);
        if(!policy.enabled())throw new ApiError(503,"RECONNECT_UNAVAILABLE","自动重连策略暂不可用");
        // Same-origin browser GET may omit Origin. An explicitly supplied Origin must still match B2 exactly.
        if(request.getHeaders("Origin").hasMoreElements())AlarmChangeChannelController.origin(request,channel);
        return new Descriptor(1,new AlarmChangeChannelController.Scope(tenant,audience,AlarmChangeCursor.session(identity.tokenHash())),new Values(policy.maxRetries(),policy.delayMs(),policy.windowMs()));
    }
}
