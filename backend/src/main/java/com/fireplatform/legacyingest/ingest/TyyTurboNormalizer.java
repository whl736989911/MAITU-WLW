package com.fireplatform.legacyingest.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class TyyTurboNormalizer implements LegacyIngressNormalizer {
    private final ObjectMapper mapper;

    public TyyTurboNormalizer(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public Parsed parse(JsonNode body, String platform, String messageType) {
        String id = NormalizerSupport.text(body, "deviceId");
        JsonNode service = NormalizerSupport.object(body.path("service"));
        if (id == null || id.isBlank()) id = NormalizerSupport.text(service, "deviceId");
        JsonNode data = NormalizerSupport.object(service.path("data"));
        JsonNode payload = data.path("payload");
        boolean pending = payload.isTextual() && NormalizerSupport.hex(payload.textValue());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("notifyType", NormalizerSupport.text(body, "notifyType"));
        String eventTime = NormalizerSupport.text(body, "eventTime");
        var observed = NormalizerSupport.time(eventTime);
        if (observed == null) {
            eventTime = NormalizerSupport.text(service, "eventTime");
            observed = NormalizerSupport.time(eventTime);
        }
        if (observed == null) {
            eventTime = NormalizerSupport.text(data, "eventTime");
            observed = NormalizerSupport.time(eventTime);
        }
        params.put("serviceType", NormalizerSupport.text(service, "serviceType"));
        params.put("serviceId", NormalizerSupport.text(service, "serviceId"));
        data.fields().forEachRemaining(entry -> params.put(entry.getKey(), entry.getValue()));

        params.put("eventTime", eventTime);
        java.math.BigDecimal battery = NormalizerSupport.decimal(data, "battery");
        var events = List.of(NormalizerSupport.event(mapper, "OTHER", "tyy.deviceDataChanged", params, observed));
        return new Parsed(id, null, null, observed, battery, "OTHER", pending, events);
    }
}
