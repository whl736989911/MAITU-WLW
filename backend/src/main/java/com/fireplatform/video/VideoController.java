package com.fireplatform.video;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

/**
 * Video resource metadata, associations and the authenticated bounded media proxy. Media GETs use an
 * HttpOnly SameSite cookie, never a URL token; every request re-validates user, permission and device scope.
 */
@RestController
public class VideoController {
    private static final String READ = "/api/{audience:management|customer}/video-resources";
    private static final String WRITE = "/api/management/video-resources";
    private static final Set<String> LIST_PARAMS = Set.of("page", "size", "keyword", "include_archived",
        "scope_selected", "scope_tenant_ids", "scope_place_ids");
    private static final Set<String> DETAIL_PARAMS = Set.of("scope_selected", "scope_tenant_ids", "scope_place_ids");

    public record CreateBody(@NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 16) String resource_kind,
                             @NotBlank @Size(max = 16) String protocol,
                             @NotBlank @Size(max = 1000) String reason) { }
    public record UpdateBody(@NotNull @jakarta.validation.constraints.Min(0) Long expected_revision,
                             @NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 16) String resource_kind,
                             @NotBlank @Size(max = 16) String protocol,
                             @NotBlank @Size(max = 1000) String reason) { }
    public record EndpointBody(@NotNull @jakarta.validation.constraints.Min(0) Long expected_revision,
                               @NotBlank @Size(max = 4096) String endpoint_url, @Size(max = 128) String username,
                               @Size(max = 256) String password,
                               @NotBlank @Size(max = 1000) String reason) { }
    public record VersionedBody(@NotNull @jakarta.validation.constraints.Min(0) Long expected_revision,
                                @NotBlank @Size(max = 1000) String reason) { }
    public record CredentialsBody(@NotNull @jakarta.validation.constraints.Min(0) Long expected_revision,
                                  @NotBlank @Size(max = 128) String username, @NotBlank @Size(max = 256) String password,
                                  @NotBlank @Size(max = 1000) String reason) { }
    public record EnabledBody(@NotNull @jakarta.validation.constraints.Min(0) Long expected_revision,
                              @NotNull Boolean enabled,
                              @NotBlank @Size(max = 1000) String reason) { }
    public record AssociationBody(@NotBlank @Size(max = 64) String device_id,
                                  @NotBlank @Size(max = 1000) String reason) { }
    public record RevokeAssociationBody(@NotNull @jakarta.validation.constraints.Min(0) Long expected_revision,
                                        @NotBlank @Size(max = 1000) String revoke_reason,
                                        @NotBlank @Size(max = 1000) String reason) { }
    public record MediaSessionBody(@NotBlank @Size(max = 64) String device_id,
                                   @NotBlank @Size(max = 16) String purpose) { }
    public record GatewayBody(@NotNull @jakarta.validation.constraints.Min(0) Long expected_revision,
                              @NotBlank @Size(max = 4096) String gateway_url,
                              @NotBlank @Size(max = 1000) String reason) { }
    public record CaptureBody(@NotBlank @Size(max = 64) String device_id, @Size(max = 64) String source_id) { }

    private final RequestSecurity security;
    private final VideoResourceQueries queries;
    private final VideoResourceCommands commands;
    private final VideoMediaService media;
    private final VideoPolicy policy;
    private final VideoCaptureService captures;
    private final VideoViewIntents viewIntents;
    private final VideoUpstreamClient upstream;

    public VideoController(RequestSecurity security, VideoResourceQueries queries, VideoResourceCommands commands,
                           VideoMediaService media, VideoPolicy policy, VideoCaptureService captures,
                           VideoViewIntents viewIntents, VideoUpstreamClient upstream) {
        this.security = security; this.queries = queries; this.commands = commands; this.media = media;
        this.policy = policy; this.captures = captures; this.viewIntents = viewIntents; this.upstream = upstream;
    }

    // ---------------- reads ----------------

    @GetMapping(READ)
    public Page<VideoModels.Resource> page(@PathVariable String audience, @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String include_archived,
                                           HttpServletRequest request) {
        var actor = security.require(request, "video:read", audience);
        only(request, LIST_PARAMS);
        boolean includeArchived = "true".equals(include_archived);
        if (include_archived != null && !include_archived.isEmpty() && !includeArchived && !"false".equals(include_archived))
            throw ApiError.bad("归档筛选参数无效");
        if (includeArchived && !"management".equals(audience))
            throw ApiError.bad("客户读取不包含已归档视频资源");
        return queries.page(actor.user().id(), security.tenant(request), security.readSelection(request),
            keyword, includeArchived, page, size, "customer".equals(audience));
    }

    @GetMapping(READ + "/{id}")
    public VideoModels.ResourceDetail detail(@PathVariable String audience, @PathVariable String id,
                                             HttpServletRequest request) {
        var actor = security.require(request, "video:read", audience);
        only(request, DETAIL_PARAMS);
        return queries.detail(actor.user().id(), security.tenant(request), security.readSelection(request), id,
            "customer".equals(audience));
    }

    // ---------------- media sessions ----------------

    @PostMapping("/api/{audience:management|customer}/video-resources/{id}/media-sessions")
    public VideoModels.MediaSession openMediaSession(@PathVariable String audience, @PathVariable String id,
                                                     @Valid @RequestBody MediaSessionBody body,
                                                     HttpServletRequest request, HttpServletResponse response) {
        var actor = security.require(request, "video:play", audience);
        if (!actor.audience().equals(audience)) throw ApiError.forbidden("登录入口与资源受众不匹配");
        var issued = media.issue(actor, audience, security.tenant(request), id,
            VideoInputs.mediaSession(new VideoInputs.MediaSessionBody(body.device_id(), body.purpose())),
            RequestContext.requestId(request));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(issued.token(), issued.cookiePath(),
            policy.sessionTtl().toSeconds()).toString());
        return issued.session();
    }

    @PostMapping("/api/{audience:management|customer}/video-resources/{id}/media-sessions/revoke")
    public void revokeMediaSession(@PathVariable String audience, @PathVariable String id,
                                   @CookieValue(name = "fp_video_media", required = false) String token,
                                   HttpServletRequest request, HttpServletResponse response) {
        var actor = security.require(request, "video:play", audience);
        media.revoke(actor, audience, id, token);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", VideoMediaService.cookiePath(audience, id), 0).toString());
    }

    @GetMapping(READ + "/{id}/media/play")
    public void play(@PathVariable String audience, @PathVariable String id,
                     @CookieValue(name = "fp_video_media", required = false) String token,
                     @RequestHeader(value = "Range", required = false) String range,
                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        writePrimary(audience, id, token, "PLAYBACK", range, response);
    }

    @GetMapping(READ + "/{id}/media/snapshot")
    public void snapshot(@PathVariable String audience, @PathVariable String id,
                         @CookieValue(name = "fp_video_media", required = false) String token,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        writePrimary(audience, id, token, "SNAPSHOT", null, response);
    }

    @GetMapping(READ + "/{id}/media/hls/{entry}")
    public void hls(@PathVariable String audience, @PathVariable String id, @PathVariable int entry,
                    @CookieValue(name = "fp_video_media", required = false) String token,
                    HttpServletRequest request, HttpServletResponse response) throws IOException {
        var context = media.validate(audience, id, token, "PLAYBACK");
        try (var payload = media.openHls(context, entry)) {
            if (payload.isBytes()) {
                writeBytes(response, payload.contentType(), payload.bytes(), policy.maxSubresourceBytes());
            } else {
                throw new ApiError(502, "VIDEO_UPSTREAM_UNEXPECTED", "HLS子资源读取结果未确认");
            }
        }
    }

    private void writePrimary(String audience, String id, String token, String purpose, String range,
                              HttpServletResponse response) throws IOException {
        var context = media.validate(audience, id, token, purpose);
        try (var payload = media.openPrimary(context, range)) {
            if (payload.isBytes()) {
                writeBytes(response, payload.contentType(), payload.bytes(), policy.maxPrimaryBytes());
                return;
            }
            response.setStatus(payload.streaming().status());
            response.setContentType(payload.contentType());
            harden(response);
            if (payload.contentRange() != null) response.setHeader("Content-Range", payload.contentRange());
            response.setHeader("Accept-Ranges", payload.acceptsRanges() ? "bytes" : "none");
            if (payload.streaming().contentLength() >= 0)
                response.setContentLengthLong(payload.streaming().contentLength());
            upstream.stream(payload.streaming(), response.getOutputStream(), policy.maxPrimaryBytes());
        }
    }

    private static void writeBytes(HttpServletResponse response, String contentType, byte[] body, long maximum)
            throws IOException {
        if (body.length > maximum) throw new ApiError(502, "VIDEO_UPSTREAM_TOO_LARGE", "媒体内容超过大小上限");
        response.setStatus(200);
        response.setContentType(contentType);
        harden(response);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static void harden(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
    }

    private ResponseCookie cookie(String value, String path, long maxAgeSeconds) {
        return ResponseCookie.from(VideoMediaService.cookieName(), value)
            .httpOnly(true).sameSite("Strict").secure(policy.cookieSecure()).path(path)
            .maxAge(java.time.Duration.ofSeconds(maxAgeSeconds)).build();
    }

    // ---------------- capture jobs and alarm intents ----------------

    @PostMapping(READ + "/{id}/capture-jobs")
    public VideoCaptureService.Job capture(@PathVariable String audience, @PathVariable String id,
                                           @Valid @RequestBody CaptureBody body, HttpServletRequest request) {
        var actor = security.require(request, "video:play", audience);
        return captures.queueManual(actor.user().id(), security.tenant(request), id, body.device_id(),
            body.source_id() == null || body.source_id().isBlank() ? id : body.source_id().trim());
    }

    @GetMapping(READ + "/{id}/capture-jobs/{jobId}")
    public VideoCaptureService.Job captureJob(@PathVariable String audience, @PathVariable String id,
                                              @PathVariable String jobId, HttpServletRequest request) {
        var actor = security.require(request, "video:read", audience);
        return captures.visibleJob(actor.user().id(), security.tenant(request), id, jobId);
    }

    @GetMapping(READ + "/{id}/capture-jobs/{jobId}/image")
    public void captureImage(@PathVariable String audience, @PathVariable String id, @PathVariable String jobId,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        var actor = security.require(request, "video:read", audience);
        security.require(request, "video:play", audience);
        var image = captures.image(actor.user().id(), security.tenant(request), id, jobId);
        response.setStatus(200);
        response.setContentType(image.mediaType());
        harden(response);
        response.setHeader("X-Capture-Sha256", image.sha256());
        response.setHeader("X-Capture-At", image.captureAt().toString());
        response.setContentLength(image.bytes().length);
        response.getOutputStream().write(image.bytes());
    }

    @GetMapping("/api/{audience:management|customer}/video-alarm-intents")
    public List<VideoViewIntents.AlarmVideo> alarmIntents(@PathVariable String audience,
                                                          @RequestParam("alarm_id") String alarmId,
                                                          HttpServletRequest request) {
        var actor = security.require(request, "video:read", audience);
        only(request, Set.of("alarm_id"));
        return viewIntents.forAlarm(actor.user().id(), security.tenant(request), alarmId);
    }

    @PutMapping(WRITE + "/{id}/gateway")
    public VideoModels.Resource gateway(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody GatewayBody body, HttpServletRequest request) {
        var actor = security.require(request, "video:write", "management");
        return commands.saveGateway(actor, security.tenant(request), id, key,
            VideoInputs.gateway(new VideoInputs.GatewayBody(body.expected_revision(), body.gateway_url(), body.reason())),
            RequestContext.requestId(request));
    }

    @PostMapping(WRITE + "/{id}/gateway/clear")
    public VideoModels.Resource clearGateway(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody VersionedBody body, HttpServletRequest request) {
        var actor = security.require(request, "video:write", "management");
        return commands.clearGateway(actor, security.tenant(request), id, key,
            VideoInputs.versioned(body.expected_revision(), body.reason(), "网关"),
            RequestContext.requestId(request));
    }

    // ---------------- management writes ----------------

    @PostMapping(WRITE)
    public VideoModels.Resource create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateBody body,
                                       HttpServletRequest request) {
        var actor = security.require(request, "video:write", "management");
        return commands.create(actor, security.tenant(request), key,
            VideoInputs.createResource(new VideoInputs.CreateResourceBody(body.name(), body.resource_kind(),
                body.protocol(), body.reason())),
            RequestContext.requestId(request));
    }

    @PutMapping(WRITE + "/{id}")
    public VideoModels.Resource update(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody UpdateBody body, HttpServletRequest request) {
        var actor = security.require(request, "video:write", "management");
        return commands.update(actor, security.tenant(request), id, key,
            VideoInputs.updateResource(new VideoInputs.UpdateResourceBody(body.expected_revision(), body.name(),
                body.resource_kind(), body.protocol(), body.reason())),
            RequestContext.requestId(request));
    }

    @PutMapping(WRITE + "/{id}/endpoint")
    public VideoModels.Resource endpoint(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody EndpointBody body, HttpServletRequest request) {
        var actor = security.require(request, "video:write", "management");
        return commands.saveEndpoint(actor, security.tenant(request), id, key,
            VideoInputs.endpoint(new VideoInputs.EndpointBody(body.expected_revision(), body.endpoint_url(),
                body.username(), body.password(), body.reason())),
            RequestContext.requestId(request));
    }

    @PostMapping(WRITE + "/{id}/endpoint/clear")
    public VideoModels.Resource clearEndpoint(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody VersionedBody body, HttpServletRequest request) {
        var actor = security.require(request, "video:write", "management");
        return commands.clearEndpoint(actor, security.tenant(request), id, key,
            VideoInputs.versioned(body.expected_revision(), body.reason(), "端点"),
            RequestContext.requestId(request));
    }

    @PutMapping(WRITE + "/{id}/credentials")
    public VideoModels.Resource credentials(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody CredentialsBody body, HttpServletRequest request) {
        var actor = security.require(request, "video:write", "management");
        return commands.saveCredentials(actor, security.tenant(request), id, key,
            VideoInputs.credentials(new VideoInputs.CredentialsBody(body.expected_revision(), body.username(),
                body.password(), body.reason())),
            RequestContext.requestId(request));
    }

    @PostMapping(WRITE + "/{id}/credentials/clear")
    public VideoModels.Resource clearCredentials(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody VersionedBody body, HttpServletRequest request) {
        var actor = security.require(request, "video:write", "management");
        return commands.clearCredentials(actor, security.tenant(request), id, key,
            VideoInputs.versioned(body.expected_revision(), body.reason(), "凭据"),
            RequestContext.requestId(request));
    }

    @PostMapping(WRITE + "/{id}/enabled")
    public VideoModels.Resource enabled(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody EnabledBody body, HttpServletRequest request) {
        var actor = security.require(request, "video:write", "management");
        return commands.setEnabled(actor, security.tenant(request), id, key,
            VideoInputs.enabled(new VideoInputs.EnabledBody(body.expected_revision(), body.enabled(), body.reason())),
            RequestContext.requestId(request));
    }

    @PostMapping(WRITE + "/{id}/archive")
    public VideoModels.Resource archive(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody VersionedBody body, HttpServletRequest request) {
        var actor = security.require(request, "video:write", "management");
        return commands.archive(actor, security.tenant(request), id, key,
            VideoInputs.versioned(body.expected_revision(), body.reason(), "归档"),
            RequestContext.requestId(request));
    }

    @PostMapping(WRITE + "/{id}/associations")
    public VideoModels.Association associate(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody AssociationBody body, HttpServletRequest request) {
        var actor = security.require(request, "video:write", "management");
        return commands.associate(actor, security.tenant(request), key,
            VideoInputs.association(new VideoInputs.AssociationBody(id, body.device_id(),
                body.reason())),
            RequestContext.requestId(request));
    }

    @PostMapping(WRITE + "/{id}/associations/{associationId}/revoke")
    public VideoModels.Association revokeAssociation(@PathVariable String id, @PathVariable String associationId,
                                                     @RequestHeader("Idempotency-Key") String key,
                                                     @Valid @RequestBody RevokeAssociationBody body,
                                                     HttpServletRequest request) {
        var actor = security.require(request, "video:write", "management");
        return commands.revokeAssociation(actor, security.tenant(request), associationId, key,
            VideoInputs.revokeAssociation(new VideoInputs.RevokeAssociationBody(body.expected_revision(),
                body.revoke_reason(), body.reason())),
            RequestContext.requestId(request));
    }

    private static void only(HttpServletRequest request, Set<String> allowed) {
        for (var entry : request.getParameterMap().entrySet())
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
                throw ApiError.bad("不支持的查询参数");
    }
}
