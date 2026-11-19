package com.fireplatform.legacyingest.web;

import com.fireplatform.legacyingest.ingest.LegacyIngestService;
import org.springframework.web.bind.annotation.*;

@RestController
public class LegacyIngestController {
    private final LegacyIngestService ingest;
    public LegacyIngestController(LegacyIngestService ingest){this.ingest=ingest;}
    @PostMapping("/api/notify/v1/{token}/{platform}/telemetry")
    public LegacyIngestService.Result notify(@PathVariable String platform,@RequestBody String rawBody){return ingest.ingest("HTTP_NOTIFY",platform,null,rawBody);}
    @PostMapping("/v1/formttl/{messageType}/telemetry")
    public LegacyIngestService.Result formTtl(@PathVariable String messageType,@RequestBody String rawBody){return ingest.ingest("HTTP_FORM_TTL","formttl",messageType,rawBody);}
}
