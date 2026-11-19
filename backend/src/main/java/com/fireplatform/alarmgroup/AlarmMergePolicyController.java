package com.fireplatform.alarmgroup;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/management/alarm-merge-policy")
public class AlarmMergePolicyController {
    public record Save(@NotNull @Min(0) Long expected_revision,@NotNull Boolean enabled,
            @Min(1) @Max(604800) Integer window_seconds,@NotBlank @Size(max=1000) String reason) { }

    private final RequestSecurity security;
    private final AlarmMergePolicyQueries queries;
    private final AlarmMergePolicyCommands commands;

    public AlarmMergePolicyController(RequestSecurity security,AlarmMergePolicyQueries queries,AlarmMergePolicyCommands commands) {
        this.security=security; this.queries=queries; this.commands=commands;
    }

    @GetMapping
    public AlarmMergePolicyQueries.Policy get(HttpServletRequest request) {
        security.require(request,"alarm-base:read","management");
        return queries.policy(security.tenant(request));
    }

    @GetMapping("/history")
    public List<AlarmMergePolicyQueries.Revision> history(HttpServletRequest request) {
        security.require(request,"alarm-base:read","management");
        return queries.history(security.tenant(request));
    }

    @PutMapping
    public AlarmMergePolicyQueries.Policy save(@Valid @RequestBody Save body,
            @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request) {
        var actor=security.require(request,"alarm-base:write","management");
        return commands.save(actor,security.tenant(request),key,body.expected_revision(),
            body.enabled(),body.window_seconds(),body.reason(),RequestContext.requestId(request));
    }
}
