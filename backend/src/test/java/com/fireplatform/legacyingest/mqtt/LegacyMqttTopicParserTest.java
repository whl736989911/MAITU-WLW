package com.fireplatform.legacyingest.mqtt;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LegacyMqttTopicParserTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void parsesIotAndInjectsMissingDeviceId() throws Exception {
        var parsed = LegacyMqttTopicParser.parse("iot/vendor/dev-7/uplink", "{\"value\":1}", mapper).orElseThrow();
        assertEquals("MQTT_IOT_UPLINK", parsed.ingress());
        assertEquals("maitu_mqtt", parsed.platform());
        assertEquals("dev-7", mapper.readTree(parsed.body()).get("deviceId").asText());
    }

    @Test void preservesExistingIotDeviceIdAndParsesGatewayBodyUnchanged() throws Exception {
        var iot = LegacyMqttTopicParser.parse("iot/x/d/uplink", "{\"deviceId\":\"payload-id\"}", mapper).orElseThrow();
        assertEquals("payload-id", mapper.readTree(iot.body()).get("deviceId").asText());
        String raw = "{ \"data\": \"aa\" }";
        var gateway = LegacyMqttTopicParser.parse("gateway/AA:BB/uplink", raw, mapper).orElseThrow();
        assertEquals("MQTT_GATEWAY_UPLINK", gateway.ingress());
        assertEquals("turmass", gateway.platform());
        assertEquals(raw, gateway.body());
    }

    @Test void rejectsMalformedTopicLengthsAndPayloads() {
        for (String topic : new String[] {"iot/x/d", "iot/x/d/uplink/extra", "iot/x/y/d/uplink", "iot//d/uplink", "gateway/m", "gateway/m/uplink/extra", "gateway//uplink", "other/x/uplink"})
            assertTrue(LegacyMqttTopicParser.parse(topic, "{}", mapper).isEmpty(), topic);
        assertTrue(LegacyMqttTopicParser.parse("iot/x/d/uplink", "[]", mapper).isEmpty());
    }
}
