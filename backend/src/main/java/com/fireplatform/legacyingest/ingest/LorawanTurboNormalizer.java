package com.fireplatform.legacyingest.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class LorawanTurboNormalizer implements LegacyIngressNormalizer {
    private static final BigDecimal BATTERY_UNAVAILABLE = BigDecimal.valueOf(255);
    private final ObjectMapper mapper;
    public LorawanTurboNormalizer(ObjectMapper mapper){this.mapper=mapper;}
    @Override
    public Parsed parse(JsonNode body, String platform, String messageType) {
        String id = NormalizerSupport.text(body, "deveui");
        BigDecimal battery = NormalizerSupport.decimal(body, "battery");
        // The network server uses 255 for an unavailable battery reading.
        boolean unavailableBattery = battery != null && battery.compareTo(BATTERY_UNAVAILABLE) == 0;
        var params = new LinkedHashMap<String, Object>();
        for (String key : List.of("data", "fcnt", "port", "datr", "freq", "rssi", "lsnr", "battery", "datetime")) {
            JsonNode value = body.get(key);
            if (value != null && !value.isNull() && !(unavailableBattery && key.equals("battery"))) params.put(key, value);
        }
        var observed = NormalizerSupport.time(NormalizerSupport.text(body, "datetime"));
        return new Parsed(id, NormalizerSupport.text(body, "upPacketSN"), NormalizerSupport.text(body, "fcnt"), observed,
                unavailableBattery ? null : battery, "OTHER", true,
                List.of(NormalizerSupport.event(mapper, "OTHER", "lorawan.uplink", params, observed)));
    }
}
