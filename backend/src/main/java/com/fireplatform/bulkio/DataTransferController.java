package com.fireplatform.bulkio;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/data-transfer")
public class DataTransferController {
    public record PreviewRequest(@NotBlank String kind,@NotBlank String format,String tenant_id,@NotBlank @Size(max=3_000_000) String content_base64) { }
    public record ConfirmRequest(@NotBlank String plan_id,@NotBlank @Size(min=64,max=64) String expected_sha256,@NotBlank @Size(max=1000) String reason,String tenant_id) { }
    public record ConfirmAction(@NotBlank @Size(max=1000) String reason) { }
    public record ExportRequest(@NotBlank String kind,@NotBlank String format,String tenant_id,@NotBlank @Size(max=1000) String reason,Map<String,String> filters,List<String> selected_ids) { }
    private final RequestSecurity security; private final DataTransferService transfer;
    public DataTransferController(RequestSecurity security,DataTransferService transfer){this.security=security;this.transfer=transfer;}

    @GetMapping("/kinds") public Map<String,Object> kinds(HttpServletRequest request){var actor=security.identity(request);if(!actor.audience().equals("management"))throw ApiError.forbidden("仅管理端可用");return Map.of("kinds",transfer.kinds(actor,"management"),"protected_storage_ready",transfer.protectedStorageReady());}
    @GetMapping("/jobs") public DataTransferService.JobPage jobs(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,HttpServletRequest request){var actor=security.require(request,"imports:read","management");return transfer.jobs(actor,page,size);}
    @PostMapping("/jobs/{id}/reconfirm") public DataTransferService.Job reconfirm(@PathVariable String id,@Valid @RequestBody ConfirmAction body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){var actor=security.require(request,"imports:write","management");return transfer.reconfirm(actor,id,body.reason(),key);}
    @PostMapping("/jobs/{id}/cancel") public DataTransferService.Job cancel(@PathVariable String id,@Valid @RequestBody ConfirmAction body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){var actor=security.require(request,"imports:write","management");return transfer.cancel(actor,id,body.reason(),key);}
    @GetMapping("/jobs/{id}/errors/download") public DataTransferService.Artifact errorFile(@PathVariable String id,@RequestParam String format,HttpServletRequest request){var actor=security.require(request,"imports:read","management");return transfer.errorFile(actor,id,format);}
    @GetMapping("/exports") public DataTransferService.ExportPage exports(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String kind,HttpServletRequest request){var actor=security.require(request,"exports:read","management");return transfer.exports(actor,page,size,kind);}
    @GetMapping("/template") public DataTransferService.Template template(@RequestParam String kind,@RequestParam(defaultValue="CSV") String format,HttpServletRequest request){var actor=security.identity(request);var descriptor=transfer.descriptor(kind);if(!actor.audience().equals("management")||!descriptor.importable()||!descriptor.audiences().contains("management")||!actor.permissions().contains("imports:read")&&!actor.permissions().contains(descriptor.writePermission()))throw ApiError.forbidden("没有此操作权限");return transfer.template(kind,format);}
    @PostMapping("/preview") public DataTransferService.Preview preview(@Valid @RequestBody PreviewRequest body,HttpServletRequest request){var actor=security.require(request,"imports:write","management");var descriptor=transfer.descriptor(body.kind());if(!descriptor.importable()||!actor.permissions().contains(descriptor.writePermission()))throw ApiError.forbidden("没有此类型的导入权限");String tenant=descriptor.tenantRequired()?security.tenantFilter(request,body.tenant_id()):null;if(descriptor.tenantRequired()&&tenant==null)throw ApiError.bad("导入必须明确选择写入单位");return transfer.preview(actor,tenant,body.kind(),body.format(),body.content_base64());}
    @PostMapping("/confirm") public DataTransferService.Job confirm(@Valid @RequestBody ConfirmRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){var actor=security.require(request,"imports:write","management");return transfer.confirm(actor,body.tenant_id(),body.plan_id(),body.expected_sha256(),body.reason(),key);}
    @GetMapping("/jobs/{id}") public DataTransferService.Job job(@PathVariable String id,HttpServletRequest request){var actor=security.require(request,"imports:read","management");return transfer.job(null,id,actor);}
    @GetMapping("/jobs/{id}/rows") public List<DataTransferService.JobRow> rows(@PathVariable String id,HttpServletRequest request){var actor=security.require(request,"imports:read","management");return transfer.rows(null,id,actor);}
    @GetMapping("/jobs/{id}/errors") public List<DataTransferService.ErrorRow> errors(@PathVariable String id,HttpServletRequest request){var actor=security.require(request,"imports:read","management");return transfer.errors(null,id,actor);}
    @PostMapping("/exports") public DataTransferService.Export export(@Valid @RequestBody ExportRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){var actor=security.require(request,"exports:write","management");var descriptor=transfer.descriptor(body.kind());if(!descriptor.exportable()||!descriptor.audiences().contains("management")||!actor.permissions().contains(descriptor.readPermission()))throw ApiError.forbidden("没有此类型的导出权限");String tenant=body.tenant_id()==null?null:security.tenantFilter(request,body.tenant_id());return transfer.export(actor,tenant,body.kind(),body.format(),body.filters(),body.selected_ids(),body.reason(),key);}
    @GetMapping("/exports/{id}/download") public DataTransferService.Artifact download(@PathVariable String id,@RequestHeader(value="X-Tenant-Id",required=false) String requestedTenant,HttpServletRequest request){var actor=security.require(request,"exports:read","management");String tenant=requestedTenant==null?null:security.tenantFilter(request,requestedTenant);return transfer.download(actor,tenant,id);}
}
