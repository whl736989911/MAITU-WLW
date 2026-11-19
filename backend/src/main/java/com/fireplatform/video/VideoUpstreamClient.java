package com.fireplatform.video;

import com.fireplatform.shared.ApiError;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Component;

/**
 * Bounded HTTPS media client with an explicit security posture: the URL host must be allowlisted, DNS is
 * resolved and pinned for the actual TCP connection, TLS verifies the allowlisted host name, redirects are
 * refused, and the response body is capped. No URL or credential is ever logged.
 *
 * <p>HTTPS is implemented directly on a pinned SSLSocket instead of HttpsURLConnection because the JDK client
 * creates an unconnected socket and resolves the host itself, which would bypass DNS pinning.
 */
@Component
public class VideoUpstreamClient {
    private static final int MAX_URL_LENGTH = 8192;
    private static final int MAX_HEADER_BYTES = 65536;
    private static final int MAX_HEADERS = 100;
    private static final int MAX_LINE_BYTES = 8192;
    private static final int MAX_TRAILER_BYTES = 65536;
    private static final int MAX_TRAILERS = 100;
    private static final long EXCHANGE_DEADLINE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
    private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINE_CLOSER =
        new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "video-exchange-deadline");
            thread.setDaemon(true);
            return thread;
        });
    static { DEADLINE_CLOSER.setRemoveOnCancelPolicy(true); }
    private final VideoPolicy policy;

    public VideoUpstreamClient(VideoPolicy policy) { this.policy = policy; }

    public record Target(URI uri, String host) { }

    public static final class Upstream implements AutoCloseable {
        private final Socket socket;
        private final int status;
        private final String contentType;
        private final long contentLength;
        private final String contentRange;
        private final boolean acceptsRanges;
        private final InputStream body;
        private final java.util.concurrent.ScheduledFuture<?> deadlineCloser;

        Upstream(Socket socket, int status, String contentType, long contentLength, String contentRange,
                 boolean acceptsRanges, InputStream body) {
            this(socket, status, contentType, contentLength, contentRange, acceptsRanges, body, null);
        }

        Upstream(Socket socket, int status, String contentType, long contentLength, String contentRange,
                 boolean acceptsRanges, InputStream body, java.util.concurrent.ScheduledFuture<?> deadlineCloser) {
            this.socket = socket; this.status = status; this.contentType = contentType;
            this.contentLength = contentLength; this.contentRange = contentRange; this.acceptsRanges = acceptsRanges;
            this.body = body; this.deadlineCloser = deadlineCloser;
        }

        public int status() { return status; }
        public String contentType() { return contentType; }
        public long contentLength() { return contentLength; }
        public String contentRange() { return contentRange; }
        public boolean acceptsRanges() { return acceptsRanges; }
        public InputStream body() { return body; }

        @Override public void close() {
            if (deadlineCloser != null) deadlineCloser.cancel(false);
            try { body.close(); } catch (IOException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    public boolean allowlistConfigured() { return policy.allowlistConfigured(); }

    /** Validates an HTTPS media URL against the allowlist. DNS is resolved and pinned later, at open(). */
    public Target resolve(String rawUrl) {
        if (!policy.allowlistConfigured())
            throw new ApiError(409, "VIDEO_ALLOWLIST_NOT_CONFIGURED",
                "未配置 APP_VIDEO_ALLOWED_HOSTS，视频媒体代理不可用；不会直连任意主机");
        if (rawUrl == null || rawUrl.length() > MAX_URL_LENGTH) throw ApiError.bad("视频地址无效或过长");
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException invalid) {
            throw ApiError.bad("视频地址格式无效");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()))
            throw new ApiError(409, "VIDEO_PROTOCOL_NOT_IMPLEMENTED", "只有 HTTPS MP4/HLS/JPEG 可通过浏览器代理播放");
        if (uri.getUserInfo() != null)
            throw ApiError.bad("视频地址不能内嵌账号密码；凭据必须单独写入");
        if (uri.getRawFragment() != null) throw ApiError.bad("视频地址不能包含片段");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw ApiError.bad("视频地址缺少主机");
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (isIpLiteral(host))
            throw new ApiError(409, "VIDEO_HOST_NOT_ALLOWED", "视频地址必须使用已登记的DNS主机名，不能使用IP字面量");
        if (!policy.hostAllowed(host))
            throw new ApiError(409, "VIDEO_HOST_NOT_ALLOWED", "视频主机不在部署允许列表中");
        if (uri.getPort() != -1 && uri.getPort() != 443)
            throw new ApiError(409, "VIDEO_HOST_NOT_ALLOWED", "视频代理只允许HTTPS默认端口");
        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/")) throw ApiError.bad("视频地址路径无效");
        return new Target(uri, host);
    }

    public Upstream open(Target target, String range, String authorization, long maxBytes) {
        long deadline = System.nanoTime() + EXCHANGE_DEADLINE_NANOS;
        Socket socket = new Socket();
        var deadlineCloser = DEADLINE_CLOSER.schedule(() -> closeQuietly(socket), EXCHANGE_DEADLINE_NANOS,
            java.util.concurrent.TimeUnit.NANOSECONDS);
        try {
            InetAddress address = pin(target.host());
            socket.connect(new InetSocketAddress(address, 443),
                Math.min(policy.connectTimeoutMs(), remainingMillis(deadline)));
            socket.setSoTimeout(Math.min(policy.readTimeoutMs(), remainingMillis(deadline)));
            socket.setTcpNoDelay(true);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tls = (SSLSocket) factory.createSocket(socket, target.host(), 443, true);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.setSoTimeout(Math.min(policy.readTimeoutMs(), remainingMillis(deadline)));
            tls.startHandshake();
            OutputStream out = tls.getOutputStream();
            out.write(request(target, range, authorization).getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = new DeadlineInputStream(
                new BufferedInputStream(tls.getInputStream(), 16384), tls, deadline, policy.readTimeoutMs());
            Response response = readResponse(in);
            if (response.status >= 300 && response.status < 400)
                throw new ApiError(502, "VIDEO_UPSTREAM_REDIRECT_REFUSED", "视频上游返回重定向；代理不跟随跳转");
            if (response.status == 401 || response.status == 403)
                throw new ApiError(502, "VIDEO_UPSTREAM_UNAUTHORIZED", "视频上游拒绝访问；请核对端点凭据");
            if (response.status >= 400)
                throw new ApiError(502, "VIDEO_UPSTREAM_HTTP_ERROR", "视频上游返回错误状态（" + response.status + "）");
            long length = response.contentLength;
            if (length > maxBytes)
                throw new ApiError(502, "VIDEO_UPSTREAM_TOO_LARGE", "视频上游内容超过大小上限");
            InputStream body;
            if (response.chunked) body = new ChunkedInputStream(in, maxBytes);
            else if (response.status == 204 || response.status == 304) body = InputStream.nullInputStream();
            else if (length >= 0) body = new BoundedInputStream(in, length);
            else body = new BoundedInputStream(in, maxBytes);
            String contentType = response.headers.get("content-type");
            return new Upstream(tls, response.status, contentType, length,
                response.headers.get("content-range"),
                "bytes".equalsIgnoreCase(response.headers.get("accept-ranges")), body, deadlineCloser);
        } catch (ApiError invalid) {
            deadlineCloser.cancel(false);
            closeQuietly(socket);
            throw invalid;
        } catch (IOException | ArithmeticException failure) {
            deadlineCloser.cancel(false);
            closeQuietly(socket);
            throw new ApiError(502, "VIDEO_UPSTREAM_UNREACHABLE", "视频上游连接、TLS或交换时限失败；未生成替代内容");
        }
    }

    public byte[] readBounded(Upstream upstream, long maximum) {
        try (InputStream in = upstream.body()) {
            var out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maximum) throw new ApiError(502, "VIDEO_UPSTREAM_TOO_LARGE", "视频上游响应超过大小上限");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException failure) {
            throw new ApiError(502, "VIDEO_UPSTREAM_READ_FAILED", "视频上游读取失败");
        }
    }

    public void stream(Upstream upstream, OutputStream out, long maximum) throws IOException {
        if (upstream.contentLength() > maximum)
            throw new ApiError(502, "VIDEO_UPSTREAM_TOO_LARGE", "视频上游内容超过大小上限");
        try (InputStream in = upstream.body()) {
            byte[] buffer = new byte[32768];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maximum) throw new ApiError(502, "VIDEO_UPSTREAM_TOO_LARGE", "视频上游内容超过大小上限");
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private InetAddress pin(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException failure) {
            throw new ApiError(502, "VIDEO_HOST_UNRESOLVED", "视频主机无法解析");
        }
        InetAddress chosen = null;
        for (InetAddress address : addresses) {
            if (address == null) continue;
            if (!publicAddress(address))
                throw new ApiError(409, "VIDEO_HOST_NOT_ALLOWED", "视频主机解析到非公网地址，已拒绝");
            if (chosen == null) chosen = address;
        }
        if (chosen == null) throw new ApiError(502, "VIDEO_HOST_UNRESOLVED", "视频主机没有可用地址");
        return chosen;
    }

    private static String request(Target target, String range, String authorization) {
        URI uri = target.uri();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();
        var request = new StringBuilder(256);
        request.append("GET ").append(path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(target.host()).append("\r\n");
        request.append("Connection: close\r\n");
        request.append("Accept: */*\r\n");
        request.append("User-Agent: fire-platform-video-proxy/1\r\n");
        if (range != null && !range.isBlank()) request.append("Range: ").append(range).append("\r\n");
        if (authorization != null) request.append("Authorization: ").append(authorization).append("\r\n");
        request.append("\r\n");
        return request.toString();
    }

    private record Response(int status, Map<String, String> headers, long contentLength, boolean chunked) { }

    private static Response readResponse(InputStream in) throws IOException {
        int received = 0;
        while (true) {
            String statusLine = readLine(in, MAX_LINE_BYTES);
            received += statusLine.length() + 2;
            if (received > MAX_HEADER_BYTES) throw new IOException("Response header block too large");
            if (!statusLine.startsWith("HTTP/")) throw new IOException("Invalid status line");
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2) throw new IOException("Invalid status line");
            int status;
            try { status = Integer.parseInt(parts[1]); } catch (NumberFormatException failure) { throw new IOException("Invalid status"); }
            var headers = new LinkedHashMap<String, String>();
            int count = 0;
            while (true) {
                String line = readLine(in, MAX_LINE_BYTES);
                received += line.length() + 2;
                if (received > MAX_HEADER_BYTES) throw new IOException("Response header block too large");
                if (line.isEmpty()) break;
                if (++count > MAX_HEADERS) throw new IOException("Too many response headers");
                int colon = line.indexOf(':');
                if (colon <= 0) throw new IOException("Invalid response header");
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                headers.merge(name, value, (left, right) -> left + "," + right);
            }
            if (status >= 100 && status < 200) continue;
            long length = -1;
            String declared = headers.get("content-length");
            if (declared != null) {
                try { length = Long.parseLong(declared.trim()); } catch (NumberFormatException ignored) { length = -1; }
                if (length < 0) throw new IOException("Invalid content length");
            }
            boolean chunked = headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT).contains("chunked");
            return new Response(status, headers, length, chunked);
        }
    }

    private static String readLine(InputStream in, int maximum) throws IOException {
        var line = new ByteArrayOutputStream(128);
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') length--;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            if (line.size() >= maximum) throw new IOException("Header line too long");
            line.write(read);
        }
        throw new EOFException("Response ended before headers completed");
    }

    private static int remainingMillis(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new java.net.SocketTimeoutException("HTTPS exchange deadline exceeded");
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE,
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining) + 1));
    }

    static final class DeadlineInputStream extends FilterInputStream {
        private final Socket socket;
        private final long deadline;
        private final int readTimeoutMs;

        DeadlineInputStream(InputStream in, Socket socket, long deadline, int readTimeoutMs) {
            super(in);
            this.socket = socket;
            this.deadline = deadline;
            this.readTimeoutMs = readTimeoutMs;
        }

        private void arm() throws IOException {
            socket.setSoTimeout(Math.min(readTimeoutMs, remainingMillis(deadline)));
        }

        @Override public int read() throws IOException {
            arm();
            return super.read();
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            arm();
            return super.read(buffer, offset, length);
        }
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0 || host.startsWith("[") || host.endsWith("]")) return true;
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** Rejects loopback/private/link-local/metadata/documentation ranges, including IPv4-mapped IPv6. */
    static boolean publicAddress(InetAddress address) {
        if (address == null) return false;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) return publicV4(b);
        if (b.length != 16) return false;
        boolean mapped = true;
        for (int index = 0; index < 10; index++) if (b[index] != 0) { mapped = false; break; }
        if (mapped && (b[10] & 0xFF) == 255 && (b[11] & 0xFF) == 255)
            return publicV4(new byte[]{b[12], b[13], b[14], b[15]});
        if ((b[0] & 0xFE) == 0xFC) return false;
        if ((b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) == 0x80) return false;
        if ((b[0] & 0xFF) == 0xFF) return false;
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) return false;
        return !((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02);
    }

    private static boolean publicV4(byte[] b) {
        int a = b[0] & 0xFF, c = b[1] & 0xFF, d = b[2] & 0xFF;
        if (a == 0 || a == 10 || a == 127) return false;
        if (a == 100 && c >= 64 && c <= 127) return false;
        if (a == 169 && c == 254) return false;
        if (a == 172 && c >= 16 && c <= 31) return false;
        if (a == 192 && c == 0 && (d == 0 || d == 2)) return false;
        if (a == 192 && c == 168) return false;
        if (a == 198 && (c == 18 || c == 19)) return false;
        if (a == 198 && c == 51 && d == 100) return false;
        if (a == 203 && c == 0 && d == 113) return false;
        return a < 224;
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        BoundedInputStream(InputStream in, long remaining) { super(in); this.remaining = remaining; }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value != -1) remaining--;
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }
        @Override public long skip(long count) throws IOException {
            long skipped = super.skip(Math.min(count, remaining));
            remaining -= skipped;
            return skipped;
        }
        @Override public int available() throws IOException { return (int) Math.min(super.available(), remaining); }
    }

    /** Decodes HTTP/1.1 chunked transfer while enforcing a hard total-byte limit. */
    private static final class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private final long maximum;
        private long total;
        private long chunkRemaining;
        private boolean first = true;
        private boolean finished;
        private int trailerBytes;
        private int trailers;

        ChunkedInputStream(InputStream in, long maximum) { this.in = in; this.maximum = maximum; }

        @Override public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read == -1 ? -1 : single[0] & 0xFF;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (finished) return -1;
            if (chunkRemaining == 0) {
                if (!first) expectCrlf();
                first = false;
                chunkRemaining = readChunkSize();
                if (chunkRemaining == 0) {
                    readTrailers();
                    finished = true;
                    return -1;
                }
            }
            int read = in.read(buffer, offset, (int) Math.min(length, chunkRemaining));
            if (read == -1) throw new EOFException("Chunked body ended early");
            chunkRemaining -= read;
            total += read;
            if (total > maximum) throw new IOException("Chunked body exceeds size limit");
            return read;
        }

        private long readChunkSize() throws IOException {
            String line = readLine(in, MAX_LINE_BYTES);
            int semicolon = line.indexOf(';');
            String size = (semicolon >= 0 ? line.substring(0, semicolon) : line).trim();
            try {
                long value = Long.parseLong(size, 16);
                if (value < 0) throw new IOException("Invalid chunk size");
                if (total + value > maximum) throw new IOException("Chunked body exceeds size limit");
                return value;
            } catch (NumberFormatException failure) {
                throw new IOException("Invalid chunk size");
            }
        }

        private void expectCrlf() throws IOException {
            int first = in.read();
            int second = in.read();
            if (first != '\r' || second != '\n') throw new IOException("Malformed chunk terminator");
        }

        private void readTrailers() throws IOException {
            while (true) {
                String line = readLine(in, MAX_LINE_BYTES);
                trailerBytes += line.length() + 2;
                if (trailerBytes > MAX_TRAILER_BYTES || (!line.isEmpty() && ++trailers > MAX_TRAILERS))
                    throw new IOException("Chunked trailers exceed limits");
                if (line.isEmpty()) return;
            }
        }
    }
}
