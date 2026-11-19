package com.fireplatform.firearchive;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Limits;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class FireArchiveController {
    private static final String BASE = "/api/{audience:management|customer}/fire-archives";
    private static final Set<String> LIST_PARAMS = Set.of("page", "size", "status", "keyword", "device_id",
        "scope_selected", "scope_tenant_ids", "scope_place_ids");
    private static final Set<String> SCOPE_PARAMS = Set.of("scope_selected", "scope_tenant_ids", "scope_place_ids");
    /** The upload route is the one bounded route whose base64 body exceeds the generic 128KiB JSON string limit. */
    private static final ObjectMapper UPLOAD_BODY = uploadMapper();

    private final RequestSecurity security;
    private final FireArchiveQueries queries;
    private final FireArchiveCommands commands;
    private final FireArchiveAttachments attachments;

    public FireArchiveController(RequestSecurity security, FireArchiveQueries queries, FireArchiveCommands commands,
                                 FireArchiveAttachments attachments) {
        this.security = security; this.queries = queries; this.commands = commands; this.attachments = attachments;
    }

    @GetMapping(BASE)
    public FireArchiveModels.Page page(@PathVariable String audience,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(required = false) Integer size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String device_id,
                                       HttpServletRequest request) {
        var actor = security.require(request, "fire-archives:read", audience);
        only(request, LIST_PARAMS);
        return queries.page(actor.user().id(), security.optionalTenant(request), security.readSelection(request),
            status, keyword, device_id, page, size);
    }

    @GetMapping(BASE + "/{id}")
    public FireArchiveModels.Detail detail(@PathVariable String audience, @PathVariable String id,
                                           HttpServletRequest request) {
        var actor = security.require(request, "fire-archives:read", audience);
        only(request, SCOPE_PARAMS);
        return queries.detail(actor.user().id(), security.optionalTenant(request), security.readSelection(request), id);
    }

    @GetMapping(BASE + "/{id}/history")
    public List<FireArchiveModels.History> history(@PathVariable String audience, @PathVariable String id,
                                                   HttpServletRequest request) {
        var actor = security.require(request, "fire-archives:read", audience);
        only(request, SCOPE_PARAMS);
        return queries.history(actor.user().id(), security.optionalTenant(request), security.readSelection(request), id);
    }

    @PostMapping(BASE)
    public FireArchiveModels.Detail create(@PathVariable String audience,
                                           @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody FireArchiveInputs.CreateBody body,
                                           HttpServletRequest request) {
        var actor = security.require(request, "fire-archives:write", audience);
        return commands.create(actor, security.tenant(request), key, FireArchiveInputs.create(body),
            RequestContext.requestId(request));
    }

    @PutMapping(BASE + "/{id}")
    public FireArchiveModels.Detail update(@PathVariable String audience, @PathVariable String id,
                                           @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody FireArchiveInputs.UpdateBody body,
                                           HttpServletRequest request) {
        var actor = security.require(request, "fire-archives:write", audience);
        return commands.update(actor, security.tenant(request), id, key, FireArchiveInputs.update(body),
            RequestContext.requestId(request));
    }

    @PostMapping(BASE + "/{id}/archive")
    public FireArchiveModels.Detail archive(@PathVariable String audience, @PathVariable String id,
                                            @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody FireArchiveInputs.ArchiveBody body,
                                            HttpServletRequest request) {
        var actor = security.require(request, "fire-archives:write", audience);
        return commands.archive(actor, security.tenant(request), id, key, FireArchiveInputs.archive(body),
            RequestContext.requestId(request));
    }

    @PostMapping(BASE + "/{id}/attachments")
    public FireArchiveModels.Detail upload(@PathVariable String audience, @PathVariable String id,
                                           @RequestHeader("Idempotency-Key") String key,
                                           HttpServletRequest request) {
        var actor = security.require(request, "fire-archives:write", audience);
        FireArchiveInputs.AttachmentBody body = uploadBody(request);
        return attachments.upload(actor, security.tenant(request), id, key, FireArchiveInputs.attachment(body),
            RequestContext.requestId(request));
    }

    @GetMapping(BASE + "/{id}/attachments/{attachmentId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String audience, @PathVariable String id,
                                           @PathVariable String attachmentId, HttpServletRequest request) {
        var actor = security.require(request, "fire-archives:read", audience);
        only(request, SCOPE_PARAMS);
        var file = attachments.download(actor, security.optionalTenant(request), security.readSelection(request),
            id, attachmentId, RequestContext.requestId(request));
        String disposition = ContentDisposition.attachment()
            .filename(file.filename(), StandardCharsets.UTF_8).build().toString();
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(file.content().length)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(file.content());
    }

    private static FireArchiveInputs.AttachmentBody uploadBody(HttpServletRequest request) {
        try (InputStream input = request.getInputStream()) {
            byte[] raw = input.readNBytes(Limits.MAX_JSON_BODY_BYTES + 1);
            if (raw.length > Limits.MAX_JSON_BODY_BYTES)
                throw new ApiError(413, "REQUEST_TOO_LARGE", "JSON请求体超过资源限制");
            if (raw.length == 0) throw ApiError.bad("附件请求体为空");
            return UPLOAD_BODY.readValue(raw, FireArchiveInputs.AttachmentBody.class);
        } catch (ApiError failure) {
            throw failure;
        } catch (IOException failure) {
            if (failure instanceof com.fireplatform.shared.JsonRequestSizeFilter.BodyTooLarge)
                throw new ApiError(413, "REQUEST_TOO_LARGE", "JSON请求体超过资源限制");
            throw ApiError.bad("附件JSON结构无效");
        }
    }

    private static ObjectMapper uploadMapper() {
        var mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        mapper.disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
        mapper.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
            .maxNestingDepth(Limits.JSON_MAX_DEPTH).maxStringLength(1_600_000)
            .maxNumberLength(Limits.JSON_MAX_NUMBER_LENGTH).build());
        mapper.getFactory().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        return mapper;
    }

    private static void only(HttpServletRequest request, Set<String> allowed) {
        for (var entry : request.getParameterMap().entrySet())
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
                throw ApiError.bad("不支持的查询参数");
    }
}
