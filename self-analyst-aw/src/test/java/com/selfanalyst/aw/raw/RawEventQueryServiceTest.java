package com.selfanalyst.aw.raw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawEventQueryServiceTest {

    @Test
    void pagesAcrossMonthsWithoutOmissionOrDuplication(@TempDir Path dir) throws Exception {
        RawEventIdGenerator ids = new RawEventIdGenerator();
        List<RawEvent> inserted = new ArrayList<>();
        try (RawEventStore store = new RawEventStore(dir)) {
            for (String time : List.of(
                    "2026-08-31T23:59:57Z", "2026-08-31T23:59:58Z",
                    "2026-08-31T23:59:59Z", "2026-09-01T00:00:00Z",
                    "2026-09-01T00:00:01Z", "2026-09-01T00:00:02Z")) {
                RawEvent event = event(ids, "bucket-a", time, "id-" + inserted.size());
                inserted.add(store.append(event));
            }
            store.append(event(ids, "other-bucket", "2026-09-01T00:00:01Z", "other"));
        }

        Instant start = Instant.parse("2026-08-31T23:59:00Z");
        Instant end = Instant.parse("2026-09-01T00:01:00Z");
        try (RawEventQueryService queries = new RawEventQueryService(dir, 31, 1000)) {
            List<RawEvent> actual = new ArrayList<>();
            String cursor = null;
            do {
                RawEventQueryService.QueryPage page = queries.query(
                        "bucket-a", start, end, 2, cursor);
                actual.addAll(page.events());
                cursor = page.nextCursor();
            } while (cursor != null);

            assertEquals(inserted.stream().map(RawEvent::eventId).toList(),
                    actual.stream().map(RawEvent::eventId).toList());
            assertEquals(actual.size(), new HashSet<>(actual.stream()
                    .map(RawEvent::eventId).toList()).size());
            assertTrue(actual.stream().allMatch(event -> event.bucketId().equals("bucket-a")));
        }
    }

    @Test
    void cursorIsBoundToBucketAndTimeRange(@TempDir Path dir) throws Exception {
        RawEventIdGenerator ids = new RawEventIdGenerator();
        try (RawEventStore store = new RawEventStore(dir)) {
            store.append(event(ids, "bucket-a", "2026-09-01T00:00:00Z", "one"));
            store.append(event(ids, "bucket-a", "2026-09-01T00:00:01Z", "two"));
        }
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        Instant end = Instant.parse("2026-09-02T00:00:00Z");
        try (RawEventQueryService queries = new RawEventQueryService(dir, 31, 1000)) {
            String cursor = queries.query("bucket-a", start, end, 1, null).nextCursor();
            assertThrows(IllegalArgumentException.class,
                    () -> queries.query("bucket-b", start, end, 1, cursor));
            assertThrows(IllegalArgumentException.class,
                    () -> queries.query("bucket-a", start.minusSeconds(1), end, 1, cursor));
            assertThrows(IllegalArgumentException.class,
                    () -> queries.query("bucket-a", start, end, 1, "not-base64"));
            assertNull(queries.query("bucket-a", start, end, 100, null).nextCursor());
        }
    }

    @Test
    void rejectsUnboundedOrOverLimitRequests(@TempDir Path dir) throws Exception {
        try (RawEventQueryService queries = new RawEventQueryService(dir, 31, 10)) {
            Instant start = Instant.parse("2026-09-01T00:00:00Z");
            Instant end = Instant.parse("2026-10-03T00:00:00Z");
            assertThrows(IllegalArgumentException.class,
                    () -> queries.query("", start, end, 1, null));
            assertThrows(NullPointerException.class,
                    () -> queries.query("bucket", null, end, 1, null));
            assertThrows(IllegalArgumentException.class,
                    () -> queries.query("bucket", start, end, 1, null));
            assertThrows(IllegalArgumentException.class,
                    () -> queries.query("bucket", start, start.plusSeconds(1), 11, null));
        }
    }

    private static RawEvent event(RawEventIdGenerator ids, String bucket,
                                  String time, String sourceId) {
        Instant received = Instant.parse(time);
        return RawEvent.create(ids, sourceId, bucket, RawEventSource.CONTENT, 1,
                RawIngestKind.HEARTBEAT, received, received, 1,
                Map.of("title", sourceId), null, null);
    }
}
