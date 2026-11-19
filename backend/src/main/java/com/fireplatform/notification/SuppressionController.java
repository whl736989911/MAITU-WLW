package com.fireplatform.notification;

import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** Notification-only suppression, with explicit owner and revision-checked writes. */
@RestController @RequestMapping("/api/management/suppressions")
public class SuppressionController {
    private final RequestSecurity security; private final SuppressionCommands commands;
    public SuppressionController(RequestSecurity security,SuppressionCommands commands) { this.security=security; this.commands=commands; }
    public record CreateSuppression(@NotBlank String scope,List<String> device_ids,List<String> channel_ids,boolean fire_confirmed,@NotBlank @Size(max=1000) String reason,OffsetDateTime starts_at,@NotNull OffsetDateTime ends_at) { }
    public record Mutation(@NotNull @Min(0) Long expected_revision,@NotBlank @Size(max=1000) String reason) { }
    public record Edit(@NotNull @Min(0) Long expected_revision,@NotNull OffsetDateTime starts_at,@NotNull OffsetDateTime ends_at,@NotBlank @Size(max=1000) String reason) { }
    public record Enable(@NotNull @Min(0) Long expected_revision,@NotNull Boolean enabled,@NotBlank @Size(max=1000) String reason) { }
    @GetMapping public Page<SuppressionCommands.Suppression> list(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String scope,HttpServletRequest request) {
        var actor=security.require(request,"suppressions:read","management");
        return commands.list(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size==null?20:size,status,scope);
    }
    @GetMapping("/{id}") public SuppressionCommands.Suppression detail(@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"suppressions:read","management");return commands.view(actor.user().id(),security.tenant(request),id);
    }
    @GetMapping("/{id}/targets") public List<SuppressionCommands.ChannelTarget> targets(@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"suppressions:read","management");String tenant=security.tenant(request);commands.view(actor.user().id(),tenant,id);return commands.targets(tenant,id);
    }
    @GetMapping("/{id}/history") public List<SuppressionCommands.HistoryEvent> history(@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"suppressions:read","management");String tenant=security.tenant(request);commands.view(actor.user().id(),tenant,id);return commands.history(tenant,id);
    }
    @PostMapping public SuppressionCommands.Suppression create(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CreateSuppression body,HttpServletRequest request) {
        var actor=security.require(request,"suppressions:write","management");return commands.create(actor,security.tenant(request),key,new SuppressionCommands.Create(body.scope(),body.device_ids(),body.channel_ids(),body.fire_confirmed(),body.reason(),body.starts_at(),body.ends_at()),RequestContext.requestId(request));
    }
    @PutMapping("/{id}") public SuppressionCommands.Suppression edit(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Edit body,HttpServletRequest request) {
        var actor=security.require(request,"suppressions:write","management");return commands.edit(actor,security.tenant(request),key,id,body.expected_revision(),body.starts_at(),body.ends_at(),body.reason(),RequestContext.requestId(request));
    }
    @PostMapping("/{id}/enabled") public SuppressionCommands.Suppression enable(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Enable body,HttpServletRequest request) {
        var actor=security.require(request,"suppressions:write","management");return commands.enable(actor,security.tenant(request),key,id,body.expected_revision(),body.enabled(),body.reason(),RequestContext.requestId(request));
    }
    @PostMapping("/{id}/cancel") public SuppressionCommands.Suppression cancel(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Mutation body,HttpServletRequest request) {
        var actor=security.require(request,"suppressions:write","management");return commands.cancel(actor,security.tenant(request),key,id,body.expected_revision(),body.reason(),RequestContext.requestId(request));
    }
    @DeleteMapping("/{id}") public SuppressionCommands.Suppression delete(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Mutation body,HttpServletRequest request) {
        var actor=security.require(request,"suppressions:write","management");return commands.delete(actor,security.tenant(request),key,id,body.expected_revision(),body.reason(),RequestContext.requestId(request));
    }
}
