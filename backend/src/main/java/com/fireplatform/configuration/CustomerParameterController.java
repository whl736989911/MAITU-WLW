package com.fireplatform.configuration;

import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Explicit customer self-service, never a management-token or arbitrary-scope facade. */
@RestController
@RequestMapping("/api/customer/unit-parameters")
public class CustomerParameterController {
    public record Settings(ParameterModels.Definition definition,ParameterModels.PolicyView policy,long allowedMaximum) { }
    public record Change(String id,String key,String action,Long previousValue,Long value,long revisionBefore,long revisionAfter,OffsetDateTime createdAt) { }
    private final RequestSecurity security;
    private final ParameterQueries queries;
    private final ParameterCommands commands;
    private final DeviceAccessScope scopes;
    public CustomerParameterController(RequestSecurity security,ParameterQueries queries,ParameterCommands commands,DeviceAccessScope scopes) {
        this.security=security; this.queries=queries; this.commands=commands; this.scopes=scopes;
    }
    private AuthService.Identity reader(HttpServletRequest request) {
        var actor=security.require(request,"unit-parameters:read","customer");
        scopes.requireAll(actor.user().id(),security.tenant(request));
        return actor;
    }
    private void key(String key) { if(!OfflinePolicySql.KEY.equals(key)) throw ApiError.missing(); }
    @GetMapping
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Settings settings(HttpServletRequest request) {
        var actor=reader(request); String tenant=security.tenant(request);
        var definition=queries.definitions().stream().filter(item->item.key().equals(OfflinePolicySql.KEY)).findFirst().orElseThrow(ApiError::missing);
        return new Settings(definition,queries.view(actor.user().id(),tenant,"TENANT",tenant,definition.key()),Math.min(definition.max(),definition.globalValue()));
    }
    @GetMapping("/{key}/history")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<Change> history(@PathVariable String key,@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,HttpServletRequest request) {
        var actor=reader(request); key(key); String tenant=security.tenant(request);
        var result=queries.history(actor.user().id(),tenant,"TENANT",tenant,key,page,size);
        return new Page<>(result.items().stream().map(item->new Change(item.id(),item.key(),item.action(),item.previousValue(),item.value(),item.revisionBefore(),item.revisionAfter(),item.createdAt())).toList(),result.total(),result.page(),result.size());
    }
    @PutMapping("/{key}")
    public ParameterModels.Mutation set(@PathVariable String key,@RequestHeader(value="Idempotency-Key",required=false) String idem,@Valid @RequestBody ParameterInputs.SetValue body,HttpServletRequest request) {
        var actor=reader(request); security.require(request,"unit-parameters:write","customer"); key(key);
        return commands.setForCustomer(actor,security.tenant(request),key,idem,body,RequestContext.requestId(request));
    }
    @PostMapping("/{key}/clear")
    public ParameterModels.Mutation clear(@PathVariable String key,@RequestHeader(value="Idempotency-Key",required=false) String idem,@Valid @RequestBody ParameterInputs.Clear body,HttpServletRequest request) {
        var actor=reader(request); security.require(request,"unit-parameters:write","customer"); key(key);
        return commands.clearForCustomer(actor,security.tenant(request),key,idem,body,RequestContext.requestId(request));
    }
}
