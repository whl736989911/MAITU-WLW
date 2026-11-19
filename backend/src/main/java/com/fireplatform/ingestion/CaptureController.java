package com.fireplatform.ingestion;

import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class CaptureController {
    private final CaptureService capture;
    public CaptureController(CaptureService capture) { this.capture=capture; }
    @PostMapping("/api/ingest/{source}/messages") public IngestionModels.Receipt capture(@PathVariable String source,@Valid @RequestBody IngestionInputs.Envelope envelope,HttpServletRequest request) { return capture.capture(source,request.getHeader("Authorization"),envelope,RequestContext.requestId(request)); }
}
