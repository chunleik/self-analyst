package com.selfanalyst.file.semantic;

import com.selfanalyst.file.FileRecord;
import com.selfanalyst.file.FileWatchStore;
import com.selfanalyst.wiki.semantic.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embeds indexed files into {@link FileSemanticIndex} (SPEC-FILE-016).
 *
 * <p>Sole writer of the Lucene index (SPEC-FILE-016a). Work arrives two ways:
 * {@link #enqueue(String)} from the index worker after a fresh summary, and a
 * startup reconcile that re-enqueues every INDEXED row. Already-embedded files
 * (same path + hash) are skipped via {@link FileSemanticIndex#isIndexed}.
 */
public class FileEmbeddingWorker {

    private static final Logger log = LoggerFactory.getLogger(FileEmbeddingWorker.class);

    private final FileWatchStore store;
    private final FileSemanticIndex index;
    private final EmbeddingClient embeddingClient;
    private final int intervalSeconds;

    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private final Set<String> metadataRefreshPaths = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private volatile List<String> activeWatchRoots;
    private volatile Set<String> activeWatchRootSet;

    public FileEmbeddingWorker(FileWatchStore store, FileSemanticIndex index,
                               EmbeddingClient embeddingClient, int intervalSeconds) {
        this.store = store;
        this.index = index;
        this.embeddingClient = embeddingClient;
        this.intervalSeconds = Math.max(5, intervalSeconds);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "file-embedding-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void updateWatchRoots(List<Path> watchRoots) {
        List<String> normalized = watchRoots == null ? List.of() : watchRoots.stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .distinct()
                .toList();
        activeWatchRoots = normalized;
        activeWatchRootSet = Set.copyOf(new LinkedHashSet<>(normalized));
        queue.clear();
        metadataRefreshPaths.clear();
        if (running.get()) enqueueIndexedForActiveRoots();
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        queue.clear();
        enqueueIndexedForActiveRoots();
        if (scheduled.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(this::processOneRound,
                    intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        }
        log.info("FileEmbeddingWorker started (interval={}s)", intervalSeconds);
    }

    private void enqueueIndexedForActiveRoots() {
        try {
            List<FileRecord> indexed = activeWatchRoots == null
                    ? store.findIndexed(10_000)
                    : store.findIndexed(activeWatchRoots, 10_000);
            for (FileRecord r : indexed) {
                queue.offer(r.absolutePath());
            }
        } catch (Exception e) {
            log.warn("Embedding reconcile failed: {}", e.getMessage());
        }
    }

    /** Called by FileIndexWorker right after a successful summary (SPEC-FILE-004 path B). */
    public void enqueue(String absolutePath) {
        if (running.get() && absolutePath != null) queue.offer(absolutePath);
    }

    /** Rebuild semantic metadata when an unchanged file is reassigned to another watch root. */
    public void enqueueMetadataRefresh(String absolutePath) {
        if (!running.get() || absolutePath == null) return;
        metadataRefreshPaths.add(absolutePath);
        queue.offer(absolutePath);
    }

    synchronized void processOneRound() {
        if (!running.get()) return;
        String path = queue.poll();
        if (path == null) return;
        boolean metadataRefresh = metadataRefreshPaths.remove(path);
        try {
            FileRecord rec = store.findByPath(path);
            if (rec == null || rec.status() != com.selfanalyst.file.FileStatus.INDEXED
                    || rec.summary() == null || rec.summary().isBlank()) {
                return;
            }
            if (!isActiveRoot(rec.watchRoot())) return;
            if (!metadataRefresh && index.isIndexed(path, rec.fileHash(), rec.watchRoot())) {
                return; // already embedded at this content hash
            }
            String topics = rec.mainTopics() != null ? String.join(", ", rec.mainTopics()) : "";
            String text = buildIndexText(rec, topics);
            float[] vector = embeddingClient.embedSingle(text);
            if (!running.get() || !isActiveRoot(rec.watchRoot())) return;
            index.indexDocument(rec.absolutePath(), rec.watchRoot(), rec.extension(),
                    rec.lastModified(), rec.summary(), topics, rec.fileHash(), vector);
            log.debug("Embedded file {}", path);
        } catch (Exception e) {
            log.warn("Embedding failed for {}: {}", path, e.getMessage());
            if (running.get()) queue.offer(path);
        }
    }

    private static String buildIndexText(FileRecord rec, String topics) {
        StringBuilder sb = new StringBuilder();
        if (rec.relativePath() != null) sb.append(rec.relativePath()).append(' ');
        if (rec.summary() != null) sb.append(rec.summary()).append(' ');
        if (!topics.isBlank()) sb.append(topics);
        return sb.toString().trim();
    }

    public synchronized void pause() {
        running.set(false);
        queue.clear();
        metadataRefreshPaths.clear();
        log.info("FileEmbeddingWorker paused");
    }

    public void shutdown() {
        synchronized (this) {
            running.set(false);
            queue.clear();
            metadataRefreshPaths.clear();
            executor.shutdownNow();
        }
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("FileEmbeddingWorker shut down");
    }

    /** Runtime health used by the desktop collector status. */
    public boolean isRunning() {
        return running.get() && !executor.isShutdown() && !executor.isTerminated();
    }

    private boolean isActiveRoot(String watchRoot) {
        Set<String> roots = activeWatchRootSet;
        if (roots == null) return true;
        if (watchRoot == null) return false;
        try {
            return roots.contains(Path.of(watchRoot).toAbsolutePath().normalize().toString());
        } catch (InvalidPathException ignored) {
            return false;
        }
    }
}
