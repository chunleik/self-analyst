package com.selfanalyst.aw.raw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawEventStoreTest {

    private final RawEventIdGenerator ids = new RawEventIdGenerator();

    @Test
    void receivedAtUtcMonthControlsPartitionAcrossEventTimeZones(@TempDir java.nio.file.Path dir)
            throws Exception {
        RawEvent august = event(null, "bucket", RawIngestKind.EVENTS,
                Instant.parse("2026-09-01T08:00:00Z"),
                Instant.parse("2026-08-31T23:59:59Z"), null, null);
        RawEvent september = event(null, "bucket", RawIngestKind.EVENTS,
                Instant.parse("2026-08-31T16:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), null, null);

        try (RawEventStore store = new RawEventStore(dir)) {
            store.append(august);
            store.append(september);

            assertEquals(1, store.count(YearMonth.of(2026, 8)));
            assertEquals(1, store.count(YearMonth.of(2026, 9)));
            assertTrue(java.nio.file.Files.isRegularFile(
                    store.partitionPath(YearMonth.of(2026, 8))));
            assertTrue(java.nio.file.Files.isRegularFile(
                    store.partitionPath(YearMonth.of(2026, 9))));
        }
    }

    @Test
    void stableSourceIdentityIsIdempotentButAnonymousDuplicatesArePreserved(
            @TempDir java.nio.file.Path dir) throws Exception {
        Instant received = Instant.parse("2026-09-03T12:00:00Z");
        RawEvent firstAttempt = event("source-1", "bucket", RawIngestKind.HEARTBEAT,
                received, received, null, null);
        RawEvent retry = event("source-1", "bucket", RawIngestKind.HEARTBEAT,
                received, received, null, null);
        RawEvent anonymous1 = event(null, "third-party", RawIngestKind.EVENTS,
                received, received, null, null);
        RawEvent anonymous2 = event(null, "third-party", RawIngestKind.EVENTS,
                received, received, null, null);

        try (RawEventStore store = new RawEventStore(dir)) {
            assertEquals(firstAttempt.eventId(), store.append(firstAttempt).eventId());
            assertEquals(firstAttempt.eventId(), store.append(retry).eventId());
            assertNotEquals(store.append(anonymous1).eventId(), store.append(anonymous2).eventId());
            assertEquals(3, store.count(YearMonth.of(2026, 9)));
        }
    }

    @Test
    void importBatchIsAtomicAndRetryUsesSessionBucketOrdinalIdentity(
            @TempDir java.nio.file.Path dir) throws Exception {
        Instant received = Instant.parse("2026-09-03T12:00:00Z");
        List<RawEvent> batch = List.of(
                event(null, "bucket", RawIngestKind.IMPORT, received, received, "session", 0),
                event(null, "bucket", RawIngestKind.IMPORT, received, received, "session", 1));

        try (RawEventStore store = new RawEventStore(dir)) {
            List<RawEvent> inserted = store.appendBatch(batch);
            List<RawEvent> retried = store.appendBatch(List.of(
                    event(null, "bucket", RawIngestKind.IMPORT, received, received, "session", 0),
                    event(null, "bucket", RawIngestKind.IMPORT, received, received, "session", 1)));
            assertEquals(inserted.stream().map(RawEvent::eventId).toList(),
                    retried.stream().map(RawEvent::eventId).toList());
            assertEquals(2, store.count(YearMonth.of(2026, 9)));

            CanonicalJson.Value data = CanonicalJson.encode(Map.of("same", true));
            String collision = ids.nextId();
            RawEvent valid = raw(collision, "first", received, data);
            RawEvent duplicateEventId = raw(collision, "second", received, data);
            assertThrows(IllegalStateException.class,
                    () -> store.appendBatch(List.of(valid, duplicateEventId)));
            assertEquals(2, store.count(YearMonth.of(2026, 9)),
                    "失败批次不得留下第一条原始行");
        }
    }

    @Test
    void oneBatchCannotCrossReceivedUtcMonths(@TempDir java.nio.file.Path dir) throws Exception {
        try (RawEventStore store = new RawEventStore(dir)) {
            assertThrows(IllegalArgumentException.class, () -> store.appendBatch(List.of(
                    event(null, "bucket", RawIngestKind.EVENTS,
                            Instant.EPOCH, Instant.parse("2026-08-31T23:59:59Z"), null, null),
                    event(null, "bucket", RawIngestKind.EVENTS,
                            Instant.EPOCH, Instant.parse("2026-09-01T00:00:00Z"), null, null))));
            assertEquals(0, store.count(YearMonth.of(2026, 8)));
            assertEquals(0, store.count(YearMonth.of(2026, 9)));
        }
    }

    private RawEvent event(String sourceEventId, String bucket, RawIngestKind kind,
                           Instant eventAt, Instant receivedAt,
                           String importSession, Integer importOrdinal) {
        RawEventSource source = kind == RawIngestKind.IMPORT
                ? RawEventSource.IMPORT
                : sourceEventId == null ? RawEventSource.THIRD_PARTY : RawEventSource.CONTENT;
        return RawEvent.create(ids, sourceEventId, bucket, source, 1, kind,
                eventAt, receivedAt, 1.0, Map.of("value", 1),
                importSession, importOrdinal);
    }

    private static RawEvent raw(String eventId, String sourceEventId, Instant received,
                                CanonicalJson.Value data) {
        return new RawEvent(eventId, sourceEventId, "collision", RawEventSource.CONTENT,
                1, RawIngestKind.EVENTS, received, received, 1,
                data.json(), data.sha256(), null, null);
    }
}
