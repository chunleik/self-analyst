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
import java.util.UUID;
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
    private static final int MAX_SEND_ATTEMPTS = 3;
    private PendingHeartbeat pendingHeartbeat;

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
        processOnce();
    }

    void processOnce() {
        try {
            if (pendingHeartbeat == null) {
                Event event = collect();
                if (event != null) {
                    pendingHeartbeat = new PendingHeartbeat(
                            event, UUID.randomUUID().toString(), 0);
                }
            }
            if (pendingHeartbeat != null) {
                if (sendHeartbeat(pendingHeartbeat.event(), pendingHeartbeat.sourceEventId())) {
                    pendingHeartbeat = null;
                } else {
                    int attempts = pendingHeartbeat.attempts() + 1;
                    if (attempts >= MAX_SEND_ATTEMPTS) {
                        log.warn("{} heartbeat delivery exhausted retries sourceEventId={}",
                                name, pendingHeartbeat.sourceEventId());
                        pendingHeartbeat = null;
                    } else {
                        pendingHeartbeat = new PendingHeartbeat(pendingHeartbeat.event(),
                                pendingHeartbeat.sourceEventId(), attempts);
                    }
                }
            }
        } catch (Exception e) {
            log.error("{} heartbeat processing failed type={}",
                    name, e.getClass().getSimpleName());
        }
    }

    protected abstract Event collect();

    protected boolean sendHeartbeat(Event event, String sourceEventId) {
        try {
            String url = serverUrl + "/api/0/buckets/" + bucketId + "/heartbeat";
            String body = MAPPER.writeValueAsString(Map.of(
                    "timestamp", event.timestamp().toString(),
                    "duration", event.duration(),
                    "sourceEventId", sourceEventId,
                    "data", event.data()));
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            int status = httpClient.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            return false;
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

    private record PendingHeartbeat(Event event, String sourceEventId, int attempts) {}
}
