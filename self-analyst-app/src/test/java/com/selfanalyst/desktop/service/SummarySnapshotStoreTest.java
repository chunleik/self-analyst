package com.selfanalyst.desktop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarySnapshotStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripSurvivesNewStoreInstance() {
        SummarySnapshot snapshot = new SummarySnapshot(
                Instant.parse("2026-09-06T04:00:00Z").toString(),
                "fp-1",
                Map.of("headline", "当前在写代码"),
                List.of(Map.of("key", "yesterday", "headline", "昨天在开会")),
                Map.of("type", "suggestion", "title", "减少切换"));

        SummarySnapshotStore writer = new SummarySnapshotStore(tempDir);
        writer.save(snapshot);

        SummarySnapshotStore reader = new SummarySnapshotStore(tempDir);
        SummarySnapshot loaded = reader.load().orElseThrow();
        assertEquals("fp-1", loaded.currentWindowFingerprint());
        assertEquals("当前在写代码", loaded.current().get("headline"));
        assertEquals("昨天在开会", loaded.timeline().get(0).get("headline"));
        assertEquals("suggestion", loaded.behaviorAdvice().get("type"));
    }

    @Test
    void missingAndCorruptFilesAreEmpty() throws Exception {
        SummarySnapshotStore store = new SummarySnapshotStore(tempDir);
        assertTrue(store.load().isEmpty());

        Files.writeString(tempDir.resolve(SummarySnapshotStore.FILE_NAME), "{not-json");
        assertTrue(store.load().isEmpty());
    }
}
