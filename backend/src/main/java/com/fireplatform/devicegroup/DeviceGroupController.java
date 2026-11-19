package com.fireplatform.devicegroup;

import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UrlPathHelper;
import static com.fireplatform.devicegroup.DeviceGroupModels.*;

@RestController
public class DeviceGroupController {
    private static final String M="/api/management/device-groups",C="/api/customer/device-groups";
    private static final Set<String> KEYS=Set.of("page","size","keyword","scope_selected","scope_tenant_ids","scope_place_ids");
    private final RequestSecurity security;private final DeviceGroupQueries queries;private final DeviceGroupCommands commands;private final DeviceGroupInputs inputs;
    public DeviceGroupController(RequestSecurity security,DeviceGroupQueries queries,DeviceGroupCommands commands,DeviceGroupInputs inputs){this.security=security;this.queries=queries;this.commands=commands;this.inputs=inputs;}
    private String read(HttpServletRequest request,boolean candidate){String audience=UrlPathHelper.defaultInstance.getPathWithinApplication(request).startsWith("/api/customer/")?"customer":"management";var actor=security.require(request,"device:read",audience);if(candidate&&(!actor.user().userType().equals("PLATFORM")||!actor.permissions().contains("device:write")))throw ApiError.forbidden("没有分组维护权限");security.readSelection(request);String tenant=security.optionalTenant(request);if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw ApiError.bad("只读分组查询不接受请求体");for(var entry:request.getParameterMap().entrySet())if(!KEYS.contains(entry.getKey())||entry.getValue().length!=1)throw ApiError.bad("分组查询参数未知或重复");return tenant;}
    private DeviceGroupFilter filter(HttpServletRequest request){return DeviceGroupFilter.parse(request.getParameter("keyword"));}
    private int page(HttpServletRequest request){return DeviceGroupFilter.number(request.getParameter("page"),1);}
    private Integer size(HttpServletRequest request){return DeviceGroupFilter.number(request.getParameter("size"),null);}
    private void id(String id){if(!DeviceGroupProjection.groupId(id))throw DeviceGroupProjection.missing();}
    @GetMapping({M,C}) public GroupPage groups(HttpServletRequest request){var actor=security.identity(request);String tenant=read(request,false);return queries.groups(actor.user().id(),tenant,page(request),size(request),filter(request),security.readSelection(request));}
    @GetMapping({M+"/{id}/members",C+"/{id}/members"}) public MemberPage members(@PathVariable String id,HttpServletRequest request){var actor=security.identity(request);String tenant=read(request,false);id(id);return queries.members(actor.user().id(),tenant,id,page(request),size(request),filter(request),security.readSelection(request));}
    @GetMapping(M+"/{id}/candidates") public CandidatePage candidates(@PathVariable String id,HttpServletRequest request){var actor=security.identity(request);String tenant=read(request,true);id(id);return queries.candidates(actor.user().id(),tenant,id,page(request),size(request),filter(request),security.readSelection(request));}
    private AuthService.Identity writer(HttpServletRequest request){var actor=security.require(request,"device:write","management");if(!actor.user().userType().equals("PLATFORM"))throw ApiError.forbidden("仅平台账号可维护分组");if(request.getQueryString()!=null||!request.getParameterMap().isEmpty())throw ApiError.bad("分组写入不接受query参数");try{var type=MediaType.parseMediaType(request.getContentType()==null?"":request.getContentType());if(!type.getType().equalsIgnoreCase("application")||!type.getSubtype().equalsIgnoreCase("json"))throw ApiError.bad("分组写入仅接受application/json");}catch(org.springframework.http.InvalidMediaTypeException e){throw ApiError.bad("分组写入仅接受application/json");}return actor;}
    private byte[] body(HttpServletRequest request){try{byte[] raw=request.getInputStream().readNBytes(Limits.MAX_JSON_BODY_BYTES+1);if(raw.length>Limits.MAX_JSON_BODY_BYTES)throw ApiError.bad("分组JSON超过资源上界");return raw;}catch(IOException e){throw ApiError.bad("分组JSON读取未完成");}}
    /** The injected service proxy has already rolled back before this boundary sees SQL errors. */
    private Group commit(Supplier<Group> write){try{return write.get();}catch(DataAccessException failure){if(DeviceGroupConflicts.name(failure))throw ApiError.conflict("DEVICE_GROUP_NAME_CONFLICT","单位内已存在同名设备分组");throw failure;}}
    @PostMapping(value=M) public Group create(@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){var actor=writer(request);String tenant=security.tenant(request);var input=inputs.metadata(body(request),false);return commit(()->commands.create(actor,tenant,key,input,RequestContext.requestId(request)));}
    @PutMapping(value=M+"/{id}") public Group edit(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){var actor=writer(request);String tenant=security.tenant(request);id(id);var input=inputs.metadata(body(request),true);return commit(()->commands.edit(actor,tenant,id,key,input,RequestContext.requestId(request)));}
    @PostMapping(value=M+"/{id}/members") public Group add(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){return membership(id,key,false,request);}
    @PostMapping(value=M+"/{id}/members/remove") public Group remove(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request){return membership(id,key,true,request);}
    private Group membership(String id,String key,boolean remove,HttpServletRequest request){var actor=writer(request);String tenant=security.tenant(request);id(id);var input=inputs.membership(body(request));return commit(()->commands.membership(actor,tenant,id,key,input,remove,RequestContext.requestId(request)));}
}
