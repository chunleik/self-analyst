package com.selfanalyst.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.List;

/**
 * Real-time capture path (SPEC-FILE-012, SPEC-FILE-004 path A).
 *
 * <p>A daemon thread drains a NIO {@link WatchService}; events are filtered by
 * {@link PathFilter} and debounced (SPEC-FILE-019a) so only files that fall
 * silent for {@code debounceSeconds} are upserted as PENDING. The watcher never
 * reads file content or calls the LLM (SPEC-FILE-004b) — it only registers
 * intent and posts throttled heartbeats over HTTP to the AW timeline.
 *
 * <p><b>Platform note (SPEC-FILE-012c):</b> this uses the JDK default
 * {@link java.nio.file.WatchService}. On Windows/Linux it is event-driven
 * (native backend). macOS has no native FSEvents backend in the JDK, so the
 * default service falls back to a polling implementation with higher latency —
 * changes are still detected, just less promptly.
 */
public class FileWatcher {

    private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Must exceed vis-timeline's 1s filterShortEvents threshold. */
    private static final double HEARTBEAT_DURATION_S = 2.0;
    private static final double PULSETIME_S = 30.0; // SPEC-FILE-012b

    private final FileWatchStore store;
    private final PathFilter pathFilter;
    private final List<Path> watchRoots;
    private final String serverUrl;
    private final long debounceMs;          // SPEC-FILE-019a
    private final long heartbeatThrottleMs; // SPEC-FILE-019c

    private final HttpClient httpClient;
    private final String hostname;
    private final String bucketId;

    private WatchService watchService;
    private final Map<WatchKey, Path> keyToDir = new ConcurrentHashMap<>();
    private final Map<Path, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHeartbeat = new ConcurrentHashMap<>();

    private Thread watchThread;
    private ScheduledExecutorService debounceExecutor;
    private volatile boolean running = false;
    private volatile boolean registrationComplete;
    private volatile String registrationError;

    private record Pending(long lastEventMs, String eventType) {}

    public FileWatcher(FileWatchStore store, PathFilter pathFilter, List<Path> watchRoots,
                       String serverUrl, int debounceSeconds, int heartbeatThrottleSeconds) {
        this.store = store;
        this.pathFilter = pathFilter;
        this.watchRoots = watchRoots;
        this.serverUrl = serverUrl;
        this.debounceMs = Math.max(0, debounceSeconds) * 1000L;
        this.heartbeatThrottleMs = Math.max(0, heartbeatThrottleSeconds) * 1000L;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.hostname = getHostname();
        this.bucketId = "aw-watcher-file_" + hostname; // SPEC-FILE-012a
    }

    public void start() {
        if (running) return;
        running = true;
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create WatchService", e);
        }
        debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "file-watcher-debounce");
            t.setDaemon(true);
            return t;
        });
        debounceExecutor.scheduleWithFixedDelay(this::flushDebounced, 1, 1, TimeUnit.SECONDS);
        watchThread = new Thread(this::registerAndWatch, "file-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("FileWatcher started ({} roots, debounce={}s)", watchRoots.size(), debounceMs / 1000);
    }

    private void registerAndWatch() {
        try {
            ensureBucket();
            for (Path root : watchRoots) {
                if (!running) return;
                if (Files.isDirectory(root)) {
                    registerRecursive(root);
                } else {
                    log.warn("Watch root not a directory, skipping: {}", root);
                }
            }
            registrationComplete = true;
            if (keyToDir.keySet().stream().noneMatch(WatchKey::isValid)) {
                failRegistration("没有成功注册任何监控目录");
                return;
            }
            if (running) watchLoop();
        } catch (RuntimeException registrationFailure) {
            if (running) {
                failRegistration(registrationFailure.getMessage());
                log.warn("FileWatcher registration failed: {}", registrationFailure.getMessage());
            }
        } finally {
            registrationComplete = true;
        }
    }

    private void failRegistration(String error) {
        registrationError = error;
        running = false;
        ScheduledExecutorService executor = debounceExecutor;
        if (executor != null) executor.shutdownNow();
        try {
            if (watchService != null) watchService.close();
        } catch (IOException ignored) {}
    }

    public void shutdown() {
        running = false;
        if (debounceExecutor != null) debounceExecutor.shutdownNow();
        if (watchThread != null) watchThread.interrupt();
        try {
            if (watchService != null) watchService.close();
        } catch (IOException ignored) {}
        log.info("FileWatcher shut down");
    }

    /** Runtime health used by the desktop status API. */
    public boolean isRunning() {
        Thread thread = watchThread;
        ScheduledExecutorService executor = debounceExecutor;
        return running
                && thread != null
                && thread.isAlive()
                && executor != null
                && !executor.isShutdown()
                && keyToDir.keySet().stream().anyMatch(WatchKey::isValid);
    }

    public boolean isRegistrationComplete() {
        return registrationComplete;
    }

    public String registrationError() {
        return registrationError;
    }

    // ── registration ──

    private void registerRecursive(Path start) {
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!running) return FileVisitResult.TERMINATE;
                    if (pathFilter.isExcludedDir(dir)) return FileVisitResult.SKIP_SUBTREE;
                    registerDir(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to register {}: {}", start, e.getMessage());
        }
    }

    private void registerDir(Path dir) {
        try {
            WatchKey key = dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            keyToDir.put(key, dir);
        } catch (IOException e) {
            log.debug("Failed to register dir {}: {}", dir, e.getMessage());
        }
    }

    // ── watch loop ──

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
            Path dir = keyToDir.get(key);
            if (dir != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path child = dir.resolve(ev.context());
                    handleEvent(ev.kind(), child);
                }
            }
            boolean valid = key.reset();
            if (!valid) {
                keyToDir.remove(key);
            }
        }
    }

    private void handleEvent(WatchEvent.Kind<Path> kind, Path child) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
            // New subdirectory: register recursively (PathFilter applied inside).
            if (!pathFilter.isExcludedDir(child)) registerRecursive(child);
            return;
        }
        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            pending.remove(child);
            String abs = child.toAbsolutePath().toString();
            if (store.findByPath(abs) != null) {
                store.markDeleted(abs); // SPEC-FILE-004
            }
            return;
        }
        // CREATE / MODIFY of a file → debounce (SPEC-FILE-019a); content is read later.
        if (pathFilter.isExcludedFile(child)) return;
        String type = kind == StandardWatchEventKinds.ENTRY_CREATE ? "create" : "modify";
        pending.put(child, new Pending(System.currentTimeMillis(), type));
    }

    // ── debounce flush (SPEC-FILE-019a) ──

    private void flushDebounced() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Path, Pending> e : pending.entrySet()) {
            Path file = e.getKey();
            Pending p = e.getValue();
            if (now - p.lastEventMs() < debounceMs) continue; // still churning → defer
            if (pending.remove(file, p)) {
                try {
                    flushOne(file, p.eventType());
                } catch (Exception ex) {
                    log.debug("Flush failed for {}: {}", file, ex.getMessage());
                }
            }
        }
    }

    private void flushOne(Path file, String eventType) {
        if (!Files.isRegularFile(file) || pathFilter.isExcludedFile(file)) return;
        Path root = matchRoot(file);
        if (root == null) return;
        String abs = file.toAbsolutePath().toString();
        String rel = FileIndexWorker.relativize(root, file);
        String ext = PathFilter.extensionOf(file.getFileName().toString());

        store.upsertPending(abs, rel, root.toAbsolutePath().toString(), ext); // SPEC-FILE-004 path A
        sendHeartbeatThrottled(abs, rel, root, eventType, ext); // SPEC-FILE-012/019c
    }

    private Path matchRoot(Path file) {
        Path absFile = file.toAbsolutePath();
        for (Path root : watchRoots) {
            if (absFile.startsWith(root.toAbsolutePath())) return root;
        }
        return null;
    }

    // ── heartbeat (SPEC-FILE-012b, throttled by SPEC-FILE-019c) ──

    private void sendHeartbeatThrottled(String abs, String rel, Path root,
                                        String eventType, String ext) {
        long now = System.currentTimeMillis();
        Long last = lastHeartbeat.get(abs);
        if (last != null && now - last < heartbeatThrottleMs) return;
        lastHeartbeat.put(abs, now);

        try {
            long size = 0;
            try { size = Files.size(Path.of(abs)); } catch (IOException ignored) {}

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", abs);
            data.put("relative_path", rel);
            data.put("watch_root", root.toAbsolutePath().toString());
            data.put("event_type", eventType);
            data.put("extension", ext);
            data.put("size_bytes", size);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("timestamp", Instant.now().toString());
            body.put("duration", HEARTBEAT_DURATION_S);
            body.put("data", data);

            String json = MAPPER.writeValueAsString(body);
            String url = serverUrl + "/api/0/buckets/" + bucketId
                    + "/heartbeat?pulsetime=" + PULSETIME_S;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // timeline best-effort
        }
    }

    private void ensureBucket() {
        try {
            String url = serverUrl + "/api/0/buckets/" + bucketId;
            Map<String, Object> body = new HashMap<>();
            body.put("id", bucketId);
            body.put("name", "file-watcher bucket");
            body.put("type", "file.changes");
            body.put("client", "aw-watcher-file");
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
            // bucket likely exists
        }
    }

    private static String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
