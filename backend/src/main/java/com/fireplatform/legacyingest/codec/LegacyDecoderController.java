package com.fireplatform.legacyingest.codec;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 旧平台产品解码器（pa_code）导入与预览。 */
@RestController @RequestMapping("/api/management/legacy-ingest/product-decoders")
public class LegacyDecoderController {
    private final RequestSecurity security; private final LegacyDecoderCommands commands;
    public LegacyDecoderController(RequestSecurity security,LegacyDecoderCommands commands) { this.security=security; this.commands=commands; }

    public record ImportDecoder(@NotBlank @Size(max=64) String legacy_product_id,@NotBlank String source,
            @NotBlank @Size(max=1000) String reason) { }
    public record PreviewDecoder(@NotBlank String source,@NotBlank String payload_hex,Map<String,Object> attrs) { }
    public record PreviewStoredRevision(@NotBlank String payload_hex,Map<String,Object> attrs) { }

    @PostMapping public LegacyDecoderCommands.Revision importSource(@Valid @RequestBody ImportDecoder body,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        var actor=security.require(request,"legacy-ingest:write","management");
        return commands.importSource(actor,body.legacy_product_id(),body.source(),body.reason(),key,
            RequestContext.requestId(request));
    }

    @GetMapping("/{legacyProductId}") public java.util.List<LegacyDecoderCommands.Revision> revisions(@PathVariable String legacyProductId,HttpServletRequest request) {
        security.require(request,"legacy-ingest:read","management");
        return commands.revisions(legacyProductId);
    }

    @PostMapping("/preview") public LegacyDecoderCommands.Preview preview(@Valid @RequestBody PreviewDecoder body,HttpServletRequest request) {
        security.require(request,"legacy-ingest:write","management");
        return commands.preview(body.source(),body.payload_hex(),body.attrs());
    }

    /** 只执行已存储修订，源码不经浏览器往返。 */
    @PostMapping("/{legacyProductId}/revisions/{revision}/preview")
    public LegacyDecoderCommands.Preview previewRevision(@PathVariable String legacyProductId,@PathVariable long revision,
            @Valid @RequestBody PreviewStoredRevision body,HttpServletRequest request) {
        security.require(request,"legacy-ingest:write","management");
        return commands.previewRevision(legacyProductId,revision,body.payload_hex(),body.attrs());
    }
}
