package com.fireplatform.legacyingest.export;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/management/legacy-ingest/exports")
public class LegacyIngestExportController {
    private final LegacyIngestExportService exports;
    private final RequestSecurity security;
    private final ManagementGuard guard;
    private final AuditService audit;

    public LegacyIngestExportController(LegacyIngestExportService exports, RequestSecurity security,
                                        ManagementGuard guard, AuditService audit) {
        this.exports = exports;
        this.security = security;
        this.guard = guard;
        this.audit = audit;
    }

    /** Explicit target, current scope and re-authentication; no implicit source unit or fallback tenant. */
    public record ExportInput(@NotBlank @Size(max = 32) String kind, @Size(max = 24) String state,
                              @Size(max = 32) String platform, @Size(max = 64) String run_id,
                              @Min(1) @Max(10_000) Integer max_rows,
                              @NotBlank @Size(max = 32) String target, @Size(max = 64) String tenant_id,
                              @NotBlank @Size(max = 1000) String reason) { }

    @PostMapping
    public ResponseEntity<LegacyIngestExportService.ExportFile> export(@Valid @RequestBody ExportInput input, HttpServletRequest request) {
        AuthService.Identity actor = security.require(request, "legacy-ingest:read", "management");
        String tenant = switch (input.target()) {
            case "TENANT" -> {
                if (input.tenant_id() == null || input.tenant_id().isBlank()) throw ApiError.bad("指定单位导出必须提供 tenant_id");
                yield security.tenantFilter(request, input.tenant_id());
            }
            case "ALL_AUTHORIZED" -> {
                if (input.tenant_id() != null && !input.tenant_id().isBlank()) throw ApiError.bad("全部授权单位导出不能同时指定 tenant_id");
                yield null;
            }
            default -> throw ApiError.bad("导出目标必须为 TENANT 或 ALL_AUTHORIZED");
        };
        var selection = security.readSelection(request);
        guard.begin(actor, "legacy-ingest:read", tenant, "management");
        var file = exports.export(actor, tenant, selection,
            new LegacyIngestExportService.ExportRequest(input.kind(), input.state(), input.platform(), input.run_id(),
                input.max_rows(), input.target(), input.tenant_id(), input.reason()));
        audit.recordReason(actor.user().id(), tenant, "LEGACY_INGEST_EXPORT", input.kind(), "SUCCESS",
            RequestContext.requestId(request), input.reason());
        return ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
            .body(file);
    }
}
