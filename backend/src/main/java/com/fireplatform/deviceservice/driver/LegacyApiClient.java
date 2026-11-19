package com.fireplatform.deviceservice.driver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.deviceservice.LegacySourceConfigService;
import com.fireplatform.transport.PinnedHttpTransport;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal client for the verified legacy login and JSON envelope. It logs in per operation, keeps the
 * token only in memory, never puts it in a URL, never logs request or response content, and never
 * follows redirects. A failed login means no device request was sent.
 */
final class LegacyApiClient {
    record ApiResult(int status, int code, JsonNode data, String message, JsonNode root) {
        boolean accepted() { return status >= 200 && status < 300 && code == 200; }
    }

    /** Lower transport seam. Production always uses PinnedHttpTransport, which pins DNS and bounds all IO. */
    @FunctionalInterface
    interface Transport {
        PinnedHttpTransport.Response exchange(PinnedHttpTransport.Target target, String method, String path,
                                              Map<String, String> headers, byte[] body);
    }

    static final class Failure extends RuntimeException {
        private final String code;
        private final Integer status;
        Failure(String code, Integer status, String message) { super(message); this.code = code; this.status = status; }
        String code() { return code; }
        Integer status() { return status; }
    }

    private final ObjectMapper json;
    private final LegacySourceConfigService.Config config;
    private final String authorizationHeader;
    private final Transport transport;

    LegacyApiClient(ObjectMapper json, LegacySourceConfigService.Config config, String authorizationHeader,
                    Transport transport) {
        this.json = json;
        this.config = config;
        this.authorizationHeader = authorizationHeader;
        this.transport = transport;
    }

    Session open() {
        if (config == null || !config.configured() || config.username() == null || config.password() == null)
            throw new Failure(config == null ? "NOT_CONFIGURED" : config.code(), null, "旧平台账号未配置或不可用");
        ObjectNode body = json.createObjectNode().put("username", config.username()).put("password", config.password());
        ApiResult result = exchange("POST", "/api/login", body, false, null);
        String token = result.data() == null ? null : text(result.data().path("token"));
        if (!result.accepted() || token == null || token.isBlank())
            throw new Failure(result.status() == 200 ? "LOGIN_REJECTED" : "LOGIN_FAILED", result.status(), "旧平台登录未接受");
        return new Session(token);
    }

    final class Session {
        private final String token;
        private Session(String token) { this.token = token; }

        ApiResult get(String path) { return exchange("GET", path, null, true, token); }
        ApiResult post(String path, ObjectNode body) { return exchange("POST", path, body, true, token); }
        ApiResult put(String path, ObjectNode body) { return exchange("PUT", path, body, true, token); }
        ApiResult delete(String path) { return exchange("DELETE", path, null, true, token); }
    }

    private ApiResult exchange(String method, String path, ObjectNode body, boolean authenticated, String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        if (body != null) headers.put("Content-Type", "application/json");
        if (authenticated) headers.put(authorizationHeader, "Bearer " + token);
        byte[] payload = null;
        if (body != null) {
            try { payload = json.writeValueAsBytes(body); }
            catch (IOException invalid) { throw new Failure("REQUEST_SERIALIZATION_FAILED", null, "旧平台请求无法序列化"); }
        }
        PinnedHttpTransport.Response response;
        try {
            response = transport.exchange(new PinnedHttpTransport.Target("http".equals(scheme())
                    ? "http" : "https", host(), port(), config.allowInsecureHttp()),
                method, path, headers, payload);
        } catch (PinnedHttpTransport.Failure failure) {
            throw new Failure(failure.code(), null, failure.getMessage());
        }
        JsonNode root = null;
        if (response.body() != null && response.body().length > 0) {
            try { root = json.readTree(response.body()); }
            catch (IOException invalid) { root = null; }
        }
        int code = root != null && root.path("code").canConvertToInt() ? root.path("code").asInt() : -1;
        JsonNode data = root == null ? null : root.get("data");
        String message = root != null && root.path("message").isTextual() ? root.path("message").asText() : null;
        return new ApiResult(response.status(), code, data, message, root);
    }

    private String scheme() {
        int separator = config.origin().indexOf("://");
        return separator < 0 ? "" : config.origin().substring(0, separator);
    }

    private String host() {
        int start = config.origin().indexOf("://") + 3;
        int end = config.origin().indexOf(':', start);
        return end < 0 ? config.origin().substring(start) : config.origin().substring(start, end);
    }

    private int port() {
        int start = config.origin().indexOf("://") + 3;
        int end = config.origin().indexOf(':', start);
        if (end < 0) return "http".equals(scheme()) ? 80 : 443;
        return Integer.parseInt(config.origin().substring(end + 1));
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String text(JsonNode value) {
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }
}
