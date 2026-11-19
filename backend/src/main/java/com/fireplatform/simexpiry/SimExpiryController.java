package com.fireplatform.simexpiry;

import com.fireplatform.simexpiry.SimExpiryModels.Page;
import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import static com.fireplatform.simexpiry.SimExpiryModels.*;

@RestController
public class SimExpiryController {
    private static final String BASE="/api/management/sim-expiry";
    private final RequestSecurity security;private final ManagementGuard guard;private final SimExpiryInputs inputs;private final SimExpiryQueries queries;private final SimExpiryCommands commands;
    public SimExpiryController(RequestSecurity s,ManagementGuard g,SimExpiryInputs i,SimExpiryQueries q,SimExpiryCommands c){security=s;guard=g;inputs=i;queries=q;commands=c;}
    private AuthService.Identity actor(HttpServletRequest r,String perm){var a=security.identity(r);if(!a.audience().equals("management")||!"PLATFORM".equals(a.user().userType()))throw ApiError.forbidden("仅平台账号可访问到期提醒");String tenant=security.tenant(r);if(!guard.hasCurrentPermission(a,"sim-expiry:read")||perm!=null&&!guard.hasCurrentPermission(a,perm))throw ApiError.forbidden("没有此操作权限");return a;}
    private String tenant(HttpServletRequest r){actor(r,null);String t=security.tenant(r);if(r.getContentLengthLong()>0||r.getHeader("Transfer-Encoding")!=null)throw ApiError.bad("查询不接受请求体");return t;}
    private void query(HttpServletRequest r,Set<String> allowed){tenant(r);if(r.getQueryString()==null)return;for(var e:r.getParameterMap().entrySet())if(!allowed.contains(e.getKey())||e.getValue().length!=1)throw ApiError.bad("未知或重复查询参数");}
    private byte[] body(HttpServletRequest r){if(r.getQueryString()!=null||!r.getParameterMap().isEmpty())throw ApiError.bad("写入不接受query");try{MediaType t=MediaType.parseMediaType(r.getContentType()==null?"":r.getContentType());if(!"application".equalsIgnoreCase(t.getType())||!"json".equalsIgnoreCase(t.getSubtype()))throw ApiError.bad("仅接受application/json");}catch(org.springframework.http.InvalidMediaTypeException e){throw ApiError.bad("仅接受application/json");}try{byte[] b=r.getInputStream().readNBytes(Limits.MAX_JSON_BODY_BYTES+1);if(b.length>Limits.MAX_JSON_BODY_BYTES)throw ApiError.bad("JSON超过资源上界");return b;}catch(IOException e){throw ApiError.bad("JSON读取未完成");}}
    /** Bodyless writes still reject query framing; no actor confirmation body exists. */
    private void frame(HttpServletRequest r){if(r.getQueryString()!=null||!r.getParameterMap().isEmpty())throw ApiError.bad("写入不接受query");}
    private String key(HttpServletRequest r){return r.getHeader("Idempotency-Key");}
    private int page(String s,int fallback){return integer(s,fallback,1,Limits.MAX_PAGE);}
    private Integer size(String s){return s==null?null:integer(s,0,1,Limits.MAX_PAGE_SIZE);}
    private int integer(String s,int fallback,int min,int max){if(s==null)return fallback;if(!s.matches("[1-9][0-9]*"))throw ApiError.bad("分页参数无效");try{int n=Integer.parseInt(s);if(n<min||n>max)throw ApiError.bad("分页参数超出范围");return n;}catch(NumberFormatException e){throw ApiError.bad("分页参数超出范围");}}
    private void fresh(String permission,HttpServletRequest r){var a=actor(r,permission);}
    @GetMapping(BASE+"/config") public Config config(HttpServletRequest r){fresh("sim-expiry:write",r);query(r,Set.of());return queries.readConfig(security.tenant(r));}
    @PutMapping(BASE+"/config") public Config configure(@RequestHeader(value="Idempotency-Key",required=false)String k,HttpServletRequest r){var a=security.require(r,"sim-expiry:read","management");security.require(r,"sim-expiry:write","management");String t=security.tenant(r);var in=inputs.config(body(r));return commands.configure(a,t,k,in,RequestContext.requestId(r));}
    @GetMapping(BASE+"/recipients") public Page<Recipient> recipients(HttpServletRequest r){fresh("sim-expiry:write",r);query(r,Set.of("page","size"));return queries.readRecipients(security.tenant(r),page(r.getParameter("page"),1),size(r.getParameter("size")));}
    @PostMapping(BASE+"/recipients") public Recipient add(@RequestHeader(value="Idempotency-Key",required=false)String k,HttpServletRequest r){var a=security.require(r,"sim-expiry:read","management");security.require(r,"sim-expiry:write","management");return commands.addRecipient(a,security.tenant(r),k,inputs.recipient(body(r)),RequestContext.requestId(r));}
    @DeleteMapping(BASE+"/recipients/{user_id}") public Map<String,Object> remove(@PathVariable("user_id") String user_id,@RequestHeader(value="Idempotency-Key",required=false)String k,HttpServletRequest r){var a=security.require(r,"sim-expiry:read","management");security.require(r,"sim-expiry:write","management");frame(r);return commands.removeRecipient(a,security.tenant(r),user_id,k,RequestContext.requestId(r));}
    @GetMapping(BASE+"/rounds") public Page<Round> rounds(HttpServletRequest r){actor(r,null);query(r,Set.of("page","size","state"));String state=r.getParameter("state");if(state!=null&&!Set.of("OPEN","HANDLED","SUPERSEDED").contains(state))throw ApiError.bad("轮次状态无效");var a=security.identity(r);boolean manager=guard.hasCurrentPermission(a,"sim-expiry:handle")||guard.hasCurrentPermission(a,"sim-expiry:write");if(!manager&&!queries.eligible(a.user().id(),security.tenant(r)))throw ApiError.forbidden("当前用户不是有效提醒收件人");return queries.readRounds(security.tenant(r),a.user().id(),state,page(r.getParameter("page"),1),size(r.getParameter("size")),!manager);}
    @PostMapping(BASE+"/scan") public Scan scan(@RequestHeader(value="Idempotency-Key",required=false)String k,HttpServletRequest r){var a=security.require(r,"sim-expiry:read","management");security.require(r,"sim-expiry:write","management");frame(r);return commands.explicitScan(a,security.tenant(r),k,RequestContext.requestId(r));}
    @PostMapping(BASE+"/rounds/{id}/read") public ReadReceipt read(@PathVariable("id") String id,@RequestHeader(value="Idempotency-Key",required=false)String k,HttpServletRequest r){var a=security.require(r,"sim-expiry:read","management");frame(r);return commands.read(a,security.tenant(r),id,k,RequestContext.requestId(r));}
    @PostMapping(BASE+"/rounds/{id}/handle") public Round handle(@PathVariable("id") String id,@RequestHeader(value="Idempotency-Key",required=false)String k,HttpServletRequest r){var a=security.require(r,"sim-expiry:read","management");security.require(r,"sim-expiry:handle","management");return commands.handle(a,security.tenant(r),id,k,inputs.handle(body(r)),RequestContext.requestId(r));}
    @PostMapping(BASE+"/rounds/{id}/reopen") public Round reopen(@PathVariable("id") String id,@RequestHeader(value="Idempotency-Key",required=false)String k,HttpServletRequest r){var a=security.require(r,"sim-expiry:read","management");security.require(r,"sim-expiry:handle","management");return commands.reopen(a,security.tenant(r),id,k,inputs.handle(body(r)),RequestContext.requestId(r));}
}
