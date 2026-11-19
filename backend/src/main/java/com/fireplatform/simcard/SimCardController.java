package com.fireplatform.simcard;

import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import static com.fireplatform.simcard.SimCardModels.*;

@RestController
public class SimCardController {
    private static final String BASE="/api/management/sim-cards";
    private final RequestSecurity security;private final SimCardInputs inputs;private final SimCardQueries queries;private final SimCardCommands commands;
    public SimCardController(RequestSecurity security,SimCardInputs inputs,SimCardQueries queries,SimCardCommands commands){this.security=security;this.inputs=inputs;this.queries=queries;this.commands=commands;}
    private AuthService.Identity platform(HttpServletRequest request,String permission){var actor=security.require(request,permission,"management");if(!"PLATFORM".equals(actor.user().userType()))throw ApiError.forbidden("仅平台账号可访问SIM手工台账");return actor;}
    private String read(HttpServletRequest request,Set<String> keys){
        platform(request,"sim-cards:read");String tenant=security.tenant(request);
        if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw ApiError.bad("只读SIM查询不接受请求体");
        for(var entry:request.getParameterMap().entrySet())if(!keys.contains(entry.getKey())||entry.getValue().length!=1)throw ApiError.bad("SIM查询参数未知或重复");return tenant;
    }
    private int page(HttpServletRequest request){return SimCardFilter.number(request.getParameter("page"),1);}
    private Integer size(HttpServletRequest request){return SimCardFilter.number(request.getParameter("size"),null);}
    private void id(String value){if(!SimCardProjection.uuid(value))throw SimCardProjection.missing();}
    @GetMapping(BASE) public CardPage cards(HttpServletRequest request){String tenant=read(request,Set.of("page","size","keyword"));return queries.cards(tenant,page(request),size(request),SimCardFilter.parse(request.getParameter("keyword")));}
    @GetMapping(BASE+"/{id}") public Card detail(@PathVariable String id,HttpServletRequest request){String tenant=read(request,Set.of());id(id);return queries.detail(tenant,id);}
    @GetMapping(BASE+"/{id}/changes") public ChangesPage changes(@PathVariable String id,HttpServletRequest request){String tenant=read(request,Set.of("page","size"));id(id);return queries.changes(tenant,id,page(request),size(request));}
    private AuthService.Identity writer(HttpServletRequest request){
        var actor=platform(request,"sim-cards:write");security.require(request,"sim-cards:read","management");
        if(request.getQueryString()!=null||!request.getParameterMap().isEmpty())throw ApiError.bad("SIM写入不接受query参数");
        try{var type=MediaType.parseMediaType(request.getContentType()==null?"":request.getContentType());if(!type.getType().equalsIgnoreCase("application")||!type.getSubtype().equalsIgnoreCase("json"))throw ApiError.bad("SIM写入仅接受application/json");}catch(org.springframework.http.InvalidMediaTypeException e){throw ApiError.bad("SIM写入仅接受application/json");}return actor;
    }
    private byte[] body(HttpServletRequest request){try{byte[] raw=request.getInputStream().readNBytes(Limits.MAX_JSON_BODY_BYTES+1);if(raw.length>Limits.MAX_JSON_BODY_BYTES)throw ApiError.bad("SIM JSON超过资源上界");return raw;}catch(IOException e){throw ApiError.bad("SIM JSON读取未完成");}}
    /** The service proxy has rolled back before a driver conflict is classified here. */
    private Card commit(Supplier<Card> write){try{return write.get();}catch(DataAccessException failure){if(SimCardConflicts.iccid(failure))throw ApiError.conflict("SIM_CARD_ICCID_CONFLICT","此单位内已存在相同ICCID的手工台账");throw failure;}}
    @PostMapping(BASE) public Card create(@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){var actor=writer(request);String tenant=security.tenant(request);var input=inputs.metadata(body(request),false);return commit(()->commands.create(actor,tenant,key,input,RequestContext.requestId(request)));}
    @PutMapping(BASE+"/{id}") public Card edit(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){var actor=writer(request);String tenant=security.tenant(request);id(id);var input=inputs.metadata(body(request),true);return commit(()->commands.edit(actor,tenant,id,key,input,RequestContext.requestId(request)));}
}
