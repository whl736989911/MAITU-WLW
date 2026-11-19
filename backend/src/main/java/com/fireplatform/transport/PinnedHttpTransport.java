package com.fireplatform.transport;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Bounded HTTP/1.1 exchange for operator-approved origins. The host is resolved exactly once and the
 * socket is connected to that resolved address (DNS pinning); redirects are never followed, response
 * bytes and header counts are capped, and no request/response content is logged. Plain HTTP is only
 * possible when the caller has already recorded an explicit risk acknowledgement.
 */
public final class PinnedHttpTransport {
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_HEADER_LINE = 8192;
    private static final int MAX_HEADERS = 64;
    private static final int MAX_ADDRESSES = 8;
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern PATH = Pattern.compile("/[\\x21-\\x7e]{0,2048}");
    private static final int EXCHANGE_DEADLINE_MS = 35_000;
    private static final ThreadPoolExecutor EXCHANGES = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "legacy-http-exchange");
            thread.setDaemon(true);
            return thread;
        });

    private PinnedHttpTransport() { }

    public record Target(String scheme, String host, int port, boolean allowInsecureHttp) { }

    public record Response(int status, Map<String, List<String>> headers, byte[] body) {
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.getFirst();
        }
    }

    public static final class Failure extends RuntimeException {
        private final String code;
        public Failure(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    public static Response exchange(Target target, String method, String path, Map<String, String> headers, byte[] body) {
        return exchange(target, method, path, headers, body, EXCHANGE_DEADLINE_MS, PinnedHttpTransport::resolve);
    }

    @FunctionalInterface
    interface Resolver {
        InetAddress resolve(String host);
    }

    /** Package-local seam for deterministic deadline tests; public callers always use the checked resolver. */
    static Response exchange(Target target, String method, String path, Map<String, String> headers, byte[] body,
                             int timeoutMs, Resolver resolver) {
        if (timeoutMs < 1 || timeoutMs > EXCHANGE_DEADLINE_MS || resolver == null)
            throw new IllegalArgumentException("Invalid exchange deadline");
        if (target == null) throw new Failure("TARGET_MISSING", "目标未配置");
        String scheme = target.scheme() == null ? "" : target.scheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new Failure("SCHEME_UNSUPPORTED", "只支持已审批的HTTP/HTTPS来源");
        if (scheme.equals("http") && !target.allowInsecureHttp())
            throw new Failure("HTTP_NOT_ACKNOWLEDGED", "未记录明文HTTP风险确认，拒绝连接");
        String host = target.host() == null ? "" : target.host().trim();
        if (host.isEmpty() || host.length() > 253) throw new Failure("HOST_INVALID", "来源主机无效");
        int port = target.port();
        if (port < 1 || port > 65535) throw new Failure("PORT_INVALID", "来源端口无效");
        if (method == null || !method.matches("[A-Z]{3,8}")) throw new Failure("METHOD_INVALID", "请求方法无效");
        if (path == null || !PATH.matcher(path).matches() || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0)
            throw new Failure("PATH_INVALID", "请求路径无效");
        if (body != null && body.length > MAX_REQUEST_BYTES) throw new Failure("REQUEST_TOO_LARGE", "请求体超过限制");
        Map<String, String> requestHeaders = headers == null ? Map.of() : headers;
        for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
            if (header.getKey() == null || !HEADER_NAME.matcher(header.getKey()).matches())
                throw new Failure("HEADER_INVALID", "请求头名称无效");
            String value = header.getValue();
            if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.getBytes(StandardCharsets.ISO_8859_1).length > 4096)
                throw new Failure("HEADER_INVALID", "请求头值无效");
        }
        Socket socket = new Socket();
        Future<Response> future;
        try {
            future = EXCHANGES.submit(() -> perform(socket, scheme, host, port, method, path, requestHeaders, body, resolver));
        } catch (RejectedExecutionException busy) {
            try { socket.close(); } catch (IOException ignored) { }
            throw new Failure("NETWORK_BUSY", "旧平台网络交换繁忙，本次未建立连接");
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            throw new Failure("NETWORK_TIMEOUT", "来源网络交换超过总时限；已关闭连接，结果未确认");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Failure("NETWORK_INTERRUPTED", "来源网络交换被中断，结果未确认");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Failure known) throw known;
            if (failed.getCause() instanceof Error fatal) throw fatal;
            throw new Failure("NETWORK_ERROR", "来源网络请求未确认");
        } finally {
            future.cancel(true);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static Response perform(Socket socket, String scheme, String host, int port, String method, String path,
                                    Map<String, String> headers, byte[] body, Resolver resolver) {
        InetAddress address = resolver.resolve(host);
        try {
            socket.connect(new InetSocketAddress(address, port), 10_000);
            socket.setSoTimeout(25_000);
            socket.setTcpNoDelay(true);
            Socket connected = socket;
            if (scheme.equals("https")) {
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(socket, host, port, true);
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
                connected = ssl;
            }
            try (Socket active = connected) {
                writeRequest(active.getOutputStream(), host, port, scheme, method, path, headers, body);
                return readResponse(active.getInputStream());
            }
        } catch (Failure failure) {
            throw failure;
        } catch (IOException failure) {
            throw new Failure("NETWORK_ERROR", "来源网络请求未确认");
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static InetAddress resolve(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (IOException failure) {
            throw new Failure("DNS_FAILED", "来源主机无法解析");
        }
        if (addresses.length == 0 || addresses.length > MAX_ADDRESSES)
            throw new Failure("DNS_INVALID", "来源主机解析结果数量无效");
        for (InetAddress address : addresses) {
            if (isForbidden(address)) throw new Failure("DNS_FORBIDDEN_ADDRESS", "来源主机解析到非公网地址，拒绝连接");
        }
        return addresses[0];
    }

    private static boolean isForbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff, second = bytes[1] & 0xff, third = bytes[2] & 0xff;
            if (first == 0 || first == 10 || first == 127) return true;
            if (first == 100 && second >= 64 && second <= 127) return true;
            if (first == 169 && second == 254) return true;
            if (first == 172 && second >= 16 && second <= 31) return true;
            if (first == 192 && second == 168) return true;
            if (first == 192 && second == 0 && third == 0) return true;
            if (first == 198 && (second == 18 || second == 19)) return true;
            if (first == 198 && second == 51 && third == 100) return true;
            if (first == 203 && second == 0 && third == 113) return true;
            return first >= 224;
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return true;
            if (first == 0x20 && (bytes[1] & 0xff) == 0x01 && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8)
                return true;
            return first == 0xff;
        }
        return true;
    }

    private static void writeRequest(OutputStream out, String host, int port, String scheme, String method,
                                     String path, Map<String, String> headers, byte[] body) throws IOException {
        boolean defaultPort = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        StringBuilder head = new StringBuilder(512);
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(host).append(defaultPort ? "" : ":" + port).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("Accept-Encoding: identity\r\n");
        for (Map.Entry<String, String> header : headers.entrySet())
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        if (body != null) head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) out.write(body);
        out.flush();
    }

    private static Response readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) throw new Failure("RESPONSE_INVALID", "来源响应状态行无效");
        int status;
        try { status = Integer.parseInt(parts[1]); }
        catch (NumberFormatException invalid) { throw new Failure("RESPONSE_INVALID", "来源响应状态码无效"); }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int count = 0;
        while (true) {
            String line = readLine(in);
            if (line.isEmpty()) break;
            if (++count > MAX_HEADERS) throw new Failure("RESPONSE_HEADERS_TOO_LARGE", "来源响应头超过限制");
            int colon = line.indexOf(':');
            if (colon <= 0) throw new Failure("RESPONSE_INVALID", "来源响应头无效");
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!HEADER_NAME.matcher(name).matches()) throw new Failure("RESPONSE_INVALID", "来源响应头名称无效");
            headers.computeIfAbsent(name, key -> new ArrayList<>()).add(line.substring(colon + 1).trim());
        }
        byte[] body = readBody(in, headers);
        return new Response(status, headers, body);
    }

    private static byte[] readBody(InputStream in, Map<String, List<String>> headers) throws IOException {
        String transfer = first(headers, "transfer-encoding");
        if (transfer != null) {
            if (!transfer.toLowerCase(Locale.ROOT).contains("chunked"))
                throw new Failure("RESPONSE_ENCODING_UNSUPPORTED", "来源响应传输编码未支持");
            return readChunked(in);
        }
        String length = first(headers, "content-length");
        if (length != null) {
            long declared;
            try { declared = Long.parseLong(length); }
            catch (NumberFormatException invalid) { throw new Failure("RESPONSE_INVALID", "来源响应长度无效"); }
            if (declared < 0 || declared > MAX_RESPONSE_BYTES) throw new Failure("RESPONSE_TOO_LARGE", "来源响应超过限制");
            return readExactly(in, (int) declared);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (out.size() + read > MAX_RESPONSE_BYTES) throw new Failure("RESPONSE_TOO_LARGE", "来源响应超过限制");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in);
            int semicolon = line.indexOf(';');
            String sizeText = (semicolon >= 0 ? line.substring(0, semicolon) : line).trim();
            int size;
            try { size = Integer.parseInt(sizeText, 16); }
            catch (NumberFormatException invalid) { throw new Failure("RESPONSE_INVALID", "来源分块长度无效"); }
            if (size < 0 || size > MAX_RESPONSE_BYTES - out.size()) throw new Failure("RESPONSE_TOO_LARGE", "来源响应超过限制");
            if (size == 0) {
                int trailers = 0;
                while (!readLine(in).isEmpty()) {
                    if (++trailers > MAX_HEADERS) throw new Failure("RESPONSE_HEADERS_TOO_LARGE", "来源响应尾部超过限制");
                }
                return out.toByteArray();
            }
            out.write(readExactly(in, size));
            if (!readLine(in).isEmpty()) throw new Failure("RESPONSE_INVALID", "来源分块未以空行结束");
        }
    }

    private static byte[] readExactly(InputStream in, int size) throws IOException {
        byte[] data = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = in.read(data, offset, size - offset);
            if (read < 0) throw new EOFException("来源响应提前结束");
            offset += read;
        }
        return data;
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        while (true) {
            int value = in.read();
            if (value < 0) {
                if (out.size() == 0) throw new EOFException("来源响应提前结束");
                break;
            }
            if (value == '\n') break;
            if (value == '\r') continue;
            if (out.size() >= MAX_HEADER_LINE) throw new Failure("RESPONSE_HEADER_TOO_LARGE", "来源响应行超过限制");
            out.write(value);
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }
}
