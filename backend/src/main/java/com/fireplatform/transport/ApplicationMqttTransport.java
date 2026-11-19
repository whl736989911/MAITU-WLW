package com.fireplatform.transport;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Component;

/**
 * One-shot MQTT 3.1.1 publish over TLS. The caller resolves and pins the destination address and supplies the
 * original hostname, so this client never performs DNS, never follows a redirect (MQTT has none), never reconnects
 * and never retries. It opens one TCP+TLS connection with SNI and hostname verification, sends one CONNECT with a
 * clean session and username/password, sends one QoS 1 PUBLISH and reads exactly one CONNACK and one PUBACK.
 *
 * <p>A matching PUBACK proves only that the broker accepted the packet; it is never reported as remote business
 * processing or delivery. A refused CONNACK is an explicit rejection. A timeout, truncated packet, unexpected
 * packet type, mismatched packet id or an interrupted exchange is UNKNOWN and must never be re-sent automatically.
 * Every failure is bounded by a single absolute deadline that covers TCP connect, TLS handshake and both replies.
 */
@Component
public class ApplicationMqttTransport {
    public static final int TLS_PORT = 8883;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int MAX_REPLY_BODY = 1_024;
    private static final int MAX_CLIENT_ID = 23;

    private static final ExecutorService EXCHANGES = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "application-output-mqtt");
        thread.setDaemon(true);
        return thread;
    });
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SSLSocketFactory tlsFactory;
    private final int port;

    /** Deployed client: the platform trust store and the fixed MQTT TLS port. */
    public ApplicationMqttTransport() {
        this((SSLSocketFactory) SSLSocketFactory.getDefault(), TLS_PORT);
    }

    /** Seam for the isolated broker tests; production never selects a trust store or port here. */
    ApplicationMqttTransport(SSLSocketFactory tlsFactory, int port) {
        this.tlsFactory = tlsFactory;
        this.port = port;
    }

    public record Outcome(String status, String failureCode) {
        static Outcome accepted() { return new Outcome("ACCEPTED", null); }
        static Outcome rejected(String code) { return new Outcome("REJECTED", code); }
        static Outcome unknown(String code) { return new Outcome("UNKNOWN", code); }
    }

    /** The same fixed-topic rule the configuration write path uses; never a wildcard subscription filter. */
    public static String topic(String value) { return MqttWire.topic(value); }

    /**
     * @param address the already-pinned public address the caller resolved exactly once
     * @param host    the original allowlisted hostname, used verbatim for TLS SNI and endpoint identification
     */
    public Outcome publish(InetAddress address, String host, String topic, String username, String password,
                           byte[] payload, int timeoutMs, String clientId) {
        String normalizedTopic;
        byte[] connect;
        byte[] publish;
        int packetId;
        try {
            normalizedTopic = MqttWire.topic(topic);
            if (username == null || username.isEmpty() || password == null || password.isEmpty())
                return new Outcome("BLOCKED", "MQTT_CREDENTIALS_REQUIRED");
            connect = MqttWire.connect(normalizeClientId(clientId), username, password);
            packetId = RANDOM.nextInt(65_535) + 1;
            publish = MqttWire.publish(normalizedTopic, packetId, payload);
        } catch (IllegalArgumentException invalid) {
            return new Outcome("BLOCKED", "MQTT_REQUEST_INVALID");
        }
        int deadline = Math.max(1, timeoutMs);
        Socket socket = new Socket();
        int expectedPacketId = packetId;
        Future<Outcome> exchange = EXCHANGES.submit(
            (java.util.concurrent.Callable<Outcome>) () -> exchange(socket, address, host, connect, publish,
                expectedPacketId, deadline));
        try {
            return exchange.get(deadline, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            closeQuietly(socket);
            exchange.cancel(true);
            return Outcome.unknown("TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closeQuietly(socket);
            exchange.cancel(true);
            return Outcome.unknown("INTERRUPTED");
        } catch (ExecutionException failure) {
            closeQuietly(socket);
            return Outcome.unknown("NETWORK_ERROR");
        }
    }

    private Outcome exchange(Socket socket, InetAddress address, String host, byte[] connect,
                             byte[] publish, int expectedPacketId, int timeoutMs) {
        SSLSocket tls = null;
        try {
            socket.connect(new InetSocketAddress(address, port), Math.min(CONNECT_TIMEOUT_MS, timeoutMs));
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMs);
            tls = (SSLSocket) tlsFactory.createSocket(socket, host, port, true);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.setSoTimeout(timeoutMs);
            tls.startHandshake();
            OutputStream out = tls.getOutputStream();
            out.write(connect);
            out.flush();
            InputStream in = new BufferedInputStream(tls.getInputStream(), 1_024);
            MqttWire.Packet connack = MqttWire.read(in, MAX_REPLY_BODY);
            int code = MqttWire.connackReturnCode(connack);
            if (code != 0) return Outcome.rejected("MQTT_CONNACK_" + code);
            out.write(publish);
            out.flush();
            MqttWire.Packet puback = MqttWire.read(in, MAX_REPLY_BODY);
            int acknowledged = MqttWire.pubackPacketId(puback);
            if (acknowledged != expectedPacketId) return Outcome.unknown("MQTT_PUBACK_MISMATCH");
            return Outcome.accepted();
        } catch (SocketTimeoutException timeout) {
            return Outcome.unknown("TIMEOUT");
        } catch (IOException failure) {
            return Outcome.unknown("NETWORK_ERROR");
        } catch (RuntimeException failure) {
            return Outcome.unknown("NETWORK_ERROR");
        } finally {
            if (tls != null) closeQuietly(tls);
            closeQuietly(socket);
        }
    }

    private static String normalizeClientId(String clientId) {
        String candidate = clientId == null ? "" : clientId.replaceAll("[^A-Za-z0-9]", "");
        if (candidate.isEmpty()) candidate = "fp" + Long.toHexString(RANDOM.nextLong());
        return candidate.length() <= MAX_CLIENT_ID ? candidate : candidate.substring(0, MAX_CLIENT_ID);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Closing is best effort; the exchange outcome is already decided.
        }
    }
}
