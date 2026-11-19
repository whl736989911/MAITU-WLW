package com.fireplatform.device;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** N12 批量导入 API。导入整批校验、整批成败。 */
@RestController @RequestMapping("/api/management")
public class DeviceBulkController {
    private final RequestSecurity security; private final DeviceBulkService bulk;
    public DeviceBulkController(RequestSecurity security,DeviceBulkService bulk) { this.security=security; this.bulk=bulk; }

    public record PreviewImport(@NotBlank @Size(max=1048576) String csv) { }
    public record ConfirmImport(@NotBlank @Size(max=1048576) String csv,@NotBlank @Size(min=64,max=64) String expected_sha256,@NotBlank @Size(max=1000) String reason) { }

    @PostMapping("/devices/import/preview") public DeviceBulkService.Preview preview(@Valid @RequestBody PreviewImport body,HttpServletRequest request) {
        var actor=security.require(request,"imports:write","management");
        return bulk.preview(security.tenant(request),body.csv());
    }

    @PostMapping("/devices/import/confirm") public DeviceBulkService.Job confirm(@Valid @RequestBody ConfirmImport body,HttpServletRequest request) {
        var actor=security.require(request,"imports:write","management");
        return bulk.confirm(actor,security.tenant(request),body.csv(),body.expected_sha256(),body.reason());
    }

    @GetMapping("/devices/imports/{id}") public DeviceBulkService.Job job(@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"imports:read","management");
        return bulk.job(security.tenant(request),id);
    }

    @GetMapping("/devices/imports/{id}/rows") public java.util.List<DeviceBulkService.Row> rows(@PathVariable String id,HttpServletRequest request) {
        var actor=security.require(request,"imports:read","management");
        return bulk.jobRows(security.tenant(request),id);
    }

}
