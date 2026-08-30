package com.selfanalyst.file.semantic;

import com.selfanalyst.file.FileRecord;
import com.selfanalyst.file.FileWatchStore;
import com.selfanalyst.wiki.semantic.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

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

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        // Reconcile: re-enqueue all INDEXED rows; already-embedded ones are skipped.
        try {
            for (FileRecord r : store.findIndexed(10_000)) {
                queue.offer(r.absolutePath());
            }
        } catch (Exception e) {
            log.warn("Embedding reconcile failed: {}", e.getMessage());
        }
        executor.scheduleWithFixedDelay(this::processOneRound,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("FileEmbeddingWorker started (interval={}s)", intervalSeconds);
    }

    /** Called by FileIndexWorker right after a successful summary (SPEC-FILE-004 path B). */
    public void enqueue(String absolutePath) {
        if (absolutePath != null) queue.offer(absolutePath);
    }

    void processOneRound() {
        if (!running.get()) return;
        String path = queue.poll();
        if (path == null) return;
        try {
            FileRecord rec = store.findByPath(path);
            if (rec == null || rec.status() != com.selfanalyst.file.FileStatus.INDEXED
                    || rec.summary() == null || rec.summary().isBlank()) {
                return;
            }
            if (index.isIndexed(path, rec.fileHash())) {
                return; // already embedded at this content hash
            }
            String topics = rec.mainTopics() != null ? String.join(", ", rec.mainTopics()) : "";
            String text = buildIndexText(rec, topics);
            float[] vector = embeddingClient.embedSingle(text);
            index.indexDocument(rec.absolutePath(), rec.watchRoot(), rec.extension(),
                    rec.lastModified(), rec.summary(), topics, rec.fileHash(), vector);
            log.debug("Embedded file {}", path);
        } catch (Exception e) {
            log.warn("Embedding failed for {}: {}", path, e.getMessage());
            // Best-effort: dropped from queue; startup reconcile will retry later.
        }
    }

    private static String buildIndexText(FileRecord rec, String topics) {
        StringBuilder sb = new StringBuilder();
        if (rec.relativePath() != null) sb.append(rec.relativePath()).append(' ');
        if (rec.summary() != null) sb.append(rec.summary()).append(' ');
        if (!topics.isBlank()) sb.append(topics);
        return sb.toString().trim();
    }

    public void shutdown() {
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
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
}
