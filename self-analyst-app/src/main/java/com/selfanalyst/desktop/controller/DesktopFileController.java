package com.selfanalyst.desktop.controller;

import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.file.FileRecord;
import com.selfanalyst.file.FileStatus;
import com.selfanalyst.file.FileWatchStore;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Desktop visibility API for the local file collector. */
public class DesktopFileController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final boolean semanticConfigured;
    private final FileWatchStore store;
    private final UserConfigStore configStore;
    private final Supplier<CollectorState> stateSupplier;
    private final SettingsApplier settingsApplier;

    public DesktopFileController(boolean enabled,
                                 boolean semanticConfigured,
                                 List<Path> watchRoots,
                                 FileWatchStore store,
                                 BooleanSupplier watcherRunning,
                                 BooleanSupplier indexWorkerRunning,
                                 BooleanSupplier semanticWorkerRunning,
                                 String startupReason,
                                 String startupError) {
        List<Path> roots = watchRoots == null ? List.of() : List.copyOf(watchRoots);
        this.semanticConfigured = semanticConfigured;
        this.store = store;
        this.configStore = null;
        this.stateSupplier = () -> new CollectorState(
                enabled, roots,
                safeBoolean(orFalse(watcherRunning)),
                safeBoolean(orFalse(indexWorkerRunning)),
                safeBoolean(orFalse(semanticWorkerRunning)),
                startupReason, startupError);
        this.settingsApplier = null;
    }

    public DesktopFileController(boolean semanticConfigured,
                                 FileWatchStore store,
                                 UserConfigStore configStore,
                                 Supplier<CollectorState> stateSupplier,
                                 SettingsApplier settingsApplier) {
        this.semanticConfigured = semanticConfigured;
        this.store = store;
        this.configStore = configStore;
        this.stateSupplier = stateSupplier;
        this.settingsApplier = settingsApplier;
    }

    /** GET /desktop/files?limit=20. */
    public void getOverview(Context ctx) {
        ctx.json(overviewPayload(parseLimit(ctx.queryParam("limit"))));
    }

    /** PUT /desktop/files/settings. Persists and applies watched folders immediately. */
    @SuppressWarnings("unchecked")
    public void putSettings(Context ctx) throws IOException {
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        Object enabledValue = body.get("enabled");
        Object pathsValue = body.get("paths");
        if (!(enabledValue instanceof Boolean enabled) || !(pathsValue instanceof List<?> rawPaths)) {
            ctx.status(400).json(Map.of("error", "enabled 必须是布尔值，paths 必须是目录数组"));
            return;
        }
        List<String> paths = new ArrayList<>();
        for (Object rawPath : rawPaths) {
            if (!(rawPath instanceof String path)) {
                ctx.status(400).json(Map.of("error", "paths 中的每一项都必须是目录字符串"));
                return;
            }
            paths.add(path);
        }
        try {
            ctx.json(updateSettings(enabled, paths));
        } catch (IllegalArgumentException invalidSettings) {
            ctx.status(400).json(Map.of("error", invalidSettings.getMessage()));
        } catch (IllegalStateException unavailable) {
            ctx.status(503).json(Map.of("error", unavailable.getMessage()));
        }
    }

    /** Coarse collector state shared with GET /desktop/status. */
    public String collectorStatus() {
        return runtimeStatus(currentState()).status();
    }

    Map<String, Object> overviewPayload(int requestedLimit) {
        int limit = Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
        CollectorState collector = currentState();
        RuntimeStatus runtime = runtimeStatus(collector);
        String status = runtime.status();
        String reason = runtime.reason();
        String error = safeError(collector.error());
        Map<String, Map<String, Long>> rawCounts = Map.of();
        List<FileRecord> recent = List.of();
        Set<String> configuredRoots = new LinkedHashSet<>();
        for (Path root : collector.watchRoots()) {
            configuredRoots.add(root.toAbsolutePath().normalize().toString());
        }

        if (store != null && store.isHealthy()) {
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
        for (Path root : collector.watchRoots()) {
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
        payload.put("enabled", collector.enabled());
        payload.put("status", status);
        if (reason != null) payload.put("reason", reason);
        if (error != null) payload.put("error", error);
        payload.put("restartRequiredOnChange", false);
        payload.put("semantic", Map.of(
                "configured", semanticConfigured,
                "available", semanticConfigured && collector.semanticWorkerRunning()));
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

    synchronized Map<String, Object> updateSettings(boolean enabled, List<String> requestedPaths)
            throws IOException {
        if (configStore == null || settingsApplier == null) {
            throw new IllegalStateException("当前运行环境不支持修改文件采集设置");
        }
        List<Path> roots = normalizeWatchRoots(requestedPaths);
        if (enabled && roots.isEmpty()) {
            throw new IllegalArgumentException("启用文件采集前请至少添加一个监控目录");
        }

        Properties user = configStore.loadUser();
        user.setProperty("file.watch.enabled", Boolean.toString(enabled));
        if (roots.isEmpty()) {
            user.remove("file.watch.paths");
        } else {
            user.setProperty("file.watch.paths", roots.stream()
                    .map(Path::toString)
                    .reduce((left, right) -> left + "," + right)
                    .orElse(""));
        }
        configStore.save(user);
        settingsApplier.apply(enabled, roots);
        return overviewPayload(DEFAULT_LIMIT);
    }

    private CollectorState currentState() {
        if (stateSupplier == null) {
            return new CollectorState(false, List.of(), false, false, false,
                    "runtime_unavailable", null);
        }
        CollectorState state = stateSupplier.get();
        return state != null ? state : new CollectorState(false, List.of(), false, false, false,
                "runtime_unavailable", null);
    }

    private RuntimeStatus runtimeStatus(CollectorState state) {
        if (!state.enabled()) return new RuntimeStatus("disabled", "disabled_by_config");
        if (state.reason() != null && !state.reason().isBlank()) {
            return new RuntimeStatus("degraded", state.reason());
        }
        if (state.watchRoots().isEmpty()) return new RuntimeStatus("degraded", "paths_unavailable");
        if (store == null || !store.isHealthy()) {
            return new RuntimeStatus("degraded", "store_unavailable");
        }
        if (!state.indexWorkerRunning()) {
            return new RuntimeStatus("degraded", "index_worker_unavailable");
        }
        if (!state.watcherRunning()) {
            return new RuntimeStatus("degraded", "watcher_unavailable");
        }
        return new RuntimeStatus("running", null);
    }

    private static List<Path> normalizeWatchRoots(List<String> requestedPaths) {
        if (requestedPaths == null) {
            throw new IllegalArgumentException("paths 必须是目录数组");
        }
        if (requestedPaths.size() > 100) {
            throw new IllegalArgumentException("监控目录不能超过 100 个");
        }
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (String requestedPath : requestedPaths) {
            String value = requestedPath == null ? "" : requestedPath.strip();
            if (value.isEmpty()) continue;
            if (value.contains(",")) {
                throw new IllegalArgumentException("监控目录路径不能包含英文逗号：" + value);
            }
            final Path path;
            try {
                Path candidate = Path.of(value);
                if (!candidate.isAbsolute()) {
                    throw new IllegalArgumentException("监控目录必须使用绝对路径：" + value);
                }
                path = candidate.toAbsolutePath().normalize();
            } catch (InvalidPathException invalidPath) {
                throw new IllegalArgumentException("无效的监控目录：" + value);
            }
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("监控目录不存在或不是目录：" + value);
            }
            roots.add(path);
        }
        return List.copyOf(roots);
    }

    private static BooleanSupplier orFalse(BooleanSupplier supplier) {
        return supplier != null ? supplier : () -> false;
    }

    private static boolean safeBoolean(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
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

    @FunctionalInterface
    public interface SettingsApplier {
        void apply(boolean enabled, List<Path> watchRoots);
    }

    public record CollectorState(boolean enabled,
                                 List<Path> watchRoots,
                                 boolean watcherRunning,
                                 boolean indexWorkerRunning,
                                 boolean semanticWorkerRunning,
                                 String reason,
                                 String error) {
        public CollectorState {
            watchRoots = watchRoots == null ? List.of() : List.copyOf(watchRoots);
        }
    }

    private record RuntimeStatus(String status, String reason) {}
}
