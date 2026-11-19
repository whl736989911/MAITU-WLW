package com.fireplatform.application;

import com.fireplatform.shared.ApiError;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
 * Real outbound POST over a DNS-pinned TLS socket. The hostname is resolved exactly once, every answer must be
 * public, and the TCP connection is made to that verified address while TLS still sends SNI and verifies the
 * certificate plus hostname for the original allowlisted host — a second resolution can never rebind the
 * destination. Redirects are never followed and no proxy is used.
 *
 * <p>The whole exchange (connect, handshake, request write, status read) runs under one absolute deadline: the
 * calling worker waits on the task and closes the socket when the deadline expires, so neither a stalled write nor a
 * slow peer can hang the delivery loop. Only the status line and headers are consumed; the response body is never
 * read, so a receiver that echoes Authorization or payload content cannot leak it into the database or an API
 * projection. A 2xx means the remote endpoint accepted the HTTP request; it is recorded as ACCEPTED, never as
 * business processing. Nothing here retries.
 */
@Component
public class ApplicationDeliveryTransport {
    private static final int MAX_HEADER_BYTES = 65_536;
    private static final int MAX_HEADERS = 100;
    private static final int MAX_LINE_BYTES = 8_192;
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private static final ExecutorService EXCHANGES = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "application-output-http");
        thread.setDaemon(true);
        return thread;
    });

    public record Outcome(String status, Integer httpStatus, String failureCode) {
        public static Outcome blocked(String code) { return new Outcome("BLOCKED", null, code); }
    }

    private final ApplicationEndpointPolicy endpoints;

    public ApplicationDeliveryTransport(ApplicationEndpointPolicy endpoints) {
        this.endpoints = endpoints;
    }

    public Outcome post(String url, String contentType, byte[] payload, String authScheme, String authHeader,
                        String credential, int timeoutMs, String requestId) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException invalid) {
            return Outcome.blocked("REQUEST_NOT_SENT");
        }
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (host == null) return Outcome.blocked("REQUEST_NOT_SENT");
        InetAddress address;
        try {
            address = endpoints.pin(host);
        } catch (ApiError refused) {
            return Outcome.blocked(refused.code);
        }
        byte[] request;
        try {
            request = request(uri, host, contentType, payload, authScheme, authHeader, credential, requestId);
        } catch (RuntimeException invalid) {
            return Outcome.blocked("REQUEST_NOT_SENT");
        }
        Socket socket = new Socket();
        Future<Outcome> exchange = EXCHANGES.submit(
            (java.util.concurrent.Callable<Outcome>) () -> exchange(socket, address, host, request, timeoutMs));
        try {
            return exchange.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException deadline) {
            closeQuietly(socket);
            exchange.cancel(true);
            return new Outcome("UNKNOWN", null, "TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closeQuietly(socket);
            exchange.cancel(true);
            return new Outcome("UNKNOWN", null, "INTERRUPTED");
        } catch (ExecutionException failure) {
            closeQuietly(socket);
            return new Outcome("UNKNOWN", null, "NETWORK_ERROR");
        }
    }

    private static Outcome exchange(Socket socket, InetAddress address, String host, byte[] request, int timeoutMs) {
        SSLSocket tls = null;
        try {
            socket.connect(new InetSocketAddress(address, 443), Math.min(CONNECT_TIMEOUT_MS, timeoutMs));
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMs);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            tls = (SSLSocket) factory.createSocket(socket, host, 443, true);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.setSoTimeout(timeoutMs);
            tls.startHandshake();
            OutputStream out = tls.getOutputStream();
            out.write(request);
            out.flush();
            InputStream in = new BufferedInputStream(tls.getInputStream(), 8192);
            return classify(readStatus(in));
        } catch (SocketTimeoutException timeout) {
            return new Outcome("UNKNOWN", null, "TIMEOUT");
        } catch (IOException failure) {
            return new Outcome("UNKNOWN", null, "NETWORK_ERROR");
        } catch (RuntimeException failure) {
            return new Outcome("UNKNOWN", null, "NETWORK_ERROR");
        } finally {
            if (tls != null) closeQuietly(tls);
            closeQuietly(socket);
        }
    }

    private static Outcome classify(int status) {
        if (status >= 200 && status < 300) return new Outcome("ACCEPTED", status, null);
        if (status >= 400 && status < 500) return new Outcome("REJECTED", status, "HTTP_" + status);
        return new Outcome("UNKNOWN", status, "HTTP_" + status);
    }

    private static byte[] request(URI uri, String host, String contentType, byte[] payload, String authScheme,
                                  String authHeader, String credential, String requestId) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();
        var head = new StringBuilder(320);
        head.append("POST ").append(path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(host).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Accept: ").append("application/json".equals(contentType) ? "application/json" : "*/*").append("\r\n");
        head.append("User-Agent: fire-platform-output/1\r\n");
        head.append("X-Request-Id: ").append(requestId).append("\r\n");
        head.append("Content-Length: ").append(payload.length).append("\r\n");
        if ("BEARER".equals(authScheme)) head.append("Authorization: Bearer ").append(credential).append("\r\n");
        else if ("BASIC".equals(authScheme))
            head.append("Authorization: Basic ")
                .append(java.util.Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)))
                .append("\r\n");
        else if ("HEADER".equals(authScheme)) head.append(authHeader).append(": ").append(credential).append("\r\n");
        head.append("\r\n");
        byte[] header = head.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] request = new byte[header.length + payload.length];
        System.arraycopy(header, 0, request, 0, header.length);
        System.arraycopy(payload, 0, request, header.length, payload.length);
        return request;
    }

    /** Reads only the status line and headers; the body is intentionally left unread and the socket is closed. */
    private static int readStatus(InputStream in) throws IOException {
        int received = 0;
        while (true) {
            String statusLine = readLine(in, MAX_LINE_BYTES);
            received += statusLine.length() + 2;
            if (received > MAX_HEADER_BYTES) throw new IOException("Response header block too large");
            if (!statusLine.startsWith("HTTP/")) throw new IOException("Invalid status line");
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2) throw new IOException("Invalid status line");
            int status;
            try {
                status = Integer.parseInt(parts[1]);
            } catch (NumberFormatException invalid) {
                throw new IOException("Invalid status");
            }
            int count = 0;
            while (true) {
                String line = readLine(in, MAX_LINE_BYTES);
                received += line.length() + 2;
                if (received > MAX_HEADER_BYTES) throw new IOException("Response header block too large");
                if (line.isEmpty()) break;
                if (++count > MAX_HEADERS) throw new IOException("Too many response headers");
                if (line.indexOf(':') <= 0) throw new IOException("Invalid response header");
            }
            if (status >= 100 && status < 200) continue;
            return status;
        }
    }

    private static String readLine(InputStream in, int maximum) throws IOException {
        var line = new StringBuilder();
        while (true) {
            int read = in.read();
            if (read == -1) throw new IOException("Unexpected end of response");
            if (read == '\n') break;
            if (read != '\r') {
                if (line.length() >= maximum) throw new IOException("Response line too long");
                line.append((char) read);
            }
        }
        return line.toString();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Closing is best effort; the exchange outcome is already decided.
        }
    }
}
