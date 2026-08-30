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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
            worker.start();
            try {
                worker.processOneRound();
            } finally {
                worker.shutdown();
            }

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
}
