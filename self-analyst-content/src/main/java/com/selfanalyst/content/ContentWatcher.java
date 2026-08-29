package com.selfanalyst.content;

import com.selfanalyst.content.capture.ContentCapture;
import com.selfanalyst.content.capture.ContentResult;
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
 * A capture thread polls foreground-window metadata, captures immediately on
 * window changes, and throttles stable-window screenshots. OCR results are
 * fingerprint-cached by {@link ContentCapture}. The
 * heartbeat thread posts on a strict {@code pollIntervalMs} timer,
 * independent of capture latency.
 * <p>
 * SPEC-WCH-001.
 */
public class ContentWatcher extends Thread {

    private static final Logger log = LoggerFactory.getLogger(ContentWatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PlatformCapture platform;
    private final ContentCapture capture;
    private final UiaTreeWalker treeWalker;
    private final String serverUrl;
    private final String bucketId;
    private final String hostname;
    private final HttpClient httpClient;
    private final int pollIntervalMs;
    private final int stableCaptureIntervalMs;
    private final long pollIntervalNanos;
    private final long stableCaptureIntervalNanos;
    private volatile boolean running = true;
    private volatile Thread captureThread;

    /** Minimum heartbeat duration (seconds) — must exceed vis-timeline's filterShortEvents threshold of 1s. */
    private static final double HEARTBEAT_DURATION_S = 2.0;

    /** Latest capture snapshot — written by capture thread, read by heartbeat thread. */
    private volatile Snapshot latest;

    private record Snapshot(long handle, String app, String title, ContentResult result) {}

    public ContentWatcher(String serverUrl, int pollIntervalMs) {
        this.serverUrl = serverUrl;
        this.pollIntervalMs = pollIntervalMs;
        this.stableCaptureIntervalMs = Math.max(
                pollIntervalMs, parseStableCaptureIntervalMs());
        this.pollIntervalNanos = TimeUnit.MILLISECONDS.toNanos(pollIntervalMs);
        this.stableCaptureIntervalNanos =
                TimeUnit.MILLISECONDS.toNanos(stableCaptureIntervalMs);
        this.treeWalker = new UiaTreeWalker();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.hostname = getHostname();
        this.bucketId = "aw-watcher-content_" + hostname;
        this.platform = detectPlatform();
        this.capture = platform.createCapture();
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
            capture.close();
        }
    }

    // ── Capture thread (metadata-polled, screenshot-throttled) ──────

    /** Cached UIA result for the current foreground window handle. */
    private volatile UiaTreeWalker.UiaWalkResult cachedWalk = new UiaTreeWalker.UiaWalkResult("", null);
    private volatile long cachedHandle;
    private volatile String cachedTitle;

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
                    cachedHandle = 0;
                    cachedTitle = null;
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

                // Excluded apps / credential windows: skip UIA walk, OCR, and heartbeat entirely
                if (capture.isExcluded(app, title)) {
                    latest = null;
                    continue;
                }

                long nowNanos = System.nanoTime();
                if (!captureIsDue(
                        windowChanged, nowNanos, lastCaptureAttemptNanos,
                        stableCaptureIntervalNanos)) {
                    continue;
                }
                // Advance the monotonic schedule before starting potentially slow work so a
                // failed or long-running OCR attempt cannot cause a 500ms retry storm.
                lastCaptureAttemptNanos = nowNanos;

                // Refresh UIA when the foreground window or its title changes.
                UiaTreeWalker.UiaWalkResult walkResult;
                if (handle == cachedHandle
                        && Objects.equals(title, cachedTitle)
                        && cachedWalk != null) {
                    walkResult = cachedWalk;
                } else {
                    try {
                        walkResult = treeWalker.walk(handle);
                        cachedWalk = walkResult;
                        cachedHandle = handle;
                        cachedTitle = title;
                    } catch (Exception e) {
                        walkResult = new UiaTreeWalker.UiaWalkResult("", null);
                    }
                }

                ContentResult result = capture.capture(
                    handle, app, title, walkResult.root(), walkResult.text());

                PlatformCapture.ForegroundWindow confirmed =
                        platform.getForegroundWindowInfo();
                if (sameWindow(handle, app, title, confirmed)) {
                    latest = new Snapshot(handle, app, title, result);
                } else {
                    latest = null;
                }
            } catch (Exception e) {
                log.error("Capture error", e);
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
                                long stableCaptureIntervalNanos) {
        return windowChanged
                || lastCaptureAttemptNanos == Long.MIN_VALUE
                || nowNanos - lastCaptureAttemptNanos >= stableCaptureIntervalNanos;
    }

    static boolean sameWindow(long handle, String app, String title,
                              PlatformCapture.ForegroundWindow current) {
        return current != null
                && handle == current.handle()
                && Objects.equals(app, current.app())
                && Objects.equals(title, current.title());
    }

    private static int parseStableCaptureIntervalMs() {
        try {
            int value = Integer.parseInt(System.getProperty(
                    "ocr.stable-capture-interval-ms", "1500"));
            return value >= 1000 && value <= 2000 ? value : 1500;
        } catch (NumberFormatException e) {
            return 1500;
        }
    }

    public void shutdown() {
        running = false;
        this.interrupt();
        Thread worker = captureThread;
        if (worker != null) worker.interrupt();
        capture.close();
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static PlatformCapture detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return new WindowsCapture();
        throw new UnsupportedOperationException(
            "ContentWatcher is only supported on Windows. OS: " + os);
    }

    private void sendHeartbeat(String app, String title, ContentResult result) {
        try {
            String url = serverUrl + "/api/0/buckets/" + bucketId + "/heartbeat";

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("app", app != null ? app : "");
            data.put("title", title != null ? title : "");
            data.put("text_content", result.textContent() != null ? result.textContent() : "");
            data.put("source", result.source() != null ? result.source() : "uia");
            data.put("uia_chars", result.uiaChars());
            data.put("ocr_chars", result.ocrChars());
            if (result.sampleId() != null) {
                data.put("sample_id", result.sampleId());
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("timestamp", Instant.now().toString());
            body.put("duration", Math.max(HEARTBEAT_DURATION_S, pollIntervalMs / 1000.0));
            body.put("data", data);

            String json = MAPPER.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // Silent
        }
    }

    private void ensureBucket() {
        try {
            String url = serverUrl + "/api/0/buckets/" + bucketId;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", bucketId);
            body.put("name", "content-watcher bucket");
            body.put("type", "listening");
            body.put("client", "aw-watcher-content");
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
}
