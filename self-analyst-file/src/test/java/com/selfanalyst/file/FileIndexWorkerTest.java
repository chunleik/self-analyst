package com.selfanalyst.file;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileIndexWorkerTest {

    private FileWatchStore store;
    private Path root;
    private Path file;
    private PathFilter pathFilter;
    private Path dbPath;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        dbPath = tmp.resolve("file-watch.db");
        store = new FileWatchStore(dbPath);
        root = Files.createDirectories(tmp.resolve("root"));
        file = Files.writeString(root.resolve("notes.txt"), "private body must never be read");
        pathFilter = new PathFilter(FileFilterConfig.parse(
                0, List.of(), List.of(), List.of("txt"), true));
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    private FileIndexWorker worker() {
        return worker(List.of(root));
    }

    private FileIndexWorker worker(List<Path> roots) {
        return new FileIndexWorker(store, pathFilter, roots, 60);
    }

    private void upsert() {
        store.upsertPending(file.toAbsolutePath().toString(),
                "notes.txt", root.toAbsolutePath().toString(), "txt");
    }

    @Test
    void runtimeHealthFollowsLifecycle() {
        FileIndexWorker worker = worker();
        assertFalse(worker.isRunning());
        worker.start();
        assertTrue(worker.isRunning());
        worker.shutdown();
        assertFalse(worker.isRunning());
    }

    @Test
    void collectsOnlyFilesystemMetadata() throws Exception {
        BasicFileAttributes expected = Files.readAttributes(file, BasicFileAttributes.class);
        upsert();
        worker().processOneRound();

        FileRecord rec = store.findByPath(file.toAbsolutePath().toString());
        assertEquals(FileStatus.COLLECTED, rec.status());
        assertEquals(expected.size(), rec.sizeBytes());
        assertEquals(expected.creationTime().toInstant(), rec.fileCreatedAt());
        assertEquals(expected.lastModifiedTime().toInstant(), rec.lastModified());
        assertNotNull(rec.lastCollectedAt());
    }

    @Test
    void hiddenAttributeFailureDoesNotRetireCollectedMetadata() throws Exception {
        upsert();
        store.updateCollected(file.toAbsolutePath().toString(), Files.size(file),
                Files.readAttributes(file, BasicFileAttributes.class).creationTime().toInstant(),
                Files.getLastModifiedTime(file).toInstant());
        pathFilter = new PathFilter(
                FileFilterConfig.parse(0, List.of(), List.of(), List.of("txt"), false),
                path -> {
                    if (path.equals(file)) throw new java.io.IOException("simulated hidden error");
                    return false;
                });

        worker().reconcileScan();

        assertEquals(FileStatus.COLLECTED,
                store.findByPath(file.toAbsolutePath().toString()).status());
    }

    @Test
    void collectionPipelineBytecodeHasNoOrdinaryFileContentReaderHashSummarizerOrEmbeddingDependency()
            throws Exception {
        for (Class<?> pipelineClass : List.of(
                FileIndexWorker.class, FileWatcher.class, PathFilter.class, FileTools.class)) {
            byte[] classBytes;
            try (var in = pipelineClass.getResourceAsStream(
                    pipelineClass.getSimpleName() + ".class")) {
                assertNotNull(in);
                classBytes = in.readAllBytes();
            }
            String symbols = new String(classBytes, StandardCharsets.ISO_8859_1);
            for (String forbidden : List.of(
                    "newInputStream", "newBufferedReader", "readString", "readAllBytes",
                    "FileInputStream", "sha256", "MessageDigest", "FileSummarizer",
                    "FileContentExtractor", "FileEmbeddingWorker")) {
                assertFalse(symbols.contains(forbidden),
                        pipelineClass.getSimpleName() + " references forbidden symbol " + forbidden);
            }
        }
    }

    @Test
    void ignoresQueuedFilesOutsideCurrentWatchRoots(@TempDir Path tmp) throws Exception {
        Path removedRoot = Files.createDirectories(tmp.resolve("removed"));
        Path removedFile = Files.writeString(removedRoot.resolve("private.txt"), "private");
        store.upsertPending(removedFile.toAbsolutePath().toString(), "private.txt",
                removedRoot.toAbsolutePath().toString(), "txt");
        upsert();

        worker().processOneRound();

        assertEquals(FileStatus.PENDING,
                store.findByPath(removedFile.toAbsolutePath().toString()).status());
        assertEquals(FileStatus.COLLECTED,
                store.findByPath(file.toAbsolutePath().toString()).status());
    }

    @Test
    void reconcileReassignsExistingRecordWhenRootIsNarrowed() throws Exception {
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        Path projectFile = Files.writeString(projectRoot.resolve("plan.txt"), "private");
        store.upsertPending(projectFile.toAbsolutePath().toString(), "project/plan.txt",
                root.toAbsolutePath().toString(), "txt");

        FileIndexWorker narrowed = worker(List.of(projectRoot));
        narrowed.reconcileScan();
        narrowed.processOneRound();

        FileRecord record = store.findByPath(projectFile.toAbsolutePath().toString());
        assertEquals(projectRoot.toAbsolutePath().normalize().toString(), record.watchRoot());
        assertEquals("plan.txt", record.relativePath());
        assertEquals(FileStatus.COLLECTED, record.status());
    }

    @Test
    void metadataChangeIsCollectedWithoutContentHash() throws Exception {
        upsert();
        worker().processOneRound();
        Instant firstCollected = store.findByPath(file.toAbsolutePath().toString()).lastCollectedAt();

        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
        worker().reconcileScan();
        worker().processOneRound();

        FileRecord updated = store.findByPath(file.toAbsolutePath().toString());
        assertEquals(FileStatus.COLLECTED, updated.status());
        assertTrue(!updated.lastCollectedAt().isBefore(firstCollected));
    }

    @Test
    void missingFileMarkedDeleted() throws Exception {
        upsert();
        Files.delete(file);
        worker().processOneRound();
        assertEquals(FileStatus.DELETED, store.findByPath(file.toAbsolutePath().toString()).status());
    }

    @Test
    void startupReconcileRetiresFileDeletedWhileWatcherWasStopped() throws Exception {
        upsert();
        worker().processOneRound();
        assertEquals(FileStatus.COLLECTED,
                store.findByPath(file.toAbsolutePath().toString()).status());

        Files.delete(file);
        worker().reconcileScan();

        assertEquals(FileStatus.DELETED,
                store.findByPath(file.toAbsolutePath().toString()).status());
    }

    @Test
    void startupReconcileDoesNotDeleteFileCreatedAfterTraversalSnapshot() throws Exception {
        Path lateFile = root.resolve("late.txt").toAbsolutePath();
        FileIndexWorker worker = new FileIndexWorker(store, pathFilter, List.of(root), 60, () -> {
            try {
                Files.writeString(lateFile, "private body");
                store.upsertPending(lateFile.toString(), "late.txt",
                        root.toAbsolutePath().toString(), "txt");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        worker.reconcileScan();

        assertEquals(FileStatus.PENDING, store.findByPath(lateFile.toString()).status());
    }

    @Test
    void startupReconcileRetiresFileReplacedByDirectory() throws Exception {
        upsert();
        worker().processOneRound();
        Files.delete(file);
        Files.createDirectory(file);

        worker().reconcileScan();

        assertEquals(FileStatus.DELETED,
                store.findByPath(file.toAbsolutePath().toString()).status());
    }

    @Test
    void changedGitIgnoreRulesRetireAndReincludeMetadata() throws Exception {
        Path markdown = Files.writeString(root.resolve("draft.md"), "private body");
        String absolute = markdown.toAbsolutePath().toString();
        store.upsertPending(absolute, "draft.md", root.toAbsolutePath().toString(), "md");
        store.updateCollected(absolute, 12, Instant.now(), Instant.now());
        PathFilter filter = new PathFilter(FileFilterConfig.parse(
                0, List.of(), List.of(), List.of("md"), true));
        FileIndexWorker worker = new FileIndexWorker(store, filter, List.of(root), 60);
        Path ignore = Files.writeString(root.resolve(".gitignore"), "draft.md\n");

        worker.reconcileScan();
        assertEquals(FileStatus.DELETED, store.findByPath(absolute).status());

        Files.writeString(ignore, "");
        filter.invalidateIgnoreRules(root);
        worker.reconcileScan();
        worker.processOneRound();
        assertEquals(FileStatus.COLLECTED, store.findByPath(absolute).status());
    }

    @Test
    void requestedSubtreeReconcileAppliesRuntimeIgnoreChange() throws Exception {
        upsert();
        worker().processOneRound();
        FileIndexWorker active = worker();
        active.start();
        try {
            Files.writeString(root.resolve(".gitignore"), "notes.txt\n");
            pathFilter.invalidateIgnoreRules(root);
            active.requestReconcile(root, root);

            assertTrue(pollForStatus(file.toAbsolutePath().toString(), FileStatus.DELETED, 10_000));
        } finally {
            active.shutdown();
        }
    }

    @Test
    void transientGitIgnoreReadFailureNeverRetiresCollectedMetadata() throws Exception {
        upsert();
        worker().processOneRound();
        Files.write(root.resolve(".gitignore"), new byte[1024 * 1024 + 1]);
        pathFilter.invalidateIgnoreRules(root);

        worker().reconcileScan();

        assertEquals(FileStatus.COLLECTED,
                store.findByPath(file.toAbsolutePath().toString()).status());
    }

    @Test
    void rawFileBodyNeverAppearsInDatabaseWalOrShm() throws Exception {
        String forbidden = "SELF_ANALYST_FORBIDDEN_FILE_BODY_9C2E";
        Files.writeString(file, forbidden);
        upsert();
        worker().processOneRound();
        store.close();
        store = null;

        for (Path path : List.of(dbPath, Path.of(dbPath + "-wal"), Path.of(dbPath + "-shm"))) {
            if (!Files.exists(path)) continue;
            String bytes = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
            assertFalse(bytes.contains(forbidden), "raw file body leaked into " + path);
        }
    }

    private boolean pollForStatus(String absolutePath, FileStatus status, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            FileRecord record = store.findByPath(absolutePath);
            if (record != null && record.status() == status) return true;
            Thread.sleep(50);
        }
        FileRecord record = store.findByPath(absolutePath);
        return record != null && record.status() == status;
    }
}
