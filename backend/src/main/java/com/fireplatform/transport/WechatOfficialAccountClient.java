package com.fireplatform.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Official Account HTTPS transport. Only the published WeChat hosts are reachable; the base URL is
 * not page-configurable and redirects are never followed.
 *
 * <p>The client never logs AppSecret, access tokens, OAuth codes, OpenIDs or full provider bodies.
 * Every call has an 8 second timeout and a bounded response body.</p>
 */
@Component
public class WechatOfficialAccountClient {
    private static final Logger log = LoggerFactory.getLogger(WechatOfficialAccountClient.class);
    private static final String API_ORIGIN = "https://api.weixin.qq.com";
    private static final String AUTHORIZE_ORIGIN = "https://open.weixin.qq.com";
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Outcome { ACCEPTED, REJECTED, TOKEN_INVALID, UNKNOWN }

    /** ACCESS TOKEN acquisition result; expiry is reported by WeChat and clamped by the cache. */
    public record TokenResult(boolean success, String token, String code, String message, long expiresIn) { }
    /** OAuth code exchange result. Snapshot-mode users are rejected by the binding workflow. */
    public record OAuthResult(boolean success, String openid, String unionid, boolean snapshotUser, String code, String message) { }
    /** Template submission result. ACCEPTED is never delivery. */
    public record SendResult(Outcome outcome, String msgid, String code, String message) {
        public boolean accepted() { return outcome == Outcome.ACCEPTED; }
        public boolean definiteRejection() { return outcome == Outcome.REJECTED || outcome == Outcome.TOKEN_INVALID; }
        public boolean tokenInvalid() { return outcome == Outcome.TOKEN_INVALID; }
        public boolean unknown() { return outcome == Outcome.UNKNOWN; }
    }

    private final HttpClient http;
    private final String apiOrigin;
    private final String authorizeOrigin;

    public WechatOfficialAccountClient() {
        this(API_ORIGIN, AUTHORIZE_ORIGIN, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** Test constructor: explicit origins and client. Production wiring uses the no-argument form. */
    WechatOfficialAccountClient(String apiOrigin, String authorizeOrigin, HttpClient http) {
        this.apiOrigin = requireOrigin(apiOrigin, "api.weixin.qq.com");
        this.authorizeOrigin = requireOrigin(authorizeOrigin, "open.weixin.qq.com");
        this.http = http;
    }

    /** The exact published authorize URL; state and redirect are encoded by this method. */
    public String authorizeUrl(String appId, String redirectUri, String state) {
        String query = "appid=" + encode(appId)
            + "&redirect_uri=" + encode(redirectUri)
            + "&response_type=code"
            + "&scope=snsapi_base"
            + "&state=" + encode(state)
            + "#wechat_redirect";
        return authorizeOrigin + "/connect/oauth2/authorize?" + query;
    }

    public TokenResult accessToken(String appId, String appSecret) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank())
            return new TokenResult(false, null, "NOT_CONFIGURED", "公众号 AppID/AppSecret 未配置", 0);
        String path = "/cgi-bin/token?grant_type=client_credential&appid=" + encode(appId) + "&secret=" + encode(appSecret);
        JsonNode body = get(path);
        if (body == null) return new TokenResult(false, null, "PROVIDER_UNAVAILABLE", "公众号接口不可用", 0);
        int error = errorCode(body);
        if (error != 0) return new TokenResult(false, null, Integer.toString(error), text(body, "errmsg"), 0);
        String token = text(body, "access_token");
        long expires = body.path("expires_in").asLong(0);
        if (token == null || token.isBlank()) return new TokenResult(false, null, "EMPTY_TOKEN", "公众号未返回 access_token", 0);
        return new TokenResult(true, token, "0", "ok", expires);
    }

    public OAuthResult exchangeCode(String appId, String appSecret, String code) {
        if (code == null || code.isBlank()) return new OAuthResult(false, null, null, false, "CODE_MISSING", "缺少网页授权 code");
        String path = "/sns/oauth2/access_token?appid=" + encode(appId) + "&secret=" + encode(appSecret)
            + "&code=" + encode(code) + "&grant_type=authorization_code";
        JsonNode body = get(path);
        if (body == null) return new OAuthResult(false, null, null, false, "PROVIDER_UNAVAILABLE", "公众号网页授权接口不可用");
        int error = errorCode(body);
        if (error != 0) return new OAuthResult(false, null, null, false, Integer.toString(error), text(body, "errmsg"));
        String openid = text(body, "openid");
        if (openid == null || openid.isBlank()) return new OAuthResult(false, null, null, false, "OPENID_MISSING", "公众号未返回 OpenID");
        boolean snapshot = body.path("is_snapshotuser").asInt(0) == 1;
        return new OAuthResult(true, openid, text(body, "unionid"), snapshot, "0", "ok");
    }

    /**
     * Submits a template message. {@code clientMsgId} is bound to the local attempt id, which lets
     * WeChat deduplicate an accidental retry for the same OpenID within its documented window.
     */
    public SendResult sendTemplate(String accessToken, String openid, String templateId, Map<String, String> values, String clientMsgId) {
        if (accessToken == null || accessToken.isBlank()) return new SendResult(Outcome.REJECTED, null, "TOKEN_UNAVAILABLE", "公众号 access_token 未获取");
        if (openid == null || openid.isBlank()) return new SendResult(Outcome.REJECTED, null, "OPENID_UNAVAILABLE", "接收 OpenID 不可用");
        if (templateId == null || templateId.isBlank()) return new SendResult(Outcome.REJECTED, null, "TEMPLATE_NOT_CONFIGURED", "模板 ID 未配置");
        Map<String, Object> data = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            data.put(entry.getKey(), Map.of("value", entry.getValue() == null ? "" : entry.getValue()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("touser", openid);
        payload.put("template_id", templateId);
        payload.put("client_msg_id", clientMsgId);
        payload.put("data", data);
        try {
            String body = MAPPER.writeValueAsString(payload);
            if (body.getBytes(StandardCharsets.UTF_8).length > 16 * 1024)
                return new SendResult(Outcome.REJECTED, null, "PAYLOAD_TOO_LARGE", "模板数据超过本平台上限");
            JsonNode response = postJson("/cgi-bin/message/template/send?access_token=" + encode(accessToken), body);
            if (response == null) return new SendResult(Outcome.UNKNOWN, null, "RESPONSE_UNKNOWN", "提交结果未知，不能自动重发");
            int error = errorCode(response);
            if (error == 0) {
                String msgid = exactInteger(response.get("msgid"));
                return new SendResult(Outcome.ACCEPTED, msgid, "0", text(response, "errmsg"));
            }
            if (error == 40001 || error == 40014 || error == 42001)
                return new SendResult(Outcome.TOKEN_INVALID, null, Integer.toString(error), text(response, "errmsg"));
            return new SendResult(Outcome.REJECTED, null, Integer.toString(error), text(response, "errmsg"));
        } catch (Exception failure) {
            log.warn("wechat official account template submission failed: {}", failure.getClass().getSimpleName());
            return new SendResult(Outcome.UNKNOWN, null, "TRANSPORT_ERROR", "提交结果未知，不能自动重发");
        }
    }

    private JsonNode get(String pathAndQuery) {
        URI uri = api(pathAndQuery);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                drain(response.body());
                return null;
            }
            byte[] body = readBounded(response.body(), MAX_RESPONSE_BYTES);
            return body.length == 0 ? null : MAPPER.readTree(body);
        } catch (Exception failure) {
            log.warn("wechat official account request failed: {}", failure.getClass().getSimpleName());
            return null;
        }
    }

    private JsonNode postJson(String pathAndQuery, String body) {
        URI uri = api(pathAndQuery);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                drain(response.body());
                return null;
            }
            byte[] raw = readBounded(response.body(), MAX_RESPONSE_BYTES);
            return raw.length == 0 ? null : MAPPER.readTree(raw);
        } catch (Exception failure) {
            log.warn("wechat official account submission failed: {}", failure.getClass().getSimpleName());
            return null;
        }
    }

    private URI api(String pathAndQuery) {
        URI uri = URI.create(apiOrigin + pathAndQuery);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"api.weixin.qq.com".equalsIgnoreCase(uri.getHost()))
            throw new IllegalArgumentException("official account endpoint");
        return uri;
    }

    private static int errorCode(JsonNode body) {
        if (body == null) return -1;
        JsonNode value = body.get("errcode");
        return value == null || value.isNull() ? 0 : value.asInt(-1);
    }

    /** Preserves the exact decimal msgid representation instead of routing it through a double. */
    private static String exactInteger(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isIntegralNumber()) return node.bigIntegerValue().toString();
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText("");
        return text.isBlank() ? null : text;
    }

    private static byte[] readBounded(InputStream input, int limit) throws java.io.IOException {
        try (InputStream stream = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new java.io.IOException("response too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void drain(InputStream input) {
        try (InputStream stream = input) { stream.readNBytes(1024); }
        catch (Exception ignored) { }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String requireOrigin(String value, String host) {
        URI uri = URI.create(value == null ? "" : value.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !host.equalsIgnoreCase(uri.getHost()) || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Official Account origin must be the published HTTPS host");
        String text = uri.toString();
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }
}
