package com.selfanalyst.events.projection;

import com.selfanalyst.events.model.Bucket;
import com.selfanalyst.events.raw.RawEvent;
import com.selfanalyst.events.raw.RawEventIdGenerator;
import com.selfanalyst.events.raw.RawEventSource;
import com.selfanalyst.events.raw.RawEventStore;
import com.selfanalyst.events.raw.RawIngestKind;
import com.selfanalyst.events.store.BucketStore;
import com.selfanalyst.events.store.Database;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.PulseTimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectionRecoveryServiceTest {

    @Test
    void restartResumesAfterLastCommittedCheckpointWithoutSkippingFailure(
            @TempDir Path dir) throws Exception {
        Path rawDir = dir.resolve("raw");
        RawEvent first = raw("first", "2026-09-03T12:00:00Z");
        RawEvent second = raw("second", "2026-09-03T12:00:01Z");
        try (RawEventStore rawStore = new RawEventStore(rawDir)) {
            rawStore.appendBatch(List.of(first, second));
        }

        try (Database database = databaseWithBucket(dir.resolve("aw"))) {
            AtomicInteger attempts = new AtomicInteger();
            EventProjector failsSecond = new EventProjector(database, 120, 2, "v1",
                    (event, id) -> {
                        if (attempts.incrementAndGet() == 2) throw new Exception("crash");
                    });
            try (ProjectionRecoveryService recovery =
                         new ProjectionRecoveryService(rawDir, failsSecond)) {
                assertThrows(IllegalStateException.class, recovery::recoverPending);
            }
            ProjectionCheckpointStore checkpoints =
                    new ProjectionCheckpointStore(database.metaConnection());
            assertEquals(first.eventId(), checkpoints.find(YearMonth.of(2026, 9))
                    .orElseThrow().eventId());
            assertEquals(1, new EventStore(database, PulseTimeConfig.DEFAULT)
                    .countByBucket("bucket"));

            EventProjector resumed = new EventProjector(database, 120, 2, "v1");
            try (ProjectionRecoveryService recovery =
                         new ProjectionRecoveryService(rawDir, resumed)) {
                assertEquals(1, recovery.recoverPending());
                assertEquals(0, recovery.recoverPending());
            }
            assertEquals(second.eventId(), checkpoints.find(YearMonth.of(2026, 9))
                    .orElseThrow().eventId());
            assertEquals(2, new EventStore(database, PulseTimeConfig.DEFAULT)
                    .countByBucket("bucket"));
        }
    }

    private static Database databaseWithBucket(Path dir) {
        Database database = new Database(dir);
        Instant now = Instant.parse("2026-09-03T00:00:00Z");
        new BucketStore(database).create(new Bucket(
                "bucket", "bucket", "test", "test", "host", now, now));
        return database;
    }

    private static RawEvent raw(String sourceId, String time) {
        Instant instant = Instant.parse(time);
        return RawEvent.create(new RawEventIdGenerator(), sourceId, "bucket",
                RawEventSource.THIRD_PARTY, 1, RawIngestKind.EVENTS,
                instant, instant, 1, Map.of("value", sourceId), null, null);
    }
}
