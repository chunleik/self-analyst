package com.selfanalyst.file;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end watcher tests over a real NIO WatchService. Heartbeats target a
 * refused port and fail silently; only the store side is asserted.
 */
class FileWatcherTest {

    /**
     * Windows WatchService 的事件投递延迟在共享 CI 运行器上偶尔会远超本地水平，
     * 20 秒预算下 FileWatcherTest 约有十分之一的概率超时。轮询命中即返回，
     * 放宽预算不影响正常耗时，只在异常停顿时多等一会儿。
     */
    private static final long POLL_TIMEOUT_MS = 60_000;

    private FileWatchStore store;
    private FileWatcher watcher;

    @AfterEach
    void tearDown() {
        if (watcher != null) watcher.shutdown();
        if (store != null) store.close();
    }

    @Test
    void createdFileBecomesPendingAfterDebounce(@TempDir Path tmp) throws Exception {
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        PathFilter filter = txtFilter();
        watcher = new FileWatcher(store, filter, List.of(tmp), "http://127.0.0.1:9", 1, 1);
        watcher.start();
        assertTrue(pollForAvailable(POLL_TIMEOUT_MS));

        Path file = tmp.resolve("hello.txt");
        Files.writeString(file, "content");

        FileRecord rec = pollForPending(file.toAbsolutePath().toString(), POLL_TIMEOUT_MS);
        assertNotNull(rec, "file should be upserted PENDING after the debounce window");
        assertEquals(FileStatus.PENDING, rec.status());

        watcher.shutdown();
        assertFalse(watcher.isRunning());
    }

    @Test
    void excludedFileNeverEnqueued(@TempDir Path tmp) throws Exception {
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        PathFilter filter = txtFilter();
        watcher = new FileWatcher(store, filter, List.of(tmp), "http://127.0.0.1:9", 1, 1);
        watcher.start();

        Path log = tmp.resolve("app.log"); // volatile → excluded (SPEC-FILE-023)
        Files.writeString(log, "noise");

        // give the watcher more than the debounce window to (not) act
        Thread.sleep(3_000);
        assertNull(store.findByPath(log.toAbsolutePath().toString()),
                "volatile file must not be enqueued");
    }

    @Test
    void reportsUnavailableAfterAllWatchRegistrationsBecomeInvalid(@TempDir Path tmp)
            throws Exception {
        Path root = Files.createDirectory(tmp.resolve("watched"));
        Path historicalFile = root.resolve("historical.txt").toAbsolutePath();
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        store.upsertPending(historicalFile.toString(), "historical.txt",
                root.toAbsolutePath().toString(), "txt");
        store.updateCollected(historicalFile.toString(), 7,
                java.time.Instant.now(), java.time.Instant.now());
        PathFilter filter = txtFilter();
        watcher = new FileWatcher(store, filter, List.of(root), "http://127.0.0.1:9", 1, 1);
        watcher.start();
        assertTrue(pollForAvailable(POLL_TIMEOUT_MS));

        Files.delete(root);

        assertTrue(pollForUnavailable(POLL_TIMEOUT_MS),
                "watcher health should turn false after its only WatchKey is invalidated");
        assertTrue(pollForStatus(historicalFile.toString(), FileStatus.DELETED, POLL_TIMEOUT_MS),
                "invalidated deleted root must retire descendant metadata");
    }

    @Test
    void reportsTerminalRegistrationFailureWhenConfiguredRootDisappears(@TempDir Path tmp)
            throws Exception {
        Path root = Files.createDirectory(tmp.resolve("vanished"));
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        PathFilter filter = txtFilter();
        watcher = new FileWatcher(store, filter, List.of(root), "http://127.0.0.1:9", 1, 1);
        Files.delete(root);

        watcher.start();

        assertTrue(pollForRegistrationComplete(POLL_TIMEOUT_MS));
        assertFalse(watcher.isRunning());
        assertNotNull(watcher.registrationError());
    }

    @Test
    void deletingDirectoryRetiresPreviouslyCollectedDescendants(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectory(tmp.resolve("watched"));
        Path childDir = Files.createDirectory(root.resolve("child"));
        Path historicalFile = childDir.resolve("historical.txt").toAbsolutePath();
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        store.upsertPending(historicalFile.toString(), "child/historical.txt",
                root.toAbsolutePath().toString(), "txt");
        store.updateCollected(historicalFile.toString(), 7,
                java.time.Instant.now(), java.time.Instant.now());
        PathFilter filter = txtFilter();
        watcher = new FileWatcher(store, filter, List.of(root), "http://127.0.0.1:9", 1, 1);
        watcher.start();
        assertTrue(pollForAvailable(POLL_TIMEOUT_MS));

        Files.delete(childDir);

        assertTrue(pollForStatus(historicalFile.toString(), FileStatus.DELETED, POLL_TIMEOUT_MS));
    }

    @Test
    void gitIgnoreChangeInvalidatesRulesAndRequestsSubtreeReconcile(@TempDir Path tmp)
            throws Exception {
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        PathFilter filter = txtFilter();
        Path seed = Files.writeString(tmp.resolve("seed.txt"), "x");
        filter.evaluateFile(tmp, seed, Files.readAttributes(
                seed, java.nio.file.attribute.BasicFileAttributes.class));
        CountDownLatch reconcileRequested = new CountDownLatch(2);
        watcher = new FileWatcher(store, filter, List.of(tmp), "http://127.0.0.1:9", 1, 1,
                (root, subtree) -> reconcileRequested.countDown());
        watcher.start();
        assertTrue(pollForAvailable(POLL_TIMEOUT_MS));

        Path ignore = Files.writeString(tmp.resolve(".gitignore"), "ignored.txt\n");
        assertTrue(reconcileRequested.await(20, TimeUnit.SECONDS));
        Path ignored = Files.writeString(tmp.resolve("ignored.txt"), "private");
        Thread.sleep(3_000);

        assertNull(store.findByPath(ignore.toAbsolutePath().toString()));
        assertNull(store.findByPath(ignored.toAbsolutePath().toString()));
    }

    @Test
    void gitIgnoreChangeRegistersNewlyIncludedDirectory(@TempDir Path tmp) throws Exception {
        Path ignore = Files.writeString(tmp.resolve(".gitignore"), "generated/\n");
        Path generated = Files.createDirectory(tmp.resolve("generated"));
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        CountDownLatch reconciled = new CountDownLatch(2);
        watcher = new FileWatcher(store, txtFilter(), List.of(tmp),
                "http://127.0.0.1:9", 1, 1,
                (root, subtree) -> reconciled.countDown());
        watcher.start();
        assertTrue(pollForAvailable(POLL_TIMEOUT_MS));

        Files.writeString(ignore, "");
        assertTrue(reconciled.await(20, TimeUnit.SECONDS));
        Path included = Files.writeString(generated.resolve("included.txt"), "metadata only");

        assertNotNull(pollForPending(included.toAbsolutePath().toString(), POLL_TIMEOUT_MS));
    }

    @Test
    void populatedDirectoryCreateRequestsSubtreeReconcile(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectory(tmp.resolve("watched"));
        Path incoming = Files.createDirectory(tmp.resolve("incoming"));
        Files.writeString(incoming.resolve("existing.txt"), "metadata only");
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        CountDownLatch childReconcile = new CountDownLatch(1);
        AtomicReference<Path> expectedChild = new AtomicReference<>();
        watcher = new FileWatcher(store, txtFilter(), List.of(root),
                "http://127.0.0.1:9", 1, 1, (watchRoot, subtree) -> {
                    Path expected = expectedChild.get();
                    if (expected != null && subtree.equals(expected)) childReconcile.countDown();
                });
        watcher.start();
        assertTrue(pollForAvailable(POLL_TIMEOUT_MS));

        Path moved = root.resolve("incoming").toAbsolutePath().normalize();
        expectedChild.set(moved);
        Files.move(incoming, moved);

        assertTrue(childReconcile.await(20, TimeUnit.SECONDS));
    }

    @Test
    void overflowInvalidatesRulesAndRequestsReconcile(@TempDir Path tmp) throws Exception {
        Path ignore = Files.writeString(tmp.resolve(".gitignore"), "old.txt\n");
        Path oldFile = Files.writeString(tmp.resolve("old.txt"), "x");
        PathFilter filter = txtFilter();
        assertTrue(filter.evaluateFile(tmp, oldFile, Files.readAttributes(
                oldFile, java.nio.file.attribute.BasicFileAttributes.class)).excluded());
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        CountDownLatch reconcile = new CountDownLatch(2);
        watcher = new FileWatcher(store, filter, List.of(tmp),
                "http://127.0.0.1:9", 1, 1, (root, subtree) -> reconcile.countDown());
        watcher.start();
        assertTrue(pollForAvailable(POLL_TIMEOUT_MS));

        Files.writeString(ignore, "new.txt\n");
        watcher.handleOverflow(tmp);

        assertTrue(reconcile.await(20, TimeUnit.SECONDS));
        assertFalse(filter.evaluateFile(tmp, oldFile, Files.readAttributes(
                oldFile, java.nio.file.attribute.BasicFileAttributes.class)).excluded());
    }

    @Test
    void excludedAndGitIgnoredDirectorySubtreesAreNeverRegistered(@TempDir Path tmp)
            throws Exception {
        Path nodeModules = Files.createDirectory(tmp.resolve("NODE_MODULES"));
        Files.writeString(tmp.resolve(".gitignore"), "generated/\n");
        Path generated = Files.createDirectory(tmp.resolve("generated"));
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        watcher = new FileWatcher(store, txtFilter(), List.of(tmp),
                "http://127.0.0.1:9", 1, 1);
        watcher.start();
        assertTrue(pollForAvailable(POLL_TIMEOUT_MS));

        Path dependency = Files.writeString(nodeModules.resolve("dependency.txt"), "private");
        Path generatedFile = Files.writeString(generated.resolve("generated.txt"), "private");
        Thread.sleep(3_000);

        assertNull(store.findByPath(dependency.toAbsolutePath().toString()));
        assertNull(store.findByPath(generatedFile.toAbsolutePath().toString()));
    }

    @Test
    void nestedWatchRootsUseMostSpecificRootForRelativeFiltering(@TempDir Path tmp)
            throws Exception {
        Path parent = Files.createDirectory(tmp.resolve("parent"));
        Path child = Files.createDirectory(parent.resolve("project"));
        Files.writeString(parent.resolve(".gitignore"), "project/\n");
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        watcher = new FileWatcher(store, txtFilter(), List.of(parent, child),
                "http://127.0.0.1:9", 1, 1);
        watcher.start();
        assertTrue(pollForAvailable(POLL_TIMEOUT_MS));

        Path file = Files.writeString(child.resolve("notes.txt"), "private");
        FileRecord record = pollForPending(file.toAbsolutePath().toString(), POLL_TIMEOUT_MS);

        assertNotNull(record);
        assertEquals(child.toAbsolutePath().normalize().toString(), record.watchRoot());
        assertEquals("notes.txt", record.relativePath());
    }

    private FileRecord pollForPending(String absPath, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            FileRecord rec = store.findByPath(absPath);
            if (rec != null && rec.status() == FileStatus.PENDING) return rec;
            Thread.sleep(200);
        }
        return store.findByPath(absPath);
    }

    private static PathFilter txtFilter() {
        return new PathFilter(FileFilterConfig.parse(
                0, List.of(), List.of(), List.of("txt"), true));
    }

    private boolean pollForUnavailable(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!watcher.isRunning()) return true;
            Thread.sleep(50);
        }
        return !watcher.isRunning();
    }

    private boolean pollForAvailable(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (watcher.isRunning()) return true;
            Thread.sleep(50);
        }
        return watcher.isRunning();
    }

    private boolean pollForRegistrationComplete(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (watcher.isRegistrationComplete()) return true;
            Thread.sleep(50);
        }
        return watcher.isRegistrationComplete();
    }

    private boolean pollForStatus(String absolutePath, FileStatus status, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            FileRecord record = store.findByPath(absolutePath);
            if (record != null && record.status() == status) return true;
            Thread.sleep(100);
        }
        FileRecord record = store.findByPath(absolutePath);
        return record != null && record.status() == status;
    }
}
