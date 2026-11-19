package com.fireplatform.transport;

import java.net.URI;

/** Validates provider endpoints without coupling domain services to URI APIs. */
public final class NotificationEndpoint {
    private NotificationEndpoint() { }

    public static String httpsOrDefault(String value, String fallback) {
        String endpoint = value == null || value.isBlank() ? fallback : value.trim();
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Invalid HTTPS endpoint");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid HTTPS endpoint", invalid);
        }
        return endpoint;
    }

    /**
     * Normalizes one absolute HTTPS callback URL that must use exactly {@code fixedPath}, with no query,
     * fragment or user info. Provider modules call this instead of importing URI APIs themselves.
     */
    public static String fixedHttpsPath(String value, String fixedPath) {
        String text = value == null ? "" : value.trim();
        try {
            URI uri = URI.create(text);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null
                || !fixedPath.equals(uri.getPath()))
                throw new IllegalArgumentException("Invalid fixed HTTPS path");
            return uri.toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid fixed HTTPS path", invalid);
        }
    }
}
