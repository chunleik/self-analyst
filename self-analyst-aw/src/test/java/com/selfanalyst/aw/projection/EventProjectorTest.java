package com.selfanalyst.aw.projection;

import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.raw.RawEvent;
import com.selfanalyst.aw.raw.RawEventIdGenerator;
import com.selfanalyst.aw.raw.RawEventSource;
import com.selfanalyst.aw.raw.RawEventStore;
import com.selfanalyst.aw.raw.RawIngestKind;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.Database;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.PulseTimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventProjectorTest {

    @Test
    void failureRollsBackProjectionAndRetryCreatesOneLogicalResult(@TempDir Path dir)
            throws Exception {
        RawEvent raw = raw("source-1", "2026-09-03T12:00:00Z", 1,
                RawIngestKind.EVENTS);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        try (Database database = databaseWithBucket(dir)) {
            EventProjector projector = new EventProjector(database, 120, 1000, "v1",
                    (event, id) -> {
                        if (failOnce.getAndSet(false)) throw new Exception("injected");
                    });
            EventStore events = new EventStore(database, PulseTimeConfig.DEFAULT);

            assertThrows(IllegalStateException.class, () -> projector.project(raw));
            assertEquals(0, events.countByBucket("bucket"));
            assertTrue(new ProjectionCheckpointStore(database.metaConnection())
                    .find(YearMonth.of(2026, 9)).isEmpty());

            long projectedId = projector.project(raw).projectionEventId();
            assertFalse(projector.project(raw).newlyProjected());
            assertEquals(projectedId, projector.project(raw).projectionEventId());
            assertEquals(1, events.countByBucket("bucket"));
        }
    }

    @Test
    void heartbeatMergeUsesConfiguredPulseAndUpdatesRawCoverage(@TempDir Path dir)
            throws Exception {
        RawEvent first = raw("source-1", "2026-09-03T12:00:00Z", 5,
                RawIngestKind.HEARTBEAT);
        RawEvent second = raw("source-2", "2026-09-03T12:00:10Z", 5,
                RawIngestKind.HEARTBEAT);
        List<RawEvent> immutableBefore;
        try (RawEventStore rawStore = new RawEventStore(dir.resolve("raw"))) {
            rawStore.appendBatch(List.of(first, second));
            immutableBefore = rawStore.readPartition(YearMonth.of(2026, 9));
        }
        try (Database database = databaseWithBucket(dir.resolve("projection"))) {
            EventProjector projector = new EventProjector(database, 30, 1000, "v1");
            var results = projector.projectBatch(List.of(second, first));

            assertEquals(2, results.size());
            assertEquals(results.get(0).projectionEventId(), results.get(1).projectionEventId());
            EventStore events = new EventStore(database, PulseTimeConfig.DEFAULT);
            assertEquals(1, events.countByBucket("bucket"));
            assertEquals(15, events.queryAllEvents("bucket").getFirst().duration());
            try (var statement = database.metaConnection().createStatement();
                 var coverage = statement.executeQuery(
                         "SELECT * FROM projection_event_coverage")) {
                assertTrue(coverage.next());
                assertEquals(2, coverage.getInt("raw_event_count"));
                assertEquals(first.eventId(), coverage.getString("first_raw_event_id"));
                assertEquals(second.eventId(), coverage.getString("last_raw_event_id"));
                assertEquals("v1", coverage.getString("projector_version"));
            }
        }
        try (RawEventStore rawStore = new RawEventStore(dir.resolve("raw"))) {
            assertEquals(immutableBefore,
                    rawStore.readPartition(YearMonth.of(2026, 9)),
                    "heartbeat 投影合并不得修改原始行");
        }
    }

    private static Database databaseWithBucket(Path dir) {
        Database database = new Database(dir);
        Instant now = Instant.parse("2026-09-03T00:00:00Z");
        new BucketStore(database).create(new Bucket(
                "bucket", "bucket", "test", "test", "host", now, now));
        return database;
    }

    private static RawEvent raw(String sourceId, String time, double duration,
                                RawIngestKind ingestKind) {
        Instant instant = Instant.parse(time);
        return RawEvent.create(new RawEventIdGenerator(), sourceId, "bucket",
                RawEventSource.WINDOW, 1, ingestKind, instant, instant, duration,
                Map.of("app", "editor", "title", "same"), null, null);
    }
}
