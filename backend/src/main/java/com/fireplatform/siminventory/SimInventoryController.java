package com.fireplatform.siminventory;

import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import static com.fireplatform.siminventory.SimInventoryModels.*;
import static com.fireplatform.siminventory.SimInventoryInputs.Operation;

@RestController
public class SimInventoryController {
    private static final String BASE="/api/management/sim-card-inventory";
    private final RequestSecurity security;private final SimInventoryInputs inputs;private final SimInventoryQueries queries;private final SimInventoryCommands commands;
    public SimInventoryController(RequestSecurity security,SimInventoryInputs inputs,SimInventoryQueries queries,SimInventoryCommands commands){this.security=security;this.inputs=inputs;this.queries=queries;this.commands=commands;}
    private AuthService.Identity reader(HttpServletRequest request){var actor=security.require(request,"sim-cards:read","management");security.require(request,"sim-inventory:read","management");if(!"PLATFORM".equals(actor.user().userType()))throw ApiError.forbidden("仅平台账号可访问SIM人工库存");return actor;}
    private String read(HttpServletRequest request,Set<String> allowed){reader(request);String tenant=security.tenant(request);if((allowed.isEmpty()&&request.getQueryString()!=null)||request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw ApiError.bad("库存查询不接受请求体或未知query");for(var entry:request.getParameterMap().entrySet())if(!allowed.contains(entry.getKey())||entry.getValue().length!=1)throw ApiError.bad("库存查询参数未知或重复");return tenant;}
    @GetMapping(BASE+"/{card_id}") public State state(@PathVariable("card_id")String card,HttpServletRequest request){return queries.state(read(request,Set.of()),card);}
    @GetMapping(BASE) public StatePage list(HttpServletRequest request){String tenant=read(request,Set.of("page","size","status"));return queries.list(tenant,SimInventoryInputs.page(request.getParameter("page"),1),SimInventoryInputs.page(request.getParameter("size"),null),SimInventoryInputs.status(request.getParameter("status")));}
    @GetMapping(BASE+"/{card_id}/changes") public HistoryPage history(@PathVariable("card_id")String card,HttpServletRequest request){String tenant=read(request,Set.of("page","size"));return queries.history(tenant,card,SimInventoryInputs.page(request.getParameter("page"),1),SimInventoryInputs.page(request.getParameter("size"),null));}
    private byte[] body(HttpServletRequest request){try{byte[] raw=request.getInputStream().readNBytes(Limits.MAX_JSON_BODY_BYTES+1);if(raw.length>Limits.MAX_JSON_BODY_BYTES)throw ApiError.bad("库存JSON超过资源上界");return raw;}catch(IOException e){throw ApiError.bad("库存JSON读取未完成");}}
    private State write(String card,String key,Operation operation,HttpServletRequest request){var actor=reader(request);security.require(request,"sim-inventory:write","management");String tenant=security.tenant(request);if(request.getQueryString()!=null||!request.getParameterMap().isEmpty())throw ApiError.bad("库存写入不接受query");try{MediaType type=MediaType.parseMediaType(request.getContentType()==null?"":request.getContentType());if(!type.getType().equalsIgnoreCase("application")||!type.getSubtype().equalsIgnoreCase("json"))throw ApiError.bad("库存写入仅接受application/json");}catch(org.springframework.http.InvalidMediaTypeException e){throw ApiError.bad("库存写入仅接受application/json");}return commands.record(actor,tenant,card,key,operation,inputs.command(body(request),operation),RequestContext.requestId(request));}
    @PostMapping(BASE+"/{card_id}/receive") public State receive(@PathVariable("card_id")String card,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){return write(card,key,Operation.RECEIVE,request);}
    @PostMapping(BASE+"/{card_id}/issue") public State issue(@PathVariable("card_id")String card,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){return write(card,key,Operation.ISSUE,request);}
    @PostMapping(BASE+"/{card_id}/recover") public State recover(@PathVariable("card_id")String card,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){return write(card,key,Operation.RECOVER,request);}
    @PostMapping(BASE+"/{card_id}/scrap") public State scrap(@PathVariable("card_id")String card,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){return write(card,key,Operation.SCRAP,request);}
    @PostMapping(BASE+"/{card_id}/correct") public State correct(@PathVariable("card_id")String card,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){return write(card,key,Operation.CORRECT,request);}
}
