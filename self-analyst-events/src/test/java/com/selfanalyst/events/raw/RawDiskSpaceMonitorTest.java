package com.selfanalyst.events.raw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawDiskSpaceMonitorTest {

    @Test
    void warningAllowsCollectionButBlockRejectsWithoutDeletingOldPartition(
            @TempDir Path dir) throws Exception {
        AtomicLong space = new AtomicLong(5_000);
        RawDiskSpaceMonitor monitor = new RawDiskSpaceMonitor(dir, 10_000, 1_000,
                ignored -> space.get());
        RawEventIdGenerator ids = new RawEventIdGenerator();
        Instant received = Instant.parse("2026-09-03T12:00:00Z");
        try (RawEventStore store = new RawEventStore(dir, monitor)) {
            store.append(event(ids, "accepted", received));
            assertEquals(RawDiskSpaceMonitor.State.WARNING, monitor.state());
            Path partition = store.partitionPath(YearMonth.of(2026, 9));
            String hash = RawEventStore.fileSha256(partition);

            space.set(999);
            assertThrows(RawStorageFullException.class,
                    () -> store.append(event(ids, "blocked", received.plusSeconds(1))));

            assertEquals(RawDiskSpaceMonitor.State.BLOCKED, monitor.state());
            assertEquals(1, store.count(YearMonth.of(2026, 9)));
            assertTrue(Files.exists(partition));
            assertEquals(hash, RawEventStore.fileSha256(partition));
        }
    }

    @Test
    void storageFullErrorsAreClassifiedWithoutPayloadInspection() {
        assertTrue(RawEventStore.isStorageFull(
                new SQLException("[SQLITE_FULL] database or disk is full", "", 13)));
        assertTrue(RawEventStore.isStorageFull(
                new IllegalStateException("No space left on device")));
        assertFalse(RawEventStore.isStorageFull(new SQLException("database is locked", "", 5)));
    }

    private static RawEvent event(RawEventIdGenerator ids, String sourceId, Instant received) {
        return RawEvent.create(ids, sourceId, "bucket", RawEventSource.CONTENT, 1,
                RawIngestKind.HEARTBEAT, received, received, 1,
                Map.of("title", "safe"), null, null);
    }
}
