package com.selfanalyst.events;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

final class LocalRequestGuard {
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    private LocalRequestGuard() {
    }

    static boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isBlank() || "null".equals(origin)) {
            return false;
        }
        try {
            URI uri = new URI(origin);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getUserInfo() == null
                    && isLoopbackHost(uri.getHost())
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && (uri.getPath().isEmpty() || "/".equals(uri.getPath()));
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return false;
        }
    }

    static boolean isAllowedHost(String hostHeader, int serverPort) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI("http://" + hostHeader);
            int suppliedPort = uri.getPort();
            boolean portMatches = suppliedPort == serverPort || (suppliedPort == -1 && serverPort == 80);
            return uri.getUserInfo() == null
                    && isLoopbackHost(uri.getHost())
                    && portMatches
                    && (uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return false;
        }
    }

    static boolean isProtectedDesktopPath(String path) {
        return path != null && path.startsWith("/desktop/") && !"/desktop/session".equals(path);
    }

    static boolean isSensitiveRawPath(String path) {
        return "/desktop/raw-events".equals(path)
                || (path != null && path.startsWith("/desktop/raw-"));
    }

    static boolean hasConfiguredDesktopToken(String configuredToken) {
        return configuredToken != null && !configuredToken.isBlank();
    }

    static boolean hasDesktopCredential(String configuredToken, String headerToken, String cookieToken) {
        if (configuredToken == null || configuredToken.isBlank()) {
            return true;
        }
        return tokenEquals(configuredToken, headerToken) || tokenEquals(configuredToken, cookieToken);
    }

    private static boolean tokenEquals(String expected, String supplied) {
        return supplied != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return LOOPBACK_HOSTS.contains(normalized);
    }
}
