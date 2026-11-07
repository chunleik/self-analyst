package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.Config;
import com.selfanalyst.config.ConfigApplicationService;
import com.selfanalyst.events.store.EventStore;
import io.javalin.http.Context;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** Local privacy settings; every save uses the shared atomic configuration commit. */
public final class DesktopPrivacyController {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> VALIDATION_ERRORS = Set.of("invalidPayload", "invalidApp", "invalidMode", "invalidSite");
    private final ConfigApplicationService configuration;
    private final EventStore events;
    private final String hostname;
    private final Clock clock;

    public DesktopPrivacyController(ConfigApplicationService configuration, EventStore events) {
        this(configuration, events, hostname(), Clock.systemUTC());
    }

    DesktopPrivacyController(ConfigApplicationService configuration, EventStore events, String hostname, Clock clock) {
        this.configuration = Objects.requireNonNull(configuration);
        this.events = events;
        this.hostname = hostname;
        this.clock = clock;
    }

    public void getPrivacy(Context ctx) {
        try { ctx.json(payload()); }
        catch (RuntimeException failure) { ctx.status(500).json(Map.of("errorCode", "unavailable")); }
    }

    public void putPrivacy(Context ctx) {
        try {
            ctx.json(update(JSON.readTree(ctx.body())));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            ctx.status(400).json(Map.of("errorCode", "invalidPayload"));
        } catch (IllegalArgumentException invalid) {
            String code = VALIDATION_ERRORS.contains(invalid.getMessage()) ? invalid.getMessage() : "invalidPayload";
            ctx.status(400).json(Map.of("errorCode", code));
        } catch (IOException | RuntimeException failure) {
            ctx.status(500).json(Map.of("errorCode", "saveFailed"));
        }
    }

    Map<String, Object> payload() {
        Config saved = configuration.saved().config();
        Map<String, AppRule> apps = new LinkedHashMap<>();
        split(saved.wikiExcludeApps()).forEach(app -> addRule(apps, app, "model"));
        split(saved.collectionExcludeApps()).forEach(app -> addRule(apps, app, "capture"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apps", apps.values().stream().map(rule -> Map.of("app", rule.app(), "mode", rule.mode())).toList());
        result.put("sites", unique(split(saved.wikiExcludeSites())));
        addCandidates(result, apps.keySet());
        return result;
    }

    Map<String, Object> update(JsonNode request) throws IOException {
        if (request == null || !request.isObject() || !request.path("apps").isArray() || !request.path("sites").isArray()) {
            throw new IllegalArgumentException("invalidPayload");
        }
        request.fieldNames().forEachRemaining(key -> {
            if (!Set.of("apps", "sites").contains(key)) throw new IllegalArgumentException("invalidPayload");
        });
        Map<String, AppRule> apps = new LinkedHashMap<>();
        for (JsonNode item : request.path("apps")) {
            if (!item.isObject()) throw new IllegalArgumentException("invalidApp");
            String app = listItem(item.path("app"), "invalidApp");
            String mode = item.path("mode").asText("");
            if (!Set.of("capture", "model").contains(mode)) throw new IllegalArgumentException("invalidMode");
            addRule(apps, app, mode);
        }
        List<String> sites = new ArrayList<>();
        for (JsonNode item : request.path("sites")) sites.add(listItem(item, "invalidSite"));
        Map<String, String> changes = Map.of(
                "events.collection.excludeApps", joinApps(apps, "capture"),
                "wiki.privacy.excludeApps", joinApps(apps, "model"),
                "wiki.privacy.excludeSites", String.join(",", unique(sites)));
        synchronized (configuration.commitLock()) {
            var saved = configuration.update(changes);
            Map<String, Object> result = payload();
            result.putAll(saved.payload());
            result.put("applied", true);
            return result;
        }
    }

    private void addCandidates(Map<String, Object> payload, Set<String> listed) {
        payload.put("candidates", List.of());
        if (events == null) { payload.put("candidatesError", "unavailable"); return; }
        try {
            var end = clock.instant();
            var start = end.minus(Duration.ofDays(30));
            String bucket = "watcher-window_" + hostname;
            var ranges = events.queryIntersecting(List.of(bucket, "aw-" + bucket), start, end);
            var range = ranges.get(bucket);
            if (range == null || "missing".equals(range.status())) range = ranges.get("aw-" + bucket);
            if (range == null || "missing".equals(range.status())) return;
            if ("failed".equals(range.status())) { payload.put("candidatesError", "unavailable"); return; }
            if (!"complete".equals(range.status())) payload.put("candidatesError", "partial");
            Map<String, Candidate> candidates = new LinkedHashMap<>();
            for (var event : range.events()) {
                if (!(event.data().get("app") instanceof String raw)) continue;
                String app = raw.strip(), key = key(app);
                if (app.isEmpty() || "unknown".equals(key) || listed.contains(key)) continue;
                if (!Double.isFinite(event.duration()) || event.duration() <= 0) continue;
                var eventEnd = com.selfanalyst.events.statistics.ActivityStatistics.end(event);
                var clippedStart = event.timestamp().isBefore(start) ? start : event.timestamp();
                var clippedEnd = eventEnd.isAfter(end) ? end : eventEnd;
                double seconds = Duration.between(clippedStart, clippedEnd).toNanos() / 1_000_000_000d;
                if (seconds <= 0) continue;
                Candidate prior = candidates.get(key);
                candidates.put(key, new Candidate(prior == null ? app : prior.app(), seconds + (prior == null ? 0 : prior.seconds())));
            }
            payload.put("candidates", candidates.values().stream()
                    .sorted(Comparator.comparingDouble(Candidate::seconds).reversed().thenComparing(candidate -> key(candidate.app())))
                    .limit(100).map(candidate -> Map.<String, Object>of("app", candidate.app(), "seconds", candidate.seconds())).toList());
        } catch (RuntimeException failure) {
            payload.put("candidates", List.of());
            payload.put("candidatesError", "unavailable");
        }
    }

    private static String listItem(JsonNode item, String error) {
        if (!item.isTextual()) throw new IllegalArgumentException(error);
        String value = item.asText().strip();
        if (value.isEmpty() || value.contains(",") || value.contains(";")) throw new IllegalArgumentException(error);
        return value;
    }

    private static void addRule(Map<String, AppRule> apps, String app, String mode) {
        String key = key(app);
        AppRule prior = apps.get(key);
        if (prior == null) apps.put(key, new AppRule(app, mode));
        else if ("capture".equals(mode)) apps.put(key, new AppRule(prior.app(), "capture"));
    }

    private static String joinApps(Map<String, AppRule> apps, String mode) {
        return apps.values().stream().filter(rule -> mode.equals(rule.mode())).map(AppRule::app)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static List<String> split(String value) {
        return value == null ? List.of() : Arrays.stream(value.split("[,;]")).map(String::strip).filter(item -> !item.isEmpty()).toList();
    }

    private static List<String> unique(List<String> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach(value -> result.putIfAbsent(key(value), value));
        return List.copyOf(result.values());
    }

    private static String key(String app) { return app.toLowerCase(Locale.ROOT); }
    private static String hostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception failure) { return "unknown"; }
    }
    private record AppRule(String app, String mode) {}
    private record Candidate(String app, double seconds) {}
}
