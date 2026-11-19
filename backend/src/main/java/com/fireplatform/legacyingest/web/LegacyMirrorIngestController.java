package com.fireplatform.legacyingest.web;

import com.fireplatform.legacyingest.mirror.LegacyMirrorIntakeService;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 旧平台入口镜像接收：nginx mirror 把原始请求复制到这里。
 * 前缀路由、Token 常量时间校验、原始 body 先落 raw 后异步解析。
 */
@RestController
public class LegacyMirrorIngestController {
    private static final Pattern TTL_PATH = Pattern.compile("(?:/api)?/api/notify/v1/[^/]+/([A-Za-z0-9_]+)/telemetry");
    private static final Pattern CAT1_PATH = Pattern.compile("(?:/api)?/v1/formttl/([A-Za-z0-9_-]+)/telemetry");
    private final LegacyMirrorIntakeService intake;
    private final String token;
    private final String previousToken;

    public LegacyMirrorIngestController(LegacyMirrorIntakeService intake,
            @Value("${fire.legacy.mirror.token:}") String token,
            @Value("${fire.legacy.mirror.token-previous:}") String previousToken) {
        this.intake = intake; this.token = token; this.previousToken = previousToken;
    }

    @PostMapping({"/ingest/ttl", "/ingest/ttl/**", "/api/ingest/ttl", "/api/ingest/ttl/**"})
    public ResponseEntity<Map<String, Object>> ttlMirror(HttpServletRequest request,
            @RequestBody(required = false) String body) {
        verify(request);
        String original = originalUri(request, "/api/ingest/ttl", "/ingest/ttl");
        Matcher matcher = TTL_PATH.matcher(original);
        String platform = matcher.find() ? matcher.group(1) : null;
        return accepted(intake.accept("HTTP_NOTIFY", platform, null, requireBody(body), request.getContentType()));
    }

    @PostMapping({"/ingest/cat1", "/ingest/cat1/**", "/api/ingest/cat1", "/api/ingest/cat1/**"})
    public ResponseEntity<Map<String, Object>> cat1Mirror(HttpServletRequest request,
            @RequestBody(required = false) String body) {
        verify(request);
        String original = originalUri(request, "/api/ingest/cat1", "/ingest/cat1");
        Matcher matcher = CAT1_PATH.matcher(original);
        String messageType = matcher.find() ? matcher.group(1) : null;
        return accepted(intake.accept("HTTP_FORM_TTL", "formttl", messageType, requireBody(body), request.getContentType()));
    }

    private ResponseEntity<Map<String, Object>> accepted(LegacyMirrorIntakeService.Accepted accepted) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ACCEPTED");
        response.put("id", accepted.id());
        response.put("duplicate", accepted.duplicate());
        return ResponseEntity.accepted().body(response);
    }

    private static String requireBody(String body) {
        if (body == null || body.isBlank()) throw ApiError.bad("镜像报文为空");
        return body;
    }

    private void verify(HttpServletRequest request) {
        String supplied = request.getHeader("X-Ingest-Token");
        if (supplied == null || !(matches(token, supplied) || matches(previousToken, supplied))) {
            intake.recordAuthFailure();
            throw new ApiError(401, "MIRROR_INGEST_UNAUTHORIZED", "镜像入口认证失败");
        }
    }

    private static boolean matches(String configured, String supplied) {
        if (configured == null || configured.isBlank()) return false;
        return MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static String originalUri(HttpServletRequest request, String apiPrefix, String plainPrefix) {
        String supplied = request.getHeader("X-Original-Uri");
        if (supplied != null && !supplied.isBlank()) {
            int query = supplied.indexOf('?');
            return query >= 0 ? supplied.substring(0, query) : supplied;
        }
        String raw = request.getRequestURI();
        if (raw.startsWith(apiPrefix)) return raw.substring(apiPrefix.length());
        if (raw.startsWith(plainPrefix)) return raw.substring(plainPrefix.length());
        return raw;
    }
}
