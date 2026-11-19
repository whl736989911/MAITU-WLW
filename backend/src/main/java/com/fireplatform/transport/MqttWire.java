package com.fireplatform.transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal MQTT 3.1.1 wire framing for one publish. Deliberately not a general client: only CONNECT (clean session,
 * username/password), one QoS 1 PUBLISH, CONNACK and PUBACK exist. Every length is bounded and every UTF-8 string
 * is validated before a byte reaches a socket.
 */
final class MqttWire {
    static final int CONNECT = 0x10;
    static final int PUBLISH_QOS1 = 0x32;
    static final int MAX_PAYLOAD = 1_048_576;
    static final int MAX_TOPIC_BYTES = 255;
    private static final int MAX_REMAINING = 268_435_455;

    record Packet(int header, byte[] body) {
        int type() { return header >> 4; }
        int flags() { return header & 0x0f; }
    }

    private MqttWire() { }

    /**
     * Fixed topic form: no wildcards, no empty levels, no NUL/control characters, no $-prefixed broker control
     * topics and at most 255 UTF-8 bytes. Configuration never supplies a subscription filter here.
     */
    static String topic(String value) {
        if (value == null) throw new IllegalArgumentException("MQTT topic is required");
        String topic = value.trim();
        if (topic.isEmpty() || topic.startsWith("$") || topic.startsWith("/") || topic.endsWith("/")
            || topic.contains("//")) throw new IllegalArgumentException("Invalid MQTT topic");
        for (int index = 0; index < topic.length(); index++) {
            char character = topic.charAt(index);
            if (character == '+' || character == '#' || character == 0 || character < 0x20 || character == 0x7f)
                throw new IllegalArgumentException("Invalid MQTT topic");
        }
        if (topic.getBytes(StandardCharsets.UTF_8).length > MAX_TOPIC_BYTES)
            throw new IllegalArgumentException("MQTT topic is too long");
        return topic;
    }

    static byte[] connect(String clientId, String username, String password) {
        var body = new ByteArrayOutputStream(96);
        writeUtf(body, "MQTT");
        body.write(4);                              // protocol level 3.1.1
        body.write(0x02 | 0x80 | 0x40);             // clean session + username + password
        body.write(0); body.write(30);              // keepalive seconds; the exchange deadline is shorter
        writeUtf(body, clientId);
        writeUtf(body, username);
        writeUtf(body, password);
        return packet(CONNECT, body.toByteArray());
    }

    static byte[] publish(String topic, int packetId, byte[] payload) {
        if (packetId < 1 || packetId > 65_535) throw new IllegalArgumentException("Invalid MQTT packet id");
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD)
            throw new IllegalArgumentException("Invalid MQTT payload");
        byte[] topicBytes = utf8(topic);
        var body = new ByteArrayOutputStream(topicBytes.length + payload.length + 8);
        body.write(topicBytes.length >> 8); body.write(topicBytes.length);
        body.write(topicBytes, 0, topicBytes.length);
        body.write(packetId >> 8); body.write(packetId);
        body.write(payload, 0, payload.length);
        return packet(PUBLISH_QOS1, body.toByteArray());
    }

    /** Reads exactly one packet, rejecting oversized, truncated or non-canonical remaining lengths. */
    static Packet read(InputStream in, int maxBody) throws IOException {
        int header = in.read();
        if (header < 0) throw new EOFException("Connection closed before a reply");
        int remaining = 0;
        int multiplier = 1;
        int digits = 0;
        while (true) {
            int digit = in.read();
            if (digit < 0) throw new EOFException("Truncated remaining length");
            digits++;
            remaining += (digit & 127) * multiplier;
            if ((digit & 128) == 0) break;
            if (digits >= 4) throw new IOException("Invalid remaining length");
            multiplier *= 128;
        }
        if (remaining > MAX_REMAINING || remaining > maxBody) throw new IOException("Reply is too large");
        byte[] body = in.readNBytes(remaining);
        if (body.length != remaining) throw new IOException("Truncated MQTT packet");
        return new Packet(header, body);
    }

    static int connackReturnCode(Packet packet) throws IOException {
        if (packet.type() != 2 || packet.flags() != 0 || packet.body().length != 2)
            throw new IOException("Unexpected CONNACK");
        if (packet.body()[0] != 0) throw new IOException("Unexpected CONNACK session flag");
        return packet.body()[1] & 0xff;
    }

    static int pubackPacketId(Packet packet) throws IOException {
        if (packet.type() != 4 || packet.flags() != 0 || packet.body().length != 2)
            throw new IOException("Unexpected PUBACK");
        return ((packet.body()[0] & 0xff) << 8) | (packet.body()[1] & 0xff);
    }

    static Packet parse(byte[] bytes) throws IOException {
        return read(new ByteArrayInputStream(bytes), MAX_PAYLOAD + 16);
    }

    private static byte[] utf8(String value) {
        if (value == null) throw new IllegalArgumentException("MQTT string is required");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_535) throw new IllegalArgumentException("MQTT string is too long");
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("MQTT string contains NUL");
        return bytes;
    }

    private static void writeUtf(ByteArrayOutputStream out, String value) {
        byte[] bytes = utf8(value);
        out.write(bytes.length >> 8);
        out.write(bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] packet(int header, byte[] body) {
        if (body.length > MAX_REMAINING) throw new IllegalArgumentException("MQTT packet is too large");
        var out = new ByteArrayOutputStream(body.length + 5);
        out.write(header);
        int remaining = body.length;
        do {
            int digit = remaining % 128;
            remaining /= 128;
            if (remaining > 0) digit |= 128;
            out.write(digit);
        } while (remaining > 0);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }
}
