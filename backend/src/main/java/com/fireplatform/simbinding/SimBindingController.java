package com.fireplatform.simbinding;

import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import static com.fireplatform.simbinding.SimBindingModels.*;

@RestController
public class SimBindingController {
    private static final String BASE="/api/management/sim-cards/{card_id}";
    private final RequestSecurity security;private final SimBindingInputs inputs;private final SimBindingQueries queries;private final SimBindingCommands commands;
    public SimBindingController(RequestSecurity security,SimBindingInputs inputs,SimBindingQueries queries,SimBindingCommands commands){this.security=security;this.inputs=inputs;this.queries=queries;this.commands=commands;}
    private AuthService.Identity reader(HttpServletRequest request){var actor=security.require(request,"sim-cards:read","management");security.require(request,"device:read","management");if(!"PLATFORM".equals(actor.user().userType()))throw ApiError.forbidden("仅平台账号可访问SIM设备关联");return actor;}
    private String read(HttpServletRequest request,Set<String> keys){reader(request);String tenant=security.tenant(request);if(keys.isEmpty()&&request.getQueryString()!=null)throw ApiError.bad("当前关联查询不接受query参数");if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw ApiError.bad("关联查询不接受请求体");for(var entry:request.getParameterMap().entrySet())if(!keys.contains(entry.getKey())||entry.getValue().length!=1)throw ApiError.bad("关联查询参数未知或重复");return tenant;}
    @GetMapping(BASE+"/association") public State state(@PathVariable("card_id")String card,HttpServletRequest request){var actor=reader(request);return queries.state(actor,read(request,Set.of()),card);}
    @GetMapping(BASE+"/association-changes") public HistoryPage history(@PathVariable("card_id")String card,HttpServletRequest request){var actor=reader(request);String tenant=read(request,Set.of("page","size"));return queries.history(actor,tenant,card,SimBindingInputs.page(request.getParameter("page"),1),SimBindingInputs.page(request.getParameter("size"),null));}
    private AuthService.Identity writer(HttpServletRequest request){var actor=reader(request);security.require(request,"sim-cards:bind","management");if(request.getQueryString()!=null||!request.getParameterMap().isEmpty())throw ApiError.bad("关联写入不接受query参数");try{MediaType type=MediaType.parseMediaType(request.getContentType()==null?"":request.getContentType());if(!type.getType().equalsIgnoreCase("application")||!type.getSubtype().equalsIgnoreCase("json"))throw ApiError.bad("关联写入仅接受application/json");}catch(org.springframework.http.InvalidMediaTypeException e){throw ApiError.bad("关联写入仅接受application/json");}return actor;}
    private byte[] body(HttpServletRequest request){try{byte[] raw=request.getInputStream().readNBytes(Limits.MAX_JSON_BODY_BYTES+1);if(raw.length>Limits.MAX_JSON_BODY_BYTES)throw ApiError.bad("关联JSON超过资源上界");return raw;}catch(IOException e){throw ApiError.bad("关联JSON读取未完成");}}
    private Receipt commit(Supplier<Receipt> write){try{return write.get();}catch(DataAccessException failure){if(SimBindingConflicts.occupied(failure))throw SimBindingProjection.occupied();throw failure;}}
    @PostMapping(BASE+"/bind") public Receipt bind(@PathVariable("card_id")String card,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){var actor=writer(request);String tenant=security.tenant(request);var input=inputs.command(body(request));return commit(()->commands.bind(actor,tenant,card,key,input,RequestContext.requestId(request)));}
    @PostMapping(BASE+"/unbind") public Receipt unbind(@PathVariable("card_id")String card,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){var actor=writer(request);String tenant=security.tenant(request);var input=inputs.command(body(request));return commit(()->commands.unbind(actor,tenant,card,key,input,RequestContext.requestId(request)));}
}
