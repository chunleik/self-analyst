package com.selfanalyst.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Metadata-only file collection worker (SPEC-FILE-040..043).
 *
 * <p>The historical class name is retained for compatibility, but the worker
 * never opens a file byte stream. It reads only {@link BasicFileAttributes}
 * and persists title/path metadata.
 */
public class FileIndexWorker {

    private static final Logger log = LoggerFactory.getLogger(FileIndexWorker.class);
    private static final int BATCH_SIZE = 256;

    private final FileWatchStore store;
    private final PathFilter pathFilter;
    private final List<Path> watchRoots;
    private final List<String> activeWatchRoots;
    private final Set<String> activeWatchRootSet;
    private final int intervalSeconds;
    private final Runnable beforeRetireMissing;
    private final Map<Path, Path> pendingReconciles = new LinkedHashMap<>();
    private final AtomicBoolean reconcileDrainScheduled = new AtomicBoolean(false);
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public FileIndexWorker(FileWatchStore store, PathFilter pathFilter,
                           List<Path> watchRoots, int intervalSeconds) {
        this(store, pathFilter, watchRoots, intervalSeconds, () -> {});
    }

    FileIndexWorker(FileWatchStore store, PathFilter pathFilter,
                    List<Path> watchRoots, int intervalSeconds,
                    Runnable beforeRetireMissing) {
        this.store = store;
        this.pathFilter = pathFilter;
        this.watchRoots = watchRoots == null ? List.of() : watchRoots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
        this.activeWatchRoots = this.watchRoots.stream().map(Path::toString).toList();
        this.activeWatchRootSet = Set.copyOf(new LinkedHashSet<>(activeWatchRoots));
        this.intervalSeconds = Math.max(5, intervalSeconds);
        this.beforeRetireMissing = beforeRetireMissing != null ? beforeRetireMissing : () -> {};
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "file-metadata-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                reconcileScan();
                if (running.get() && !cancelled.get()) processOneRound();
            } catch (Exception e) {
                if (!cancelled.get()) log.warn("File metadata reconcile failed ({})", errorType(e));
            }
        });
        executor.scheduleWithFixedDelay(this::tick,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("File metadata worker started (interval={}s)", intervalSeconds);
    }

    public void shutdown() {
        cancelled.set(true);
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("File metadata worker shut down");
    }

    public boolean isRunning() {
        return running.get() && !executor.isShutdown() && !executor.isTerminated();
    }

    /** Queue one coalesced subtree reconcile per active root. */
    public void requestReconcile(Path watchRoot, Path subtree) {
        if (!running.get() || cancelled.get() || watchRoot == null || subtree == null) return;
        Path root = watchRoot.toAbsolutePath().normalize();
        Path child = subtree.toAbsolutePath().normalize();
        if (!activeWatchRootSet.contains(root.toString()) || !child.startsWith(root)) return;
        synchronized (pendingReconciles) {
            pendingReconciles.merge(root, child, FileIndexWorker::commonAncestor);
        }
        if (reconcileDrainScheduled.compareAndSet(false, true)) {
            executor.execute(this::drainReconcileRequests);
        }
    }

    void reconcileScan() throws IOException {
        for (Path root : watchRoots) {
            if (cancelled.get()) return;
            reconcileSubtree(root, root);
        }
    }

    private void reconcileSubtree(Path root, Path subtree) throws IOException {
        if (cancelled.get()) return;
        if (!Files.isDirectory(subtree, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.notExists(subtree, LinkOption.NOFOLLOW_LINKS)) {
                store.markDeletedTree(subtree.toAbsolutePath().normalize().toString());
            }
            return;
        }
        Instant scanStartedAt = Instant.now();
        Set<String> seen = new HashSet<>();
        boolean[] traversalComplete = {true};
        Files.walkFileTree(subtree, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (cancelled.get()) return FileVisitResult.TERMINATE;
                    if (isNestedWatchRoot(root, dir)) return FileVisitResult.SKIP_SUBTREE;
                    PathFilter.FilterDecision decision =
                            pathFilter.evaluateDirectory(root, dir, attrs);
                    if (!decision.determinate()) traversalComplete[0] = false;
                    return decision.excluded()
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (cancelled.get()) return FileVisitResult.TERMINATE;
                    try {
                        PathFilter.FilterDecision decision = pathFilter.evaluateFile(root, file, attrs);
                        if (!decision.determinate()) traversalComplete[0] = false;
                        if (!decision.excluded()) {
                            seen.add(file.toAbsolutePath().toString());
                            maybeEnqueue(root, file, attrs);
                        }
                    } catch (Exception e) {
                        log.debug("Metadata reconcile skipped {} ({})", file, errorType(e));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    traversalComplete[0] = false;
                    return FileVisitResult.CONTINUE;
                }
        });
        if (!cancelled.get() && traversalComplete[0]) {
            beforeRetireMissing.run();
            retireMissingFiles(root, subtree, seen, scanStartedAt);
        }
    }

    private void retireMissingFiles(Path root, Path subtree, Set<String> seen, Instant scanStartedAt) {
        String watchRoot = root.toAbsolutePath().normalize().toString();
        Path normalizedSubtree = subtree.toAbsolutePath().normalize();
        for (FileRecord record : store.findActiveByWatchRoot(watchRoot)) {
            Path recordPath;
            try {
                recordPath = Path.of(record.absolutePath()).toAbsolutePath().normalize();
            } catch (InvalidPathException invalidPath) {
                store.markDeleted(record.absolutePath());
                continue;
            }
            if (!recordPath.startsWith(normalizedSubtree)) continue;
            if (seen.contains(record.absolutePath())) continue;
            if (record.updatedAt() != null && record.updatedAt().isAfter(scanStartedAt)) continue;
            try {
                if (Files.notExists(recordPath, LinkOption.NOFOLLOW_LINKS)) {
                    store.markDeleted(record.absolutePath());
                    continue;
                }
                BasicFileAttributes attrs = Files.readAttributes(
                        recordPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                PathFilter.FilterDecision decision = pathFilter.evaluateFile(root, recordPath, attrs);
                if (decision.determinate() && decision.excluded()) {
                    store.markDeleted(record.absolutePath());
                }
            } catch (IOException accessError) {
                // Fail closed for collection, but do not retire on ambiguous access errors.
            }
        }
    }

    private void maybeEnqueue(Path root, Path file, BasicFileAttributes attrs) {
        String abs = file.toAbsolutePath().toString();
        FileRecord existing = store.findByPath(abs);
        String currentRoot = root.toAbsolutePath().normalize().toString();
        String currentRelativePath = relativize(root, file);
        String currentExtension = PathFilter.extensionOf(file.getFileName().toString());
        Instant created = attrs.creationTime().toInstant();
        Instant modified = attrs.lastModifiedTime().toInstant();
        boolean locationChanged = existing != null
                && (!currentRoot.equals(existing.watchRoot())
                || !currentRelativePath.equals(existing.relativePath()));
        if (locationChanged) {
            store.updateWatchLocation(abs, currentRelativePath, currentRoot, currentExtension);
        }
        boolean changed = existing == null
                || existing.status() == FileStatus.DELETED
                || existing.fileCreatedAt() == null
                || existing.lastModified() == null
                || existing.sizeBytes() != attrs.size()
                || !created.equals(existing.fileCreatedAt())
                || !modified.equals(existing.lastModified());
        if (existing != null
                && (existing.status() == FileStatus.PENDING || existing.status() == FileStatus.FAILED)) {
            return;
        }
        if (changed) upsert(root, file);
    }

    private void upsert(Path root, Path file) {
        store.upsertPending(file.toAbsolutePath().toString(), relativize(root, file),
                root.toAbsolutePath().normalize().toString(),
                PathFilter.extensionOf(file.getFileName().toString()));
    }

    static String relativize(Path root, Path file) {
        try {
            return root.toAbsolutePath().relativize(file.toAbsolutePath()).toString();
        } catch (Exception e) {
            return file.getFileName().toString();
        }
    }

    private void tick() {
        if (running.get()) processOneRound();
    }

    private void drainReconcileRequests() {
        try {
            while (running.get() && !cancelled.get()) {
                Map.Entry<Path, Path> request;
                synchronized (pendingReconciles) {
                    var iterator = pendingReconciles.entrySet().iterator();
                    if (!iterator.hasNext()) break;
                    request = iterator.next();
                    iterator.remove();
                }
                try {
                    reconcileSubtree(request.getKey(), request.getValue());
                    processOneRound();
                } catch (IOException error) {
                    log.warn("File metadata subtree reconcile failed ({})", errorType(error));
                }
            }
        } finally {
            reconcileDrainScheduled.set(false);
            synchronized (pendingReconciles) {
                if (!pendingReconciles.isEmpty()
                        && running.get() && reconcileDrainScheduled.compareAndSet(false, true)) {
                    executor.execute(this::drainReconcileRequests);
                }
            }
        }
    }

    /** Process a bounded metadata batch without opening any file content stream. */
    void processOneRound() {
        if (cancelled.get()) return;
        try {
            int processed = 0;
            for (FileRecord rec : store.findPending(activeWatchRoots, BATCH_SIZE)) {
                processOne(rec);
                if (++processed >= BATCH_SIZE || cancelled.get()) return;
            }
            int remaining = BATCH_SIZE - processed;
            if (remaining <= 0) return;
            for (FileRecord rec : store.findRetryable(activeWatchRoots, remaining)) {
                processOne(rec);
                if (cancelled.get()) return;
            }
        } catch (Exception e) {
            log.warn("File metadata worker round failed ({})", errorType(e));
        }
    }

    private void processOne(FileRecord rec) {
        String abs = rec.absolutePath();
        if (cancelled.get() || !isActiveRoot(rec.watchRoot())) return;
        Path file = Path.of(abs);
        try {
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                store.markDeleted(abs);
                return;
            }
            BasicFileAttributes attrs = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path root = Path.of(rec.watchRoot()).toAbsolutePath().normalize();
            PathFilter.FilterDecision decision = pathFilter.evaluateFile(root, file, attrs);
            if (!decision.determinate()) {
                throw new IOException("File filter decision is indeterminate");
            }
            if (decision.excluded()) {
                store.markDeleted(abs);
                return;
            }
            store.updateCollected(abs, attrs.size(), attrs.creationTime().toInstant(),
                    attrs.lastModifiedTime().toInstant());
            log.debug("Collected file metadata {}", abs);
        } catch (Exception e) {
            if (cancelled.get() || !isActiveRoot(rec.watchRoot())) return;
            int retryCount = rec.retryCount() + 1;
            long delayMinutes = (long) Math.min(1440, Math.pow(2, retryCount));
            store.markFailed(abs, "FILE_METADATA_FAILED:" + errorType(e),
                    Instant.now().plus(Duration.ofMinutes(delayMinutes)));
        }
    }

    private boolean isActiveRoot(String watchRoot) {
        if (watchRoot == null) return false;
        try {
            return activeWatchRootSet.contains(
                    Path.of(watchRoot).toAbsolutePath().normalize().toString());
        } catch (InvalidPathException ignored) {
            return false;
        }
    }

    private boolean isNestedWatchRoot(Path currentRoot, Path directory) {
        Path root = currentRoot.toAbsolutePath().normalize();
        Path dir = directory.toAbsolutePath().normalize();
        return !dir.equals(root) && activeWatchRootSet.contains(dir.toString());
    }

    private static String errorType(Throwable error) {
        return error == null ? "Unknown" : error.getClass().getSimpleName();
    }

    private static Path commonAncestor(Path left, Path right) {
        Path candidate = left;
        while (candidate != null && !right.startsWith(candidate)) candidate = candidate.getParent();
        return candidate != null ? candidate : left;
    }
}
