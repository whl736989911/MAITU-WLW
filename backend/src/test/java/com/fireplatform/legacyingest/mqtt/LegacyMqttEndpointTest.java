package com.fireplatform.legacyingest.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class LegacyMqttEndpointTest {
    @Test void supportsPlainAndTlsSchemesWithDefaultPorts() throws IOException {
        var plain = LegacyMqttSubscriber.parseEndpoint("tcp://192.0.2.10");
        assertThat(plain.host()).isEqualTo("192.0.2.10");
        assertThat(plain.port()).isEqualTo(1883);
        assertThat(plain.tls()).isFalse();

        var tls = LegacyMqttSubscriber.parseEndpoint("ssl://mqtt.example.com");
        assertThat(tls.port()).isEqualTo(8883);
        assertThat(tls.tls()).isTrue();

        var explicit = LegacyMqttSubscriber.parseEndpoint("mqtts://mqtt.example.com:18884");
        assertThat(explicit.port()).isEqualTo(18884);
        assertThat(explicit.tls()).isTrue();
    }

    @Test void rejectsUnsupportedSchemesAndMissingHost() {
        assertThatThrownBy(() -> LegacyMqttSubscriber.parseEndpoint("ws://127.0.0.1:8083")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> LegacyMqttSubscriber.parseEndpoint("tcp:///missing")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> LegacyMqttSubscriber.parseEndpoint("not a url")).isInstanceOf(IOException.class);
    }

    @Test void connectFlagsMarkCleanSessionAndCredentials() {
        assertThat(LegacyMqttSubscriber.connectFlags(false, false)).isEqualTo(0x02);
        assertThat(LegacyMqttSubscriber.connectFlags(true, true)).isEqualTo(0xC2);
        assertThat(LegacyMqttSubscriber.connectFlags(true, false)).isEqualTo(0x82);
    }
}
