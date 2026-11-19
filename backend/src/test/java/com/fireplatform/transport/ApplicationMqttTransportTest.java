package com.fireplatform.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;

/**
 * End-to-end TLS exchange against a loopback broker that speaks exactly the one-shot 3.1.1 flow. The broker uses a
 * test-only self-signed certificate for {@code localhost}; the client still performs real TLS with SNI and the
 * platform endpoint-identification check, so hostname verification is exercised rather than mocked.
 *
 * <p>The assertions pin the delivery contract: a matching PUBACK is the only ACCEPTED, a refused CONNACK is
 * REJECTED without a PUBLISH, and a timeout, truncated reply, wrong packet type or mismatched packet id is UNKNOWN
 * with exactly one connection and one publish — never a resend.
 */
class ApplicationMqttTransportTest {
    private static final String HOST = "localhost";
    private static final byte[] PAYLOAD = "{\"event\":\"FIRE\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void matchingPubackIsAcceptedAndTheBrokerSeesCleanSessionQosOnePublish() throws Exception {
        try (Broker broker = new Broker((self, in, out) -> {
            out.write(new byte[]{0x20, 0x02, 0x00, 0x00});
            out.flush();
            MqttWire.Packet publish = MqttWire.read(in, 1024);
            self.publishes.add(publish);
            int packetId = packetId(publish);
            out.write(new byte[]{0x40, 0x02, (byte) (packetId >> 8), (byte) packetId});
            out.flush();
        })) {
            var outcome = transport(broker).publish(InetAddress.getByName("127.0.0.1"), HOST, "fire/alarm",
                "output-user", "output-password", PAYLOAD, 5_000, "delivery-1");

            assertThat(outcome.status()).isEqualTo("ACCEPTED");
            assertThat(outcome.failureCode()).isNull();
            assertThat(broker.connections.get()).isEqualTo(1);

            MqttWire.Packet connect = broker.connects.getFirst();
            assertThat(connect.type()).isEqualTo(1);
            assertThat(connect.body()[7] & 0xff).isEqualTo(0xC2);
            assertThat(connect.body()[6]).isEqualTo((byte) 4);
            String clientId = utf8(connect.body(), 10);
            assertThat(clientId).isEqualTo("delivery1");
            assertThat(clientId.length()).isLessThanOrEqualTo(23);

            MqttWire.Packet publish = broker.publishes.getFirst();
            assertThat(publish.type()).isEqualTo(3);
            assertThat(publish.flags()).isEqualTo(2);
            int topicLength = ((publish.body()[0] & 0xff) << 8) | (publish.body()[1] & 0xff);
            assertThat(new String(publish.body(), 2, topicLength, StandardCharsets.UTF_8)).isEqualTo("fire/alarm");
            int cursor = 2 + topicLength + 2;
            assertThat(java.util.Arrays.copyOfRange(publish.body(), cursor, publish.body().length))
                .isEqualTo(PAYLOAD);
        }
    }

    @Test
    void refusedConnackIsRejectedWithoutAnyPublish() throws Exception {
        try (Broker broker = new Broker((self, in, out) -> {
            out.write(new byte[]{0x20, 0x02, 0x00, 0x05});
            out.flush();
            self.trailingByte = in.read();
        })) {
            var outcome = transport(broker).publish(InetAddress.getByName("127.0.0.1"), HOST, "fire/alarm",
                "output-user", "output-password", PAYLOAD, 5_000, "delivery-2");

            assertThat(outcome.status()).isEqualTo("REJECTED");
            assertThat(outcome.failureCode()).isEqualTo("MQTT_CONNACK_5");
            broker.awaitTrailingRead();
            assertThat(broker.trailingByte).isEqualTo(-1);
            assertThat(broker.publishes).isEmpty();
            assertThat(broker.connections.get()).isEqualTo(1);
        }
    }

    @Test
    void missingPubackIsUnknownWithOnePublishAndNoResend() throws Exception {
        try (Broker broker = new Broker((self, in, out) -> {
            out.write(new byte[]{0x20, 0x02, 0x00, 0x00});
            out.flush();
            self.publishes.add(MqttWire.read(in, 1024));
            // No PUBACK: hold the connection open until the client's absolute deadline closes it.
            self.trailingByte = in.read();
        })) {
            var outcome = transport(broker).publish(InetAddress.getByName("127.0.0.1"), HOST, "fire/alarm",
                "output-user", "output-password", PAYLOAD, 2_000, "delivery-3");

            assertThat(outcome.status()).isEqualTo("UNKNOWN");
            assertThat(outcome.failureCode()).isEqualTo("TIMEOUT");
            broker.awaitPublishCount(1);
            assertThat(broker.connections.get()).isEqualTo(1);
            assertThat(broker.connects).hasSize(1);
            assertThat(broker.publishes).hasSize(1);
        }
    }

    @Test
    void mismatchedPubackPacketIdIsUnknown() throws Exception {
        try (Broker broker = new Broker((self, in, out) -> {
            out.write(new byte[]{0x20, 0x02, 0x00, 0x00});
            out.flush();
            MqttWire.Packet publish = MqttWire.read(in, 1024);
            self.publishes.add(publish);
            int other = packetId(publish) % 65_535 + 1;
            out.write(new byte[]{0x40, 0x02, (byte) (other >> 8), (byte) other});
            out.flush();
        })) {
            var outcome = transport(broker).publish(InetAddress.getByName("127.0.0.1"), HOST, "fire/alarm",
                "output-user", "output-password", PAYLOAD, 5_000, "delivery-4");

            assertThat(outcome.status()).isEqualTo("UNKNOWN");
            assertThat(outcome.failureCode()).isEqualTo("MQTT_PUBACK_MISMATCH");
            assertThat(broker.publishes).hasSize(1);
        }
    }

    @Test
    void unexpectedPacketTypeInsteadOfConnackIsUnknown() throws Exception {
        try (Broker broker = new Broker((self, in, out) -> {
            out.write(new byte[]{0x30, 0x02, 0x00, 0x00});
            out.flush();
        })) {
            var outcome = transport(broker).publish(InetAddress.getByName("127.0.0.1"), HOST, "fire/alarm",
                "output-user", "output-password", PAYLOAD, 5_000, "delivery-5");

            assertThat(outcome.status()).isEqualTo("UNKNOWN");
            assertThat(outcome.failureCode()).isEqualTo("NETWORK_ERROR");
            assertThat(broker.publishes).isEmpty();
        }
    }

    @Test
    void truncatedConnackIsUnknown() throws Exception {
        try (Broker broker = new Broker((self, in, out) -> {
            out.write(new byte[]{0x20, 0x02, 0x00});
            out.flush();
        })) {
            var outcome = transport(broker).publish(InetAddress.getByName("127.0.0.1"), HOST, "fire/alarm",
                "output-user", "output-password", PAYLOAD, 5_000, "delivery-6");

            assertThat(outcome.status()).isEqualTo("UNKNOWN");
            assertThat(broker.publishes).isEmpty();
        }
    }

    @Test
    void hostnameVerificationFailureSendsNoMqttBytes() throws Exception {
        try (Broker broker = new Broker((self, in, out) -> { })) {
            var outcome = transport(broker).publish(InetAddress.getByName("127.0.0.1"), "broker.example.invalid",
                "fire/alarm", "output-user", "output-password", PAYLOAD, 5_000, "delivery-7");

            assertThat(outcome.status()).isEqualTo("UNKNOWN");
            assertThat(outcome.failureCode()).isEqualTo("NETWORK_ERROR");
            assertThat(broker.connects).isEmpty();
            assertThat(broker.publishes).isEmpty();
            assertThat(broker.connections.get()).isEqualTo(1);
        }
    }

    private static ApplicationMqttTransport transport(Broker broker) throws Exception {
        return new ApplicationMqttTransport((SSLSocketFactory) TestTls.clientContext().getSocketFactory(),
            broker.port());
    }

    private static int packetId(MqttWire.Packet publish) {
        byte[] body = publish.body();
        int topicLength = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
        int cursor = 2 + topicLength;
        return ((body[cursor] & 0xff) << 8) | (body[cursor + 1] & 0xff);
    }

    private static String utf8(byte[] body, int offset) {
        int length = ((body[offset] & 0xff) << 8) | (body[offset + 1] & 0xff);
        return new String(body, offset + 2, length, StandardCharsets.UTF_8);
    }

    /** Accepts one connection at a time, records the CONNECT and runs the scripted broker behaviour. */
    private static final class Broker implements AutoCloseable {
        interface Script {
            void run(Broker broker, InputStream in, OutputStream out) throws Exception;
        }

        private final SSLServerSocket server;
        private final Thread thread;
        private final List<MqttWire.Packet> connects = Collections.synchronizedList(new ArrayList<>());
        private final List<MqttWire.Packet> publishes = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile Socket active;
        private volatile boolean closed;
        private volatile int trailingByte = Integer.MIN_VALUE;

        Broker(Script script) throws Exception {
            server = (SSLServerSocket) TestTls.serverContext().getServerSocketFactory()
                .createServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(10_000);
            thread = new Thread(() -> serve(script), "test-mqtt-broker");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        void awaitPublishCount(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (publishes.size() < expected && System.nanoTime() < deadline) Thread.sleep(10);
        }

        void awaitTrailingRead() throws InterruptedException {
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (trailingByte == Integer.MIN_VALUE && System.nanoTime() < deadline) Thread.sleep(10);
        }

        private void serve(Script script) {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    active = socket;
                    connections.incrementAndGet();
                    try (socket) {
                        socket.setSoTimeout(10_000);
                        InputStream in = new BufferedInputStream(socket.getInputStream());
                        OutputStream out = socket.getOutputStream();
                        connects.add(MqttWire.read(in, 1024));
                        script.run(this, in, out);
                        out.flush();
                    }
                } catch (SocketTimeoutException idle) {
                    return;
                } catch (IOException | RuntimeException ended) {
                    if (!closed) failure.compareAndSet(null, ended);
                    if (closed) return;
                } catch (Exception scripted) {
                    failure.compareAndSet(null, scripted);
                }
            }
        }

        @Override
        public void close() throws Exception {
            closed = true;
            try {
                server.close();
            } catch (IOException ignored) {
                // The listener is already unusable.
            }
            Socket socket = active;
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // The exchange is over; the assertion result is already decided.
                }
            }
            thread.join(5_000);
        }
    }

    /**
     * Test-only PKCS#12 material for {@code CN=localhost} with a {@code dns:localhost}/{@code ip:127.0.0.1} SAN.
     * The DER is built directly so the test needs no external tool, provider or checked-in private key.
     */
    private static final class TestTls {
        private static final byte[] SHA256_WITH_RSA =
            new byte[]{0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b};
        private static final byte[] COMMON_NAME = new byte[]{0x06, 0x03, 0x55, 0x04, 0x03};
        private static final byte[] SUBJECT_ALT_NAME = new byte[]{0x06, 0x03, 0x55, 0x1d, 0x11};
        private static final char[] PASSWORD = "mqtt-test".toCharArray();
        private static final Material MATERIAL = material();

        static SSLContext serverContext() throws Exception {
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(MATERIAL.keyStore(), PASSWORD);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), null, null);
            return context;
        }

        static SSLContext clientContext() throws Exception {
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(MATERIAL.trustStore());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), null);
            return context;
        }

        private record Material(KeyStore keyStore, KeyStore trustStore) { }

        private static Material material() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                X509Certificate certificate = certificate(pair);
                certificate.verify(pair.getPublic());
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                keyStore.load(null, null);
                keyStore.setKeyEntry("broker", pair.getPrivate(), PASSWORD, new Certificate[]{certificate});
                KeyStore trustStore = KeyStore.getInstance("PKCS12");
                trustStore.load(null, null);
                trustStore.setCertificateEntry("broker", certificate);
                return new Material(keyStore, trustStore);
            } catch (Exception impossible) {
                throw new IllegalStateException("Cannot build the isolated broker certificate", impossible);
            }
        }

        private static X509Certificate certificate(KeyPair pair) throws Exception {
            byte[] algorithm = der(0x30, concat(SHA256_WITH_RSA, new byte[]{0x05, 0x00}));
            byte[] name = der(0x30, der(0x31, der(0x30, concat(COMMON_NAME, der(0x0c,
                "localhost".getBytes(StandardCharsets.UTF_8))))));
            byte[] validity = der(0x30, concat(der(0x17, "240101000000Z".getBytes(StandardCharsets.US_ASCII)),
                der(0x17, "340101000000Z".getBytes(StandardCharsets.US_ASCII))));
            byte[] generalNames = der(0x30, concat(der(0x82, "localhost".getBytes(StandardCharsets.US_ASCII)),
                der(0x87, new byte[]{127, 0, 0, 1})));
            byte[] extensions = der(0xa3, der(0x30, der(0x30, concat(SUBJECT_ALT_NAME,
                der(0x04, generalNames)))));
            byte[] tbs = der(0x30, concat(der(0xa0, der(0x02, new byte[]{2})),
                der(0x02, BigInteger.valueOf(0x4d515454).toByteArray()), algorithm, name, validity, name,
                pair.getPublic().getEncoded(), extensions));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(pair.getPrivate());
            signature.update(tbs);
            byte[] signed = signature.sign();
            byte[] certificate = der(0x30, concat(tbs, algorithm, der(0x03, concat(new byte[]{0}, signed))));
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificate));
        }

        private static byte[] der(int tag, byte[] value) {
            var out = new ByteArrayOutputStream(value.length + 6);
            out.write(tag);
            if (value.length < 0x80) {
                out.write(value.length);
            } else {
                int bytes = value.length <= 0xff ? 1 : value.length <= 0xffff ? 2 : 3;
                out.write(0x80 | bytes);
                for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) out.write(value.length >> shift);
            }
            out.write(value, 0, value.length);
            return out.toByteArray();
        }

        private static byte[] concat(byte[]... parts) {
            var out = new ByteArrayOutputStream();
            for (byte[] part : parts) out.write(part, 0, part.length);
            return out.toByteArray();
        }
    }
}
