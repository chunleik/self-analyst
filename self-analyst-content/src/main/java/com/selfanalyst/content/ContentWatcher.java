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

/**
 * Content watcher with decoupled capture and heartbeat.
 * <p>
 * A capture thread continuously walks the UIA tree (via the accessibility
 * sidecar, SPEC-AXS-*) and runs OCR, updating a shared snapshot. The
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
    private volatile boolean running = true;

    /** Minimum heartbeat duration (seconds) — must exceed vis-timeline's filterShortEvents threshold of 1s. */
    private static final double HEARTBEAT_DURATION_S = 2.0;

    /** Latest capture snapshot — written by capture thread, read by heartbeat thread. */
    private volatile Snapshot latest;

    private record Snapshot(String app, String title, ContentResult result) {}

    public ContentWatcher(String serverUrl, int pollIntervalMs) {
        this.serverUrl = serverUrl;
        this.pollIntervalMs = pollIntervalMs;
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
        ensureBucket();

        Thread captureThread = new Thread(this::captureLoop, "content-capture");
        captureThread.setDaemon(true);
        captureThread.start();

        while (running) {
            long cycleStart = System.currentTimeMillis();
            Snapshot snap = latest;
            if (snap != null) {
                sendHeartbeat(snap.app, snap.title, snap.result);
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
    }

    // ── Capture thread (UIA-cached, as fast as possible) ────────────

    /** Cached UIA result for the current foreground window handle. */
    private volatile UiaTreeWalker.UiaWalkResult cachedWalk = new UiaTreeWalker.UiaWalkResult("", null);
    private volatile long cachedHandle;

    private void captureLoop() {
        while (running) {
            try {
                long handle = platform.getForegroundWindow();
                if (handle == 0) {
                    cachedHandle = 0;
                    Thread.sleep(pollIntervalMs);
                    continue;
                }

                String app = platform.getActiveAppName();
                String title = platform.getActiveWindowTitle();

                // Excluded apps / credential windows: skip UIA walk, OCR, and heartbeat entirely
                if (capture.isExcluded(app, title)) {
                    latest = null;
                    Thread.sleep(pollIntervalMs);
                    continue;
                }

                // Only walk UIA when the foreground window changes (cache by handle)
                UiaTreeWalker.UiaWalkResult walkResult;
                if (handle == cachedHandle && cachedWalk != null) {
                    walkResult = cachedWalk;
                } else {
                    try {
                        walkResult = treeWalker.walk(handle);
                        cachedWalk = walkResult;
                        cachedHandle = handle;
                    } catch (Exception e) {
                        walkResult = new UiaTreeWalker.UiaWalkResult("", null);
                    }
                }

                ContentResult result = capture.capture(
                    handle, app, title, walkResult.root(), walkResult.text());

                latest = new Snapshot(app, title, result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Capture error", e);
            }
        }
    }

    public void shutdown() {
        running = false;
        this.interrupt();
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
