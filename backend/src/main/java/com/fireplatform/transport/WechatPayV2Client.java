package com.fireplatform.transport;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;

/**
 * WeChat Pay APIv2 Native transport (unified order, order query, close order, transaction bill).
 *
 * <p>The client is stateless with respect to merchant configuration: every official call takes an
 * explicit {@link Credentials} snapshot so a page-managed configuration, a rotated APIv2 key and a
 * legacy environment configuration can coexist for historical orders. Nothing here decides business
 * state; callers must verify signature, merchant identity, merchant order number and amount before
 * treating any response as payment evidence.</p>
 *
 * <p>No refund/certificate APIs are implemented. Secrets are never written to logs.</p>
 */
@Component
public class WechatPayV2Client {
    private static final Logger log = LoggerFactory.getLogger(WechatPayV2Client.class);
    private static final DateTimeFormatter BEIJING_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_XML_BYTES = 64 * 1024;
    private static final int MAX_BILL_BYTES = 8 * 1024 * 1024;

    /** A merchant/appid/APIv2-key snapshot. The notify URL is only used when creating a new order. */
    public record Credentials(String mchId, String appId, String apiV2Key, String notifyUrl) {
        public boolean credentialsComplete() {
            return mchId != null && !mchId.isBlank() && appId != null && !appId.isBlank()
                && apiV2Key != null && apiV2Key.length() == 32;
        }
        public boolean orderReady() {
            return credentialsComplete() && notifyUrl != null && notifyUrl.startsWith("https://");
        }
    }

    /** ACCEPTED means the provider accepted the submission; UNKNOWN must never be re-issued blindly. */
    public record UnifiedOrder(String state, String codeUrl, String prepayId, String code, String message) {
        public boolean accepted() { return "ACCEPTED".equals(state); }
    }
    /** PAID requires a verified signature, matching merchant identity/order number and a positive amount. */
    public record Query(String state, String tradeState, String transactionId, Long totalFee, String code, String message) {
        public boolean paid() { return "PAID".equals(state); }
    }
    /** CLOSED/ALREADY_CLOSED are the only merchant-verified terminal close results. */
    public record Close(String state, String code, String message) { }
    public record Bill(boolean success, String content, String code, String message) { }

    private final String baseUrl;
    private final String envMchId, envAppId, envApiV2Key, envNotifyUrl;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public WechatPayV2Client(Environment env) {
        this(env.getProperty("app.wechat-pay-base-url", env.getProperty("APP_WECHAT_PAY_BASE_URL", "https://api.mch.weixin.qq.com")),
            env.getProperty("WECHAT_PAY_MCH_ID", ""), env.getProperty("WECHAT_PAY_APP_ID", ""),
            env.getProperty("WECHAT_PAY_API_V2_KEY", ""),
            env.getProperty("WECHAT_PAY_NOTIFY_URL", ""),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    /** Wire-test constructor: explicit base URL and HTTP client, no environment credentials. */
    public WechatPayV2Client(String baseUrl, HttpClient http, Clock clock) {
        this(baseUrl, "", "", "", "", http);
    }

    private WechatPayV2Client(String baseUrl, String mchId, String appId, String apiV2Key, String notifyUrl, HttpClient http) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (!normalized.startsWith("https://") && !normalized.startsWith("http://"))
            throw new IllegalStateException("WeChat Pay base URL must be an absolute http(s) URL");
        this.baseUrl = normalized;
        this.envMchId = mchId == null ? "" : mchId.trim();
        this.envAppId = appId == null ? "" : appId.trim();
        this.envApiV2Key = apiV2Key == null ? "" : apiV2Key.trim();
        this.envNotifyUrl = notifyUrl == null ? "" : notifyUrl.trim();
        this.http = http == null ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build() : http;
        log.info("wechat native payment env-configured={} merchant-masked={}", envCredentials() != null, mask(envMchId));
    }

    /** Legacy environment configuration; only consulted when no page-managed configuration exists. */
    public Credentials envCredentials() {
        if (envMchId.isBlank() || envAppId.isBlank() || envApiV2Key.length() != 32) return null;
        return new Credentials(envMchId, envAppId, envApiV2Key, envNotifyUrl);
    }
    public String envNotifyUrl() { return envNotifyUrl; }

    /** Format the official {@code time_expire} value in Beijing time. */
    public static String timeExpire(OffsetDateTime expiresAt) {
        return expiresAt == null ? null : BEIJING_TIME.format(expiresAt.atZoneSameInstant(ZoneId.of("Asia/Shanghai")));
    }

    public UnifiedOrder unifiedOrder(Credentials credentials, String outTradeNo, long amountCents, String description,
                                     String clientIp, String timeExpire) {
        if (credentials == null || !credentials.orderReady())
            return new UnifiedOrder("REJECTED", null, null, "NOT_CONFIGURED", "微信支付未配置");
        var params = baseParams(credentials);
        params.put("body", description);
        params.put("out_trade_no", outTradeNo);
        params.put("total_fee", String.valueOf(amountCents));
        params.put("spbill_create_ip", clientIp == null || clientIp.isBlank() ? "127.0.0.1" : clientIp);
        params.put("notify_url", credentials.notifyUrl());
        params.put("trade_type", "NATIVE");
        if (timeExpire != null && !timeExpire.isBlank()) params.put("time_expire", timeExpire);
        var response = post("/pay/unifiedorder", params, credentials);
        if (response.failure() != null) return new UnifiedOrder("UNKNOWN", null, null, "TRANSPORT_ERROR", response.failure());
        var body = response.params();
        String returnCode = body.get("return_code");
        if (!"SUCCESS".equals(returnCode))
            return new UnifiedOrder("UNKNOWN", null, null, value(body, "err_code", value(body, "return_msg", "COMMUNICATION_FAILED")),
                value(body, "err_code_des", value(body, "return_msg", "微信支付请求未获得成功应答")));
        if (!signatureValid(body, credentials.apiV2Key()) || !merchantMatches(body, credentials))
            return new UnifiedOrder("UNKNOWN", null, null, "UNVERIFIED_RESPONSE", "微信支付响应签名或商户身份未通过校验");
        if (!"SUCCESS".equals(body.get("result_code"))) {
            String code = value(body, "err_code", "UNIFIED_ORDER_FAILED");
            String message = value(body, "err_code_des", value(body, "return_msg", "微信支付未受理该订单"));
            String state = "SYSTEMERROR".equals(code) || "ORDERPAID".equals(code) ? "UNKNOWN" : "REJECTED";
            return new UnifiedOrder(state, null, null, code, message);
        }
        String codeUrl = body.get("code_url"), prepayId = body.get("prepay_id");
        if (codeUrl == null || codeUrl.isBlank() || prepayId == null || prepayId.isBlank())
            return new UnifiedOrder("UNKNOWN", null, null, "INCOMPLETE_RESPONSE", "微信支付响应缺少二维码或预支付标识");
        return new UnifiedOrder("ACCEPTED", codeUrl, prepayId, "OK", "微信支付已受理下单");
    }

    public Query queryOrder(Credentials credentials, String outTradeNo) {
        if (credentials == null || !credentials.credentialsComplete())
            return new Query("UNKNOWN", null, null, null, "NOT_CONFIGURED", "商户凭据未配置");
        var params = baseParams(credentials);
        params.put("out_trade_no", outTradeNo);
        var response = post("/pay/orderquery", params, credentials);
        if (response.failure() != null) return new Query("UNKNOWN", null, null, null, "TRANSPORT_ERROR", response.failure());
        var body = response.params();
        if (!"SUCCESS".equals(body.get("return_code")))
            return new Query("UNKNOWN", null, null, null, value(body, "err_code", value(body, "return_msg", "COMMUNICATION_FAILED")),
                value(body, "err_code_des", value(body, "return_msg", "查单请求未获得成功应答")));
        if (!signatureValid(body, credentials.apiV2Key()) || !merchantMatches(body, credentials))
            return new Query("UNKNOWN", null, null, null, "UNVERIFIED_RESPONSE", "查单响应签名或商户身份未通过校验");
        Long fee = parseCents(body.get("total_fee"));
        String tradeState = body.get("trade_state");
        if (!"SUCCESS".equals(body.get("result_code")))
            return new Query("UNKNOWN", tradeState, body.get("transaction_id"), fee,
                value(body, "err_code", "QUERY_FAILED"), value(body, "err_code_des", "查单未返回业务成功"));
        if (!outTradeNo.equals(body.get("out_trade_no")))
            return new Query("UNKNOWN", tradeState, body.get("transaction_id"), fee, "ORDER_MISMATCH", "查单返回的商户订单号不一致");
        if ("SUCCESS".equals(tradeState)) {
            String transactionId = body.get("transaction_id");
            if (transactionId == null || transactionId.isBlank() || fee == null)
                return new Query("UNKNOWN", tradeState, transactionId, null, "INCOMPLETE_RESPONSE", "已支付查单缺少交易号或金额");
            return new Query("PAID", tradeState, transactionId, fee, "OK", "查单确认已支付（签名、商户、订单号与金额已校验）");
        }
        if ("NOTPAY".equals(tradeState) || "CLOSED".equals(tradeState) || "USERPAYING".equals(tradeState)
            || "PAYERROR".equals(tradeState) || "REFUND".equals(tradeState) || "REVOKED".equals(tradeState)
            || "ACCEPT".equals(tradeState))
            return new Query(tradeState, tradeState, body.get("transaction_id"), fee, "OK", value(body, "trade_state_desc", tradeState));
        return new Query("UNKNOWN", tradeState, body.get("transaction_id"), fee, "UNKNOWN_TRADE_STATE", "微信支付返回未识别的交易状态");
    }

    public Close closeOrder(Credentials credentials, String outTradeNo) {
        if (credentials == null || !credentials.credentialsComplete())
            return new Close("UNKNOWN", "NOT_CONFIGURED", "商户凭据未配置");
        var params = baseParams(credentials);
        params.put("out_trade_no", outTradeNo);
        var response = post("/pay/closeorder", params, credentials);
        if (response.failure() != null) return new Close("UNKNOWN", "TRANSPORT_ERROR", response.failure());
        var body = response.params();
        if (!"SUCCESS".equals(body.get("return_code")))
            return new Close("UNKNOWN", value(body, "err_code", value(body, "return_msg", "COMMUNICATION_FAILED")),
                value(body, "err_code_des", value(body, "return_msg", "关单请求未获得成功应答")));
        if (!signatureValid(body, credentials.apiV2Key()) || !merchantMatches(body, credentials))
            return new Close("UNKNOWN", "UNVERIFIED_RESPONSE", "关单响应签名或商户身份未通过校验");
        if ("SUCCESS".equals(body.get("result_code"))) return new Close("CLOSED", "OK", "商户已确认关闭订单");
        String code = value(body, "err_code", "CLOSE_FAILED");
        String message = value(body, "err_code_des", "关单未返回业务成功");
        return switch (code) {
            case "ORDERCLOSED" -> new Close("ALREADY_CLOSED", code, "订单在商户侧已关闭");
            case "ORDERPAID" -> new Close("PAID", code, "商户侧订单已支付，需要查单确认金额与交易号");
            case "SYSTEMERROR" -> new Close("UNKNOWN", code, "商户侧系统繁忙，关单结果未知");
            default -> new Close("REJECTED", code, message);
        };
    }

    /** Download the SUCCESS transaction bill. A plain-text body is the bill; an XML body is a signed error. */
    public Bill downloadBill(Credentials credentials, String billDate) {
        if (credentials == null || !credentials.credentialsComplete())
            return new Bill(false, null, "NOT_CONFIGURED", "商户凭据未配置");
        var params = baseParams(credentials);
        params.put("bill_date", billDate);
        params.put("bill_type", "SUCCESS");
        var response = postBytes("/pay/downloadbill", params, credentials, MAX_BILL_BYTES);
        if (response.failure() != null) return new Bill(false, null, "TRANSPORT_ERROR", response.failure());
        byte[] raw = response.bytes();
        if (raw == null || raw.length == 0) return new Bill(false, null, "EMPTY_RESPONSE", "对账单响应为空");
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.startsWith("<")) {
            try {
                var body = parseXml(raw);
                if (!signatureValid(body, credentials.apiV2Key()) || !merchantMatches(body, credentials))
                    return new Bill(false, null, "UNVERIFIED_RESPONSE", "对账单错误响应未通过签名或商户身份校验");
                return new Bill(false, null, value(body, "err_code", value(body, "return_code", "BILL_UNAVAILABLE")),
                    value(body, "err_code_des", value(body, "return_msg", "对账单不可用")));
            } catch (Exception invalid) {
                return new Bill(false, null, "INVALID_RESPONSE", "对账单错误响应无法解析");
            }
        }
        return new Bill(true, text, "OK", "对账单已下载");
    }

    /** True when {@code sign} validates under at least one retained APIv2 key. Only MD5 is accepted. */
    public boolean verifySignature(Map<String, String> params, List<String> apiV2Keys) {
        String signature = params == null ? null : params.get("sign");
        if (signature == null || signature.isBlank()) return false;
        String declared = params.get("sign_type");
        if (declared != null && !declared.isBlank() && !"MD5".equalsIgnoreCase(declared.trim())) return false;
        byte[] supplied = signature.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        if (apiV2Keys == null) return false;
        for (String key : apiV2Keys) {
            if (key == null || key.length() != 32) continue;
            byte[] expected = sign(params, key).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, supplied)) return true;
        }
        return false;
    }

    public boolean merchantMatches(Map<String, String> params, Credentials credentials) {
        return credentials != null && params != null
            && Objects.equals(credentials.mchId(), params.get("mch_id"))
            && Objects.equals(credentials.appId(), params.get("appid"));
    }

    /** Official APIv2 MD5 signing. Extension fields are included because all non-empty parameters are signed. */
    public static String sign(Map<String, String> params, String apiV2Key) {
        String canonical = params.entrySet().stream()
            .filter(e -> e.getValue() != null && !e.getValue().isEmpty() && !"sign".equals(e.getKey()))
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + e.getValue())
            .reduce((a, b) -> a + "&" + b).orElse("") + "&key=" + apiV2Key;
        try {
            var digest = MessageDigest.getInstance("MD5").digest(canonical.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format("%02X", value));
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public Map<String, String> parseXml(String xml) throws Exception {
        return parseXml(xml == null ? new byte[0] : xml.getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, String> parseXml(byte[] raw) throws Exception {
        if (raw == null || raw.length == 0 || raw.length > MAX_XML_BYTES) throw new IllegalArgumentException("xml size");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(raw));
        Element root = document.getDocumentElement();
        if (root == null || !"xml".equals(root.getNodeName())) throw new IllegalArgumentException("root");
        NodeList nodes = root.getChildNodes();
        var out = new LinkedHashMap<String, String>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) out.put(node.getNodeName(), node.getTextContent());
        }
        return out;
    }

    private Map<String, String> baseParams(Credentials credentials) {
        var params = new TreeMap<String, String>();
        params.put("appid", credentials.appId());
        params.put("mch_id", credentials.mchId());
        params.put("nonce_str", nonce());
        return params;
    }

    private record Response(Map<String, String> params, byte[] bytes, String failure) { }

    private Response post(String path, Map<String, String> params, Credentials credentials) {
        var raw = postBytes(path, params, credentials, MAX_XML_BYTES);
        if (raw.failure() != null) return raw;
        try {
            return new Response(parseXml(raw.bytes()), raw.bytes(), null);
        } catch (Exception invalid) {
            return new Response(Map.of(), null, "INVALID_RESPONSE");
        }
    }

    private Response postBytes(String path, Map<String, String> params, Credentials credentials, int limit) {
        try {
            params.put("sign", sign(params, credentials.apiV2Key()));
            String xml = toXml(params);
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "text/xml; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                return new Response(Map.of(), null, "HTTP_" + response.statusCode());
            byte[] body = response.body();
            if (body == null) return new Response(Map.of(), null, "EMPTY_RESPONSE");
            if (body.length > limit) return new Response(Map.of(), null, "RESPONSE_TOO_LARGE");
            return new Response(Map.of(), body, null);
        } catch (Exception failure) {
            return new Response(Map.of(), null, failure.getClass().getSimpleName());
        }
    }

    private boolean signatureValid(Map<String, String> params, String apiV2Key) {
        return apiV2Key != null && apiV2Key.length() == 32 && verifySignature(params, List.of(apiV2Key));
    }

    private static Long parseCents(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long cents = Long.parseLong(value.trim());
            return cents < 0 ? null : cents;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static String toXml(Map<String, String> params) {
        var builder = new StringBuilder("<xml>");
        for (var entry : params.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().replace("]]>", "]]]]><![CDATA[>");
            builder.append('<').append(entry.getKey()).append("><![CDATA[").append(value).append("]]></").append(entry.getKey()).append('>');
        }
        return builder.append("</xml>").toString();
    }

    private static String nonce() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String value(Map<String, String> map, String key, String fallback) {
        String value = map.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }
    private static String mask(String value) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }
}
