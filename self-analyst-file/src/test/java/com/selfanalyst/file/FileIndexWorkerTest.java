package com.selfanalyst.file;

import com.selfanalyst.file.extractor.FileContentExtractorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class FileIndexWorkerTest {

    private FileWatchStore store;
    private Path root;
    private Path file;
    private AtomicInteger llmCalls;
    private FileSummarizer summarizer;
    private FileContentExtractorFactory factory;
    private PathFilter pathFilter;
    private Path dbPath;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        dbPath = tmp.resolve("file-watch.db");
        store = new FileWatchStore(dbPath);
        root = Files.createDirectories(tmp.resolve("root"));
        file = Files.writeString(root.resolve("notes.txt"), "version one content");
        llmCalls = new AtomicInteger();
        Function<String, String> llm = prompt -> {
            llmCalls.incrementAndGet();
            return "{\"summary\":\"S\",\"mainTopics\":[\"a\",\"b\"],\"estimatedPurpose\":\"p\"}";
        };
        summarizer = new FileSummarizer(llm);
        factory = new FileContentExtractorFactory();
        pathFilter = new PathFilter(1024, List.of(), List.of(), List.of());
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    private FileIndexWorker worker(int minReindexMinutes) {
        return worker(List.of(root), minReindexMinutes);
    }

    private FileIndexWorker worker(List<Path> roots, int minReindexMinutes) {
        return new FileIndexWorker(store, pathFilter, factory, summarizer, null,
                roots, 60, 8000, minReindexMinutes);
    }

    private void upsert() {
        store.upsertPending(file.toAbsolutePath().toString(),
                "notes.txt", root.toAbsolutePath().toString(), "txt");
    }

    @Test
    void runtimeHealthFollowsLifecycle() {
        FileIndexWorker worker = worker(0);
        assertFalse(worker.isRunning());
        worker.start();
        assertTrue(worker.isRunning());
        worker.shutdown();
        assertFalse(worker.isRunning());
    }

    @Test
    void indexesPendingFile() {
        upsert();
        worker(0).processOneRound();

        FileRecord rec = store.findByPath(file.toAbsolutePath().toString());
        assertEquals(FileStatus.INDEXED, rec.status());
        assertEquals("S", rec.summary());
        assertNotNull(rec.fileHash());
        assertEquals(1, llmCalls.get());
    }

    @Test
    void ignoresQueuedFilesOutsideCurrentWatchRoots(@TempDir Path tmp) throws Exception {
        Path removedRoot = Files.createDirectories(tmp.resolve("removed"));
        Path removedFile = Files.writeString(removedRoot.resolve("private.txt"), "removed content");
        store.upsertPending(removedFile.toAbsolutePath().toString(), "private.txt",
                removedRoot.toAbsolutePath().toString(), "txt");
        upsert();

        worker(0).processOneRound();

        assertEquals(FileStatus.PENDING,
                store.findByPath(removedFile.toAbsolutePath().toString()).status());
        assertEquals(FileStatus.INDEXED,
                store.findByPath(file.toAbsolutePath().toString()).status());
        assertEquals(1, llmCalls.get());
    }

    @Test
    void reconcileReassignsExistingRecordWhenRootIsNarrowed() throws Exception {
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        Path projectFile = Files.writeString(projectRoot.resolve("plan.txt"), "project plan");
        store.upsertPending(projectFile.toAbsolutePath().toString(), "project/plan.txt",
                root.toAbsolutePath().toString(), "txt");

        FileIndexWorker narrowed = worker(List.of(projectRoot), 0);
        narrowed.reconcileScan();
        narrowed.processOneRound();

        FileRecord record = store.findByPath(projectFile.toAbsolutePath().toString());
        assertEquals(projectRoot.toAbsolutePath().normalize().toString(), record.watchRoot());
        assertEquals("plan.txt", record.relativePath());
        assertEquals(FileStatus.INDEXED, record.status());
        assertEquals(1, llmCalls.get());
    }

    @Test
    void unchangedFileNotReSummarized() {
        upsert();
        worker(0).processOneRound();
        assertEquals(1, llmCalls.get());

        // spurious MODIFY with no real change → cheap (size,mtime) prefilter hit
        upsert();
        worker(0).processOneRound();

        assertEquals(1, llmCalls.get(), "unchanged content must not re-summarize");
        assertEquals(FileStatus.INDEXED, store.findByPath(file.toAbsolutePath().toString()).status());
    }

    @Test
    void changedContentReSummarizedWhenIntervalZero() throws Exception {
        upsert();
        worker(0).processOneRound();
        assertEquals(1, llmCalls.get());

        Files.writeString(file, "version two content is different");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
        upsert();
        worker(0).processOneRound();

        assertEquals(2, llmCalls.get(), "changed content must be re-summarized");
    }

    @Test
    void minReindexIntervalSkipsRecentlyIndexed() throws Exception {
        upsert();
        worker(0).processOneRound();
        assertEquals(1, llmCalls.get());

        // change content, but a large min re-index interval must defer the re-summary
        Files.writeString(file, "version two content is different");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
        upsert();
        worker(60).processOneRound();

        assertEquals(1, llmCalls.get(), "within min re-index interval → skip this round");
        assertEquals(FileStatus.PENDING, store.findByPath(file.toAbsolutePath().toString()).status(),
                "still PENDING for a later round");
    }

    @Test
    void missingFileMarkedDeleted() throws Exception {
        upsert();
        Files.delete(file);
        worker(0).processOneRound();
        assertEquals(FileStatus.DELETED, store.findByPath(file.toAbsolutePath().toString()).status());
        assertEquals(0, llmCalls.get());
    }

    @Test
    void truncateByCodepointKeepsPrefix() {
        String s = "abcdefghij";
        assertEquals("abcde", FileIndexWorker.truncateByCodepoint(s, 5));
        assertEquals(s, FileIndexWorker.truncateByCodepoint(s, 100));
    }

    @Test
    void rawFileBodyIsNeverPersistedInFileDatabase() throws Exception {
        String forbidden = "SELF_ANALYST_FORBIDDEN_FILE_BODY_9C2E";
        Files.writeString(file, forbidden);
        upsert();

        worker(0).processOneRound();
        assertEquals("S", store.findByPath(file.toAbsolutePath().toString()).summary());
        store.close();
        store = null;

        for (Path path : List.of(
                dbPath, Path.of(dbPath + "-wal"), Path.of(dbPath + "-shm"))) {
            if (!Files.exists(path)) continue;
            String bytes = new String(Files.readAllBytes(path),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(bytes.contains(forbidden), "raw file body leaked into " + path);
        }
    }

    @Test
    void promptEchoedByFailureIsNotPersistedAsLastError() throws Exception {
        String forbidden = "SELF_ANALYST_FORBIDDEN_FILE_BODY_9C2E";
        Files.writeString(file, forbidden);
        summarizer = new FileSummarizer(prompt -> {
            throw new RuntimeException(prompt);
        });
        upsert();

        worker(0).processOneRound();
        FileRecord record = store.findByPath(file.toAbsolutePath().toString());
        assertEquals(FileStatus.FAILED, record.status());
        assertEquals("FILE_INDEX_FAILED:RuntimeException", record.lastError());
        assertFalse(record.lastError().contains(forbidden));
        store.close();
        store = null;

        for (Path path : List.of(
                dbPath, Path.of(dbPath + "-wal"), Path.of(dbPath + "-shm"))) {
            if (!Files.exists(path)) continue;
            String bytes = new String(Files.readAllBytes(path),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(bytes.contains(forbidden), "failure leaked raw body into " + path);
        }
    }
}
