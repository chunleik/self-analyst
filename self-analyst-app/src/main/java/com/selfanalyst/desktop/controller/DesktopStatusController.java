package com.selfanalyst.desktop.controller;

import com.selfanalyst.config.Config;
import com.selfanalyst.content.ContentWatcher;
import com.selfanalyst.audio.AudioWatcher;
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
public class DesktopStatusController {

    private final Config config;
    private final WatcherManager watcherManager;
    private final ContentWatcher contentWatcher;
    private final AudioWatcher audioWatcher;

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
                                   AudioWatcher audioWatcher) {
        this.config = config;
        this.watcherManager = watcherManager;
        this.contentWatcher = contentWatcher;
        this.audioWatcher = audioWatcher;
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
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("backend", "running");

        // AW section
        Map<String, Object> aw = new LinkedHashMap<>();
        aw.put("mode", config.awEmbedded() ? "embedded" : "external");
        aw.put("port", config.awPort());
        aw.put("webUrl", "http://localhost:" + config.awPort() + "/");
        status.put("aw", aw);

        // Collectors section
        Map<String, String> collectors = new LinkedHashMap<>();
        collectors.put("window", watcherStatus("window"));
        collectors.put("afk", watcherStatus("afk"));
        collectors.put("content", contentStatus());
        collectors.put("audio", audioStatus());
        status.put("collectors", collectors);

        // LLM section
        Map<String, Object> llm = new LinkedHashMap<>();
        boolean configured = config.llmApiKey() != null
                && !config.llmApiKey().isBlank()
                && !config.llmApiKey().contains("CHANGE_ME");
        llm.put("configured", configured);
        llm.put("available", llmAvailableCache.get());
        llm.put("model", config.llmModel());
        status.put("llm", llm);

        ctx.json(status);
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
        if (contentWatcher == null) return "disabled";
        return contentWatcher.isRunning() ? "running" : "degraded";
    }

    private String audioStatus() {
        if (audioWatcher == null) return "disabled";
        return audioWatcher.isAlive() ? "running" : "disabled";
    }

    private boolean doCheckLlmAvailability() {
        try {
            String baseUrl = config.llmBaseUrl();
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
}
