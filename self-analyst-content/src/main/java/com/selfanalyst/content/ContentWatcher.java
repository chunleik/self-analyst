package com.selfanalyst.content;

import com.selfanalyst.content.capture.TitleCapture;
import com.selfanalyst.content.capture.TitleCaptureResult;
import com.selfanalyst.content.platform.PlatformCapture;
import com.selfanalyst.content.platform.WindowsCapture;
import com.selfanalyst.content.uia.UiaTreeWalker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Content watcher with decoupled capture and heartbeat.
 * <p>
 * A capture thread polls foreground-window metadata, refreshes UIA immediately on
 * window changes, and periodically refreshes stable application context. The
 * heartbeat thread posts on a strict {@code pollIntervalMs} timer,
 * independent of capture latency.
 * <p>
 * SPEC-WCH-001.
 */
public class ContentWatcher extends Thread {

    private static final Logger log = LoggerFactory.getLogger(ContentWatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String CLIENT = "watcher-content";

    private final PlatformCapture platform;
    private final TitleCapture capture;
    private final UiaTreeWalker treeWalker;
    private final String serverUrl;
    private final String bucketId;
    private final String hostname;
    private final HttpClient httpClient;
    private final int pollIntervalMs;
    private final long pollIntervalNanos;
    private final long stableRefreshIntervalNanos;
    private volatile boolean running = true;
    private volatile boolean heartbeatHealthy = true;
    private volatile Thread captureThread;
    private final ContentHeartbeatDelivery heartbeatDelivery = new ContentHeartbeatDelivery();

    /** Minimum heartbeat duration (seconds) — must exceed vis-timeline's filterShortEvents threshold of 1s. */
    private static final double HEARTBEAT_DURATION_S = 2.0;
    private static final int STABLE_CONTEXT_REFRESH_MS = 1_500;

    /** Latest capture snapshot — written by capture thread, read by heartbeat thread. */
    private volatile Snapshot latest;

    private record Snapshot(long handle, String app, String title, TitleCaptureResult result) {}

    public ContentWatcher(String serverUrl, int pollIntervalMs) {
        this.serverUrl = serverUrl;
        this.pollIntervalMs = pollIntervalMs;
        int stableRefreshIntervalMs = Math.max(
                pollIntervalMs, STABLE_CONTEXT_REFRESH_MS);
        this.pollIntervalNanos = TimeUnit.MILLISECONDS.toNanos(pollIntervalMs);
        this.stableRefreshIntervalNanos =
                TimeUnit.MILLISECONDS.toNanos(stableRefreshIntervalMs);
        this.treeWalker = new UiaTreeWalker();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.hostname = getHostname();
        this.bucketId = bucketIdForHostname(hostname);
        this.platform = detectPlatform();
        this.capture = new TitleCapture();
        setDaemon(true);
        setName("content-watcher");
    }

    // ── Heartbeat thread (strict timer) ────────────────────────────

    @Override
    public void run() {
        try {
            ensureBucket();

            captureThread = new Thread(this::captureLoop, "content-capture");
            captureThread.setDaemon(true);
            captureThread.start();

            while (running) {
                long cycleStart = System.currentTimeMillis();
                Snapshot snap = latest;
                if (snap != null) {
                    PlatformCapture.ForegroundWindow current =
                            platform.getForegroundWindowInfo();
                    if (sameWindow(snap.handle, snap.app, snap.title, current)) {
                        sendHeartbeat(snap.app, snap.title, snap.result);
                    }
                    // Do not clear latest on mismatch: the capture thread may have published
                    // a replacement snapshot after this heartbeat thread read `snap`.
                }
                long elapsed = System.currentTimeMillis() - cycleStart;
                long sleepMs = pollIntervalMs - elapsed;
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            running = false;
            Thread worker = captureThread;
            if (worker != null) worker.interrupt();
        }
    }

    // ── UIA title-capture thread ────────────────────────────────────

    private void captureLoop() {
        long observedHandle = 0L;
        String observedApp = null;
        String observedTitle = null;
        long lastCaptureAttemptNanos = Long.MIN_VALUE;

        while (running) {
            long cycleStartNanos = System.nanoTime();
            try {
                PlatformCapture.ForegroundWindow foreground =
                        platform.getForegroundWindowInfo();
                long handle = foreground.handle();
                if (handle == 0) {
                    observedHandle = 0;
                    observedApp = null;
                    observedTitle = null;
                    latest = null;
                    continue;
                }

                String app = foreground.app();
                String title = foreground.title();
                boolean windowChanged = handle != observedHandle
                        || !Objects.equals(app, observedApp)
                        || !Objects.equals(title, observedTitle);
                observedHandle = handle;
                observedApp = app;
                observedTitle = title;
                if (windowChanged) latest = null;

                // Excluded apps / credential windows: keep OS window metadata but never query UIA.
                if (capture.isExcluded(app, title)) {
                    latest = new Snapshot(handle, app, title, capture.windowOnly());
                    continue;
                }

                long nowNanos = System.nanoTime();
                if (!captureIsDue(
                        windowChanged, nowNanos, lastCaptureAttemptNanos,
                        stableRefreshIntervalNanos)) {
                    continue;
                }
                // Advance the monotonic schedule before starting potentially slow UIA work.
                lastCaptureAttemptNanos = nowNanos;

                UiaTreeWalker.UiaWalkResult walkResult;
                try {
                    walkResult = treeWalker.walk(handle);
                } catch (Exception e) {
                    walkResult = new UiaTreeWalker.UiaWalkResult("", null);
                }

                TitleCaptureResult titleResult = capture.capture(
                        app, walkResult.root(), walkResult.text());

                PlatformCapture.ForegroundWindow confirmed =
                        platform.getForegroundWindowInfo();
                if (sameWindow(handle, app, title, confirmed)) {
                    latest = new Snapshot(handle, app, title, titleResult);
                } else {
                    latest = null;
                }
            } catch (Exception e) {
                log.error("Content capture failed type={}", e.getClass().getSimpleName());
            } finally {
                long sleepNanos = pollIntervalNanos - (System.nanoTime() - cycleStartNanos);
                if (running && sleepNanos > 0) {
                    try {
                        long sleepMs = TimeUnit.NANOSECONDS.toMillis(sleepNanos);
                        int extraNanos = (int) (sleepNanos
                                - TimeUnit.MILLISECONDS.toNanos(sleepMs));
                        Thread.sleep(sleepMs, extraNanos);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    static boolean captureIsDue(boolean windowChanged, long nowNanos,
                                long lastCaptureAttemptNanos,
                                long stableRefreshIntervalNanos) {
        return windowChanged
                || lastCaptureAttemptNanos == Long.MIN_VALUE
                || nowNanos - lastCaptureAttemptNanos >= stableRefreshIntervalNanos;
    }

    static boolean sameWindow(long handle, String app, String title,
                              PlatformCapture.ForegroundWindow current) {
        return current != null
                && handle == current.handle()
                && Objects.equals(app, current.app())
                && Objects.equals(title, current.title());
    }

    public void shutdown() {
        running = false;
        this.interrupt();
        Thread worker = captureThread;
        if (worker != null) worker.interrupt();
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static PlatformCapture detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return new WindowsCapture();
        throw new UnsupportedOperationException(
            "ContentWatcher is only supported on Windows. OS: " + os);
    }

    private void sendHeartbeat(String app, String title, TitleCaptureResult result) {
        try {
            String url = serverUrl + "/api/0/buckets/" + bucketId + "/heartbeat";

            Map<String, Object> data = heartbeatData(app, title, result);

            Map<String, Object> body = heartbeatDelivery.request(data, Instant.now(),
                    Math.max(HEARTBEAT_DURATION_S, pollIntervalMs / 1000.0));

            String json = MAPPER.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            heartbeatHealthy = response.statusCode() >= 200 && response.statusCode() < 300;
            heartbeatDelivery.complete(heartbeatHealthy);
        } catch (Exception e) {
            heartbeatHealthy = false;
            heartbeatDelivery.complete(false);
        }
    }

    static Map<String, Object> heartbeatData(String app, String title, TitleCaptureResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", 2);
        data.put("app", app != null ? app : "");
        data.put("title", title != null ? title : "");
        if (result != null && result.contextTitle() != null && !result.contextTitle().isBlank()) {
            data.put("context_title", result.contextTitle());
            data.put("context_kind", result.contextKind());
            data.put("title_confidence", result.titleConfidence());
        }
        data.put("title_source", result != null && result.titleSource() != null
                ? result.titleSource() : "window");
        data.put("uia_chars", result != null ? result.uiaChars() : 0);
        return data;
    }

    private void ensureBucket() {
        try {
            String url = serverUrl + "/api/0/buckets/" + bucketId;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", bucketId);
            body.put("name", "content-watcher bucket");
            body.put("type", "listening");
            body.put("client", CLIENT);
            body.put("hostname", hostname);
            body.put("created", Instant.now().toString());

            String json = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // Bucket likely already exists
        }
    }

    static String bucketIdForHostname(String hostname) {
        return "watcher-content_" + hostname;
    }

    private static String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public boolean isRunning() {
        return running;
    }

    public String status() {
        return statusOf(running, heartbeatHealthy);
    }

    static String statusOf(boolean running, boolean heartbeatHealthy) {
        if (!running) return "disabled";
        return heartbeatHealthy ? "running" : "degraded";
    }
}
