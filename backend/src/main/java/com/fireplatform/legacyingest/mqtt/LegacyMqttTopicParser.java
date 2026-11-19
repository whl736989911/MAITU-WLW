package com.fireplatform.legacyingest.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

/** Pure topic-to-ingest mapping. */
public final class LegacyMqttTopicParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public record ParsedMessage(String ingress, String platform, String body) { }

    private LegacyMqttTopicParser() { }

    public static Optional<ParsedMessage> parse(String topic, String payloadJson) {
        return parse(topic, payloadJson, MAPPER);
    }

    static Optional<ParsedMessage> parse(String topic, String payloadJson, ObjectMapper mapper) {
        if (topic == null || payloadJson == null || mapper == null) return Optional.empty();
        String[] parts = topic.split("/", -1);
        String ingress;
        String platform;
        String deviceId = null;
        if (parts.length == 4 && parts[0].equals("iot") && !parts[1].isBlank()
                && !parts[2].isBlank() && parts[3].equals("uplink")) {
            ingress = "MQTT_IOT_UPLINK";
            platform = "maitu_mqtt";
            deviceId = parts[2];
        } else if (parts.length == 3 && parts[0].equals("gateway") && !parts[1].isBlank()
                && parts[2].equals("uplink")) {
            ingress = "MQTT_GATEWAY_UPLINK";
            platform = "turmass";
        } else {
            return Optional.empty();
        }
        try {
            JsonNode parsed = mapper.readTree(payloadJson);
            if (parsed == null || !parsed.isObject()) return Optional.empty();
            if (deviceId != null) {
                ObjectNode body = (ObjectNode) parsed;
                if (!body.has("deviceId")) body.put("deviceId", deviceId);
                return Optional.of(new ParsedMessage(ingress, platform, mapper.writeValueAsString(body)));
            }
            return Optional.of(new ParsedMessage(ingress, platform, payloadJson));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
