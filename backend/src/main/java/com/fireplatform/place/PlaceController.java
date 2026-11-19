package com.fireplatform.place;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/places")
public class PlaceController {
    private final RequestSecurity security;
    private final PlaceQueries queries;
    private final PlaceCommands commands;
    private final PlaceTransferService transfers;
    public PlaceController(RequestSecurity security, PlaceQueries queries, PlaceCommands commands, PlaceTransferService transfers) { this.security = security; this.queries = queries; this.commands = commands; this.transfers=transfers; }

    @GetMapping
    public List<PlaceQueries.PlaceNode> list(HttpServletRequest request) {
        var actor = security.require(request, "units:read", "management");
        return queries.places(actor.user().id(), security.optionalTenant(request), security.readSelection(request));
    }

    @GetMapping("/{id}/move-preview")
    public PlaceCommands.MovePreview movePreview(@PathVariable String id,@RequestParam(name="target_parent_id",required=false) String target,HttpServletRequest request) {
        var actor=security.require(request,"units:read","management");
        security.require(request,"units:write","management");
        return commands.movePreview(actor,security.tenant(request),id,target,"management");
    }

    @PostMapping("/{id}/move")
    public PlaceCommands.MoveResult move(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody PlaceInputs.Move body,HttpServletRequest request) {
        var actor=security.require(request,"units:write","management");
        security.require(request,"units:read","management");
        return commands.move(actor,security.tenant(request),id,body,key,RequestContext.requestId(request),"management");
    }

    @GetMapping("/{id}/transfer-preview")
    public PlaceTransferService.Preview transferPreview(@PathVariable String id,@RequestParam(name="target_tenant_id") String targetTenant,
        @RequestParam(name="target_parent_id",required=false) String targetParent,HttpServletRequest request) {
        var actor=security.require(request,"units:write","management");
        return transfers.preview(actor,security.tenant(request),id,targetTenant,targetParent);
    }

    @PostMapping("/{id}/transfer")
    public PlaceTransferService.Result transfer(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody PlaceTransferService.Input body,HttpServletRequest request) {
        var actor=security.require(request,"units:write","management");
        return transfers.transfer(actor,security.tenant(request),id,body,key,RequestContext.requestId(request));
    }

    @PostMapping
    public PlaceQueries.PlaceNode create(@Valid @RequestBody PlaceInputs.Create body, HttpServletRequest request) {
        var actor = security.require(request, "units:write", "management");
        return commands.create(actor, security.tenant(request), body, RequestContext.requestId(request), "management");
    }

    @PutMapping("/{id}")
    public PlaceQueries.PlaceNode update(@PathVariable String id, @Valid @RequestBody PlaceInputs.Update body, HttpServletRequest request) {
        var actor = security.require(request, "units:write", "management");
        return commands.update(actor, security.tenant(request), id, body, RequestContext.requestId(request), "management");
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id, @Valid @RequestBody PlaceInputs.Delete body, HttpServletRequest request) {
        var actor = security.require(request, "units:write", "management");
        commands.delete(actor, security.tenant(request), id, body, RequestContext.requestId(request), "management");
    }
}
