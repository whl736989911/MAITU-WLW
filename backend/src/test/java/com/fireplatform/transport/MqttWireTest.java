package com.fireplatform.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Deterministic wire vectors for the one-shot MQTT output. No socket, broker, certificate or provider is involved;
 * these assertions pin the exact 3.1.1 bytes and the fail-closed reply rules the delivery worker relies on.
 */
class MqttWireTest {
    @Test
    void connectUsesProtocolLevelFourCleanSessionAndUsernamePassword() throws IOException {
        byte[] packet = MqttWire.connect("fp123", "user", "secret");
        MqttWire.Packet parsed = MqttWire.parse(packet);
        assertThat(parsed.type()).isEqualTo(1);
        assertThat(parsed.flags()).isZero();
        byte[] body = parsed.body();
        assertThat(new String(body, 2, 4, StandardCharsets.US_ASCII)).isEqualTo("MQTT");
        assertThat(body[6]).isEqualTo((byte) 4);
        assertThat(body[7] & 0xff).isEqualTo(0xC2);
        assertThat(body[8]).isZero();
        assertThat(body[9]).isEqualTo((byte) 30);
    }

    @Test
    void publishIsQosOneWithTopicPacketIdAndExactPayload() throws IOException {
        byte[] payload = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] packet = MqttWire.publish("fire/alarm", 0x1234, payload);
        MqttWire.Packet parsed = MqttWire.parse(packet);
        assertThat(parsed.type()).isEqualTo(3);
        assertThat(parsed.flags()).isEqualTo(2);
        byte[] body = parsed.body();
        int topicLength = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
        assertThat(new String(body, 2, topicLength, StandardCharsets.UTF_8)).isEqualTo("fire/alarm");
        int cursor = 2 + topicLength;
        assertThat(((body[cursor] & 0xff) << 8) | (body[cursor + 1] & 0xff)).isEqualTo(0x1234);
        assertThat(new String(body, cursor + 2, body.length - cursor - 2, StandardCharsets.UTF_8))
            .isEqualTo("{\"a\":1}");
    }

    @Test
    void connackAndPubackAreDecodedStrictly() throws IOException {
        assertThat(MqttWire.connackReturnCode(MqttWire.parse(new byte[]{0x20, 0x02, 0x00, 0x00}))).isZero();
        assertThat(MqttWire.connackReturnCode(MqttWire.parse(new byte[]{0x20, 0x02, 0x00, 0x05}))).isEqualTo(5);
        assertThat(MqttWire.pubackPacketId(MqttWire.parse(new byte[]{0x40, 0x02, 0x12, 0x34}))).isEqualTo(0x1234);
    }

    @Test
    void unexpectedTruncatedOrMismatchedRepliesFailClosed() {
        assertThatThrownBy(() -> MqttWire.connackReturnCode(MqttWire.parse(new byte[]{0x20, 0x03, 0x00, 0x00, 0x00})))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> MqttWire.connackReturnCode(MqttWire.parse(new byte[]{0x20, 0x02, 0x01, 0x00})))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> MqttWire.pubackPacketId(MqttWire.parse(new byte[]{0x40, 0x01, 0x12})))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> MqttWire.pubackPacketId(MqttWire.parse(new byte[]{0x30, 0x02, 0x12, 0x34})))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> MqttWire.read(new ByteArrayInputStream(new byte[]{0x20}), 1024))
            .isInstanceOf(IOException.class);
    }

    @Test
    void topicsAreFixedAndBounded() {
        assertThat(MqttWire.topic(" fire/alarm ")).isEqualTo("fire/alarm");
        for (String invalid : new String[]{"", "/fire", "fire/", "fire//alarm", "fire/+", "fire/#", "$SYS/x",
            "fire/\0alarm", "fire/\n"}) {
            assertThatThrownBy(() -> MqttWire.topic(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> MqttWire.topic("a".repeat(256))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transportRejectsIncompleteRequestsBeforeAnySocket() {
        var transport = new ApplicationMqttTransport();
        assertThat(transport.publish(null, "broker.example.test", "fire/alarm", "", "secret", new byte[]{1}, 1000, "id")
            .failureCode()).isEqualTo("MQTT_CREDENTIALS_REQUIRED");
        assertThat(transport.publish(null, "broker.example.test", "fire/#", "user", "secret", new byte[]{1}, 1000, "id")
            .failureCode()).isEqualTo("MQTT_REQUEST_INVALID");
        assertThat(transport.publish(null, "broker.example.test", "fire/alarm", "user", "secret", new byte[0], 1000, "id")
            .failureCode()).isEqualTo("MQTT_REQUEST_INVALID");
    }
}
