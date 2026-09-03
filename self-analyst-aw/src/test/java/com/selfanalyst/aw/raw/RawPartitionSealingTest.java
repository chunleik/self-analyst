package com.selfanalyst.aw.raw;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawPartitionSealingTest {

    @Test
    void monthSwitchCreatesNewPartitionThenSealsOldWithVerifiableManifest(
            @TempDir Path dir) throws Exception {
        RawEventIdGenerator ids = new RawEventIdGenerator();
        Instant august = Instant.parse("2026-08-31T23:59:59Z");
        Instant september = Instant.parse("2026-09-01T00:00:00Z");
        try (RawEventStore store = new RawEventStore(dir)) {
            RawEvent oldEvent = event(ids, "old", august);
            store.append(oldEvent);
            store.append(event(ids, "new", september));

            RawPartitionMetadata old = store.partitionMetadata(YearMonth.of(2026, 8));
            RawPartitionMetadata current = store.partitionMetadata(YearMonth.of(2026, 9));
            assertEquals(RawPartitionStatus.SEALED, old.status());
            assertEquals(RawPartitionStatus.ACTIVE, current.status());
            assertEquals(List.of(oldEvent.eventId()), store.readPartition(YearMonth.of(2026, 8))
                    .stream().map(RawEvent::eventId).toList());
            assertThrows(IllegalStateException.class,
                    () -> store.append(event(ids, "late-old", august)));

            Path database = store.partitionPath(YearMonth.of(2026, 8));
            Path manifest = RawEventStore.manifestPath(database);
            assertTrue(Files.isRegularFile(manifest));
            Map<?, ?> json = new ObjectMapper().readValue(Files.readString(manifest), Map.class);
            assertEquals(1, ((Number) json.get("eventCount")).intValue());
            assertEquals(old.fileSha256(), json.get("fileSha256"));
            assertEquals(RawEventStore.fileSha256(database), old.fileSha256());
            assertNotNull(old.verifiedAt());
        }
    }

    @Test
    void concurrentWritesAroundMonthBoundaryHaveNoLossOrWrongPartition(
            @TempDir Path dir) throws Exception {
        RawEventIdGenerator ids = new RawEventIdGenerator();
        try (RawEventStore store = new RawEventStore(dir);
             var executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int ordinal = i;
                futures.add(executor.submit(() -> store.append(event(ids, "aug-" + ordinal,
                        Instant.parse("2026-08-31T23:59:59Z")))));
            }
            for (Future<?> future : futures) future.get();
            futures.clear();
            for (int i = 0; i < 40; i++) {
                int ordinal = i;
                futures.add(executor.submit(() -> store.append(event(ids, "sep-" + ordinal,
                        Instant.parse("2026-09-01T00:00:00Z")))));
            }
            for (Future<?> future : futures) future.get();

            assertEquals(40, store.count(YearMonth.of(2026, 8)));
            assertEquals(40, store.count(YearMonth.of(2026, 9)));
            assertTrue(store.readPartition(YearMonth.of(2026, 8)).stream()
                    .allMatch(event -> event.receivedAt().isBefore(
                            Instant.parse("2026-09-01T00:00:00Z"))));
            assertTrue(store.readPartition(YearMonth.of(2026, 9)).stream()
                    .allMatch(event -> !event.receivedAt().isBefore(
                            Instant.parse("2026-09-01T00:00:00Z"))));
        }
    }

    private static RawEvent event(RawEventIdGenerator ids, String sourceId, Instant receivedAt) {
        return RawEvent.create(ids, sourceId, "bucket", RawEventSource.WINDOW, 1,
                RawIngestKind.HEARTBEAT, receivedAt, receivedAt, 1,
                Map.of("app", "test"), null, null);
    }
}
