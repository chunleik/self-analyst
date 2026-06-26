package com.selfanalyst.file;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileWatchStoreTest {

    private FileWatchStore store;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void upsertCreatesPending() {
        store.upsertPending("/a/b.txt", "b.txt", "/a", "txt");
        FileRecord rec = store.findByPath("/a/b.txt");
        assertNotNull(rec);
        assertEquals(FileStatus.PENDING, rec.status());
        assertEquals("txt", rec.extension());
    }

    @Test
    void statusFlowsPendingToIndexed() {
        store.upsertPending("/a/b.txt", "b.txt", "/a", "txt");
        Instant mtime = Instant.parse("2026-06-15T10:00:00Z");
        store.updateIndexed("/a/b.txt", 123, mtime, "hash1",
                "summary text", List.of("topic1", "topic2"), "llm", "file-v1");
        FileRecord rec = store.findByPath("/a/b.txt");
        assertEquals(FileStatus.INDEXED, rec.status());
        assertEquals("hash1", rec.fileHash());
        assertEquals(123, rec.sizeBytes());
        assertEquals("summary text", rec.summary());
        assertEquals(List.of("topic1", "topic2"), rec.mainTopics());
        assertEquals(mtime, rec.lastModified());
        assertNotNull(rec.lastIndexedAt());
    }

    @Test
    void reupsertKeepsIndexedSnapshot() {
        store.upsertPending("/a/b.txt", "b.txt", "/a", "txt");
        store.updateIndexed("/a/b.txt", 10, Instant.parse("2026-06-15T10:00:00Z"),
                "hash1", "s", List.of(), "llm", "file-v1");
        // a spurious MODIFY re-upserts → PENDING, but the last-indexed snapshot survives
        store.upsertPending("/a/b.txt", "b.txt", "/a", "txt");
        FileRecord rec = store.findByPath("/a/b.txt");
        assertEquals(FileStatus.PENDING, rec.status());
        assertEquals("hash1", rec.fileHash(), "indexed hash retained for change detection");
    }

    @Test
    void markDeletedTerminal() {
        store.upsertPending("/a/b.txt", "b.txt", "/a", "txt");
        store.markDeleted("/a/b.txt");
        assertEquals(FileStatus.DELETED, store.findByPath("/a/b.txt").status());
    }

    @Test
    void findRetryableDrivenByNextRetryAt() {
        store.upsertPending("/a/past.txt", "past.txt", "/a", "txt");
        store.upsertPending("/a/future.txt", "future.txt", "/a", "txt");
        store.markFailed("/a/past.txt", "boom", Instant.now().minusSeconds(60));
        store.markFailed("/a/future.txt", "boom", Instant.now().plusSeconds(3600));

        List<FileRecord> retryable = store.findRetryable(10);
        List<String> paths = retryable.stream().map(FileRecord::absolutePath).toList();
        assertTrue(paths.contains("/a/past.txt"), "elapsed backoff is retryable");
        assertFalse(paths.contains("/a/future.txt"), "future backoff is not retryable");
    }

    @Test
    void markSkipped() {
        store.upsertPending("/a/b.txt", "b.txt", "/a", "txt");
        store.markSkipped("/a/b.txt", "empty");
        assertEquals(FileStatus.SKIPPED, store.findByPath("/a/b.txt").status());
    }

    @Test
    void queryByTimeReturnsIndexedOnly() {
        store.upsertPending("/a/old.txt", "old.txt", "/a", "txt");
        store.upsertPending("/a/new.txt", "new.txt", "/a", "txt");
        store.updateIndexed("/a/old.txt", 1, Instant.parse("2026-06-10T00:00:00Z"),
                "h", "s", List.of(), "llm", "file-v1");
        store.updateIndexed("/a/new.txt", 1, Instant.parse("2026-06-18T00:00:00Z"),
                "h", "s", List.of(), "llm", "file-v1");

        List<FileRecord> hits = store.queryByTime(
                Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-20T00:00:00Z"),
                null, null, 10);
        assertEquals(1, hits.size());
        assertEquals("/a/new.txt", hits.get(0).absolutePath());
    }

    @Test
    void statusCountsByWatchRoot() {
        store.upsertPending("/a/x.txt", "x.txt", "/a", "txt");
        store.upsertPending("/a/y.txt", "y.txt", "/a", "txt");
        store.updateIndexed("/a/y.txt", 1, Instant.now(), "h", "s", List.of(), "llm", "file-v1");
        Map<String, Map<String, Long>> counts = store.statusCountsByWatchRoot();
        Map<String, Long> a = counts.get("/a");
        assertNotNull(a);
        assertEquals(1L, a.get("PENDING"));
        assertEquals(1L, a.get("INDEXED"));
    }
}
