package com.fireplatform.bulkio;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer/data-transfer")
public class CustomerDataTransferController {
    public record PreviewRequest(@NotBlank String kind,@NotBlank String format,@NotBlank @Size(max=3_000_000) String content_base64,String tenant_id) { }
    public record ConfirmRequest(@NotBlank String plan_id,@NotBlank @Size(min=64,max=64) String expected_sha256,@NotBlank @Size(max=1000) String reason,String tenant_id) { }
    public record ExportRequest(@NotBlank String kind,@NotBlank String format,@NotBlank @Size(max=1000) String reason,Map<String,String> filters,List<String> selected_ids,String tenant_id) { }
    public record ActionRequest(@NotBlank @Size(max=1000) String reason) { }
    private final RequestSecurity security; private final DataTransferService transfer;
    public CustomerDataTransferController(RequestSecurity security,DataTransferService transfer){this.security=security;this.transfer=transfer;}

    @GetMapping("/kinds") public Map<String,Object> kinds(HttpServletRequest request){var actor=security.identity(request);if(!actor.audience().equals("customer"))throw ApiError.forbidden("仅客户端可用");return Map.of("kinds",transfer.kinds(actor,"customer"),"protected_storage_ready",transfer.protectedStorageReady());}
    @GetMapping("/template") public DataTransferService.Template template(@RequestParam String kind,@RequestParam(defaultValue="CSV") String format,HttpServletRequest request){var actor=security.require(request,"imports:read","customer");var descriptor=transfer.descriptor(kind);if(!descriptor.importable()||!descriptor.audiences().contains("customer")||!actor.permissions().contains(descriptor.writePermission()))throw ApiError.forbidden("没有此类型的导入权限");return transfer.template(kind,format);}
    @PostMapping("/preview") public DataTransferService.Preview preview(@Valid @RequestBody PreviewRequest body,HttpServletRequest request){var actor=security.require(request,"imports:write","customer");if(!Objects.equals(body.tenant_id(),actor.user().tenantId()))throw ApiError.forbidden("客户只能操作会话单位");var descriptor=transfer.descriptor(body.kind());if(!descriptor.importable()||!descriptor.audiences().contains("customer")||!actor.permissions().contains(descriptor.writePermission()))throw ApiError.forbidden("没有此类型的导入权限");return transfer.preview(actor,actor.user().tenantId(),body.kind(),body.format(),body.content_base64());}
    @PostMapping("/confirm")
    public DataTransferService.Job confirm(@Valid @RequestBody ConfirmRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){
        var actor=security.require(request,"imports:write","customer");
        if(!Objects.equals(body.tenant_id(),actor.user().tenantId()))throw ApiError.forbidden("客户只能操作会话单位");
        return transfer.confirm(actor,actor.user().tenantId(),body.plan_id(),body.expected_sha256(),body.reason(),key);
    }
    @GetMapping("/jobs") public DataTransferService.JobPage jobs(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,HttpServletRequest request){var actor=security.require(request,"imports:read","customer");return transfer.jobs(actor,page,size);}
    @GetMapping("/jobs/{id}") public DataTransferService.Job job(@PathVariable String id,HttpServletRequest request){var actor=security.require(request,"imports:read","customer");return transfer.job(actor.user().tenantId(),id,actor);}
    @GetMapping("/jobs/{id}/rows") public List<DataTransferService.JobRow> rows(@PathVariable String id,HttpServletRequest request){var actor=security.require(request,"imports:read","customer");return transfer.rows(actor.user().tenantId(),id,actor);}
    @GetMapping("/jobs/{id}/errors") public List<DataTransferService.ErrorRow> errors(@PathVariable String id,HttpServletRequest request){var actor=security.require(request,"imports:read","customer");return transfer.errors(actor.user().tenantId(),id,actor);}
    @GetMapping("/jobs/{id}/errors/download") public DataTransferService.Artifact errorFile(@PathVariable String id,@RequestParam String format,HttpServletRequest request){var actor=security.require(request,"imports:read","customer");return transfer.errorFile(actor,id,format);}
    @PostMapping("/jobs/{id}/reconfirm") public DataTransferService.Job reconfirm(@PathVariable String id,@Valid @RequestBody ActionRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){var actor=security.require(request,"imports:write","customer");return transfer.reconfirm(actor,id,body.reason(),key);}
    @PostMapping("/jobs/{id}/cancel") public DataTransferService.Job cancel(@PathVariable String id,@Valid @RequestBody ActionRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){var actor=security.require(request,"imports:write","customer");return transfer.cancel(actor,id,body.reason(),key);}
    @PostMapping("/exports") public DataTransferService.Export export(@Valid @RequestBody ExportRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){var actor=security.require(request,"exports:write","customer");if(!Objects.equals(body.tenant_id(),actor.user().tenantId()))throw ApiError.forbidden("客户只能导出会话单位");var descriptor=transfer.descriptor(body.kind());if(!descriptor.exportable()||!descriptor.audiences().contains("customer")||!actor.permissions().contains(descriptor.readPermission()))throw ApiError.forbidden("没有此类型的导出权限");return transfer.exportCustomer(actor,actor.user().tenantId(),body.kind(),body.format(),body.reason(),body.filters(),body.selected_ids(),key);}
    @GetMapping("/exports") public DataTransferService.ExportPage exports(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,HttpServletRequest request){var actor=security.require(request,"exports:read","customer");return transfer.exports(actor,page,size);}
    @GetMapping("/exports/{id}/download") public DataTransferService.Artifact download(@PathVariable String id,HttpServletRequest request){var actor=security.require(request,"exports:read","customer");return transfer.download(actor,actor.user().tenantId(),id);}
}
