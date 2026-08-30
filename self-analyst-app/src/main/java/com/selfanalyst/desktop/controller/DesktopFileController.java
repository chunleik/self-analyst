package com.selfanalyst.desktop.controller;

import com.selfanalyst.file.FileRecord;
import com.selfanalyst.file.FileStatus;
import com.selfanalyst.file.FileWatchStore;
import io.javalin.http.Context;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Desktop visibility API for the local file collector. */
public class DesktopFileController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final boolean enabled;
    private final boolean semanticConfigured;
    private final boolean semanticAvailable;
    private final List<Path> watchRoots;
    private final FileWatchStore store;
    private final BooleanSupplier running;
    private final String startupReason;
    private final String startupError;

    public DesktopFileController(boolean enabled,
                                 boolean semanticConfigured,
                                 boolean semanticAvailable,
                                 List<Path> watchRoots,
                                 FileWatchStore store,
                                 BooleanSupplier running,
                                 String startupReason,
                                 String startupError) {
        this.enabled = enabled;
        this.semanticConfigured = semanticConfigured;
        this.semanticAvailable = semanticAvailable;
        this.watchRoots = watchRoots == null ? List.of() : List.copyOf(watchRoots);
        this.store = store;
        this.running = running != null ? running : () -> false;
        this.startupReason = startupReason;
        this.startupError = startupError;
    }

    /** GET /desktop/files?limit=20. */
    public void getOverview(Context ctx) {
        ctx.json(overviewPayload(parseLimit(ctx.queryParam("limit"))));
    }

    /** Coarse collector state shared with GET /desktop/status. */
    public String collectorStatus() {
        if (!enabled) return "disabled";
        return running.getAsBoolean() ? "running" : "degraded";
    }

    Map<String, Object> overviewPayload(int requestedLimit) {
        int limit = Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
        String status = collectorStatus();
        String reason = reasonFor(status);
        String error = safeError(startupError);
        Map<String, Map<String, Long>> rawCounts = Map.of();
        List<FileRecord> recent = List.of();
        Set<String> configuredRoots = new LinkedHashSet<>();
        for (Path root : watchRoots) {
            configuredRoots.add(root.toAbsolutePath().normalize().toString());
        }

        if (store != null) {
            try {
                rawCounts = store.statusCountsByWatchRoot();
                recent = store.findRecentlyIndexed(new ArrayList<>(configuredRoots), limit);
            } catch (RuntimeException storeFailure) {
                status = "degraded";
                reason = "store_unavailable";
                error = safeError(storeFailure.getMessage());
            }
        }

        Map<String, Long> totals = emptyCounts();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Path root : watchRoots) {
            String path = root.toAbsolutePath().normalize().toString();
            Map<String, Long> counts = normalizedCounts(rawCounts.get(path));
            counts.forEach((key, value) -> totals.merge(key, value, Long::sum));
            roots.add(Map.of("path", path, "counts", counts));
        }

        List<Map<String, Object>> files = recent.stream()
                .filter(record -> configuredRoots.contains(record.watchRoot()))
                .limit(limit)
                .map(DesktopFileController::filePayload)
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", enabled);
        payload.put("status", status);
        if (reason != null) payload.put("reason", reason);
        if (error != null) payload.put("error", error);
        payload.put("restartRequiredOnChange", true);
        payload.put("semantic", Map.of(
                "configured", semanticConfigured,
                "available", semanticAvailable));
        payload.put("roots", roots);
        payload.put("totals", totals);
        payload.put("files", files);
        if (!files.isEmpty()) {
            Map<String, Object> latest = files.get(0);
            payload.put("latestIndexedAt", latest.get("lastIndexedAt"));
            payload.put("latestPath", latest.get("path"));
        }
        return payload;
    }

    private String reasonFor(String status) {
        if ("disabled".equals(status)) return "disabled_by_config";
        if ("degraded".equals(status)) {
            return startupReason == null || startupReason.isBlank()
                    ? "runtime_unavailable"
                    : startupReason;
        }
        return null;
    }

    private static Map<String, Object> filePayload(FileRecord record) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("path", record.absolutePath());
        file.put("relativePath", record.relativePath());
        file.put("watchRoot", record.watchRoot());
        file.put("extension", record.extension());
        file.put("status", record.status().name().toLowerCase(Locale.ROOT));
        file.put("summary", record.summary());
        file.put("mainTopics", record.mainTopics());
        file.put("sizeBytes", record.sizeBytes());
        file.put("lastModified", record.lastModified() != null
                ? record.lastModified().toString() : null);
        file.put("lastIndexedAt", record.lastIndexedAt() != null
                ? record.lastIndexedAt().toString() : null);
        return file;
    }

    private static Map<String, Long> emptyCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (FileStatus status : FileStatus.values()) {
            counts.put(status.name().toLowerCase(Locale.ROOT), 0L);
        }
        return counts;
    }

    private static Map<String, Long> normalizedCounts(Map<String, Long> raw) {
        Map<String, Long> counts = emptyCounts();
        if (raw != null) {
            raw.forEach((key, value) -> {
                if (key != null) counts.put(key.toLowerCase(Locale.ROOT), value);
            });
        }
        return counts;
    }

    private static int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_LIMIT;
        try {
            return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(raw)));
        } catch (NumberFormatException ignored) {
            return DEFAULT_LIMIT;
        }
    }

    private static String safeError(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String singleLine = raw.replaceAll("[\\r\\n]+", " ").strip();
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 197) + "...";
    }
}
