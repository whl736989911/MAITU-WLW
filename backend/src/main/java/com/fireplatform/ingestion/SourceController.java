package com.fireplatform.ingestion;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/management/sources")
public class SourceController {
    private final RequestSecurity security; private final SourceQueries queries; private final SourceCommands commands;
    public SourceController(RequestSecurity security,SourceQueries queries,SourceCommands commands) { this.security=security; this.queries=queries; this.commands=commands; }
    @GetMapping public Page<IngestionModels.Source> list(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String keyword,HttpServletRequest request) { var actor=security.require(request,"sources:read","management"); return queries.sources(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size,keyword); }
    @PostMapping public IngestionModels.CredentialResult create(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IngestionInputs.CreateSource body,HttpServletRequest request) { var actor=security.require(request,"sources:write","management"); return commands.create(actor,security.tenant(request),key,body,RequestContext.requestId(request)); }
    @PutMapping("/{id}") public IngestionModels.Source update(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IngestionInputs.UpdateSource body,HttpServletRequest request) { var actor=security.require(request,"sources:write","management"); return commands.update(actor,security.tenant(request),id,key,body,RequestContext.requestId(request)); }
    @PostMapping("/{id}/rotate-token") public IngestionModels.CredentialResult rotate(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IngestionInputs.RotateToken body,HttpServletRequest request) { var actor=security.require(request,"sources:credentials","management"); return commands.rotate(actor,security.tenant(request),id,key,body,RequestContext.requestId(request)); }
    @GetMapping("/{id}/bindings") public Map<String,List<IngestionModels.Binding>> bindings(@PathVariable String id,HttpServletRequest request) { var actor=security.require(request,"sources:read","management"); return Map.of("items",queries.bindings(actor.user().id(),security.tenant(request),id)); }
    @PostMapping("/{id}/bindings") public IngestionModels.BindingResult bind(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IngestionInputs.Bind body,HttpServletRequest request) { var actor=security.require(request,"sources:bind","management"); return commands.bind(actor,security.tenant(request),id,key,body,RequestContext.requestId(request)); }
    @PostMapping("/{id}/bindings/{binding}/detach") public IngestionModels.BindingResult detach(@PathVariable String id,@PathVariable String binding,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IngestionInputs.Detach body,HttpServletRequest request) { var actor=security.require(request,"sources:bind","management"); return commands.detach(actor,security.tenant(request),id,binding,key,body,RequestContext.requestId(request)); }
}
