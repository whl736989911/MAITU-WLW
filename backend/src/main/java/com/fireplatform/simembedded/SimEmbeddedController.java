package com.fireplatform.simembedded;

import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import static com.fireplatform.simembedded.SimEmbeddedModels.*;

@RestController
public class SimEmbeddedController {
    private static final String BASE="/api/management/embedded-sim-profiles";
    private final RequestSecurity security;private final SimEmbeddedInputs inputs;private final SimEmbeddedQueries queries;private final SimEmbeddedCommands commands;
    public SimEmbeddedController(RequestSecurity security,SimEmbeddedInputs inputs,SimEmbeddedQueries queries,SimEmbeddedCommands commands){this.security=security;this.inputs=inputs;this.queries=queries;this.commands=commands;}
    private AuthService.Identity reader(HttpServletRequest request){var actor=security.require(request,"device:read","management");security.require(request,"sim-embedded:read","management");if(!"PLATFORM".equals(actor.user().userType()))throw ApiError.forbidden("仅平台账号可访问内置人工资料");return actor;}
    private String read(HttpServletRequest request,Set<String> allowed){reader(request);String tenant=security.tenant(request);if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw ApiError.bad("资料查询不接受请求体");for(var entry:request.getParameterMap().entrySet())if(!allowed.contains(entry.getKey())||entry.getValue().length!=1)throw ApiError.bad("资料查询参数未知或重复");SimEmbeddedInputs.device(request.getParameter("device_id"));return tenant;}
    @GetMapping(BASE) public Profile profile(HttpServletRequest request){var actor=reader(request);String tenant=read(request,Set.of("device_id"));return queries.profile(actor,tenant,request.getParameter("device_id"));}
    @GetMapping("/api/management/embedded-sim-profile-changes") public HistoryPage history(HttpServletRequest request){var actor=reader(request);String tenant=read(request,Set.of("device_id","page","size"));return queries.history(actor,tenant,request.getParameter("device_id"),SimEmbeddedInputs.page(request.getParameter("page"),1),SimEmbeddedInputs.page(request.getParameter("size"),null));}
    private byte[] body(HttpServletRequest request){try{byte[] raw=request.getInputStream().readNBytes(Limits.MAX_JSON_BODY_BYTES+1);if(raw.length>Limits.MAX_JSON_BODY_BYTES)throw ApiError.bad("资料JSON超过资源上界");return raw;}catch(IOException e){throw ApiError.bad("资料JSON读取未完成");}}
    @PostMapping(BASE) public Profile record(@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){var actor=reader(request);security.require(request,"sim-embedded:write","management");String tenant=security.tenant(request);if(request.getQueryString()!=null||!request.getParameterMap().isEmpty())throw ApiError.bad("资料写入不接受query");try{MediaType type=MediaType.parseMediaType(request.getContentType()==null?"":request.getContentType());if(!type.getType().equalsIgnoreCase("application")||!type.getSubtype().equalsIgnoreCase("json"))throw ApiError.bad("资料写入仅接受application/json");}catch(org.springframework.http.InvalidMediaTypeException e){throw ApiError.bad("资料写入仅接受application/json");}return commands.record(actor,tenant,key,inputs.command(body(request)),RequestContext.requestId(request));}
}
