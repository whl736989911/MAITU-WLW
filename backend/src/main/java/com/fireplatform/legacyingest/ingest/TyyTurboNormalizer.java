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
        JsonNode data = NormalizerSupport.object(service.path("data"));
        JsonNode payload = data.path("payload");
        boolean pending = payload.isTextual() && NormalizerSupport.hex(payload.textValue());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("notifyType", NormalizerSupport.text(body, "notifyType"));
        params.put("eventTime", NormalizerSupport.text(body, "eventTime") != null
                ? NormalizerSupport.text(body, "eventTime") : NormalizerSupport.text(service, "eventTime"));
        params.put("serviceType", NormalizerSupport.text(service, "serviceType"));
        params.put("serviceId", NormalizerSupport.text(service, "serviceId"));
        data.fields().forEachRemaining(entry -> params.put(entry.getKey(), entry.getValue()));

        java.time.OffsetDateTime observed = NormalizerSupport.time(NormalizerSupport.text(body, "eventTime"));
        if (observed == null) observed = NormalizerSupport.time(NormalizerSupport.text(service, "eventTime"));
        java.math.BigDecimal battery = NormalizerSupport.decimal(data, "battery");
        var events = List.of(NormalizerSupport.event(mapper, "OTHER", "tyy.deviceDataChanged", params, observed));
        return new Parsed(id, null, null, observed, battery, "OTHER", pending, events);
    }
}
