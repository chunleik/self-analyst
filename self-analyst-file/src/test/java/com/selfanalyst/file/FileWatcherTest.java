package com.selfanalyst.file;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end watcher tests over a real NIO WatchService. Heartbeats target a
 * refused port and fail silently; only the store side is asserted.
 */
class FileWatcherTest {

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
        PathFilter filter = new PathFilter(1024, List.of(), List.of(), List.of());
        watcher = new FileWatcher(store, filter, List.of(tmp), "http://127.0.0.1:9", 1, 1);
        watcher.start();
        assertTrue(watcher.isRunning());

        Path file = tmp.resolve("hello.txt");
        Files.writeString(file, "content");

        FileRecord rec = pollForPending(file.toAbsolutePath().toString(), 20_000);
        assertNotNull(rec, "file should be upserted PENDING after the debounce window");
        assertEquals(FileStatus.PENDING, rec.status());

        watcher.shutdown();
        assertFalse(watcher.isRunning());
    }

    @Test
    void excludedFileNeverEnqueued(@TempDir Path tmp) throws Exception {
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        PathFilter filter = new PathFilter(1024, List.of(), List.of(), List.of());
        watcher = new FileWatcher(store, filter, List.of(tmp), "http://127.0.0.1:9", 1, 1);
        watcher.start();

        Path log = tmp.resolve("app.log"); // volatile → excluded (SPEC-FILE-011f)
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
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        PathFilter filter = new PathFilter(1024, List.of(), List.of(), List.of());
        watcher = new FileWatcher(store, filter, List.of(root), "http://127.0.0.1:9", 1, 1);
        watcher.start();
        assertTrue(watcher.isRunning());

        Files.delete(root);

        assertTrue(pollForUnavailable(20_000),
                "watcher health should turn false after its only WatchKey is invalidated");
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

    private boolean pollForUnavailable(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!watcher.isRunning()) return true;
            Thread.sleep(50);
        }
        return !watcher.isRunning();
    }
}
