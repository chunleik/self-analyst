package com.selfanalyst.headroom;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    public interface StatsReader {
        StatsResult read(URI proxyUri, Duration timeout);
    }

    public record StatsResult(boolean ok, String message, Map<String, Object> values) {
        public static StatsResult ok(Map<String, Object> values) {
            return new StatsResult(true, "", values == null ? Map.of() : new LinkedHashMap<>(values));
        }

        public static StatsResult unavailable(String message) {
            return new StatsResult(false, message, Map.of());
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
            String statsStatus,
            Double savingsPercent,
            Long originalInputTokens,
            Long compressedInputTokens,
            Long requestCount,
            String statsUpdatedAt
    ) {
        public Snapshot(String status,
                        boolean enabled,
                        String proxyUrl,
                        String originalBaseUrl,
                        String effectiveBaseUrl,
                        String lastError,
                        boolean statsEnabled,
                        boolean outputShaper,
                        String statsStatus) {
            this(status, enabled, proxyUrl, originalBaseUrl, effectiveBaseUrl, lastError,
                    statsEnabled, outputShaper, statsStatus, null, null, null, null, null);
        }

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
            m.put("savingsPercent", savingsPercent);
            m.put("originalInputTokens", originalInputTokens);
            m.put("compressedInputTokens", compressedInputTokens);
            m.put("requestCount", requestCount);
            m.put("statsUpdatedAt", statsUpdatedAt);
            return m;
        }
    }

    private record StatsSnapshot(
            String status,
            Double savingsPercent,
            Long originalInputTokens,
            Long compressedInputTokens,
            Long requestCount,
            String updatedAt
    ) {
        static StatsSnapshot disabled() {
            return new StatsSnapshot("disabled", null, null, null, null, null);
        }

        static StatsSnapshot unavailable() {
            return new StatsSnapshot("unavailable", null, null, null, null, null);
        }

        static StatsSnapshot available(Map<String, Object> values) {
            return new StatsSnapshot(
                    "available",
                    doubleValue(values.get("savingsPercent")),
                    longValue(values.get("originalInputTokens")),
                    longValue(values.get("compressedInputTokens")),
                    longValue(values.get("requestCount")),
                    stringValue(values.get("statsUpdatedAt")));
        }
    }

    private final boolean enabled;
    private final String proxyUrl;
    private final String originalBaseUrl;
    private final boolean statsEnabled;
    private final boolean outputShaper;
    private final Probe probe;
    private final StatsReader statsReader;
    private volatile Snapshot snapshot;

    public static HeadroomService fromConfig(Config config) {
        Objects.requireNonNull(config, "config");
        return new HeadroomService(
                config.headroomEnabled(),
                config.headroomProxyUrl(),
                config.llmBaseUrl(),
                config.headroomStatsEnabled(),
                config.headroomOutputShaper(),
                HeadroomService::defaultProbe,
                HeadroomService::defaultStatsReader);
    }

    public HeadroomService(boolean enabled,
                           String proxyUrl,
                           String originalBaseUrl,
                           boolean statsEnabled,
                           boolean outputShaper,
                           Probe probe) {
        this(enabled, proxyUrl, originalBaseUrl, statsEnabled, outputShaper,
                probe, HeadroomService::defaultStatsReader);
    }

    public HeadroomService(boolean enabled,
                           String proxyUrl,
                           String originalBaseUrl,
                           boolean statsEnabled,
                           boolean outputShaper,
                           Probe probe,
                           StatsReader statsReader) {
        this.enabled = enabled;
        this.proxyUrl = safeString(proxyUrl);
        this.originalBaseUrl = safeString(originalBaseUrl);
        this.statsEnabled = statsEnabled;
        this.outputShaper = outputShaper;
        this.probe = probe != null ? probe : HeadroomService::defaultProbe;
        this.statsReader = statsReader != null ? statsReader : HeadroomService::defaultStatsReader;
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
            return snapshotWithStats("disabled", false, originalBaseUrl, "", StatsSnapshot.disabled());
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
            return snapshotWithStats("unavailable", true, originalBaseUrl, msg,
                    statsEnabled ? StatsSnapshot.unavailable() : StatsSnapshot.disabled());
        }

        ProbeResult result;
        try {
            result = probe.check(uri, HEALTH_TIMEOUT);
        } catch (RuntimeException e) {
            result = ProbeResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (result.ok()) {
            return snapshotWithStats("available", true, proxyUrl, "", readStats(uri));
        }
        String msg = result.message() == null || result.message().isBlank()
                ? "Headroom proxy is not reachable"
                : result.message();
        log.warn("Headroom proxy unavailable; falling back to llm.base-url: {}", msg);
        return snapshotWithStats("fallback", true, originalBaseUrl, msg,
                statsEnabled ? StatsSnapshot.unavailable() : StatsSnapshot.disabled());
    }

    private Snapshot snapshotWithStats(String status,
                                       boolean enabled,
                                       String effectiveBaseUrl,
                                       String lastError,
                                       StatsSnapshot stats) {
        return new Snapshot(status, enabled, proxyUrl, originalBaseUrl, effectiveBaseUrl,
                lastError, statsEnabled, outputShaper, stats.status(),
                stats.savingsPercent(), stats.originalInputTokens(), stats.compressedInputTokens(),
                stats.requestCount(), stats.updatedAt());
    }

    private StatsSnapshot readStats(URI uri) {
        if (!statsEnabled) {
            return StatsSnapshot.disabled();
        }
        try {
            StatsResult result = statsReader.read(uri, HEALTH_TIMEOUT);
            if (result != null && result.ok()) {
                return StatsSnapshot.available(result.values() == null ? Map.of() : result.values());
            }
        } catch (RuntimeException e) {
            log.debug("Headroom stats unavailable: {}", e.getMessage());
        }
        return StatsSnapshot.unavailable();
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

    private static StatsResult defaultStatsReader(URI proxyUri, Duration timeout) {
        try {
            URI statsUri = statsUri(proxyUri);
            HttpRequest req = HttpRequest.newBuilder(statsUri)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .build();
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                return StatsResult.unavailable("HTTP " + status);
            }
            Map<String, Object> values = MAPPER.readValue(response.body(), new TypeReference<>() {});
            return StatsResult.ok(values);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StatsResult.unavailable("interrupted");
        } catch (Exception e) {
            return StatsResult.unavailable(e.getClass().getSimpleName() + ": " + e.getMessage());
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

    private static URI statsUri(URI proxyUri) {
        try {
            return new URI(proxyUri.getScheme(), null, proxyUri.getHost(), proxyUri.getPort(), "/stats", null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid proxy stats URI", e);
        }
    }

    private static String safeString(String value) {
        return value == null ? "" : value.trim();
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
