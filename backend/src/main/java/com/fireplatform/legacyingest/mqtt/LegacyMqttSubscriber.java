package com.fireplatform.legacyingest.mqtt;

import com.fireplatform.legacyingest.ingest.LegacyIngestService;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 旧平台 MQTT 订阅器。
 * 支持 tcp:// 与 ssl://（可选自签 CA），可选用户名/密码，topic 可配置。
 * 只读：仅 CONNECT/SUBSCRIBE/PUBACK/PINGREQ。
 */
@Component
public final class LegacyMqttSubscriber implements AutoCloseable {
    public enum Status { DISCONNECTED, CONNECTED }
    private static final Logger log = LoggerFactory.getLogger(LegacyMqttSubscriber.class);
    private static final String[] DEFAULT_TOPICS = {"$share/legacy/iot/#", "gateway/+/uplink"};

    record Endpoint(String scheme, String host, int port, boolean tls) { }

    private final LegacyIngestService ingestService;
    private final boolean enabled;
    private final String url;
    private final String username;
    private final String password;
    private final String caFile;
    private final String[] topics;
    private final String clientId;
    private final boolean readOnly;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.DISCONNECTED);
    private final AtomicBoolean closed = new AtomicBoolean();
    private Thread worker;

    /** 便捷构造：仅地址与开关，其余取默认。 */
    public LegacyMqttSubscriber(LegacyIngestService ingestService, boolean enabled, String url) {
        this(ingestService, enabled, url, "", "", "", "", "", true);
    }

    /** 兼容既有调用：省略只读开关时按只读处理。 */
    public LegacyMqttSubscriber(LegacyIngestService ingestService, boolean enabled, String url,
            String username, String password, String caFile, String topics, String clientId) {
        this(ingestService, enabled, url, username, password, caFile, topics, clientId, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LegacyMqttSubscriber(LegacyIngestService ingestService,
            @Value("${fire.legacy.mqtt.enabled:false}") boolean enabled,
            @Value("${fire.legacy.mqtt.url:tcp://127.0.0.1:1883}") String url,
            @Value("${fire.legacy.mqtt.username:}") String username,
            @Value("${fire.legacy.mqtt.password:}") String password,
            @Value("${fire.legacy.mqtt.ca-file:}") String caFile,
            @Value("${fire.legacy.mqtt.topics:}") String topics,
            @Value("${fire.legacy.mqtt.client-id:}") String clientId,
            @Value("${fire.legacy.mqtt.read-only:true}") boolean readOnly) {
        this.ingestService = ingestService;
        this.enabled = enabled;
        this.url = url;
        this.username = username == null || username.isBlank() ? null : username;
        this.password = password == null || password.isBlank() ? null : password;
        this.caFile = caFile == null || caFile.isBlank() ? null : caFile;
        this.topics = topics == null || topics.isBlank() ? DEFAULT_TOPICS : Arrays.stream(topics.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toArray(String[]::new);
        this.clientId = clientId == null || clientId.isBlank() ? "fire-platform-legacy-" + Long.toHexString(System.nanoTime()) : clientId;
        this.readOnly = readOnly;
        if (readOnly) for (String topic : this.topics) {
            String normalized = topic.toLowerCase(Locale.ROOT);
            if (normalized.contains("dnlink") || normalized.contains("/tx") || normalized.endsWith("/downlink"))
                throw new IllegalArgumentException("Read-only legacy MQTT refuses downlink topic: " + topic);
        }
        if (this.username != null && this.password == null) throw new IllegalArgumentException("fire.legacy.mqtt.password is required when username is set");
        if (enabled) {
            worker = new Thread(this::run, "legacy-mqtt-subscriber");
            worker.setDaemon(true);
            worker.start();
        }
    }

    public Status getStatus() { return status.get(); }
    public String[] topics() { return topics.clone(); }
    public boolean readOnly() { return readOnly; }

    private void run() {
        long delay = 1_000;
        while (!closed.get()) {
            try (Socket socket = connect()) {
                socket.setSoTimeout(15_000);
                var in = new BufferedInputStream(socket.getInputStream());
                var out = new BufferedOutputStream(socket.getOutputStream());
                connectProtocol(in, out);
                subscribe(out);
                status.set(Status.CONNECTED);
                delay = 1_000;
                readPublishes(in, out);
            } catch (Exception e) {
                if (!closed.get()) log.warn("Legacy MQTT connection ended; reconnecting", e);
            } finally {
                status.set(Status.DISCONNECTED);
            }
            if (!closed.get()) {
                try { Thread.sleep(delay); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                delay = Math.min(delay * 2, 60_000);
            }
        }
    }

    static Endpoint parseEndpoint(String value) throws IOException {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid MQTT URL: " + value, e); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean tls = switch (scheme) { case "ssl", "tls", "mqtts" -> true; case "tcp", "mqtt" -> false; default -> throw new IOException("Unsupported MQTT URL scheme: " + value); };
        if (uri.getHost() == null) throw new IOException("Unsupported MQTT URL: " + value);
        int port = uri.getPort() < 0 ? (tls ? 8883 : 1883) : uri.getPort();
        return new Endpoint(scheme, uri.getHost(), port, tls);
    }

    private Socket connect() throws Exception {
        Endpoint endpoint = parseEndpoint(url);
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 5_000);
        if (!endpoint.tls()) return raw;
        SSLSocketFactory factory = caFile == null ? (SSLSocketFactory) SSLSocketFactory.getDefault() : factoryForCa(caFile);
        SSLSocket tls = (SSLSocket) factory.createSocket(raw, endpoint.host(), endpoint.port(), true);
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(parameters);
        tls.startHandshake();
        return tls;
    }

    /** 用 PEM CA 构建只信任该 CA 的工厂（旧平台多为自签证书）。 */
    static SSLSocketFactory factoryForCa(String caFile) throws Exception {
        CertificateFactory certificates = CertificateFactory.getInstance("X.509");
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        try (InputStream input = Files.newInputStream(Path.of(caFile))) {
            int index = 0;
            for (var certificate : certificates.generateCertificates(input)) trust.setCertificateEntry("ca-" + index++, certificate);
        }
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context.getSocketFactory();
    }

    /** CONNECT flags: clean session + 可选 username/password。 */
    static int connectFlags(boolean hasUsername, boolean hasPassword) {
        int flags = 0x02;
        if (hasUsername) flags |= 0x80;
        if (hasPassword) flags |= 0x40;
        return flags;
    }

    private void connectProtocol(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf(body, "MQTT");
        body.write(4);
        int flags = connectFlags(username != null, password != null);
        body.write(flags);
        body.write(0); body.write(60);
        writeUtf(body, clientId);
        if (username != null) writeUtf(body, username);
        if (password != null) writeUtf(body, password);
        writePacket(out, 0x10, body.toByteArray());
        int header = in.read();
        if (header != 0x20 || readRemaining(in) != 2 || in.read() != 0 || in.read() != 0)
            throw new IOException("MQTT broker rejected CONNECT");
    }

    private void subscribe(OutputStream out) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0); body.write(1);
        for (String topic : topics) { writeUtf(body, topic); body.write(0); }
        writePacket(out, 0x82, body.toByteArray());
    }

    private void readPublishes(InputStream in, OutputStream out) throws IOException {
        while (!closed.get()) {
            int header;
            try { header = in.read(); }
            catch (SocketTimeoutException timeout) { writePacket(out, 0xC0, new byte[0]); continue; }
            if (header < 0) throw new EOFException("MQTT connection closed");
            int remaining = readRemaining(in);
            byte[] packet = in.readNBytes(remaining);
            if (packet.length != remaining) throw new EOFException("Truncated MQTT packet");
            int type = header >> 4;
            if (type == 3) handlePublish(header, packet, out);
            else if (type == 13) { }
            else if (type == 9) { }
        }
    }

    private void handlePublish(int header, byte[] packet, OutputStream out) {
        try {
            int topicLength = unsignedShort(packet, 0);
            String topic = new String(packet, 2, topicLength, StandardCharsets.UTF_8);
            int cursor = 2 + topicLength;
            int qos = (header >> 1) & 3;
            int packetId = 0;
            if (qos > 0) { packetId = unsignedShort(packet, cursor); cursor += 2; }
            String payload = new String(packet, cursor, packet.length - cursor, StandardCharsets.UTF_8);
            LegacyMqttTopicParser.parse(topic, payload).ifPresent(message -> {
                try { ingestService.ingest(message.ingress(), message.platform(), null, message.body()); }
                catch (Exception e) { log.warn("Unable to ingest legacy MQTT message on {}", topic, e); }
            });
            if (qos == 1) writePacket(out, 0x40, new byte[] {(byte)(packetId >> 8), (byte)packetId});
        } catch (Exception e) { log.warn("Ignoring invalid legacy MQTT publish", e); }
    }

    private static int unsignedShort(byte[] bytes, int at) { return ((bytes[at] & 255) << 8) | (bytes[at + 1] & 255); }
    private static void writeUtf(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.write(bytes.length >> 8); out.write(bytes.length); out.write(bytes);
    }
    private static int readRemaining(InputStream in) throws IOException {
        int value = 0, multiplier = 1;
        for (int i = 0; i < 4; i++) { int digit = in.read(); if (digit < 0) throw new EOFException(); value += (digit & 127) * multiplier; if ((digit & 128) == 0) return value; multiplier *= 128; }
        throw new IOException("Invalid MQTT remaining length");
    }

    private static void writePacket(OutputStream out, int header, byte[] body) throws IOException {
        out.write(header); int remaining = body.length;
        do { int digit = remaining % 128; remaining /= 128; if (remaining > 0) digit |= 128; out.write(digit); } while (remaining > 0);
        out.write(body); out.flush();
    }

    @Override public void close() {
        closed.set(true);
        if (worker != null) worker.interrupt();
        status.set(Status.DISCONNECTED);
    }
}
