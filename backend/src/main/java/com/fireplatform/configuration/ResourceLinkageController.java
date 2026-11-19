package com.fireplatform.configuration;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/management/resource-linkage")
public class ResourceLinkageController {
    public record DefaultUpdate(@NotNull @Min(0) Long expected_revision,@NotNull Boolean enabled,@NotNull Map<String,Object> config,@NotBlank @Size(max=1000) String reason) { }
    public record CreateRule(@NotBlank @Size(max=120) String name,@NotNull Map<String,Object> config,@NotBlank @Size(max=1000) String reason) { }
    public record UpdateRule(@NotNull @Min(1) Long expected_revision,@NotBlank @Size(max=120) String name,@NotNull Map<String,Object> config,@NotBlank @Size(max=1000) String reason) { }
    public record EnableRule(@NotNull @Min(0) Long expected_revision,@NotNull Boolean enabled,@NotBlank @Size(max=1000) String reason) { }
    public record DeleteRule(@NotNull @Min(1) Long expected_revision,@NotBlank @Size(max=1000) String reason) { }
    public record Evaluate(@NotBlank String alarm_id,@NotBlank @Size(max=1000) String reason) { }
    private final RequestSecurity security;private final ResourceLinkageCommands commands;
    public ResourceLinkageController(RequestSecurity security,ResourceLinkageCommands commands){this.security=security;this.commands=commands;}
    @GetMapping public Map<String,List<ResourceLinkageCommands.Rule>> list(HttpServletRequest request){security.require(request,"resource-linkage:read","management");return Map.of("items",commands.list(security.tenant(request)));}
    @PostMapping("/defaults/{kind}") public ResourceLinkageCommands.Rule saveDefault(@PathVariable String kind,@Valid @RequestBody DefaultUpdate body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){var actor=security.require(request,"resource-linkage:write","management");return commands.saveDefault(actor,security.tenant(request),kind,body.enabled(),body.expected_revision(),body.config(),body.reason(),RequestContext.requestId(request),key);}
    @PostMapping("/rules") public ResourceLinkageCommands.Rule create(@Valid @RequestBody CreateRule body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){var actor=security.require(request,"resource-linkage:write","management");return commands.create(actor,security.tenant(request),body.name(),body.config(),RequestContext.requestId(request),body.reason(),key);}
    @PutMapping("/rules/{id}") public ResourceLinkageCommands.Rule update(@PathVariable String id,@Valid @RequestBody UpdateRule body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){var actor=security.require(request,"resource-linkage:write","management");return commands.update(actor,security.tenant(request),id,body.expected_revision(),body.name(),body.config(),body.reason(),RequestContext.requestId(request),key);}
    @PostMapping("/rules/{id}/enabled") public ResourceLinkageCommands.Rule enable(@PathVariable String id,@Valid @RequestBody EnableRule body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){var actor=security.require(request,"resource-linkage:write","management");return commands.setEnabled(actor,security.tenant(request),id,body.expected_revision(),body.enabled(),body.reason(),RequestContext.requestId(request),key);}
    @DeleteMapping("/rules/{id}") public void delete(@PathVariable String id,@Valid @RequestBody DeleteRule body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){var actor=security.require(request,"resource-linkage:write","management");commands.delete(actor,security.tenant(request),id,body.expected_revision(),body.reason(),RequestContext.requestId(request),key);}
    @PostMapping("/evaluate") public List<ResourceLinkageCommands.Evaluation> evaluate(@Valid @RequestBody Evaluate body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){var actor=security.require(request,"resource-linkage:write","management");return commands.evaluate(actor,security.tenant(request),body.alarm_id(),body.reason(),RequestContext.requestId(request),key);}
    @GetMapping("/evaluations/{alarmId}") public List<ResourceLinkageCommands.Evaluation> outcomes(@PathVariable String alarmId,HttpServletRequest request){security.require(request,"resource-linkage:read","management");return commands.outcomes(security.tenant(request),alarmId);}
}
