package com.fireplatform.hikvision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fixed vendor origin, verified TLS, read-only API allowlist, no SDK trust-all client. */
@Component
public class HikvisionClient {
    public enum Stream {
        UNIT("/api/firepro/v1/getUnit"), DEVICE("/api/device/v2/getDevice"),
        SENSOR("/api/channel/v2/getSensor"), STATUS("/api/device/v2/getFireDeviceStatus"),
        FAULT("/api/businessData/v2/getFault"), ALARM("/api/businessData/v2/getAlarm"),
        MONITOR("/api/businessData/v2/getMonitor"), HISTORY_MONITOR("/api/businessData/v2/getHistoryMonitor"),
        DEVICE_EVENT("/api/businessData/v2/getTraditionMsg");
        private final String path;
        Stream(String path) { this.path = path; }
    }
    public static final class Failure extends RuntimeException {
        private final String code;
        Failure(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
    public record VendorPage(JsonNode records, long total, String flagId) { }
    /** Vendor envelope for documented POST operations. A non-zero code is returned, never silently treated as success. */
    public record VendorResult(String code, String msg, JsonNode data) { }
    private static final Set<String> WRITE_PATHS = Set.of(
        "/api/device/v2/add", "/api/device/v2/delete", "/api/device/v2/operate",
        "/api/device/v3/getDevice", "/api/device/v3/deviceConfig", "/api/device/v3/sinkDeviceConfig",
        "/api/device/v3/deviceCommandResult", "/api/businessData/v2/getOperateState",
        "/api/subscription/v2/add", "/api/subscription/v2/update", "/api/subscription/v2/delete",
        "/api/bws/device/v1/device/operate");
    private static final int RESPONSE_LIMIT = 4 * 1024 * 1024;
    private record ClientState(HttpClient http, HikvisionSigner signer) { }
    private final ObjectMapper json;
    private final Clock clock;
    private final ObjectReader sourceReader;
    private volatile ClientState state;

    public HikvisionClient(ObjectMapper json, Clock clock,
            @Value("${fire.hikvision.app-key:${HIKVISION_APP_KEY:}}") String appKey,
            @Value("${fire.hikvision.app-secret:${HIKVISION_APP_SECRET:}}") String appSecret) {
        this.json = json;
        this.clock = clock;
        this.sourceReader = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        configure("https://open.hikfirecloud.com", appKey, appSecret);
    }
    public synchronized void configure(String endpoint, String appKey, String appSecret) {
        HikvisionSigner signer = appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()
            ? null : new HikvisionSigner(endpoint, appKey, appSecret);
        this.state = new ClientState(signer == null ? null : HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(), signer);
    }
    public Session session() { return new Session(state); }
    public boolean configured() { return state.signer() != null; }
    public final class Session {
        private final ClientState snapshot;
        private Session(ClientState snapshot) { this.snapshot=snapshot; }
        public boolean configured() { return snapshot.signer() != null; }
        public VendorPage read(Stream stream,int pageNo,String flagId,Map<String,String> filters) {
            return HikvisionClient.this.read(snapshot,stream,pageNo,flagId,filters);
        }
        public JsonNode readSubscriptions() { return HikvisionClient.this.readSubscriptions(snapshot); }
        public VendorResult write(String apiPath, JsonNode body) { return HikvisionClient.this.write(snapshot, apiPath, body); }
    }
    public VendorPage read(Stream stream,int pageNo,String flagId,Map<String,String> filters) {
        return read(state,stream,pageNo,flagId,filters);
    }
    public VendorResult write(String apiPath, JsonNode body) { return write(state, apiPath, body); }
    private VendorResult write(ClientState current, String apiPath, JsonNode body) {
        if (current.signer() == null) throw new Failure("NOT_CONFIGURED");
        if (!WRITE_PATHS.contains(apiPath)) throw new Failure("WRITE_PATH_NOT_ALLOWED");
        if (body == null || !body.isObject()) throw new Failure("INVALID_REQUEST_BODY");
        try {
            byte[] bytes = json.writeValueAsBytes(body);
            var request = current.signer().post(apiPath, bytes, clock.instant(), UUID.randomUUID().toString());
            var response = current.http().send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                if (response.statusCode() != 200) throw new Failure("HTTP_" + response.statusCode());
                byte[] payload = input.readNBytes(RESPONSE_LIMIT + 1);
                if (payload.length > RESPONSE_LIMIT) throw new Failure("RESPONSE_TOO_LARGE");
                JsonNode result = sourceReader.readTree(payload);
                if (result == null || !result.isObject() || !result.path("code").isTextual()) throw new Failure("INVALID_RESPONSE");
                return new VendorResult(result.path("code").asText(), result.path("msg").asText(""), result.path("data"));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new Failure("INTERRUPTED");
        } catch (IOException error) {
            throw new Failure("NETWORK_OR_RESPONSE_ERROR");
        }
    }
    private JsonNode readSubscriptions(ClientState current) {
        if (current.signer() == null) throw new Failure("NOT_CONFIGURED");
        try {
            byte[] body = json.writeValueAsBytes(Map.of("msgType", ""));
            var request = current.signer().post("/api/subscription/v2/list", body, clock.instant(), UUID.randomUUID().toString());
            var response = current.http().send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input=response.body()) {
                if (response.statusCode()!=200) throw new Failure("HTTP_"+response.statusCode());
                byte[] payload=input.readNBytes(RESPONSE_LIMIT+1);
                if(payload.length>RESPONSE_LIMIT)throw new Failure("RESPONSE_TOO_LARGE");
                JsonNode result=sourceReader.readTree(payload);
                String code=result==null?"INVALID_RESPONSE":result.path("code").asText();
                if(!"0".equals(code))throw new Failure(code.matches("0x[0-9A-Fa-f]{8}|[1-9][0-9]{0,5}")?code:"UPSTREAM_ERROR");
                JsonNode data=result.path("data");
                if(!data.isArray())throw new Failure("INVALID_SUBSCRIPTION_LIST");
                return data;
            }
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new Failure("INTERRUPTED"); }
        catch(IOException e) { throw new Failure("NETWORK_OR_RESPONSE_ERROR"); }
    }
    private VendorPage read(ClientState current,Stream stream,int pageNo,String flagId,Map<String,String> filters) {
        if (current.signer() == null) throw new Failure("NOT_CONFIGURED");
        var body = json.createObjectNode();
        filters.forEach((key,value)->{if("timeType".equals(key))body.put(key,Integer.parseInt(value));else body.put(key,value);});
        body.put("pageNo", pageNo).put("pageSize", 100).put("flagId", flagId == null ? "" : flagId)
            .put("offset", pageNo == 1 ? 0 : 1);
        try {
            byte[] bytes = json.writeValueAsBytes(body);
            var request = current.signer().post(stream.path, bytes, clock.instant(), UUID.randomUUID().toString());
            var response = current.http().send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                if (response.statusCode() != 200) throw new Failure("HTTP_" + response.statusCode());
                byte[] payload = input.readNBytes(RESPONSE_LIMIT + 1);
                if (payload.length > RESPONSE_LIMIT) throw new Failure("RESPONSE_TOO_LARGE");
                JsonNode result = sourceReader.readTree(payload);
                String code = result == null ? "INVALID_RESPONSE" : result.path("code").asText();
                if (!"0".equals(code)) throw new Failure(code.matches("0x[0-9A-Fa-f]{8}|[1-9][0-9]{0,5}") ? code : "UPSTREAM_ERROR");
                JsonNode data = result.path("data"), records = data.path("list");
                if (!records.isArray() || !data.path("total").canConvertToLong() || data.path("total").asLong() < 0)
                    throw new Failure("INVALID_PAGE");
                return new VendorPage(records, data.path("total").asLong(), data.path("flagId").asText(""));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("INTERRUPTED");
        } catch (IOException e) {
            throw new Failure("NETWORK_OR_RESPONSE_ERROR");
        }
    }
}
