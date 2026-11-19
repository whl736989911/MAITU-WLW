package com.fireplatform.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 阿里云短信/语音 RPC 客户端；未配置 AK 时不启用。 */
@Component
public class AliyunNotificationClient {
    public record Result(Outcome outcome, String providerId, String code, String message) {
        public enum Outcome { ACCEPTED, REJECTED, UNKNOWN }
        public boolean success() { return outcome == Outcome.ACCEPTED; }
        public boolean unknown() { return outcome == Outcome.UNKNOWN; }
    }
    public record SmsDetail(String bizId,String outId,String phoneMasked,int sendStatus,String errCode,String templateCode,String sendAt,String receiveAt) { }
    public record SmsQuery(boolean success,String code,String message,String requestId,int totalCount,List<SmsDetail> details) { }
    public record VoiceDetail(String callId,String state,String stateDesc,String startAt,String endAt,String calleeMasked,String callerMasked,String gmtCreate,Integer duration) { }
    public record VoiceQuery(boolean success,String code,String message,String requestId,VoiceDetail detail) { }
    public record Config(String accessKeyId,String accessKeySecret,String smsSignName,String smsTemplateCode,String ttsCode,String ttsCalledShowNumber,String smsEndpoint,String voiceEndpoint) { }
    /** Official ProdId for voice notification calls (QueryCallDetailByCallId). */
    public static final String VOICE_NOTIFICATION_PROD_ID = "11000000300006";
    private static final DateTimeFormatter PROVIDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Logger log = LoggerFactory.getLogger(AliyunNotificationClient.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private volatile String accessKeyId, accessKeySecret, smsSignName, smsTemplateCode, ttsCode, ttsCalledShowNumber;
    private volatile String smsEndpoint, voiceEndpoint;
    private final HttpClient http;
    private final Duration requestTimeout;
    private volatile boolean enabled;

    @org.springframework.beans.factory.annotation.Autowired
    public AliyunNotificationClient(
            @Value("${fire.notification.aliyun.access-key-id:}") String accessKeyId,
            @Value("${fire.notification.aliyun.access-key-secret:}") String accessKeySecret,
            @Value("${fire.notification.aliyun.sms-sign-name:}") String smsSignName,
            @Value("${fire.notification.aliyun.sms-template-code:}") String smsTemplateCode,
            @Value("${fire.notification.aliyun.tts-code:}") String ttsCode,
            @Value("${fire.notification.aliyun.tts-called-show-number:}") String ttsCalledShowNumber,
            @Value("${fire.notification.aliyun.sms-endpoint:https://dysmsapi.aliyuncs.com/}") String smsEndpoint,
            @Value("${fire.notification.aliyun.voice-endpoint:https://dyvmsapi.aliyuncs.com/}") String voiceEndpoint,
            @Value("${fire.notification.aliyun.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${fire.notification.aliyun.request-timeout-ms:8000}") long requestTimeoutMs) {
        this(accessKeyId, accessKeySecret, smsSignName, smsTemplateCode, ttsCode, ttsCalledShowNumber,
                smsEndpoint, voiceEndpoint, HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build(),
                Duration.ofMillis(requestTimeoutMs));
    }

    public AliyunNotificationClient(String accessKeyId, String accessKeySecret, String smsSignName,
            String smsTemplateCode, String ttsCode, String ttsCalledShowNumber, String smsEndpoint, String voiceEndpoint) {
        this(accessKeyId, accessKeySecret, smsSignName, smsTemplateCode, ttsCode, ttsCalledShowNumber,
            smsEndpoint, voiceEndpoint, 5_000, 8_000);
    }

    AliyunNotificationClient(String accessKeyId, String accessKeySecret, String smsSignName,
            String smsTemplateCode, String ttsCode, String ttsCalledShowNumber,
            String smsEndpoint, String voiceEndpoint, HttpClient http) {
        this(accessKeyId, accessKeySecret, smsSignName, smsTemplateCode, ttsCode, ttsCalledShowNumber,
            smsEndpoint, voiceEndpoint, http, Duration.ofSeconds(8));
    }

    AliyunNotificationClient(String accessKeyId, String accessKeySecret, String smsSignName,
            String smsTemplateCode, String ttsCode, String ttsCalledShowNumber,
            String smsEndpoint, String voiceEndpoint, HttpClient http, Duration requestTimeout) {
        if (requestTimeout.isZero() || requestTimeout.isNegative()) throw new IllegalArgumentException("Aliyun request timeout must be positive");
        this.requestTimeout = requestTimeout;
        this.http = http;
        apply(new Config(accessKeyId,accessKeySecret,smsSignName,smsTemplateCode,ttsCode,ttsCalledShowNumber,smsEndpoint,voiceEndpoint));
    }

    public synchronized void apply(Config config) {
        this.accessKeyId = trim(config.accessKeyId()); this.accessKeySecret = trim(config.accessKeySecret());
        this.smsSignName = trim(config.smsSignName()); this.smsTemplateCode = trim(config.smsTemplateCode());
        this.ttsCode = trim(config.ttsCode()); this.ttsCalledShowNumber = trim(config.ttsCalledShowNumber());
        this.smsEndpoint = config.smsEndpoint() == null || config.smsEndpoint().isBlank() ? "https://dysmsapi.aliyuncs.com/" : config.smsEndpoint();
        this.voiceEndpoint = config.voiceEndpoint() == null || config.voiceEndpoint().isBlank() ? "https://dyvmsapi.aliyuncs.com/" : config.voiceEndpoint();
        this.enabled = !this.accessKeyId.isBlank() && !this.accessKeySecret.isBlank()
                && !this.smsSignName.isBlank() && !this.smsTemplateCode.isBlank();
    }
    public Config current() { return new Config(accessKeyId,accessKeySecret,smsSignName,smsTemplateCode,ttsCode,ttsCalledShowNumber,smsEndpoint,voiceEndpoint); }
    public boolean enabled() { return enabled; }
    public boolean voiceReady() { return queryReady() && !ttsCode.isBlank(); }

    public Result sendSms(String phone, Map<String, String> templateParams, String outId) {
        if (!enabled) return new Result(Result.Outcome.REJECTED, null, "NOT_CONFIGURED", "Aliyun SMS is not configured");
        Map<String, String> params = base("SendSms", "2017-05-25");
        params.put("PhoneNumbers", phone);
        params.put("SignName", smsSignName);
        params.put("TemplateCode", smsTemplateCode);
        params.put("TemplateParam", json(templateParams));
        if (outId != null && !outId.isBlank()) params.put("OutId", outId);
        return call(smsEndpoint, params);
    }

    public Result sendVoice(String phone, Map<String, String> templateParams, String outId) {
        if (!voiceReady()) return new Result(Result.Outcome.REJECTED, null, "NOT_CONFIGURED", "Aliyun voice is not configured");
        Map<String, String> params = base("SingleCallByTts", "2017-05-25");
        params.put("CalledNumber", phone);
        params.put("TtsCode", ttsCode);
        params.put("TtsParam", json(templateParams));
        if (!ttsCalledShowNumber.isBlank()) params.put("CalledShowNumber", ttsCalledShowNumber);
        // Official voice contract limits OutId to 1..15 characters; omit an unusable identifier instead of truncating it.
        if (outId != null && outId.length() >= 1 && outId.length() <= 15) params.put("OutId", outId);
        return call(voiceEndpoint, params);
    }

    /** Provider account identity for historical receipt binding. Never the secret. */
    public String accountFingerprint() {
        String id = trim(accessKeyId);
        if (id.isBlank()) return null;
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { return null; }
    }

    /** Enables receipt queries without requiring a send template to be configured. */
    public boolean queryReady() { return !trim(accessKeyId).isBlank() && !trim(accessKeySecret).isBlank(); }

    /** QuerySendDetails: one destination phone and optionally one BizId, records from the latest 30 days. */
    public SmsQuery querySms(String phone, String bizId, LocalDate sendDate, int pageSize, int currentPage) {
        if (!queryReady()) return new SmsQuery(false, "NOT_CONFIGURED", "Aliyun SMS is not configured", null, 0, List.of());
        int size = Math.max(1, Math.min(50, pageSize));
        int page = Math.max(1, currentPage);
        Map<String, String> params = base("QuerySendDetails", "2017-05-25");
        params.put("PhoneNumber", phone);
        if (bizId != null && !bizId.isBlank()) params.put("BizId", bizId);
        params.put("SendDate", PROVIDER_DATE.format(sendDate));
        params.put("PageSize", Integer.toString(size));
        params.put("CurrentPage", Integer.toString(page));
        try {
            Raw raw = execute(smsEndpoint, params);
            JsonNode body = raw.body();
            String code = body == null ? "" : body.path("Code").asText("");
            String message = body == null ? "Empty response" : body.path("Message").asText("");
            String requestId = body == null ? null : textOrNull(body, "RequestId");
            boolean success = raw.status() == 200 && "OK".equalsIgnoreCase(code);
            int total = body == null ? 0 : integer(body.path("TotalCount").asText("0"));
            List<SmsDetail> details = new ArrayList<>();
            if (success && body != null) {
                JsonNode node = body.path("SmsSendDetailDTOs").path("SmsSendDetailDTO");
                if (node.isObject()) details.add(smsDetail(node));
                else if (node.isArray()) node.forEach(item -> { if (item.isObject()) details.add(smsDetail(item)); });
            }
            return new SmsQuery(success, code, message, requestId, total, List.copyOf(details));
        } catch (Exception error) {
            log.warn("Aliyun SMS receipt query failed: {}", error.getClass().getSimpleName());
            return new SmsQuery(false, "PROVIDER_UNAVAILABLE", error.getClass().getSimpleName(), null, 0, List.of());
        }
    }

    /** QueryCallDetailByCallId: QueryDate must be the original call's provider-local date; details support the latest six months. */
    public VoiceQuery queryVoice(String callId, LocalDate sendDate) {
        if (!queryReady()) return new VoiceQuery(false, "NOT_CONFIGURED", "Aliyun voice is not configured", null, null);
        Map<String, String> params = base("QueryCallDetailByCallId", "2017-05-25");
        params.put("CallId", callId);
        params.put("ProdId", VOICE_NOTIFICATION_PROD_ID);
        params.put("QueryDate", Long.toString(sendDate.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()));
        try {
            Raw raw = execute(voiceEndpoint, params);
            JsonNode body = raw.body();
            String code = body == null ? "" : body.path("Code").asText("");
            String message = body == null ? "Empty response" : body.path("Message").asText("");
            String requestId = body == null ? null : textOrNull(body, "RequestId");
            boolean success = raw.status() == 200 && "OK".equalsIgnoreCase(code);
            VoiceDetail detail = null;
            if (success && body != null) {
                JsonNode data = body.path("Data");
                if (data.isTextual() && !data.asText().isBlank()) data = MAPPER.readTree(data.asText());
                if (data.isObject() && data.size() > 0) detail = voiceDetail(data);
            }
            return new VoiceQuery(success, code, message, requestId, detail);
        } catch (Exception error) {
            log.warn("Aliyun voice receipt query failed: {}", error.getClass().getSimpleName());
            return new VoiceQuery(false, "PROVIDER_UNAVAILABLE", error.getClass().getSimpleName(), null, null);
        }
    }

    private static SmsDetail smsDetail(JsonNode node) {
        return new SmsDetail(textOrNull(node, "BizId"), textOrNull(node, "OutId"), textOrNull(node, "PhoneNum"),
                integer(node.path("SendStatus").asText("")), textOrNull(node, "ErrCode"), textOrNull(node, "TemplateCode"),
                textOrNull(node, "SendDate"), textOrNull(node, "ReceiveDate"));
    }

    private static VoiceDetail voiceDetail(JsonNode node) {
        Integer duration = node.path("duration").asText("").isBlank() ? null : integer(node.path("duration").asText(""));
        String callId = textOrNull(node, "call_id") != null ? textOrNull(node, "call_id") : textOrNull(node, "callId");
        return new VoiceDetail(callId, textOrNull(node, "state"), textOrNull(node, "stateDesc"),
                textOrNull(node, "startDate"), textOrNull(node, "endDate"), textOrNull(node, "callee"),
                firstNonNull(textOrNull(node, "caller"), textOrNull(node, "callerShowNumber")), textOrNull(node, "gmtCreate"), duration);
    }

    private static String firstNonNull(String first, String second) { return first == null ? second : first; }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asText("");
        return text.isBlank() ? null : text;
    }

    private static int integer(String value) {
        try { return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim()); }
        catch (NumberFormatException error) { return 0; }
    }

    private Map<String, String> base(String action, String version) {
        Map<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", accessKeyId);
        params.put("Action", action);
        params.put("Format", "JSON");
        params.put("RegionId", "cn-hangzhou");
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", "1.0");
        params.put("Timestamp", TIMESTAMP.format(Instant.now()));
        params.put("Version", version);
        return params;
    }

    private record Raw(int status, JsonNode body) { }

    private Result call(String endpoint, Map<String, String> params) {
        try {
            Raw raw = execute(endpoint, params);
            JsonNode body = raw.body();
            String code = body == null ? "" : body.path("Code").asText("");
            String providerId = body == null ? null : body.hasNonNull("BizId") ? textOrNull(body, "BizId") : textOrNull(body, "CallId");
            String message = body == null ? "Empty response" : body.path("Message").asText("");
            if (raw.status() >= 500 || body == null || !body.isObject() || !body.path("Code").isTextual() || code.isBlank()
                    || "OK".equalsIgnoreCase(code) && raw.status() != 200) {
                log.warn("Aliyun notification acceptance unknown status={}", raw.status());
                return new Result(Result.Outcome.UNKNOWN, providerId, "PROVIDER_RESULT_UNKNOWN", "厂商受理结果未知，请勿自动重发");
            }
            var outcome = "OK".equalsIgnoreCase(code) ? Result.Outcome.ACCEPTED : Result.Outcome.REJECTED;
            if (outcome == Result.Outcome.REJECTED) log.warn("Aliyun notification rejected status={} code={}", raw.status(), code);
            return new Result(outcome, providerId, code, message);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Aliyun notification acceptance unknown: {}", error.getClass().getSimpleName());
            return new Result(Result.Outcome.UNKNOWN, null, "PROVIDER_RESULT_UNKNOWN", "厂商受理结果未知，请勿自动重发");
        }
    }

    private Raw execute(String endpoint, Map<String, String> params) throws Exception {
        String canonical = canonical(params);
        String stringToSign = "GET&%2F&" + percent(canonical);
        params.put("Signature", hmac(accessKeySecret + "&", stringToSign));
        String query = params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> percent(e.getKey()) + "=" + percent(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(endpoint + "?" + query)).GET().timeout(requestTimeout).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body() == null || response.body().isBlank() ? null : MAPPER.readTree(response.body());
        return new Raw(response.statusCode(), body);
    }

    static String canonical(Map<String, String> params) {
        return params.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> percent(e.getKey()) + "=" + percent(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
    }

    static String percent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    static String hmac(String key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String json(Map<String, String> params) {
        try { return MAPPER.writeValueAsString(params); }
        catch (Exception e) { return "{}"; }
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
