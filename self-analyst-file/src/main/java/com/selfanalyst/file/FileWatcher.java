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
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Real-time metadata capture path (SPEC-FILE-030..034).
 *
 * <p>A daemon thread drains a NIO {@link WatchService}; events are filtered by
 * {@link PathFilter} and debounced so only files that fall
 * silent for {@code debounceSeconds} are upserted as PENDING. The watcher never
 * reads file content or calls the LLM (SPEC-FILE-002/004) — it only registers
 * metadata collection intent and posts throttled metadata heartbeats over HTTP
 * to the AW timeline.
 *
 * <p><b>Platform note:</b> this uses the JDK default
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
    private static final double PULSETIME_S = 30.0;

    private final FileWatchStore store;
    private final PathFilter pathFilter;
    private final List<Path> watchRoots;
    private final String serverUrl;
    private final long debounceMs;
    private final long heartbeatThrottleMs;
    private final BiConsumer<Path, Path> reconcileRequester;

    private final HttpClient httpClient;
    private final String hostname;
    private final String bucketId;

    private WatchService watchService;
    private final Map<WatchKey, Path> keyToDir = new ConcurrentHashMap<>();
    private final Set<Path> registeredDirs = ConcurrentHashMap.newKeySet();
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
        this(store, pathFilter, watchRoots, serverUrl, debounceSeconds,
                heartbeatThrottleSeconds, (root, subtree) -> {});
    }

    public FileWatcher(FileWatchStore store, PathFilter pathFilter, List<Path> watchRoots,
                       String serverUrl, int debounceSeconds, int heartbeatThrottleSeconds,
                       BiConsumer<Path, Path> reconcileRequester) {
        this.store = store;
        this.pathFilter = pathFilter;
        this.watchRoots = watchRoots;
        this.serverUrl = serverUrl;
        this.debounceMs = Math.max(0, debounceSeconds) * 1000L;
        this.heartbeatThrottleMs = Math.max(0, heartbeatThrottleSeconds) * 1000L;
        this.reconcileRequester = reconcileRequester != null
                ? reconcileRequester : (root, subtree) -> {};
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.hostname = getHostname();
        this.bucketId = "aw-watcher-file_" + hostname;
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
        debounceExecutor.scheduleWithFixedDelay(this::periodicMaintenance, 1, 1, TimeUnit.SECONDS);
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
                    registerRecursive(root, root);
                    reconcileRequester.accept(root.toAbsolutePath().normalize(),
                            root.toAbsolutePath().normalize());
                } else {
                    log.warn("Configured watch root is unavailable");
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
                log.warn("FileWatcher registration failed type={}",
                        registrationFailure.getClass().getSimpleName());
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

    private void registerRecursive(Path root, Path start) {
        try {
            Files.walkFileTree(start, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!running) return FileVisitResult.TERMINATE;
                    if (isNestedWatchRoot(root, dir)) return FileVisitResult.SKIP_SUBTREE;
                    if (pathFilter.evaluateDirectory(root, dir, attrs).excluded()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    registerDir(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to register watch subtree type={}", e.getClass().getSimpleName());
        }
    }

    private void registerDir(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        if (!registeredDirs.add(normalized)) return;
        try {
            WatchKey key = normalized.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            keyToDir.put(key, normalized);
        } catch (IOException e) {
            registeredDirs.remove(normalized);
            log.debug("Failed to register watch directory type={}", e.getClass().getSimpleName());
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
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        handleOverflow(dir);
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path child = dir.resolve(ev.context());
                    handleEvent(ev.kind(), child);
                }
            }
            if (!key.reset() && retireInvalidDir(keyToDir.get(key))) {
                keyToDir.remove(key);
            }
        }
    }

    /**
     * Converges an invalidated registration (SPEC-FILE-032a). Returns true once the
     * directory is confirmed gone and its descendants are retired; a delete still
     * pending in the OS leaves the entry in place so a later sweep can retry.
     */
    private boolean retireInvalidDir(Path invalidDir) {
        if (invalidDir == null) return true;
        registeredDirs.remove(invalidDir);
        pathFilter.invalidateIgnoreRules(invalidDir);
        if (!Files.notExists(invalidDir)) return false;
        try {
            store.markDeletedTree(invalidDir.toAbsolutePath().toString());
        } catch (RuntimeException e) {
            log.debug("Failed to retire invalid watch tree type={}",
                    e.getClass().getSimpleName());
        }
        return true;
    }

    /**
     * A WatchKey can be invalidated without the service ever queueing it, so the
     * watch loop alone cannot guarantee convergence; sweep the registrations too.
     */
    private void retireInvalidRegistrations() {
        for (Map.Entry<WatchKey, Path> entry : keyToDir.entrySet()) {
            if (entry.getKey().isValid()) continue;
            if (retireInvalidDir(entry.getValue())) keyToDir.remove(entry.getKey());
        }
    }

    void handleOverflow(Path directory) {
        Path root = matchRoot(directory);
        if (root == null) return;
        pathFilter.invalidateIgnoreRules(directory);
        registerRecursive(root, directory);
        reconcileRequester.accept(root, directory);
    }

    private void handleEvent(WatchEvent.Kind<Path> kind, Path child) {
        Path root = matchRoot(child);
        if (root == null) return;
        if (GitIgnoreResolver.isRuleFile(child)) {
            Path directory = child.getParent() != null ? child.getParent() : root;
            pathFilter.invalidateIgnoreRules(directory);
            registerRecursive(root, directory);
            reconcileRequester.accept(root, directory);
            return;
        }
        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            pending.remove(child);
            // The deleted node can no longer be stat'ed. Registered paths are
            // known directories; ordinary file deletion must not flush the
            // global ignore generation in high-churn repositories.
            if (registeredDirs.contains(child.toAbsolutePath().normalize())) {
                pathFilter.invalidateIgnoreRules(child);
            }
            String abs = child.toAbsolutePath().toString();
            store.markDeletedTree(abs);
            return;
        }
        final BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(
                    child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException missingOrInaccessible) {
            return;
        }
        if (kind == StandardWatchEventKinds.ENTRY_CREATE && attrs.isDirectory()) {
            pathFilter.invalidateIgnoreRules(child);
            if (!pathFilter.evaluateDirectory(root, child, attrs).excluded()) {
                registerRecursive(root, child);
                reconcileRequester.accept(root, child);
            }
            return;
        }
        // CREATE / MODIFY of a file → debounce; only metadata is read later.
        if (pathFilter.evaluateFile(root, child, attrs).excluded()) return;
        String type = kind == StandardWatchEventKinds.ENTRY_CREATE ? "create" : "modify";
        pending.put(child, new Pending(System.currentTimeMillis(), type));
    }

    // ── debounce flush ──

    private void periodicMaintenance() {
        try {
            retireInvalidRegistrations();
        } catch (RuntimeException e) {
            log.debug("Invalid registration sweep failed type={}", e.getClass().getSimpleName());
        }
        flushDebounced();
    }

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
                    log.debug("Metadata debounce flush failed type={}",
                            ex.getClass().getSimpleName());
                }
            }
        }
    }

    private void flushOne(Path file, String eventType) {
        Path root = matchRoot(file);
        if (root == null) return;
        final BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException missingOrInaccessible) {
            return;
        }
        if (pathFilter.evaluateFile(root, file, attrs).excluded()) return;
        String abs = file.toAbsolutePath().toString();
        String rel = FileIndexWorker.relativize(root, file);
        String ext = PathFilter.extensionOf(file.getFileName().toString());

        store.upsertPending(abs, rel, root.toAbsolutePath().toString(), ext);
        sendHeartbeatThrottled(abs, rel, root, eventType, ext, attrs);
    }

    private Path matchRoot(Path file) {
        Path absFile = file.toAbsolutePath().normalize();
        Path best = null;
        for (Path root : watchRoots) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (absFile.startsWith(normalizedRoot)
                    && (best == null || normalizedRoot.getNameCount() > best.getNameCount())) {
                best = normalizedRoot;
            }
        }
        return best;
    }

    private boolean isNestedWatchRoot(Path currentRoot, Path directory) {
        Path root = currentRoot.toAbsolutePath().normalize();
        Path dir = directory.toAbsolutePath().normalize();
        if (dir.equals(root)) return false;
        return watchRoots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .anyMatch(dir::equals);
    }

    // ── metadata heartbeat (SPEC-FILE-033/034) ──

    private void sendHeartbeatThrottled(String abs, String rel, Path root,
                                        String eventType, String ext,
                                        BasicFileAttributes attrs) {
        long now = System.currentTimeMillis();
        Long last = lastHeartbeat.get(abs);
        if (last != null && now - last < heartbeatThrottleMs) return;
        lastHeartbeat.put(abs, now);

        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", abs);
            data.put("relative_path", rel);
            data.put("watch_root", root.toAbsolutePath().toString());
            data.put("event_type", eventType);
            data.put("extension", ext);
            data.put("size_bytes", attrs.size());
            data.put("file_created_at", attrs.creationTime().toInstant().toString());
            data.put("last_modified", attrs.lastModifiedTime().toInstant().toString());

            String url = serverUrl + "/api/0/buckets/" + bucketId
                    + "/heartbeat?pulsetime=" + PULSETIME_S;
            new FileHeartbeatDelivery().send(data, Instant.now(), HEARTBEAT_DURATION_S, body -> {
                String json = MAPPER.writeValueAsString(body);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                int status = httpClient.send(request,
                        HttpResponse.BodyHandlers.discarding()).statusCode();
                return status >= 200 && status < 300;
            });
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
