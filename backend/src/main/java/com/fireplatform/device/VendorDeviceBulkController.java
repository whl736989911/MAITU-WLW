package com.fireplatform.device;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Source-isolated bulk registration; this creates only local records and vendor bindings. */
@RestController
@RequestMapping("/api/management/vendor-devices")
public class VendorDeviceBulkController {
    private final RequestSecurity security;
    private final VendorDeviceBulkService bulk;
    private final VendorDeviceSpreadsheetService spreadsheets;
    private final LegacyTerminalSpreadsheetPreview legacyTerminal;

    public VendorDeviceBulkController(RequestSecurity security,VendorDeviceBulkService bulk,VendorDeviceSpreadsheetService spreadsheets,
            LegacyTerminalSpreadsheetPreview legacyTerminal) {
        this.security=security;this.bulk=bulk;this.spreadsheets=spreadsheets;this.legacyTerminal=legacyTerminal;
    }

    /** Guided download includes only units eligible for this operator's bulk import. */
    @GetMapping("/import/template")
    public VendorDeviceSpreadsheetService.Template template(@RequestParam String source,
            @RequestParam(defaultValue="false") boolean guided,HttpServletRequest request) {
        var actor=security.require(request,"device:write","management");
        if("LEGACY_REALTIME".equals(source) && guided)
            return spreadsheets.legacyTemplate(actor.user().id());
        return spreadsheets.template(source);
    }

    /** Parse old workbooks unchanged; resolve the scoped workbook's choices against current records. */
    @PostMapping("/import/spreadsheet")
    public VendorDeviceSpreadsheetService.Grid spreadsheet(
            @Valid @RequestBody VendorDeviceBulkInputs.Spreadsheet body,HttpServletRequest request) {
        var actor=security.require(request,"device:write","management");
        return spreadsheets.preview(body.source(),body.contentBase64(),actor.user().id());
    }

    @PostMapping("/import/legacy-terminal/preview")
    public LegacyTerminalSpreadsheetPreview.Preview legacyTerminal(
            @Valid @RequestBody VendorDeviceBulkInputs.LegacyTerminalSpreadsheet body,HttpServletRequest request) {
        var actor=security.require(request,"device:write","management");
        return legacyTerminal.preview(actor.user().id(),security.tenant(request),body);
    }

    @PostMapping("/import")
    public VendorDeviceBulkService.BatchResult importDevices(
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody VendorDeviceBulkInputs.Import body,HttpServletRequest request) {
        var actor=security.require(request,"device:write","management");
        return bulk.importDevices(actor,security.tenant(request),key,body,RequestContext.requestId(request));
    }
}
