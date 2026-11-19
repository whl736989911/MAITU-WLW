package com.fireplatform.legacyingest.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fireplatform.legacyingest.ingest.LegacyIngestService;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class LegacyMqttSubscriberReconnectTest {
    private static final long WAIT_SECONDS = 5;

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void enabledSubscriberConnectsRetriesAndContinuesAfterMessageHandlerThrows() throws Exception {
        var ingest = mock(LegacyIngestService.class);
        var processed = new AtomicInteger();
        doAnswer(invocation -> {
            if (processed.incrementAndGet() == 1) throw new IllegalStateException("injected ingest failure");
            return null;
        }).when(ingest).ingest(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyString());

        try (var server = new FakeMqttServer(true);
                var subscriber = new LegacyMqttSubscriber(ingest, true, server.url())) {
            assertTrue(server.firstConnection.await(WAIT_SECONDS, TimeUnit.SECONDS), "subscriber did not connect");
            assertTrue(server.secondConnection.await(WAIT_SECONDS, TimeUnit.SECONDS), "subscriber did not reconnect");
            assertTrue(awaitAtLeast(processed, 2), "message handler did not process a second message");
            assertTrue(server.connections.get() >= 2, "expected reconnect after server closed connection");
            verify(ingest, times(2)).ingest(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void disabledSubscriberNeverConnects() throws Exception {
        try (var server = new FakeMqttServer(false);
                var subscriber = new LegacyMqttSubscriber(null, false, server.url())) {
            Thread.sleep(200);
            assertEquals(0, server.connections.get());
        }
    }

    private static boolean awaitAtLeast(AtomicInteger value, int minimum) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (value.get() >= minimum) return true;
            Thread.sleep(10);
        }
        return value.get() >= minimum;
    }

    private static final class FakeMqttServer implements AutoCloseable {
        private final ServerSocket listener;
        private final boolean sendMessages;
        private final AtomicInteger connections = new AtomicInteger();
        private final CountDownLatch firstConnection = new CountDownLatch(1);
        private final CountDownLatch secondConnection = new CountDownLatch(1);
        private final Thread acceptor;
        private volatile Socket active;

        FakeMqttServer(boolean sendMessages) throws IOException {
            this.sendMessages = sendMessages;
            listener = new ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(1000);
            acceptor = new Thread(this::acceptConnections, "fake-mqtt-server");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String url() { return "tcp://127.0.0.1:" + listener.getLocalPort(); }

        private void acceptConnections() {
            while (!listener.isClosed()) {
                try {
                    Socket socket = listener.accept();
                    active = socket;
                    int count = connections.incrementAndGet();
                    firstConnection.countDown();
                    if (count >= 2) secondConnection.countDown();
                    serve(socket, count);
                } catch (java.net.SocketTimeoutException ignored) {
                    // Check whether the listener was closed.
                } catch (IOException e) {
                    if (!listener.isClosed()) throw new IllegalStateException(e);
                }
            }
        }

        private void serve(Socket socket, int connectionNumber) throws IOException {
            try (socket) {
                var in = socket.getInputStream();
                var out = socket.getOutputStream();
                readPacket(in); // CONNECT
                out.write(new byte[] {0x20, 0x02, 0x00, 0x00}); // successful CONNACK
                out.flush();
                readPacket(in); // SUBSCRIBE
                if (sendMessages && connectionNumber == 1) {
                    writePublish(out, "gateway/test/uplink", "{}");
                    writePublish(out, "gateway/test/uplink", "{}");
                }
                // Closing the first connection exercises the subscriber's backoff/reconnect path.
            }
        }

        private static byte[] readPacket(java.io.InputStream in) throws IOException {
            int header = in.read();
            if (header < 0) throw new EOFException("client closed MQTT connection");
            int remaining = 0;
            int multiplier = 1;
            int digit;
            do {
                digit = in.read();
                if (digit < 0) throw new EOFException("truncated MQTT remaining length");
                remaining += (digit & 127) * multiplier;
                multiplier *= 128;
            } while ((digit & 128) != 0);
            byte[] packet = in.readNBytes(remaining);
            if (packet.length != remaining) throw new EOFException("truncated MQTT packet");
            return packet;
        }

        private static void writePublish(java.io.OutputStream out, String topic, String payload) throws IOException {
            byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            var body = new ByteArrayOutputStream();
            body.write(topicBytes.length >> 8);
            body.write(topicBytes.length);
            body.write(topicBytes);
            body.write(payloadBytes);
            byte[] bytes = body.toByteArray();
            out.write(0x30);
            out.write(bytes.length);
            out.write(bytes);
            out.flush();
        }

        @Override public void close() throws IOException {
            listener.close();
            Socket socket = active;
            if (socket != null) socket.close();
        }
    }
}
