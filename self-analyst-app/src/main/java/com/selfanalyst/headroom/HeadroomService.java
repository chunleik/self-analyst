package com.selfanalyst.headroom;

import com.selfanalyst.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Optional Headroom proxy integration. The service only resolves routing and safe
 * diagnostics; it never receives prompt bodies or user activity content.
 */
public final class HeadroomService {

    private static final Logger log = LoggerFactory.getLogger(HeadroomService.class);
    private static final Duration HEALTH_TIMEOUT = Duration.ofMillis(1500);

    public interface Probe {
        ProbeResult check(URI proxyUri, Duration timeout);
    }

    public record ProbeResult(boolean ok, String message) {
        public static ProbeResult ok(String message) {
            return new ProbeResult(true, message);
        }

        public static ProbeResult fail(String message) {
            return new ProbeResult(false, message);
        }
    }

    public record Snapshot(
            String status,
            boolean enabled,
            String proxyUrl,
            String originalBaseUrl,
            String effectiveBaseUrl,
            String lastError,
            boolean statsEnabled,
            boolean outputShaper,
            String statsStatus
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status);
            m.put("enabled", enabled);
            m.put("proxyUrl", proxyUrl);
            m.put("originalBaseUrl", originalBaseUrl);
            m.put("effectiveBaseUrl", effectiveBaseUrl);
            m.put("lastError", lastError);
            m.put("statsEnabled", statsEnabled);
            m.put("outputShaper", outputShaper);
            m.put("statsStatus", statsStatus);
            return m;
        }
    }

    private final boolean enabled;
    private final String proxyUrl;
    private final String originalBaseUrl;
    private final boolean statsEnabled;
    private final boolean outputShaper;
    private final Probe probe;
    private volatile Snapshot snapshot;

    public static HeadroomService fromConfig(Config config) {
        Objects.requireNonNull(config, "config");
        return new HeadroomService(
                config.headroomEnabled(),
                config.headroomProxyUrl(),
                config.llmBaseUrl(),
                config.headroomStatsEnabled(),
                config.headroomOutputShaper(),
                HeadroomService::defaultProbe);
    }

    public HeadroomService(boolean enabled,
                           String proxyUrl,
                           String originalBaseUrl,
                           boolean statsEnabled,
                           boolean outputShaper,
                           Probe probe) {
        this.enabled = enabled;
        this.proxyUrl = safeString(proxyUrl);
        this.originalBaseUrl = safeString(originalBaseUrl);
        this.statsEnabled = statsEnabled;
        this.outputShaper = outputShaper;
        this.probe = probe != null ? probe : HeadroomService::defaultProbe;
        this.snapshot = resolve();
    }

    public Snapshot refresh() {
        Snapshot fresh = resolve();
        this.snapshot = fresh;
        return fresh;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public String effectiveLlmBaseUrl() {
        return snapshot.effectiveBaseUrl();
    }

    public String runtimeStatusLine() {
        Snapshot s = snapshot();
        if (!s.enabled()) {
            return "disabled";
        }
        String msg = s.status();
        if (s.lastError() != null && !s.lastError().isBlank()) {
            msg += " (" + s.lastError() + ")";
        }
        return msg;
    }

    private Snapshot resolve() {
        if (!enabled) {
            return new Snapshot("disabled", false, proxyUrl, originalBaseUrl, originalBaseUrl,
                    "", statsEnabled, outputShaper, "disabled");
        }
        URI uri;
        try {
            uri = URI.create(proxyUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("missing scheme or host");
            }
        } catch (RuntimeException e) {
            String msg = "Invalid Headroom proxy URL: " + e.getMessage();
            log.warn("Headroom unavailable: {}", msg);
            return new Snapshot("unavailable", true, proxyUrl, originalBaseUrl, originalBaseUrl,
                    msg, statsEnabled, outputShaper, statsEnabled ? "unavailable" : "disabled");
        }

        ProbeResult result;
        try {
            result = probe.check(uri, HEALTH_TIMEOUT);
        } catch (RuntimeException e) {
            result = ProbeResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (result.ok()) {
            return new Snapshot("available", true, proxyUrl, originalBaseUrl, proxyUrl,
                    "", statsEnabled, outputShaper, statsEnabled ? "unknown" : "disabled");
        }
        String msg = result.message() == null || result.message().isBlank()
                ? "Headroom proxy is not reachable"
                : result.message();
        log.warn("Headroom proxy unavailable; falling back to llm.base-url: {}", msg);
        return new Snapshot("fallback", true, proxyUrl, originalBaseUrl, originalBaseUrl,
                msg, statsEnabled, outputShaper, statsEnabled ? "unavailable" : "disabled");
    }

    private static ProbeResult defaultProbe(URI proxyUri, Duration timeout) {
        try {
            URI root = rootUri(proxyUri);
            HttpRequest req = HttpRequest.newBuilder(root)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .build();
            HttpResponse<Void> response = client.send(req, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 100 && status < 500) {
                return ProbeResult.ok("HTTP " + status);
            }
            return ProbeResult.fail("HTTP " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.fail("interrupted");
        } catch (Exception e) {
            return ProbeResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static URI rootUriForTest(URI proxyUri) {
        return rootUri(proxyUri);
    }

    private static URI rootUri(URI proxyUri) {
        try {
            return new URI(proxyUri.getScheme(), null, proxyUri.getHost(), proxyUri.getPort(), "/", null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid proxy root URI", e);
        }
    }

    private static String safeString(String value) {
        return value == null ? "" : value.trim();
    }
}
