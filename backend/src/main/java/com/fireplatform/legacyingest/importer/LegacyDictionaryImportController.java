package com.fireplatform.legacyingest.importer;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/management/legacy-ingest")
public class LegacyDictionaryImportController {
    private final LegacyDictionaryImporter importer;
    private final LegacyAdminImportCommands commands;
    private final RequestSecurity security;

    public LegacyDictionaryImportController(LegacyDictionaryImporter importer, LegacyAdminImportCommands commands,
                                            RequestSecurity security) {
        this.importer = importer;
        this.commands = commands;
        this.security = security;
    }

    public record DeviceSelection(@Size(max = 500) List<@NotBlank @Size(max = 64) String> company_ids) { }
    public record ConfirmDictionaries(@NotBlank @Size(min = 64, max = 64) String expected_sha256,
                                      @NotBlank @Size(max = 1000) String reason) { }
    public record ConfirmDevices(@NotBlank @Size(min = 64, max = 64) String expected_sha256,
                                 @NotEmpty @Size(max = 500) List<@NotBlank @Size(max = 64) String> company_ids,
                                 @NotBlank @Size(max = 1000) String reason) { }

    /** Read-only; no source row and no database row is written. */
    @PostMapping("/import-dictionaries/preview")
    public LegacyDictionaryImporter.DictionaryPreview previewDictionaries(HttpServletRequest request) {
        security.require(request, "legacy-ingest:read", "management");
        return importer.previewDictionaries();
    }

    @PostMapping("/import-dictionaries/confirm")
    public LegacyDictionaryImporter.ImportStats confirmDictionaries(@Valid @RequestBody ConfirmDictionaries body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        var actor = security.require(request, "legacy-ingest:write", "management");
        return commands.confirmDictionaries(actor, body.expected_sha256(), body.reason(),
            key, RequestContext.requestId(request));
    }

    /** Empty selection previews the whole ledger but never writes; confirmation requires explicit units. */
    @PostMapping("/import-devices/preview")
    public LegacyDictionaryImporter.DeviceImportPreview previewDevices(@Valid @RequestBody(required = false) DeviceSelection body,
            HttpServletRequest request) {
        security.require(request, "legacy-ingest:read", "management");
        return importer.previewDevices(companyIds(body));
    }

    @PostMapping("/import-devices/confirm")
    public LegacyDictionaryImporter.PilotDeviceImportStats confirmDevices(@Valid @RequestBody ConfirmDevices body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        var actor = security.require(request, "legacy-ingest:write", "management");
        return commands.confirmDevices(actor, body.expected_sha256(), body.company_ids(),
            body.reason(), key, RequestContext.requestId(request));
    }

    private static List<String> companyIds(DeviceSelection body) {
        if (body == null || body.company_ids() == null) return List.of();
        var result = new ArrayList<String>();
        for (String value : body.company_ids()) if (value != null) result.add(value);
        return result;
    }
}
