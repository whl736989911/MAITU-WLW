package com.fireplatform.legacyingest.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.legacyingest.LegacyNormalizedEvent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class FormTtlNormalizer implements LegacyIngressNormalizer {
    private final ObjectMapper mapper;

    public FormTtlNormalizer(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public Parsed parse(JsonNode body, String platform, String messageType) {
        JsonNode platformMsg = body.path("platformMsg");
        String id = firstText(body, "deviceId");
        if (id == null || id.isBlank()) id = firstText(platformMsg, "deviceId");
        if (id == null || id.isBlank()) id = firstText(body, "deviceName");
        List<LegacyNormalizedEvent.LegacyEvent> events = new ArrayList<>();
        String selectedKind = "OTHER";
        OffsetDateTime observed = null;
        BigDecimal battery = null;

        JsonNode array = body.path("parseMsg");
        if (array.isArray()) {
            for (JsonNode item : array) {
                String method = NormalizerSupport.text(item, "method");
                String kind = NormalizerSupport.eventKind(method);
                if (selectedKind.equals("OTHER") && !kind.equals("OTHER")) selectedKind = kind;
                Object value = paramsValue(item.path("params"));
                JsonNode first = NormalizerSupport.object(value instanceof JsonNode node ? node : null);
                OffsetDateTime time = firstTime(first, "time");
                if (time == null) time = firstTime(body, "ts", "timestamp");
                if (time == null) time = firstTime(platformMsg, "timestamp", "ts");
                if (observed == null) observed = time;
                if (battery == null) battery = NormalizerSupport.decimal(first, "battery");
                events.add(NormalizerSupport.event(mapper, kind, method, value, time));
            }
        } else if (body.has("params") || messageType != null) {
            // CAT1：params 可为双重 JSON 字符串；ts 和 platformMsg.timestamp 是源信封时间。
            Object value = paramsValue(body.path("params"));
            String bodyType = NormalizerSupport.text(body, "messageType");
            String method = methodFor(bodyType != null && !bodyType.isBlank() ? bodyType : messageType);
            JsonNode first = NormalizerSupport.object(value instanceof JsonNode node ? node : null);
            String stateCode = firstText(first, "deviceStateCode");
            if (stateCode == null) stateCode = NormalizerSupport.text(body, "deviceStateCode");
            if ("1001001".equals(stateCode)) method = "thing.event.alarms";
            selectedKind = NormalizerSupport.eventKind(method);
            if (body.hasNonNull("deviceStateCode") && !first.has("deviceStateCode")) {
                ObjectNode copy = first.deepCopy();
                copy.set("deviceStateCode", body.get("deviceStateCode"));
                value = copy;
                first = copy;
            }
            OffsetDateTime time = firstTime(body, "ts");
            if (time == null) time = firstTime(body, "timestamp");
            if (time == null) time = firstTime(platformMsg, "timestamp", "ts");
            if (time == null) time = firstTime(first, "time");
            observed = time;
            battery = NormalizerSupport.decimal(first, "battery");
            if (battery == null) battery = NormalizerSupport.decimal(first, "battery_value");
            events.add(NormalizerSupport.event(mapper, selectedKind, method, value, time));
        }
        return new Parsed(id, null, null, observed, battery, selectedKind, false, List.copyOf(events));
    }

    private Object paramsValue(JsonNode params) {
        JsonNode value = params;
        for (int depth = 0; depth < 2 && value.isTextual(); depth++) {
            try {
                value = mapper.readTree(value.textValue());
            } catch (Exception ignored) {
                return value.textValue();
            }
        }
        if (value.isArray()) return value.isEmpty() ? Map.of() : value.get(0);
        if (value.isObject()) return value;
        return Map.of();
    }

    private static OffsetDateTime firstTime(JsonNode node, String... keys) {
        for (String key : keys) {
            OffsetDateTime time = NormalizerSupport.time(NormalizerSupport.text(node, key));
            if (time != null) return time;
        }
        return null;
    }

    private static String methodFor(String messageType) {
        String value = messageType == null ? "" : messageType.toLowerCase(Locale.ROOT);
        if (value.contains("fault") || value.contains("故障")) return "thing.event.fault";
        if ((value.contains("recover") || value.contains("恢复")) && (value.contains("alarm") || value.contains("fire") || value.contains("火警") || value.contains("报警"))) return "thing.event.alarmRecovery";
        if (value.contains("alarm") || value.contains("火警") || value.contains("警报")) return "thing.event.alarm";
        if (value.contains("heartbeat") || value.contains("心跳")) return "thing.event.heartbeat";
        if (value.contains("param") || value.contains("参数")) return "thing.event.param";
        if (value.contains("offline") || value.contains("离线")) return "thing.event.offline";
        return "thing.event.other";
    }

    private static String firstText(JsonNode body, String... keys) {
        for (String key : keys) {
            String value = NormalizerSupport.text(body, key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
