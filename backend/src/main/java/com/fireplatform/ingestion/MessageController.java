package com.fireplatform.ingestion;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/management/messages")
public class MessageController {
    private final RequestSecurity security; private final IngestionGuard guard; private final MessageQueries queries; private final PreviewCommands commands; private final MessageApplyCommands applies;
    public MessageController(RequestSecurity security,IngestionGuard guard,MessageQueries queries,PreviewCommands commands,MessageApplyCommands applies) { this.security=security; this.guard=guard; this.queries=queries; this.commands=commands; this.applies=applies; }
    @GetMapping public Page<IngestionModels.RawSummary> list(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String keyword,@RequestParam(value="source_id",required=false) String source,HttpServletRequest request) { var actor=security.require(request,"messages:read","management"); return queries.messages(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size,keyword,source); }
    @GetMapping("/{id}") public IngestionModels.MessageDetail detail(@PathVariable String id,HttpServletRequest request) { var actor=security.require(request,"messages:read","management"); return queries.detail(actor.user().id(),security.tenant(request),id); }
    @GetMapping("/{id}/raw") public IngestionModels.RawContent raw(@PathVariable String id,HttpServletRequest request) { var actor=security.require(request,"messages:read","management"); if(!guard.raw(actor)) throw ApiError.forbidden("没有原文读取权限"); return queries.content(actor.user().id(),security.tenant(request),id); }
    @PostMapping("/{id}/preview") public IngestionModels.PreviewResult preview(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IngestionInputs.Preview body,HttpServletRequest request) { var actor=security.require(request,"messages:preview","management"); return commands.preview(actor,security.tenant(request),id,key,body,RequestContext.requestId(request)); }
    @PostMapping("/{id}/apply") public IngestionModels.ApplyResult apply(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IngestionInputs.Apply body,HttpServletRequest request) { var actor=security.require(request,"messages:apply","management"); return applies.apply(actor,security.tenant(request),id,key,body,RequestContext.requestId(request)); }
    @GetMapping("/{id}/previews/{preview}") public IngestionModels.Preview attempt(@PathVariable String id,@PathVariable String preview,HttpServletRequest request) { var actor=security.require(request,"messages:read","management"); return queries.preview(actor.user().id(),security.tenant(request),id,preview,guard.raw(actor)); }
}
