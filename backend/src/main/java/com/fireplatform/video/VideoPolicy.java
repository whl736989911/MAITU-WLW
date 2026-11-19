package com.fireplatform.video;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Deployment-owned video transport policy. An empty allowlist or missing key makes media explicitly
 * unavailable; there is no fallback to unverified hosts or unencrypted storage.
 */
@Component
public class VideoPolicy {
    private final Set<String> allowedHosts;
    private final boolean cookieSecure;
    private final Duration sessionTtl;
    private final long maxPrimaryBytes;
    private final long maxSubresourceBytes;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public VideoPolicy(Environment environment) {
        String hosts = value(environment, "app.video.allowed-hosts", "APP_VIDEO_ALLOWED_HOSTS");
        var parsed = new LinkedHashSet<String>();
        if (hosts != null) {
            for (String item : hosts.split(",")) {
                String host = item.trim().toLowerCase(Locale.ROOT);
                if (host.isEmpty()) continue;
                if (host.contains("/") || host.contains(":") || host.startsWith(".")
                    || !host.matches("[a-z0-9]([a-z0-9.-]{0,253}[a-z0-9])?"))
                    throw new IllegalStateException("APP_VIDEO_ALLOWED_HOSTS must contain plain DNS host names");
                parsed.add(host);
            }
        }
        this.allowedHosts = Set.copyOf(parsed);
        this.cookieSecure = bool(environment, true, "app.video.cookie-secure", "APP_VIDEO_COOKIE_SECURE");
        this.sessionTtl = Duration.ofSeconds(seconds(environment, 900, 60, 3600,
            "app.video.session-seconds", "APP_VIDEO_SESSION_SECONDS"));
        this.maxPrimaryBytes = bytes(environment, 134_217_728L, "app.video.max-primary-bytes", "APP_VIDEO_MAX_PRIMARY_BYTES");
        this.maxSubresourceBytes = bytes(environment, 33_554_432L, "app.video.max-subresource-bytes", "APP_VIDEO_MAX_SUBRESOURCE_BYTES");
        this.connectTimeoutMs = (int) seconds(environment, 5, 1, 30, "app.video.connect-timeout-seconds",
            "APP_VIDEO_CONNECT_TIMEOUT_SECONDS") * 1000;
        this.readTimeoutMs = (int) seconds(environment, 15, 1, 120, "app.video.read-timeout-seconds",
            "APP_VIDEO_READ_TIMEOUT_SECONDS") * 1000;
    }

    public Set<String> allowedHosts() { return allowedHosts; }
    public boolean allowlistConfigured() { return !allowedHosts.isEmpty(); }
    public boolean hostAllowed(String host) { return host != null && allowedHosts.contains(host.toLowerCase(Locale.ROOT)); }
    public boolean cookieSecure() { return cookieSecure; }
    public Duration sessionTtl() { return sessionTtl; }
    public long maxPrimaryBytes() { return maxPrimaryBytes; }
    public long maxSubresourceBytes() { return maxSubresourceBytes; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int readTimeoutMs() { return readTimeoutMs; }

    private static String value(Environment environment, String property, String environmentName) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) value = environment.getProperty(environmentName);
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean bool(Environment environment, boolean fallback, String property, String environmentName) {
        String value = value(environment, property, environmentName);
        if (value == null) return fallback;
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalStateException(property + " must be true or false");
        return Boolean.parseBoolean(value);
    }

    private static long seconds(Environment environment, long fallback, long minimum, long maximum,
                                String property, String environmentName) {
        String value = value(environment, property, environmentName);
        if (value == null) return fallback;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < minimum || parsed > maximum) throw new IllegalStateException(property + " is out of range");
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalStateException(property + " must be an integer");
        }
    }

    private static long bytes(Environment environment, long fallback, String property, String environmentName) {
        return seconds(environment, fallback, 1024, 1_073_741_824L, property, environmentName);
    }
}
