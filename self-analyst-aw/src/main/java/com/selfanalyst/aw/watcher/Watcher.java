package com.selfanalyst.aw.watcher;

import com.selfanalyst.aw.model.Event;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class Watcher implements Runnable {
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected final String name;
    protected final String bucketId;
    protected final long intervalMs;
    protected final String serverUrl;
    protected final HttpClient httpClient;
    protected final ScheduledExecutorService scheduler;
    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected String lastDataJson = "";

    protected Watcher(String name, String bucketId, long intervalMs, String serverUrl) {
        this.name = name;
        this.bucketId = bucketId;
        this.intervalMs = intervalMs;
        this.serverUrl = serverUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watcher-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        running.set(true);
        scheduler.scheduleWithFixedDelay(this, 0, intervalMs, TimeUnit.MILLISECONDS);
        log.info("{} started (interval={}ms)", name, intervalMs);
    }

    public void stop() {
        running.set(false);
        scheduler.shutdown();
        log.info("{} stopped", name);
    }

    @Override
    public void run() {
        if (!running.get()) return;
        try {
            Event event = collect();
            if (event != null) {
                sendHeartbeat(event);
            }
        } catch (Exception e) {
            log.error("{} error", name, e);
        }
    }

    protected abstract Event collect();

    protected void sendHeartbeat(Event event) {
        try {
            String url = serverUrl + "/api/0/buckets/" + bucketId + "/heartbeat";
            String body = MAPPER.writeValueAsString(Map.of(
                "timestamp", event.timestamp().toString(),
                "duration", event.duration(),
                "data", event.data()
            ));
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // Silent fail - watcher continues
        }
    }

    protected void ensureBucket() {
        try {
            String url = serverUrl + "/api/0/buckets/" + bucketId;
            String body = MAPPER.writeValueAsString(Map.of(
                "id", bucketId, "name", name + " bucket",
                "type", name, "client", name,
                "hostname", getHostname(),
                "created", Instant.now().toString()
            ));
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) { /* bucket may already exist */ }
    }

    protected static String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) { return "unknown"; }
    }
}
