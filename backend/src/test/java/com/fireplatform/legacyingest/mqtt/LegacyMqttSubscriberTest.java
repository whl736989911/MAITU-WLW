package com.fireplatform.legacyingest.mqtt;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LegacyMqttSubscriberTest {
    private static LegacyMqttSubscriber subscriber(boolean enabled, String url) {
        return new LegacyMqttSubscriber(null, enabled, url, "", "", "", "", "");
    }

    @Test void disabledSubscriberNeverConnects() throws Exception {
        try (var subscriber = subscriber(false, "tcp://127.0.0.1:1")) {
            assertEquals(LegacyMqttSubscriber.Status.DISCONNECTED, subscriber.getStatus());
        }
    }

    @Test void parsesConfiguredAndDefaultBrokerEndpoints() throws Exception {
        var explicit = LegacyMqttSubscriber.parseEndpoint("tcp://127.0.0.1:1883");
        assertEquals("127.0.0.1", explicit.host());
        assertEquals(1883, explicit.port());
        assertFalse(explicit.tls());

        var custom = LegacyMqttSubscriber.parseEndpoint("tcp://broker.local:2883");
        assertEquals("broker.local", custom.host());
        assertEquals(2883, custom.port());

        var defaultPort = LegacyMqttSubscriber.parseEndpoint("mqtt://broker.local");
        assertEquals(1883, defaultPort.port());
        assertThrows(java.io.IOException.class, () -> LegacyMqttSubscriber.parseEndpoint("ws://broker.local"));
    }

    @Test void tlsEndpointsDefaultToEightEightEightThree() throws Exception {
        var tls = LegacyMqttSubscriber.parseEndpoint("ssl://broker.local");
        assertEquals(8883, tls.port());
        assertTrue(tls.tls());
        assertTrue(LegacyMqttSubscriber.parseEndpoint("mqtts://broker.local").tls());
    }

    @Test void configuredTopicsReplaceDefaultsAndBlankValuesAreRejected() throws Exception {
        try (var subscriber = new LegacyMqttSubscriber(null, false, "tcp://127.0.0.1:1", "", "", "", "gateway/+/uplink, iot/+/uplink", "")) {
            assertArrayEquals(new String[] {"gateway/+/uplink", "iot/+/uplink"}, subscriber.topics());
        }
        try (var defaults = subscriber(false, "tcp://127.0.0.1:1")) {
            assertArrayEquals(new String[] {"$share/legacy/iot/#", "gateway/+/uplink"}, defaults.topics());
        }
    }

    @Test void readOnlyModeRefusesDownlinkTopics() throws Exception {
        try (var subscriber = subscriber(false, "tcp://127.0.0.1:1")) {
            assertTrue(subscriber.readOnly());
        }
        assertThrows(IllegalArgumentException.class, () -> new LegacyMqttSubscriber(
            null, false, "tcp://127.0.0.1:1", "", "", "", "gateway/+/dnlink", ""));
        assertThrows(IllegalArgumentException.class, () -> new LegacyMqttSubscriber(
            null, false, "tcp://127.0.0.1:1", "", "", "", "device/1/downlink", ""));
        try (var explicitWritable = new LegacyMqttSubscriber(
                null, false, "tcp://127.0.0.1:1", "", "", "", "gateway/+/dnlink", "", false)) {
            assertFalse(explicitWritable.readOnly());
        }
    }
}
