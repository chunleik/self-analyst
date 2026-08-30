package com.selfanalyst.desktop.controller;

import com.selfanalyst.config.Config;
import com.selfanalyst.content.ContentWatcher;
import com.selfanalyst.audio.AudioCaptureManager;
import com.selfanalyst.aw.watcher.Watcher;
import com.selfanalyst.aw.watcher.WatcherManager;
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

/**
 * System status endpoint.
 *
 * <pre>
 *   GET /desktop/status → { backend, aw, collectors, llm }
 * </pre>
 */
public class DesktopStatusController implements AutoCloseable {

    private final Config config;
    private final WatcherManager watcherManager;
    private final ContentWatcher contentWatcher;
    private final AudioCaptureManager audioCaptureManager;
    private final boolean contentPersistenceReady;
    private final String contentMigrationError;

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
                                   ContentWatcher contentWatcher,
                                   AudioCaptureManager audioCaptureManager) {
        this(config, watcherManager, contentWatcher, audioCaptureManager, true, null);
    }

    public DesktopStatusController(Config config,
                                   WatcherManager watcherManager,
                                   ContentWatcher contentWatcher,
                                   AudioCaptureManager audioCaptureManager,
                                   boolean contentPersistenceReady,
                                   String contentMigrationError) {
        this.config = config;
        this.watcherManager = watcherManager;
        this.contentWatcher = contentWatcher;
        this.audioCaptureManager = audioCaptureManager;
        this.contentPersistenceReady = contentPersistenceReady;
        this.contentMigrationError = contentMigrationError;
        // Kick off first check immediately, then every 60 seconds
        llmChecker.scheduleAtFixedRate(this::refreshLlmAvailability, 0, 60, TimeUnit.SECONDS);
    }

    private void refreshLlmAvailability() {
        boolean configured = config.llmApiKey() != null
                && !config.llmApiKey().isBlank()
                && !config.llmApiKey().contains("CHANGE_ME");
        llmAvailableCache.set(configured && doCheckLlmAvailability());
        llmCacheUpdatedAt.set(System.currentTimeMillis());
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
        aw.put("audioEnabled", config.audioEnabled());
        status.put("aw", aw);

        // Collectors section
        Map<String, String> collectors = new LinkedHashMap<>();
        collectors.put("window", watcherStatus("window"));
        collectors.put("afk", watcherStatus("afk"));
        String contextTitleStatus = contentStatus();
        collectors.put("contextTitle", contextTitleStatus);
        collectors.put("content", contextTitleStatus); // compatibility for older desktop UI clients
        collectors.put("audio", audioStatus());
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
        status.put("llm", buildLlmStatus(llmAvailableCache.get()));

        return status;
    }

    Map<String, Object> buildLlmStatus(boolean available) {
        Map<String, Object> llm = new LinkedHashMap<>();
        boolean configured = config.llmApiKey() != null
                && !config.llmApiKey().isBlank()
                && !config.llmApiKey().contains("CHANGE_ME");
        llm.put("configured", configured);
        llm.put("available", available);
        llm.put("model", config.llmModel());
        llm.put("baseUrl", config.llmBaseUrl());
        return llm;
    }

    String effectiveBaseUrlForAvailabilityCheck() {
        return config.llmBaseUrl();
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

    private String safeMigrationError() {
        if (contentMigrationError == null || contentMigrationError.isBlank()) {
            return "Content event migration failed";
        }
        String singleLine = contentMigrationError.replaceAll("[\\r\\n]+", " ").strip();
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 197) + "...";
    }

    private String audioStatus() {
        if (audioCaptureManager == null) return "disabled";
        return audioCaptureManager.status().status();
    }

    private boolean doCheckLlmAvailability() {
        try {
            String baseUrl = effectiveBaseUrlForAvailabilityCheck();
            String url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + config.llmApiKey())
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
