package com.fireplatform.alarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.*;
import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import java.util.*;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class AlarmChangeChannelHandshake implements HandshakeInterceptor {
    public static final String LEASE=AlarmChangeChannelHandshake.class.getName()+".lease";
    public static final String PROTOCOL="alarm-changes.v1";
    private final AlarmChangeChannelTickets tickets;private final ChannelScopeAuthorization authority;private final AlarmChangeChannelPolicy policy;private final RuntimeConfig runtime;private final ApplicationAvailability availability;private final AuditService audit;private final ObjectMapper json;
    public AlarmChangeChannelHandshake(AlarmChangeChannelTickets tickets,ChannelScopeAuthorization authority,AlarmChangeChannelPolicy policy,RuntimeConfig runtime,ApplicationAvailability availability,AuditService audit,ObjectMapper json){this.tickets=tickets;this.authority=authority;this.policy=policy;this.runtime=runtime;this.availability=availability;this.audit=audit;this.json=json;}
    @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attrs)throws Exception{
        AlarmChangeChannelTickets.Lease lease=null;String actor=null,tenant=null;String requestId=UUID.randomUUID().toString();
        try{
            if(!(request instanceof ServletServerHttpRequest servlet))throw ApiError.bad("通道请求无效");var original=servlet.getServletRequest();requestId=RequestContext.requestId(original);
            String path=request.getURI().getRawPath();
            if(!Set.of("/api/management/alerts","/api/customer/alerts").contains(path)||request.getMethod()!=HttpMethod.GET||request.getURI().getRawQuery()!=null)throw ApiError.bad("通道请求无效");
            AlarmChangeChannelController.requireAvailable(policy,runtime,availability);String origin=AlarmChangeChannelController.origin(original,policy);
            if(!(original.getServletContext().getAttribute("jakarta.websocket.server.ServerContainer") instanceof org.apache.tomcat.websocket.server.WsServerContainer))throw new ApiError(503,"CHANNEL_UNAVAILABLE","通道暂不可用");
            if(!"websocket".equalsIgnoreCase(original.getHeader("Upgrade"))||!"13".equals(original.getHeader("Sec-WebSocket-Version")))throw ApiError.bad("通道请求无效");
            var offers=new ArrayList<String>();int bytes=0;for(String line:Collections.list(original.getHeaders("Sec-WebSocket-Protocol"))){bytes+=line.length();if(bytes>policy.maxMessageBytes())throw ApiError.bad("通道协议无效");offers.addAll(Arrays.stream(line.split(",",-1)).map(String::trim).toList());}
            if(offers.size()!=2||Collections.frequency(offers,PROTOCOL)!=1)throw ApiError.bad("通道协议无效");String credential=offers.stream().filter(v->!v.equals(PROTOCOL)).findFirst().orElse("");if(!credential.matches("ticket\\.[A-Za-z0-9_-]{43}"))throw ApiError.bad("通道协议无效");
            if(original.getHeader("Authorization")!=null)throw ApiError.bad("通道凭据只能是一次性票");
            String audience=path.contains("/management/")?"management":"customer";if(audience.equals("customer")&&original.getHeader("X-Tenant-Id")!=null)throw ApiError.forbidden("客户单位由会话确定");
            lease=tickets.claim(credential.substring(7),origin,audience);var identity=authority.require(lease.tokenHash,audience,lease.tenant);actor=identity.user().id();tenant=lease.tenant;RequestContext.actor(original,actor,tenant);
            if(!tickets.claimUsable(lease))throw new ApiError(401,"CHANNEL_TICKET_INVALID","通道连接未确认");
            original.setAttribute(LEASE,lease);attrs.put(LEASE,lease);return true;
        }catch(ApiError e){tickets.release(lease);if(e.status<500){try{audit.record(actor,tenant,"CHANNEL_UPGRADE",null,"DENIED",requestId);}catch(DataAccessException failed){deny(response,503,"STORAGE_UNAVAILABLE",requestId);return false;}}deny(response,e.status,e.code,requestId);return false;}
        catch(RuntimeException e){tickets.release(lease);deny(response,503,"CHANNEL_UNAVAILABLE",requestId);return false;}
    }
    private void deny(ServerHttpResponse response,int status,String code,String requestId)throws Exception{response.setStatusCode(HttpStatus.valueOf(status));response.getHeaders().setContentType(MediaType.APPLICATION_JSON);response.getHeaders().setCacheControl("no-store");json.writeValue(response.getBody(),new ApiError.Body(code,"通道连接未确认",requestId));}
    @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception exception){if(request instanceof ServletServerHttpRequest servlet){Object value=servlet.getServletRequest().getAttribute(LEASE);servlet.getServletRequest().removeAttribute(LEASE);if((exception!=null||!(response instanceof ServletServerHttpResponse http)||http.getServletResponse().getStatus()!=101)&&value instanceof AlarmChangeChannelTickets.Lease lease)tickets.release(lease);}}
}
