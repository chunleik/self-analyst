package com.selfanalyst.desktop.controller;

import com.selfanalyst.config.Config;
import com.selfanalyst.config.LlmSettings;
import com.selfanalyst.content.ContentWatcher;
import com.selfanalyst.events.watcher.Watcher;
import com.selfanalyst.events.watcher.WatcherManager;
import io.javalin.http.Context;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * System status endpoint.
 *
 * <pre>
 *   GET /desktop/status → { backend, aw, collectors, llm }
 * </pre>
 */
public class DesktopStatusController implements AutoCloseable {

    private final Config config;
    private volatile Supplier<LlmSettings> llmSettings;
    private volatile LlmSettings checkedSettings;
    private final AtomicBoolean checkRunning = new AtomicBoolean();

    public void setLlmSettingsSupplier(Supplier<LlmSettings> supplier) { llmSettings = supplier; }
    private LlmSettings modelSettings() {
        return llmSettings == null ? LlmSettings.from(config) : llmSettings.get();
    }
    private final WatcherManager watcherManager;
    private final ContentWatcher contentWatcher;
    private final boolean contentPersistenceReady;
    private final String contentMigrationError;
    private final Supplier<String> fileCollectorStatus;
    private volatile Supplier<Map<String, Object>> rawStatusSupplier;

    private final AtomicBoolean llmAvailableCache = new AtomicBoolean(false);
    private final AtomicLong llmCacheUpdatedAt = new AtomicLong(0);
    private final ScheduledExecutorService llmChecker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "llm-availability-checker");
                t.setDaemon(true);
                return t;
            });

    public DesktopStatusController(Config config,
                                   WatcherManager watcherManager,
                                   ContentWatcher contentWatcher) {
        this(config, watcherManager, contentWatcher, true, null);
    }

    public DesktopStatusController(Config config,
                                   WatcherManager watcherManager,
                                   ContentWatcher contentWatcher,
                                   boolean contentPersistenceReady,
                                   String contentMigrationError) {
        this(config, watcherManager, contentWatcher,
                contentPersistenceReady, contentMigrationError, null);
    }

    public DesktopStatusController(Config config,
                                   WatcherManager watcherManager,
                                   ContentWatcher contentWatcher,
                                   boolean contentPersistenceReady,
                                   String contentMigrationError,
                                   Supplier<String> fileCollectorStatus) {
        this.config = config;
        this.watcherManager = watcherManager;
        this.contentWatcher = contentWatcher;
        this.contentPersistenceReady = contentPersistenceReady;
        this.contentMigrationError = contentMigrationError;
        this.fileCollectorStatus = fileCollectorStatus != null
                ? fileCollectorStatus
                : () -> config.fileWatchEnabled() ? "degraded" : "disabled";
        // Kick off first check immediately, then every 60 seconds
        llmChecker.scheduleAtFixedRate(this::refreshLlmAvailability, 0, 60, TimeUnit.SECONDS);
    }

    private void refreshLlmAvailability() {
        if (!checkRunning.compareAndSet(false, true)) return;
        try {
            LlmSettings settings = modelSettings();
            boolean available = settings.available() && doCheckLlmAvailability(settings);
            if (settings.equals(modelSettings())) {
                llmAvailableCache.set(available);
                checkedSettings = settings;
                llmCacheUpdatedAt.set(System.currentTimeMillis());
            }
        } finally { checkRunning.set(false); }
    }

    /**
     * GET /desktop/status
     */
    public void getStatus(Context ctx) {
        ctx.json(statusPayload());
    }

    Map<String, Object> statusPayload() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("backend", "running");
        // Effective language for the desktop UI to pick its message column (SPEC-I18N-RES-003)
        status.put("language", config.effectiveLanguage().code());

        // AW section
        Map<String, Object> aw = new LinkedHashMap<>();
        aw.put("mode", config.awEmbedded() ? "embedded" : "external");
        aw.put("port", config.awPort());
        aw.put("webUrl", "http://localhost:" + config.awPort() + "/");
        status.put("aw", aw);
        status.put("raw", rawStatus());

        // Collectors section
        Map<String, String> collectors = new LinkedHashMap<>();
        collectors.put("window", watcherStatus("window"));
        collectors.put("afk", watcherStatus("afk"));
        String contextTitleStatus = contentStatus();
        collectors.put("contextTitle", contextTitleStatus);
        collectors.put("content", contextTitleStatus); // compatibility for older desktop UI clients
        collectors.put("file", safeFileCollectorStatus());
        status.put("collectors", collectors);

        Map<String, Object> contentPersistence = new LinkedHashMap<>();
        contentPersistence.put("schemaVersion", 2);
        contentPersistence.put("ready", contentPersistenceReady);
        if (!contentPersistenceReady) {
            contentPersistence.put("status", "migration_failed");
            contentPersistence.put("error", safeMigrationError());
        } else {
            contentPersistence.put("status", "ready");
        }
        status.put("contentPersistence", contentPersistence);

        // LLM section
        LlmSettings settings = modelSettings();
        boolean checked = settings.equals(checkedSettings);
        status.put("llm", buildLlmStatus(checked && llmAvailableCache.get()));
        if (!checked && !checkRunning.get() && !llmChecker.isShutdown())
            llmChecker.execute(this::refreshLlmAvailability);

        return status;
    }

    public void setRawStatusSupplier(Supplier<Map<String, Object>> rawStatusSupplier) {
        this.rawStatusSupplier = rawStatusSupplier;
    }

    private Map<String, Object> rawStatus() {
        if (!config.awEmbedded()) return Map.of("status", "unavailable", "reason", "external_aw");
        Supplier<Map<String, Object>> supplier = rawStatusSupplier;
        if (supplier == null) return Map.of("status", "unavailable", "reason", "not_initialized");
        try {
            Map<String, Object> value = supplier.get();
            return value == null ? Map.of("status", "degraded", "reason", "status_unavailable") : value;
        } catch (RuntimeException failure) {
            return Map.of("status", "degraded", "reason", "status_unavailable");
        }
    }

    Map<String, Object> buildLlmStatus(boolean available) {
        Map<String, Object> llm = new LinkedHashMap<>();
        LlmSettings settings = modelSettings();
        llm.put("configured", settings.available());
        llm.put("available", available);
        llm.put("model", settings.model());
        llm.put("baseUrl", settings.baseUrl());
        return llm;
    }

    String effectiveBaseUrlForAvailabilityCheck() {
        return modelSettings().baseUrl();
    }

    private String watcherStatus(String type) {
        if (watcherManager == null) return "disabled";
        for (Watcher w : watcherManager.getWatchers()) {
            if (w.toString().contains(type)) {
                return "running";
            }
        }
        return "disabled";
    }

    private String contentStatus() {
        if (!contentPersistenceReady) return "degraded";
        if (contentWatcher == null) return "disabled";
        return contentWatcher.status();
    }

    private String safeFileCollectorStatus() {
        try {
            String status = fileCollectorStatus.get();
            return status == null || status.isBlank() ? "degraded" : status;
        } catch (RuntimeException ignored) {
            return "degraded";
        }
    }

    private String safeMigrationError() {
        if (contentMigrationError == null || contentMigrationError.isBlank()) {
            return "Content event migration failed";
        }
        String singleLine = contentMigrationError.replaceAll("[\\r\\n]+", " ").strip();
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 197) + "...";
    }

    private boolean doCheckLlmAvailability(LlmSettings settings) {
        try {
            String baseUrl = settings.baseUrl();
            String url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        llmChecker.shutdownNow();
    }

    boolean isLlmCheckerShutdownForTest() {
        return llmChecker.isShutdown();
    }
}
