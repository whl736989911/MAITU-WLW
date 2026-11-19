package com.fireplatform.processing;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.ingestion.IngestionLimits;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

/** N07 重解析 API：创建只排队，结果只读；不带任何外部副作用。 */
@RestController @RequestMapping("/api/management")
public class ReparseController {
    private final RequestSecurity security; private final ReparseCommands commands;
    public ReparseController(RequestSecurity security,ReparseCommands commands) { this.security=security; this.commands=commands; }

    public record CreateReparse(@NotBlank @Size(max=1000) String reason) { }

    @PostMapping("/messages/{id}/reparse")
    public ReparseModels.Job create(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CreateReparse body,HttpServletRequest request) {
        var actor=security.require(request,"messages:reparse","management");
        IngestionLimits.text(body.reason(),1000);
        return commands.create(actor,security.tenant(request),id,key,body.reason());
    }

    @GetMapping("/reparse-jobs")
    public Page<ReparseModels.Job> list(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String status,HttpServletRequest request) {
        var actor=security.require(request,"messages:reparse","management");
        return commands.list(actor.user().id(),security.tenant(request),page,size==null?20:size,status);
    }

    @GetMapping("/reparse-jobs/{id}")
    public ReparseModels.Detail detail(@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"messages:reparse","management");
        return commands.detail(actor.user().id(),security.tenant(request),id);
    }
}
