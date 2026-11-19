package com.fireplatform.configuration;

import com.fireplatform.identity.RequestSecurity;
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
    private final RequestSecurity security; private final AlarmBaseConfigurationQueries queries; private final AlarmBaseConfigurationCommands commands;
    @GetMapping("/outcomes/{alarmId}") public java.util.List<AlarmBaseConfigurationEventConsumer.Outcome> outcomes(@PathVariable String alarmId,HttpServletRequest request){security.require(request,"alarm-base:read","management");return queries.outcomes(security.tenant(request),alarmId);}
    public AlarmBaseConfigurationController(RequestSecurity security,AlarmBaseConfigurationQueries queries,AlarmBaseConfigurationCommands commands){this.security=security;this.queries=queries;this.commands=commands;}
    @GetMapping public AlarmBaseConfigurationQueries.Configuration get(HttpServletRequest request){security.require(request,"alarm-base:read","management");return queries.configuration(security.tenant(request));}
    @PutMapping public AlarmBaseConfigurationQueries.Configuration save(@Valid @RequestBody Save body,@org.springframework.web.bind.annotation.RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){var actor=security.require(request,"alarm-base:write","management");return commands.save(actor,security.tenant(request),key,body.expected_revision(),body.reason(),body.value(),RequestContext.requestId(request));}
}
