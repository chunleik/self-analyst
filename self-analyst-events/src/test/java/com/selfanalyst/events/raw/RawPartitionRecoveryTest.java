package com.selfanalyst.events.raw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawPartitionRecoveryTest {

    @Test
    void interruptedSealPreservesFilesAndCompletesOnNextOpen(@TempDir Path dir)
            throws Exception {
        RawEventIdGenerator ids = new RawEventIdGenerator();
        AtomicBoolean interrupted = new AtomicBoolean();
        RawEventStore.SealObserver failOnce = (step, path) -> {
            if (step == RawEventStore.SealStep.MANIFEST_WRITTEN
                    && interrupted.compareAndSet(false, true)) {
                throw new RawEventStore.SealInterruption("simulated crash");
            }
        };
        try (RawEventStore store = new RawEventStore(dir, failOnce)) {
            store.append(event(ids, "aug", "2026-08-31T23:59:59Z"));
            store.append(event(ids, "sep", "2026-09-01T00:00:00Z"));
            assertEquals(RawPartitionStatus.SEALING,
                    store.partitionMetadata(YearMonth.of(2026, 8)).status());
            assertTrue(Files.isRegularFile(store.partitionPath(YearMonth.of(2026, 8))));
            assertTrue(Files.isRegularFile(RawEventStore.manifestPath(
                    store.partitionPath(YearMonth.of(2026, 8)))));
        }

        try (RawEventStore recovered = new RawEventStore(dir)) {
            assertEquals(RawPartitionStatus.SEALED,
                    recovered.partitionMetadata(YearMonth.of(2026, 8)).status());
            assertEquals(1, recovered.count(YearMonth.of(2026, 8)));
        }
    }

    @Test
    void catalogRebuildUsesVerifiedManifestAndPreservesRawDatabase(@TempDir Path dir)
            throws Exception {
        RawEventIdGenerator ids = new RawEventIdGenerator();
        Path augustDatabase;
        try (RawEventStore store = new RawEventStore(dir)) {
            store.append(event(ids, "aug", "2026-08-31T23:59:59Z"));
            store.append(event(ids, "sep", "2026-09-01T00:00:00Z"));
            augustDatabase = store.partitionPath(YearMonth.of(2026, 8));
        }
        String beforeHash = RawEventStore.fileSha256(augustDatabase);
        Files.writeString(dir.resolve("catalog.db"), "corrupt catalog");

        Path backup = RawCatalogRebuilder.rebuild(dir);

        assertNotNull(backup);
        assertTrue(Files.isRegularFile(backup));
        assertEquals(beforeHash, RawEventStore.fileSha256(augustDatabase));
        assertTrue(Files.isRegularFile(RawEventStore.manifestPath(augustDatabase)));
        try (RawPartitionCatalog rebuilt = new RawPartitionCatalog(dir)) {
            assertEquals(1, rebuilt.partitionCount());
            assertEquals(RawPartitionStatus.SEALED,
                    rebuilt.find("2026-08").orElseThrow().status());
        }
    }

    private static RawEvent event(RawEventIdGenerator ids, String sourceId, String time) {
        Instant received = Instant.parse(time);
        return RawEvent.create(ids, sourceId, "bucket", RawEventSource.WINDOW, 1,
                RawIngestKind.HEARTBEAT, received, received, 1,
                Map.of("app", "test"), null, null);
    }
}
