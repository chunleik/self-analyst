package com.selfanalyst.file.semantic;

import com.selfanalyst.file.FileWatchStore;
import com.selfanalyst.wiki.semantic.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileEmbeddingWorkerPrivacyTest {

    @TempDir
    Path tempDir;

    @Test
    void embeddingAndLuceneReceiveOnlySummaryAndTopics() throws Exception {
        String forbidden = "SELF_ANALYST_FORBIDDEN_FILE_BODY_9C2E";
        Path source = Files.writeString(tempDir.resolve("secret.txt"), forbidden);
        Path indexPath = tempDir.resolve("index");
        AtomicReference<String> embeddedText = new AtomicReference<>();
        EmbeddingClient embedding = texts -> {
            embeddedText.set(texts.getFirst());
            return List.of(new float[]{1f, 0f, 0f});
        };

        try (FileWatchStore store = new FileWatchStore(tempDir.resolve("file-watch.db"));
             FileSemanticIndex index = new FileSemanticIndex(indexPath)) {
            String absolutePath = source.toAbsolutePath().toString();
            store.upsertPending(absolutePath, "secret.txt", tempDir.toString(), "txt");
            store.updateIndexed(absolutePath, Files.size(source), Instant.now(),
                    "hash", "safe summary", List.of("safe-topic"), "llm", "file-v1");
            FileEmbeddingWorker worker = new FileEmbeddingWorker(
                    store, index, embedding, 60);
            assertFalse(worker.isRunning());
            worker.start();
            assertTrue(worker.isRunning());
            try {
                worker.processOneRound();
            } finally {
                worker.shutdown();
            }
            assertFalse(worker.isRunning());

            assertEquals("secret.txt safe summary safe-topic", embeddedText.get());
            assertFalse(embeddedText.get().contains(forbidden));
            var hits = index.search(new float[]{1f, 0f, 0f}, 5,
                    null, null, null, null);
            assertEquals(1, hits.size());
            assertEquals("safe summary", hits.getFirst().summary());
        }

        try (var paths = Files.walk(indexPath)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String bytes = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                assertFalse(bytes.contains(forbidden), "raw body leaked into Lucene file " + path);
            }
        }
    }

    @Test
    void pauseAndActiveRootsPreventOutOfScopeEmbedding() throws Exception {
        Path activeRoot = Files.createDirectories(tempDir.resolve("active"));
        Path removedRoot = Files.createDirectories(tempDir.resolve("removed"));
        Path active = Files.writeString(activeRoot.resolve("active.txt"), "body");
        Path removed = Files.writeString(removedRoot.resolve("removed.txt"), "body");
        AtomicInteger calls = new AtomicInteger();
        EmbeddingClient embedding = texts -> {
            calls.incrementAndGet();
            return List.of(new float[]{1f, 0f, 0f});
        };

        try (FileWatchStore store = new FileWatchStore(tempDir.resolve("scoped-file-watch.db"));
             FileSemanticIndex index = new FileSemanticIndex(tempDir.resolve("scoped-index"))) {
            indexRecord(store, removed, removedRoot, "removed summary");
            indexRecord(store, active, activeRoot, "active summary");
            FileEmbeddingWorker worker = new FileEmbeddingWorker(store, index, embedding, 60);
            worker.updateWatchRoots(List.of(activeRoot));
            worker.start();
            worker.pause();
            worker.enqueue(active.toAbsolutePath().toString());
            worker.processOneRound();
            assertEquals(0, calls.get(), "paused worker must not call the embedding service");

            worker.start();
            worker.processOneRound();
            worker.processOneRound();
            worker.shutdown();

            assertEquals(1, calls.get(), "only the active root should be reconciled and embedded");
            assertEquals(1, index.search(new float[]{1f, 0f, 0f}, 5,
                    null, null, activeRoot.toAbsolutePath().toString(), null).size());
            assertTrue(index.search(new float[]{1f, 0f, 0f}, 5,
                    null, null, removedRoot.toAbsolutePath().toString(), null).isEmpty());
        }
    }

    @Test
    void metadataRefreshMovesSemanticDocumentToNarrowedRoot() throws Exception {
        Path oldRoot = Files.createDirectories(tempDir.resolve("docs"));
        Path newRoot = Files.createDirectories(oldRoot.resolve("project"));
        Path file = Files.writeString(newRoot.resolve("plan.txt"), "body");
        AtomicInteger calls = new AtomicInteger();
        EmbeddingClient embedding = texts -> {
            if (calls.incrementAndGet() == 2) throw new RuntimeException("transient embedding failure");
            return List.of(new float[]{1f, 0f, 0f});
        };

        try (FileWatchStore store = new FileWatchStore(tempDir.resolve("metadata-file-watch.db"));
             FileSemanticIndex index = new FileSemanticIndex(tempDir.resolve("metadata-index"))) {
            indexRecord(store, file, oldRoot, "plan summary");
            FileEmbeddingWorker worker = new FileEmbeddingWorker(store, index, embedding, 60);
            worker.updateWatchRoots(List.of(oldRoot));
            worker.start();
            worker.processOneRound();

            String absolutePath = file.toAbsolutePath().toString();
            store.upsertPending(absolutePath, "project/plan.txt",
                    oldRoot.toAbsolutePath().toString(), "txt");
            store.updateWatchLocation(absolutePath, "plan.txt",
                    newRoot.toAbsolutePath().toString(), "txt");
            store.updateChecksum(absolutePath, Files.size(file),
                    Files.getLastModifiedTime(file).toInstant(), "hash-plan.txt");
            worker.updateWatchRoots(List.of(newRoot));
            worker.processOneRound();
            worker.processOneRound();
            worker.shutdown();

            assertEquals(3, calls.get(), "failed root-metadata refresh must be retried");
            assertTrue(index.search(new float[]{1f, 0f, 0f}, 5,
                    null, null, oldRoot.toAbsolutePath().toString(), null).isEmpty());
            assertEquals(1, index.search(new float[]{1f, 0f, 0f}, 5,
                    null, null, newRoot.toAbsolutePath().toString(), null).size());
        }
    }

    private static void indexRecord(FileWatchStore store, Path file, Path root, String summary)
            throws Exception {
        String absolutePath = file.toAbsolutePath().toString();
        store.upsertPending(absolutePath, file.getFileName().toString(),
                root.toAbsolutePath().toString(), "txt");
        store.updateIndexed(absolutePath, Files.size(file), Instant.now(),
                "hash-" + file.getFileName(), summary, List.of("topic"), "llm", "file-v1");
    }
}
