package com.fireplatform.governance;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

/** N16 数据保留 API：策略配置、冻结试算、显式执行与运行历史。 */
@RestController @RequestMapping("/api/management/retention")
public class RetentionController {
    private final RequestSecurity security; private final RetentionService retention;
    public RetentionController(RequestSecurity security,RetentionService retention) { this.security=security; this.retention=retention; }

    public record Configure(@NotBlank String kind,@NotNull @Min(1) @Max(3650) Integer keep_days,
                            @NotNull Boolean enabled,@NotNull @Min(0) Long expected_revision,
                            @NotBlank @Size(max=1000) String reason) { }
    public record Preview(@NotBlank String kind) { }
    public record Execute(@NotBlank String kind,@NotBlank @Size(max=64) String preview_id,
                          @NotNull @Min(0) Long expected_revision,@NotBlank @Size(max=1000) String reason) { }

    /** Unit mode with X-Tenant-Id; without it, configured policies inside the authorized read scope. */
    @GetMapping("/policies") public RetentionService.PolicyPage policies(
        @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,HttpServletRequest request) {
        var actor=security.require(request,"retention:read","management");
        return retention.policies(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size);
    }

    @PostMapping("/policies") public RetentionService.Policy configure(
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @Valid @RequestBody Configure body,HttpServletRequest request) {
        var actor=security.require(request,"retention:write","management");
        return retention.configure(actor,security.tenant(request),key,body.kind(),body.keep_days(),
            body.enabled(),body.expected_revision(),body.reason(),RequestContext.requestId(request));
    }

    /** Real dry-run. It persists a frozen preview but never deletes business data. */
    @PostMapping("/preview") public RetentionService.Run preview(
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @Valid @RequestBody Preview body,HttpServletRequest request) {
        var actor=security.require(request,"retention:write","management");
        return retention.preview(actor,security.tenant(request),key,body.kind(),RequestContext.requestId(request));
    }

    /** Irreversible delete bound to the frozen preview and current policy revision. */
    @PostMapping("/execute") public RetentionService.Run execute(
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @Valid @RequestBody Execute body,HttpServletRequest request) {
        var actor=security.require(request,"retention:write","management");
        return retention.execute(actor,security.tenant(request),key,body.kind(),body.preview_id(),
            body.expected_revision(),body.reason(),RequestContext.requestId(request));
    }

    @GetMapping("/runs") public Page<RetentionService.Run> runs(
        @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,
        @RequestParam(required=false) String kind,HttpServletRequest request) {
        var actor=security.require(request,"retention:read","management");
        return retention.runs(actor.user().id(),security.optionalTenant(request),security.readSelection(request),kind,page,size);
    }
}
