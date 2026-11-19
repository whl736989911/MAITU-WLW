package com.fireplatform.legacyingest.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class AepTurboNormalizer implements LegacyIngressNormalizer {
    private final ObjectMapper mapper;

    public AepTurboNormalizer(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public Parsed parse(JsonNode body, String platform, String messageType) {
        String id = NormalizerSupport.text(body, "deviceId");
        if (id == null || id.isBlank()) id = NormalizerSupport.text(body, "IMEI");
        String serial = NormalizerSupport.text(body, "upPacketSN");
        JsonNode payload = NormalizerSupport.object(body.get("payload"));
        Map<String, Object> params = new LinkedHashMap<>();
        payload.fields().forEachRemaining(entry -> params.put(entry.getKey(), entry.getValue()));
        String type = NormalizerSupport.text(body, "messageType");
        String method = switch (type == null ? "" : type) {
            case "dataReport" -> "aep.dataReport";
            case "eventReport" -> "aep.eventReport";
            default -> "aep." + (type == null || type.isBlank() ? "unknown" : type);
        };
        if ("eventReport".equals(type)) {
            JsonNode eventContent = body.has("eventContent") ? body.get("eventContent") : payload.get("eventContent");
            if (eventContent != null) params.put("eventContent", eventContent);
        }
        if (type != null) params.put("messageType", type);
        // AEP report bodies have no generic native mapping; retain them as pending until the bound pa_code handles them.
        boolean pending = true;
        java.time.OffsetDateTime observed = NormalizerSupport.time(NormalizerSupport.text(body, "timestamp"));
        if (observed == null) observed = NormalizerSupport.time(NormalizerSupport.text(body, "eventTime"));
        java.math.BigDecimal battery = NormalizerSupport.decimal(payload, "battery_value");
        if (battery == null) battery = NormalizerSupport.decimal(payload, "battery");
        String kind = NormalizerSupport.eventKind(type == null ? null : "thing.event." + type);
        var events = List.of(NormalizerSupport.event(mapper, kind, method, params, observed));
        return new Parsed(id, serial, null, observed, battery, kind, pending, events);
    }
}
