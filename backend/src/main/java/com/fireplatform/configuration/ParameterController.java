package com.fireplatform.configuration;

import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/management")
public class ParameterController {
    private final RequestSecurity security; private final ParameterQueries queries; private final ParameterCommands commands; private final ParameterTargets targets;
    public ParameterController(RequestSecurity security,ParameterQueries queries,ParameterCommands commands,ParameterTargets targets) { this.security=security; this.queries=queries; this.commands=commands; this.targets=targets; }
    @GetMapping("/parameter-definitions") public Map<String,List<ParameterModels.Definition>> definitions(HttpServletRequest request) { security.require(request,"parameters:read","management"); return Map.of("items",queries.definitions()); }
    @GetMapping("/parameter-targets") public Page<ParameterTargets.Target> targets(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String keyword,HttpServletRequest request) { var actor=security.require(request,"parameters:read","management"); return targets.list(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size,keyword); }
    @GetMapping("/parameter-values/{scope}/{id}/{key}") public ParameterModels.PolicyView view(@PathVariable String scope,@PathVariable String id,@PathVariable String key,HttpServletRequest request) { var actor=security.require(request,"parameters:read","management"); return queries.view(actor.user().id(),security.tenant(request),scope,id,key); }
    @GetMapping("/parameter-values/{scope}/{id}/{key}/history") public Page<ParameterModels.Change> history(@PathVariable String scope,@PathVariable String id,@PathVariable String key,@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,HttpServletRequest request) { var actor=security.require(request,"parameters:read","management"); return queries.history(actor.user().id(),security.tenant(request),scope,id,key,page,size); }
    private AuthService.Identity writer(HttpServletRequest request) { security.require(request,"parameters:read","management"); return security.require(request,"parameters:write","management"); }
    @PutMapping("/parameter-values/{scope}/{id}/{key}") public ParameterModels.Mutation set(@PathVariable String scope,@PathVariable String id,@PathVariable String key,@RequestHeader(value="Idempotency-Key",required=false) String idemKey,@Valid @RequestBody ParameterInputs.SetValue body,HttpServletRequest request) { var actor=writer(request); return commands.set(actor,security.tenant(request),scope,id,key,idemKey,body,RequestContext.requestId(request)); }
    @PostMapping("/parameter-values/{scope}/{id}/{key}/clear") public ParameterModels.Mutation clear(@PathVariable String scope,@PathVariable String id,@PathVariable String key,@RequestHeader(value="Idempotency-Key",required=false) String idemKey,@Valid @RequestBody ParameterInputs.Clear body,HttpServletRequest request) { var actor=writer(request); return commands.clear(actor,security.tenant(request),scope,id,key,idemKey,body,RequestContext.requestId(request)); }
}
