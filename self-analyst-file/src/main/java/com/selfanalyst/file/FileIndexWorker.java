package com.selfanalyst.file;

import com.selfanalyst.file.extractor.FileContentExtractor;
import com.selfanalyst.file.extractor.FileContentExtractorFactory;
import com.selfanalyst.file.semantic.FileEmbeddingWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous processing path (SPEC-FILE-013, SPEC-FILE-004 path B).
 *
 * <p>On start does one reconcile scan of every watch root (SPEC-FILE-003a: no
 * heartbeats), then on a fixed schedule takes one PENDING (or retryable FAILED)
 * row per round and runs {@code extract → truncate → summarize → updateIndexed
 * → enqueueEmbedding}, with cheap (size,mtime) prefiltering and SHA-256 change
 * detection so unchanged files are never re-summarized (SPEC-FILE-010d).
 */
public class FileIndexWorker {

    private static final Logger log = LoggerFactory.getLogger(FileIndexWorker.class);

    private final FileWatchStore store;
    private final PathFilter pathFilter;
    private final FileContentExtractorFactory extractorFactory;
    private final FileSummarizer summarizer;
    private final FileEmbeddingWorker embeddingWorker; // nullable
    private final List<Path> watchRoots;
    private final int intervalSeconds;
    private final int maxContentChars;
    private final Duration minReindexInterval;

    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public FileIndexWorker(FileWatchStore store, PathFilter pathFilter,
                           FileContentExtractorFactory extractorFactory, FileSummarizer summarizer,
                           FileEmbeddingWorker embeddingWorker, List<Path> watchRoots,
                           int intervalSeconds, int maxContentChars, int minReindexIntervalMinutes) {
        this.store = store;
        this.pathFilter = pathFilter;
        this.extractorFactory = extractorFactory;
        this.summarizer = summarizer;
        this.embeddingWorker = embeddingWorker;
        this.watchRoots = watchRoots;
        this.intervalSeconds = Math.max(5, intervalSeconds);
        this.maxContentChars = Math.max(500, maxContentChars);
        this.minReindexInterval = Duration.ofMinutes(Math.max(0, minReindexIntervalMinutes));
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "file-index-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        try {
            reconcileScan();
        } catch (Exception e) {
            log.warn("File reconcile scan failed ({})", errorType(e));
        }
        executor.scheduleWithFixedDelay(this::tick,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("FileIndexWorker started (interval={}s, maxContentChars={})",
                intervalSeconds, maxContentChars);
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
        log.info("FileIndexWorker shut down");
    }

    /** Runtime health used by the desktop collector status. */
    public boolean isRunning() {
        return running.get() && !executor.isShutdown() && !executor.isTerminated();
    }

    // ── reconcile scan (SPEC-FILE-013, SPEC-FILE-003a) ──

    private void reconcileScan() throws IOException {
        for (Path root : watchRoots) {
            if (!Files.isDirectory(root)) continue;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return pathFilter.isExcludedDir(dir)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        if (!attrs.isRegularFile() || pathFilter.isExcludedFile(file)) {
                            return FileVisitResult.CONTINUE;
                        }
                        maybeEnqueue(root, file, attrs);
                    } catch (Exception e) {
                        log.debug("Reconcile skip {} ({})", file, errorType(e));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /** Enqueue a file found during scan only if it is new or changed vs its indexed snapshot. */
    private void maybeEnqueue(Path root, Path file, BasicFileAttributes attrs) {
        String abs = file.toAbsolutePath().toString();
        FileRecord existing = store.findByPath(abs);
        boolean changed = existing == null
                || existing.status() == FileStatus.DELETED
                || existing.lastModified() == null
                || existing.sizeBytes() != attrs.size()
                || !attrs.lastModifiedTime().toInstant().equals(existing.lastModified());
        // PENDING/FAILED rows are already queued; don't disturb them.
        if (existing != null
                && (existing.status() == FileStatus.PENDING || existing.status() == FileStatus.FAILED)) {
            return;
        }
        if (changed) {
            upsert(root, file);
        }
    }

    private void upsert(Path root, Path file) {
        String abs = file.toAbsolutePath().toString();
        String rel = relativize(root, file);
        String ext = PathFilter.extensionOf(file.getFileName().toString());
        store.upsertPending(abs, rel, root.toAbsolutePath().toString(), ext);
    }

    static String relativize(Path root, Path file) {
        try {
            return root.toAbsolutePath().relativize(file.toAbsolutePath()).toString();
        } catch (Exception e) {
            return file.getFileName().toString();
        }
    }

    // ── periodic processing (SPEC-FILE-004 path B) ──

    private void tick() {
        if (!running.get()) return;
        processOneRound();
    }

    /** Process at most one PENDING (else one retryable FAILED) row. Package-visible for tests. */
    void processOneRound() {
        try {
            List<FileRecord> pending = store.findPending(1);
            for (FileRecord rec : pending) {
                processOne(rec);
                return;
            }
            List<FileRecord> retryable = store.findRetryable(1);
            for (FileRecord rec : retryable) {
                processOne(rec);
                return;
            }
        } catch (Exception e) {
            log.warn("FileIndexWorker round failed ({})", errorType(e));
        }
    }

    private void processOne(FileRecord rec) {
        String abs = rec.absolutePath();
        Path file = Path.of(abs);
        try {
            if (!Files.isRegularFile(file)) {
                store.markDeleted(abs);
                return;
            }

            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            long curSize = attrs.size();
            Instant curMtime = attrs.lastModifiedTime().toInstant();

            // SPEC-FILE-010d cheap prefilter: same (size,mtime) as last index → unchanged.
            boolean cheapUnchanged = rec.fileHash() != null
                    && rec.lastModified() != null
                    && curSize == rec.sizeBytes()
                    && curMtime.equals(rec.lastModified());
            if (cheapUnchanged) {
                store.updateChecksum(abs, curSize, curMtime, rec.fileHash());
                return;
            }

            String hash = sha256(file);
            if (rec.fileHash() != null && rec.fileHash().equals(hash)) {
                // content identical (touch / metadata-only change) → no re-summary.
                store.updateChecksum(abs, curSize, curMtime, hash);
                return;
            }

            // SPEC-FILE-019b: per-file minimum re-summary interval. Leave PENDING for a later round.
            if (rec.lastIndexedAt() != null && !minReindexInterval.isZero()
                    && Duration.between(rec.lastIndexedAt(), Instant.now()).compareTo(minReindexInterval) < 0) {
                log.debug("Skip {} this round: within min re-index interval", abs);
                return;
            }

            String content = extractWithFallback(rec.extension(), file);
            String truncated = truncateByCodepoint(content, maxContentChars);

            FileSummarizer.FileSummaryResult result =
                    summarizer.summarize(rec.relativePath(), rec.extension(), curMtime, truncated);

            store.updateIndexed(abs, curSize, curMtime, hash, result.summary(),
                    result.mainTopics(), "llm", summarizer.promptVersion());

            if (embeddingWorker != null) {
                embeddingWorker.enqueue(abs);
            }
            log.debug("Indexed file {}", abs);
        } catch (com.selfanalyst.wiki.usage.BudgetExceededException be) {
            // 达到每日 token 预算：保持 PENDING，下个周期/次日重试，不计入失败重试次数
            log.debug("File {} 因 token 预算暂停，保持 PENDING 稍后重试", abs);
        } catch (Exception e) {
            log.warn("File index failed for {} ({})", abs, errorType(e));
            int retryCount = rec.retryCount() + 1;
            long delayMinutes = (long) Math.min(1440, Math.pow(2, retryCount));
            store.markFailed(abs, "FILE_INDEX_FAILED:" + errorType(e),
                    Instant.now().plus(Duration.ofMinutes(delayMinutes)));
        }
    }

    /** Dedicated extractor; on failure fall back to metadata-only (SPEC-FILE-013b). */
    private String extractWithFallback(String extension, Path file) {
        FileContentExtractor extractor = extractorFactory.forExtension(extension);
        try {
            String text = extractor.extract(file);
            if (text == null || text.isBlank()) {
                return extractorFactory.metadataOnly().extract(file);
            }
            return text;
        } catch (Exception e) {
            log.debug("Extractor failed for {} ({}), using metadata fallback ({})",
                    file, extension, errorType(e));
            try {
                return extractorFactory.metadataOnly().extract(file);
            } catch (Exception e2) {
                return "文件名: " + file.getFileName();
            }
        }
    }

    /** Truncate by Unicode codepoint, not char, so surrogate pairs stay intact (SPEC-FILE-018f). */
    static String truncateByCodepoint(String s, int maxCodepoints) {
        if (s == null) return "";
        int count = s.codePointCount(0, s.length());
        if (count <= maxCodepoints) return s;
        int end = s.offsetByCodePoints(0, maxCodepoints);
        return s.substring(0, end);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String errorType(Throwable error) {
        return error == null ? "Unknown" : error.getClass().getSimpleName();
    }
}
