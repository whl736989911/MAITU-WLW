package com.fireplatform.legacyingest.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.legacyingest.LegacyNormalizedEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LegacyDecodeService {
    public record DeviceBinding(String tenantId, String deviceId, String platform, String legacyProductId,
                                Map<String, ?> attrs) { }
    private final LegacyCodecRegistry registry;
    private final LegacyCodecRunner runner;
    private final ObjectMapper mapper;

    public LegacyDecodeService(LegacyCodecRegistry registry, LegacyCodecRunner runner, ObjectMapper mapper) {
        this.registry = registry;
        this.runner = runner;
        this.mapper = mapper;
    }

    public LegacyNormalizedEvent decode(DeviceBinding binding, String vendorDeviceId, String payloadHex) {
        if (binding == null) return failed(null, null, vendorDeviceId, "OTHER", payloadHex);
        var decoder = registry.findLatest(binding.legacyProductId());
        if (decoder.isEmpty()) return failed(binding.tenantId(), binding.deviceId(), vendorDeviceId, binding.platform(), payloadHex);
        CodecResult result = runner.run(decoder.get().source(), payloadHex, binding.attrs());
        if (!result.ok()) return failed(binding.tenantId(), binding.deviceId(), vendorDeviceId, binding.platform(), payloadHex);

        List<LegacyNormalizedEvent.LegacyEvent> events = new ArrayList<>();
        String outerKind = "OTHER";
        BigDecimal battery = null;
        for (CodecResult.DecodedEvent decoded : result.events()) {
            String kind = kind(decoded.method());
            if (outerKind.equals("OTHER") || kind.equals("ALARM")) outerKind = kind;
            JsonNode params = paramsObject(decoded.params());
            if (battery == null && params.path("battery").isNumber()) battery = params.path("battery").decimalValue();
            if (battery == null && params.path("battery_value").isNumber()) battery = params.path("battery_value").decimalValue();
            events.add(new LegacyNormalizedEvent.LegacyEvent(kind, decoded.method(), json(params), null));
        }
        return new LegacyNormalizedEvent(binding.tenantId(), binding.deviceId(), vendorDeviceId, binding.platform(),
            outerKind, null, battery, false, events);
    }

    private LegacyNormalizedEvent failed(String tenantId, String deviceId, String vendorDeviceId, String platform, String payloadHex) {
        String raw = json(Map.of("payloadHex", payloadHex == null ? "" : payloadHex));
        var event = new LegacyNormalizedEvent.LegacyEvent("OTHER", "thing.event.other", raw, null);
        return new LegacyNormalizedEvent(tenantId, deviceId, vendorDeviceId, platform, "OTHER", null, null, true, List.of(event));
    }

    private JsonNode paramsObject(Object value) {
        JsonNode node = value instanceof JsonNode json ? json : mapper.valueToTree(value);
        if (node.isArray()) return node.isEmpty() ? mapper.createObjectNode() : node.get(0);
        return node.isObject() ? node : mapper.createObjectNode();
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { return "{}"; }
    }

    private static String kind(String method) {
        return switch (method) {
            case "thing.event.heartbeat" -> "HEARTBEAT";
            case "thing.event.alarms" -> "ALARM";
            case "thing.event.faults" -> "FAULT";
            case "thing.event.params" -> "PARAM";
            default -> "OTHER";
        };
    }
}
