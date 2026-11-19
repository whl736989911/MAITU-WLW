package com.fireplatform.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PinnedHttpTransportTest {
    @Test
    void blockedDnsIsInsideTheAbsoluteDeadline() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        PinnedHttpTransport.Resolver resolver = host -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("The test resolver must not finish normally");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancelled.countDown();
                throw new PinnedHttpTransport.Failure("DNS_CANCELLED", "Isolated resolver interrupted");
            }
        };
        assertThatThrownBy(() -> PinnedHttpTransport.exchange(
            new PinnedHttpTransport.Target("http", "legacy-fixture.invalid", 80, true),
            "GET", "/api/read-only-fixture", Map.of(), null, 1_000, resolver))
            .isInstanceOfSatisfying(PinnedHttpTransport.Failure.class,
                failure -> assertThat(failure.code()).isEqualTo("NETWORK_TIMEOUT"));
        assertThat(started.await(2, TimeUnit.SECONDS)).as("The actual resolver path was reached").isTrue();
        assertThat(cancelled.await(2, TimeUnit.SECONDS)).as("A timed-out resolver is cancelled").isTrue();
    }

    @Test
    void partialHttpBodyCannotHoldTheCallerAndItsSocketIsClosed() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        AtomicBoolean clientClosed = new AtomicBoolean();
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();
        CountDownLatch requestReceived = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            Thread peer = Thread.ofPlatform().daemon().start(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    int matched = 0;
                    byte[] separator = {'\r', '\n', '\r', '\n'};
                    while (matched < separator.length) {
                        int value = socket.getInputStream().read();
                        if (value < 0) throw new IOException("Request ended before headers");
                        matched = value == separator[matched] ? matched + 1 : value == '\r' ? 1 : 0;
                    }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nx".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    requestReceived.countDown();
                    clientClosed.set(socket.getInputStream().read() == -1);
                } catch (Throwable failure) {
                    peerFailure.set(failure);
                }
            });
            try {
                assertThatThrownBy(() -> PinnedHttpTransport.exchange(
                    new PinnedHttpTransport.Target("http", "legacy-fixture.invalid", server.getLocalPort(), true),
                    "GET", "/api/read-only-fixture", Map.of(), null, 1_000, host -> loopback))
                    .isInstanceOfSatisfying(PinnedHttpTransport.Failure.class,
                        failure -> assertThat(failure.code()).isEqualTo("NETWORK_TIMEOUT"));
                assertThat(requestReceived.await(2, TimeUnit.SECONDS)).as("A real HTTP exchange reached the isolated peer").isTrue();
                peer.join(5_000);
                assertThat(peer.isAlive()).isFalse();
                assertThat(peerFailure.get()).isNull();
                assertThat(clientClosed.get()).as("The deadline closes the already-connected socket").isTrue();
            } finally {
                server.close();
                peer.interrupt();
                peer.join(5_000);
            }
        }
    }

    @Test
    void publicTransportNeverConnectsToLoopbackEvenWithHttpRiskAcknowledged() {
        assertThatThrownBy(() -> PinnedHttpTransport.exchange(
            new PinnedHttpTransport.Target("http", "127.0.0.1", 80, true),
            "GET", "/api/read-only-fixture", Map.of(), null))
            .isInstanceOfSatisfying(PinnedHttpTransport.Failure.class,
                failure -> assertThat(failure.code()).isEqualTo("DNS_FORBIDDEN_ADDRESS"));
    }
}
