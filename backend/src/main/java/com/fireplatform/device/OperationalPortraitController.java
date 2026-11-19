package com.fireplatform.device;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fireplatform.shared.DecimalTokenDeserializer;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/management")
public class OperationalPortraitController {
    private final RequestSecurity security; private final OperationalPortraitService portraits;
    public OperationalPortraitController(RequestSecurity security,OperationalPortraitService portraits){this.security=security;this.portraits=portraits;}
    public record Configure(@NotNull @Min(0) Integer expected_rule_version,@NotNull @Min(1) @Max(365) Integer window_days,@NotNull @Min(1) @Max(1000000) Integer false_alarm_count,@NotNull @Min(1) @Max(1000000) Integer fault_count,@NotNull @Min(1) @Max(1000000) Integer offline_count,@NotNull @JsonDeserialize(using=DecimalTokenDeserializer.class) @DecimalMin("1") @DecimalMax("100") BigDecimal weak_signal_percent,@jakarta.validation.constraints.Pattern(regexp="rssi|rsrp") String weak_signal_metric,@JsonDeserialize(using=DecimalTokenDeserializer.class) BigDecimal weak_signal_threshold,@NotNull @JsonDeserialize(using=DecimalTokenDeserializer.class) @DecimalMin("0") @DecimalMax("100") BigDecimal low_battery_percent,@NotNull @Min(1) @Max(365) Integer long_offline_days,@NotNull List<String> selected_labels,@NotNull Boolean enabled,@NotBlank @Size(max=1000) String reason) { }
    public record Evaluate(@NotNull @Min(1) Integer expected_rule_version,@NotBlank @Size(max=1000) String reason) { }
    @GetMapping("/portrait-rules") public OperationalPortraitService.RulePage rules(HttpServletRequest request){var actor=security.require(request,"profiles:read","management");validateQuery(request,Set.of("scope_selected","scope_tenant_ids","scope_place_ids"));return portraits.rules(actor.user().id(),security.optionalTenant(request),security.readSelection(request));}
    @PostMapping("/portrait-rules") public OperationalPortraitService.Rule configure(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Configure b,HttpServletRequest request){var actor=security.require(request,"profiles:write","management");return portraits.configure(actor,security.tenant(request),key,new OperationalPortraitService.Configure(b.expected_rule_version(),b.window_days(),b.false_alarm_count(),b.fault_count(),b.offline_count(),b.weak_signal_percent(),b.weak_signal_metric(),b.weak_signal_threshold(),b.low_battery_percent(),b.long_offline_days(),b.selected_labels(),b.enabled(),b.reason()),com.fireplatform.shared.RequestContext.requestId(request));}
    @GetMapping("/device-portraits") public OperationalPortraitService.Page list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String label,@RequestParam(required=false) String keyword,HttpServletRequest request){var actor=security.require(request,"profiles:read","management");validateQuery(request,Set.of("page","size","label","keyword","scope_selected","scope_tenant_ids","scope_place_ids"));return portraits.list(actor.user().id(),security.optionalTenant(request),security.readSelection(request),label,keyword,page,size);}
    @PostMapping("/devices/{id}/portrait/evaluate") public OperationalPortraitService.Snapshot evaluate(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Evaluate b,HttpServletRequest request){var actor=security.require(request,"profiles:write","management");validateQuery(request,Set.of("scope_selected","scope_tenant_ids","scope_place_ids"));return portraits.evaluateAndSave(actor,security.tenant(request),id,key,new OperationalPortraitService.Evaluate(b.expected_rule_version(),b.reason()),security.readSelection(request),com.fireplatform.shared.RequestContext.requestId(request));}
    @GetMapping("/devices/{id}/portrait/history") public List<OperationalPortraitService.Snapshot> history(@PathVariable String id,@RequestParam(defaultValue="20") int limit,HttpServletRequest request){var actor=security.require(request,"profiles:read","management");validateQuery(request,Set.of("limit","scope_selected","scope_tenant_ids","scope_place_ids"));return portraits.history(actor.user().id(),security.tenant(request),id,limit,security.readSelection(request));}
    private void validateQuery(HttpServletRequest request,Set<String> allowed){if(request.getParameterMap().entrySet().stream().anyMatch(e->!allowed.contains(e.getKey())||e.getValue().length!=1))throw ApiError.bad("画像范围参数无效或重复");}
}
