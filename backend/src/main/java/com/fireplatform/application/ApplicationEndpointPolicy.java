package com.fireplatform.application;

import com.fireplatform.shared.ApiError;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Destination policy for outbound application data. HTTPS is allowed only on the default TLS port; MQTT is allowed
 * only as {@code mqtts://host:8883}. Both use the same explicit deployment host allowlist, and credentials in the
 * URL, fragments, queries, redirects and any private, loopback, link-local or carrier-grade NAT destination are
 * refused. The allowlist is deployment configuration, never a request field, and it is re-evaluated on every send
 * (resolution included) so an allowlisted name that starts resolving to a private address is blocked instead of
 * contacted.
 */
@Service
public final class ApplicationEndpointPolicy {
    public static final int MAX_URL_LENGTH = 1000;
    private static final Pattern HOST =
        Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");
    private static final Pattern IPV4 = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");

    public record Validated(String url, String origin, String digest, String masked, String host) { }

    /** MQTT target: mqtts:// only, the fixed TLS port, the same deployment host allowlist, and a frozen topic digest. */
    public record MqttValidated(String url, String origin, String digest, String masked, String host) { }

    private final Set<String> allowedHosts;

    public ApplicationEndpointPolicy(Environment environment) {
        String configured = environment.getProperty("app.application-output-allowed-hosts",
            environment.getProperty("APP_APPLICATION_OUTPUT_ALLOWED_HOSTS", ""));
        var hosts = new LinkedHashSet<String>();
        for (String part : configured.split(",")) {
            String host = part.trim().toLowerCase(Locale.ROOT);
            if (!host.isEmpty()) hosts.add(host);
        }
        this.allowedHosts = Set.copyOf(hosts);
    }

    public boolean allowlistConfigured() { return !allowedHosts.isEmpty(); }

    /** Validation is local only: no DNS or network call happens inside a write transaction. */
    public Validated validate(String rawUrl) {
        if (rawUrl == null) throw ApiError.bad("输出地址不能为空");
        String value = rawUrl.trim();
        if (value.isEmpty() || value.length() > MAX_URL_LENGTH) throw ApiError.bad("输出地址为空或超过1000字符");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x20 || character == 0x7f) throw ApiError.bad("输出地址包含空白或控制字符");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw ApiError.bad("输出地址不是合法URI");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new ApiError(400, "ENDPOINT_NOT_HTTPS", "输出地址必须使用HTTPS");
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new ApiError(400, "ENDPOINT_HOST_INVALID", "输出地址缺少有效主机名");
        if (uri.getUserInfo() != null) throw new ApiError(400, "ENDPOINT_USERINFO_FORBIDDEN", "输出地址禁止内嵌用户名或密码");
        if (uri.getFragment() != null) throw new ApiError(400, "ENDPOINT_FRAGMENT_FORBIDDEN", "输出地址禁止使用片段");
        if (uri.getPort() != -1 && uri.getPort() != 443) throw new ApiError(400, "ENDPOINT_PORT_FORBIDDEN", "输出地址只允许默认TLS端口443");
        if (uri.getPath() == null || !uri.getPath().startsWith("/"))
            throw new ApiError(400, "ENDPOINT_PATH_INVALID", "输出地址必须包含绝对路径");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        assertAllowedHost(host);
        String origin = "https://" + host;
        return new Validated(value, origin, sha256(value), mask(uri), host);
    }

    /**
     * MQTT validation is local only. Only {@code mqtts://host} (optionally with the default TLS port) is accepted:
     * no path receives data, no query/credentials/fragment is allowed, and the port is fixed rather than operator
     * selectable. The digest covers protocol + normalized URL + topic so a topic change invalidates captured rows.
     */
    public MqttValidated validateMqtt(String rawUrl, String topic) {
        if (rawUrl == null) throw ApiError.bad("MQTT地址不能为空");
        String value = rawUrl.trim();
        if (value.isEmpty() || value.length() > MAX_URL_LENGTH) throw ApiError.bad("MQTT地址为空或超过1000字符");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x20 || character == 0x7f) throw ApiError.bad("MQTT地址包含空白或控制字符");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw ApiError.bad("MQTT地址不是合法URI");
        }
        if (!"mqtts".equalsIgnoreCase(uri.getScheme()))
            throw new ApiError(400, "ENDPOINT_NOT_MQTTS", "MQTT输出必须使用mqtts://（TLS）");
        if (uri.getHost() == null || uri.getHost().isBlank())
            throw new ApiError(400, "ENDPOINT_HOST_INVALID", "MQTT地址缺少有效主机名");
        if (uri.getUserInfo() != null)
            throw new ApiError(400, "ENDPOINT_USERINFO_FORBIDDEN", "MQTT地址禁止内嵌用户名或密码");
        if (uri.getFragment() != null)
            throw new ApiError(400, "ENDPOINT_FRAGMENT_FORBIDDEN", "MQTT地址禁止使用片段");
        if (uri.getQuery() != null)
            throw new ApiError(400, "ENDPOINT_QUERY_FORBIDDEN", "MQTT地址禁止使用查询参数");
        if (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
            throw new ApiError(400, "ENDPOINT_PATH_FORBIDDEN", "MQTT地址只包含主机与TLS端口；主题单独配置");
        if (uri.getPort() != -1 && uri.getPort() != com.fireplatform.transport.ApplicationMqttTransport.TLS_PORT)
            throw new ApiError(400, "ENDPOINT_PORT_FORBIDDEN", "MQTT只允许TLS端口8883");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        assertAllowedHost(host);
        if (host.length() > 230)
            throw new ApiError(400, "ENDPOINT_HOST_INVALID", "MQTT主机名过长，无法保存冻结源");
        String normalized = "mqtts://" + host + ":" + com.fireplatform.transport.ApplicationMqttTransport.TLS_PORT;
        String validatedTopic;
        try {
            validatedTopic = com.fireplatform.transport.ApplicationMqttTransport.topic(topic);
        } catch (IllegalArgumentException invalid) {
            throw new ApiError(400, "ENDPOINT_TOPIC_INVALID",
                "MQTT主题无效：必须是固定主题，不能包含通配符、空层级、控制字符或以$开头，且不超过255字节");
        }
        return new MqttValidated(normalized, normalized, mqttDigest(normalized, validatedTopic),
            normalized + "/***", host);
    }

    /** Frozen MQTT digest: protocol + normalized target + exact topic. HTTPS keeps its historical URL digest. */
    public static String mqttDigest(String normalizedUrl, String topic) {
        return sha256("MQTT_TLS_3_1_1\n" + normalizedUrl + "\n" + topic);
    }

    private void assertAllowedHost(String host) {
        if (host.length() > 253 || !HOST.matcher(host).matches())
            throw new ApiError(400, "ENDPOINT_HOST_INVALID", "输出地址主机名无效");
        if (IPV4.matcher(host).matches() && !publicAddress(address(host)))
            throw new ApiError(400, "ENDPOINT_PRIVATE_ADDRESS", "输出地址主机为私有或保留地址");
        if (!allowlistConfigured()) throw new ApiError(409, "ENDPOINT_ALLOWLIST_NOT_CONFIGURED",
            "未配置 APP_APPLICATION_OUTPUT_ALLOWED_HOSTS，禁止保存任何输出地址");
        if (!allowedHosts.contains(host)) throw new ApiError(403, "ENDPOINT_HOST_NOT_ALLOWED",
            "输出地址主机不在部署允许清单内");
    }

    /**
     * Sending-path resolution: resolve once, require every answer to be public, and return the exact address the
     * transport must connect to. The transport pins that address while TLS keeps SNI and hostname verification for
     * the original host, so a second resolution can never rebind the destination. Runs outside any transaction.
     */
    public InetAddress pin(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException unresolved) {
            throw new ApiError(409, "ENDPOINT_UNRESOLVED", "输出地址主机无法解析，未发起外呼");
        }
        InetAddress chosen = null;
        for (InetAddress address : addresses) {
            if (address == null) continue;
            if (!publicAddress(address)) throw new ApiError(409, "ENDPOINT_PRIVATE_ADDRESS",
                "输出地址解析到非公网地址，未发起外呼");
            if (chosen == null) chosen = address;
        }
        if (chosen == null) throw new ApiError(409, "ENDPOINT_UNRESOLVED", "输出地址主机没有可用地址，未发起外呼");
        return chosen;
    }

    public static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff, second = bytes[1] & 0xff;
            if (first == 0 || first == 10 || first == 127) return false;
            if (first == 169 && second == 254) return false;
            if (first == 172 && second >= 16 && second <= 31) return false;
            if (first == 192 && second == 168) return false;
            if (first == 100 && second >= 64 && second <= 127) return false;
            if (first == 192 && second == 0 && (bytes[2] & 0xff) == 0) return false;
            if (first == 198 && (second == 18 || second == 19)) return false;
            if (first >= 240) return false;
            return true;
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff, second = bytes[1] & 0xff;
            if ((first & 0xfe) == 0xfc) return false;                       // unique local fc00::/7
            if (first == 0xfe && (second & 0xc0) == 0x80) return false;     // link local fe80::/10
            if (first == 0x20 && second == 0x01
                && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) return false; // documentation 2001:db8::/32
            return true;
        }
        return false;
    }

    /** Numeric dotted-quad parsing only; a host name never reaches {@link InetAddress#getByName} here. */
    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException invalid) {
            throw new ApiError(400, "ENDPOINT_HOST_INVALID", "输出地址主机名无效");
        }
    }

    private static String mask(URI uri) {
        StringBuilder masked = new StringBuilder("https://").append(uri.getHost().toLowerCase(Locale.ROOT));
        String[] segments = uri.getPath().split("/", -1);
        java.util.List<String> visible = Arrays.stream(segments).filter(segment -> !segment.isEmpty()).toList();
        if (visible.isEmpty()) masked.append('/');
        else {
            masked.append('/').append(visible.getFirst());
            if (visible.size() > 1) masked.append("/***");
        }
        if (uri.getQuery() != null) masked.append("?***");
        return masked.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
