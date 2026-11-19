package com.fireplatform.configuration;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.fireplatform.shared.RequestContext;

@RestController
@RequestMapping("/api/management/alarm-base-configuration")
public class AlarmBaseConfigurationController {
    public record Save(@NotNull @Min(0) Long expected_revision,@NotBlank @Size(max=1000) String reason,@NotNull Map<String,Object> value) { }
    public record Clear(@NotNull @Min(0) Long expected_revision,@NotBlank @Size(max=1000) String reason) { }
    private static final String SCOPED="/{eventKind}/scopes/{scope}/{id}";
    private final RequestSecurity security; private final AlarmBaseConfigurationQueries queries; private final AlarmBaseConfigurationCommands commands;
    private final ScopedAlarmPolicyCommands scoped;
    @GetMapping("/outcomes/{alarmId}") public java.util.List<AlarmBaseConfigurationEventConsumer.Outcome> outcomes(@PathVariable String alarmId,HttpServletRequest request){security.require(request,"alarm-base:read","management");return queries.outcomes(security.tenant(request),alarmId);}
    public AlarmBaseConfigurationController(RequestSecurity security,AlarmBaseConfigurationQueries queries,
            AlarmBaseConfigurationCommands commands,ScopedAlarmPolicyCommands scoped) {
        this.security=security; this.queries=queries; this.commands=commands; this.scoped=scoped;
    }
    @GetMapping public AlarmBaseConfigurationQueries.Configuration get(HttpServletRequest request){security.require(request,"alarm-base:read","management");return queries.configuration(security.tenant(request));}
    @PutMapping public AlarmBaseConfigurationQueries.Configuration save(@Valid @RequestBody Save body,@org.springframework.web.bind.annotation.RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){var actor=security.require(request,"alarm-base:write","management");return commands.save(actor,security.tenant(request),key,body.expected_revision(),body.reason(),body.value(),RequestContext.requestId(request));}

    @GetMapping(SCOPED)
    public AlarmBaseConfigurationQueries.ScopedPolicyView scope(@PathVariable String eventKind,@PathVariable String scope,
            @PathVariable String id,@RequestParam(name="tenant_id",required=false) String tenant,HttpServletRequest request) {
        var actor=security.require(request,"alarm-base:read","management");
        return scoped.view(actor.user().id(),target(eventKind,scope,id,tenant,request,false));
    }

    @PutMapping(SCOPED)
    public AlarmBaseConfigurationQueries.ScopedPolicyView saveScope(@PathVariable String eventKind,@PathVariable String scope,
            @PathVariable String id,@RequestParam(name="tenant_id",required=false) String tenant,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Save body,HttpServletRequest request) {
        security.require(request,"alarm-base:read","management");
        var actor=security.require(request,"alarm-base:write","management");
        return scoped.save(actor,target(eventKind,scope,id,tenant,request,true),key,body.expected_revision(),body.reason(),body.value(),RequestContext.requestId(request));
    }

    @PostMapping(SCOPED+"/clear")
    public AlarmBaseConfigurationQueries.ScopedPolicyView clearScope(@PathVariable String eventKind,@PathVariable String scope,
            @PathVariable String id,@RequestParam(name="tenant_id",required=false) String tenant,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Clear body,HttpServletRequest request) {
        security.require(request,"alarm-base:read","management");
        var actor=security.require(request,"alarm-base:write","management");
        return scoped.clear(actor,target(eventKind,scope,id,tenant,request,true),key,body.expected_revision(),body.reason(),RequestContext.requestId(request));
    }

    private AlarmBaseConfigurationQueries.Target target(String eventKind,String scope,String id,String tenant,
            HttpServletRequest request,boolean write) {
        if (!eventKind.equals("FIRE") && !eventKind.equals("FAULT")) throw ApiError.bad("报警规则只支持 FIRE 或 FAULT");
        if (id.isBlank() || id.length()>64) throw ApiError.bad("报警规则目标无效");
        switch (scope) {
            case "PLATFORM" -> {
                if (!id.equals("platform") || tenant!=null) throw ApiError.bad("平台规则不接受单位目标");
                security.require(request,"settings:read","management");
                if (write) security.require(request,"settings:write","management");
            }
            case "UNIT" -> {
                if (tenant!=null && !tenant.equals(id)) throw ApiError.bad("单位路径和目标不一致");
                tenant=security.tenantFilter(request,id);
            }
            case "DEVICE","PLACE" -> {
                if (tenant==null || tenant.isBlank()) throw ApiError.bad("必须明确提交设备或位置所属的 tenant_id");
                tenant=security.tenantFilter(request,tenant);
            }
            default -> throw ApiError.bad("报警规则作用域无效");
        }
        return new AlarmBaseConfigurationQueries.Target(tenant,scope,id,eventKind);
    }
}
