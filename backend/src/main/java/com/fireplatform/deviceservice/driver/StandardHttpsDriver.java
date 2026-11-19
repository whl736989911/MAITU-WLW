package com.fireplatform.deviceservice.driver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.deviceservice.IntegrationDriver;
import com.fireplatform.deviceservice.IntegrationDriverConfigService;
import com.fireplatform.transport.PinnedHttpTransport;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Standard HTTPS product-model gateway. It is a real transport for operations declared on a published
 * product version, not a placeholder: requests and results follow one strict JSON envelope, redirects
 * never followed, and only documented terminal states become SUCCEEDED/FAILED.
 */
@Component
public class StandardHttpsDriver implements IntegrationDriver {
    public static final String KEY = "STANDARD_HTTPS_GATEWAY";
    private static final Set<String> RESULT_KEYS = Set.of("code", "state", "message", "reference", "result");
    private static final Set<String> STATES = Set.of("ACCEPTED", "REJECTED", "SUCCEEDED", "FAILED", "UNKNOWN");

    private final IntegrationDriverConfigService config;
    private final ObjectMapper json;

    public StandardHttpsDriver(IntegrationDriverConfigService config, ObjectMapper json) {
        this.config = config;
        this.json = json;
    }

    @Override public String key() { return KEY; }
    @Override public String mode() { return "PRODUCT_MODEL"; }
    @Override public String displayName() { return "标准HTTPS产品网关"; }

    @Override
    public DriverConfiguration configuration() {
        IntegrationDriverConfigService.Config current = config.load(KEY);
        return new DriverConfiguration(current.configured(), current.enabled(), current.code(), current.endpoint(),
            current.tokenMasked(), current.accountFingerprint(), current.revision());
    }

    @Override
    public String accountFingerprint() { return config.load(KEY).accountFingerprint(); }

    /** Operation descriptors are owned by each published product version, never fabricated here. */
    @Override public List<DriverServiceDescriptor> services() { return List.of(); }

    @Override
    public DriverOutcome submit(DriverCall call) {
        if (!handlesPublication(call))
            return DriverOutcome.blocked("OPERATION_NOT_DECLARED", "当前产品版本未声明该操作，未发起外部请求");
        return exchange(call, "SUBMIT");
    }

    @Override
    public DriverOutcome query(DriverCall call) {
        if (!handlesPublication(call))
            return DriverOutcome.blocked("OPERATION_NOT_DECLARED", "当前产品版本未声明该操作，未发起外部请求");
        if (call.reference() == null || call.reference().isBlank())
            return DriverOutcome.unknown("REFERENCE_MISSING", null, "没有可核对的网关引用，不能推断执行结果");
        return exchange(call, "QUERY");
    }

    private boolean handlesPublication(DriverCall call) {
        return call.service() != null && call.productVersionId() != null;
    }

    private DriverOutcome exchange(DriverCall call, String phase) {
        IntegrationDriverConfigService.Config current = config.load(KEY);
        if (!current.configured() || current.token() == null)
            return DriverOutcome.blocked(current.code(), "标准产品网关未启用或令牌不可用，未发起外部请求");
        if (call.accountFingerprint() == null || !Objects.equals(call.accountFingerprint(), current.accountFingerprint()))
            return DriverOutcome.blocked("ACCOUNT_CHANGED", "网关账号已轮换或无法核对，未发起外部请求");
        ObjectNode body = json.createObjectNode();
        body.put("request_id", call.commandId());
        body.put("operation", call.service().key());
        body.put("phase", phase);
        ObjectNode device = body.putObject("device");
        device.put("id", call.deviceId());
        if (call.deviceEui() != null) device.put("eui", call.deviceEui());
        if (call.model() != null) device.put("model", call.model());
        if (call.productVersionId() != null) device.put("product_version_id", call.productVersionId());
        body.set("arguments", call.arguments() == null ? json.createObjectNode() : call.arguments());
        if ("QUERY".equals(phase)) body.put("reference", call.reference());
        URI uri;
        try {
            uri = URI.create(config.approvedEndpoint(current.endpoint()));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443))
                return DriverOutcome.blocked("ENDPOINT_UNSAFE", "网关地址不符合已审批的HTTPS约束，未发起外部请求");
        } catch (RuntimeException error) {
            return DriverOutcome.blocked("ENDPOINT_UNSAFE", "网关地址无效或不在当前部署白名单中，未发起外部请求");
        }
        byte[] requestBody;
        try {
            requestBody = json.writeValueAsBytes(body);
        } catch (IOException error) {
            return DriverOutcome.blocked("REQUEST_NOT_SENT", "网关请求无法构建，未发起外部请求");
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        try {
            var response = PinnedHttpTransport.exchange(new PinnedHttpTransport.Target("https", uri.getHost(),
                    uri.getPort() < 0 ? 443 : uri.getPort(), false),
                "POST", path,
                Map.of("Accept", "application/json", "Content-Type", "application/json",
                    "Authorization", "Bearer " + current.token(), "X-Request-Id", call.commandId()),
                requestBody);
            if (response.status() < 200 || response.status() >= 300)
                return DriverOutcome.unknown("HTTP_" + response.status(), null,
                    "网关HTTP状态未确认业务结果；禁止自动重发");
            JsonNode parsed;
            try { parsed = json.readTree(response.body()); }
            catch (IOException invalid) { return DriverOutcome.unknown("INVALID_RESPONSE", null, "网关响应不是合法JSON，业务结果未确认"); }
            return project(parsed);
        } catch (PinnedHttpTransport.Failure failure) {
            return DriverOutcome.unknown(failure.code(), null, "网关网络结果未确认；禁止自动重发");
        }
    }

    private DriverOutcome project(JsonNode parsed) {
        if (parsed == null || !parsed.isObject()) return DriverOutcome.unknown("INVALID_RESPONSE", null, "网关响应结构未确认");
        var names = parsed.fieldNames();
        while (names.hasNext()) {
            if (!RESULT_KEYS.contains(names.next())) return DriverOutcome.unknown("INVALID_RESPONSE", null, "网关响应含未声明字段，拒绝解释");
        }
        JsonNode codeNode = parsed.get("code"), stateNode = parsed.get("state");
        if (codeNode == null || !codeNode.isTextual()) return DriverOutcome.unknown("INVALID_RESPONSE", null, "网关响应缺少code");
        String code = codeNode.asText();
        JsonNode result = parsed.get("result");
        if (result != null && result.isMissingNode()) result = null;
        String reference = parsed.path("reference").isTextual() ? parsed.path("reference").asText() : null;
        String message = parsed.path("message").asText("");
        if (stateNode == null) {
            return "0".equals(code)
                ? DriverOutcome.unknown("STATE_MISSING", result, "网关受理但未给出状态，不能推断执行结果")
                : DriverOutcome.rejected(code, result, "网关拒绝：" + message);
        }
        if (!stateNode.isTextual() || !STATES.contains(stateNode.asText()))
            return DriverOutcome.unknown("INVALID_STATE", result, "网关状态值未声明，不能推断");
        if (!"0".equals(code) && !"UNKNOWN".equals(stateNode.asText()) && !"REJECTED".equals(stateNode.asText()))
            return DriverOutcome.rejected(code, result, "网关返回非成功码：" + message);
        return switch (stateNode.asText()) {
            case "ACCEPTED" -> DriverOutcome.accepted(code, reference, result, message.isBlank() ? "网关接受；执行结果未确认" : message);
            case "REJECTED" -> DriverOutcome.rejected(code, result, message.isBlank() ? "网关拒绝" : message);
            case "SUCCEEDED" -> DriverOutcome.succeeded(code, result, message.isBlank() ? "网关报告明确成功" : message);
            case "FAILED" -> DriverOutcome.failed(code, result, message.isBlank() ? "网关报告明确失败" : message);
            default -> DriverOutcome.unknown(code, result, message.isBlank() ? "网关结果未确认" : message);
        };
    }
}
