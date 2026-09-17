package com.selfanalyst.events.store;

import com.selfanalyst.events.model.Bucket;
import com.selfanalyst.events.model.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class MergedEventStoreTest {
    @TempDir Path dir;
    private static final Instant START = Instant.parse("2026-09-16T00:00:00Z");
    private static final String BUCKET = "test";

    private Database database() {
        Database db = new Database(dir);
        new BucketStore(db).create(new Bucket(BUCKET, "test", "test", "test", "host", START, START));
        return db;
    }

    private static Event event(int second, double duration, String value) {
        return new Event(START.plusSeconds(second), duration, Map.of("app", value));
    }

    @Test void mergesEquivalentDataWithoutShrinkingAndSplitsChangesAndGaps() throws Exception {
        try (Database db = database(); MergedEventStore store = new MergedEventStore(db, 5)) {
            Event first = store.heartbeat(BUCKET, event(0, 10, "A"), "a", "s");
            assertEquals(first.id(), store.heartbeat(BUCKET, event(5, 1, "A"), "b", "s").id());
            assertEquals(10, store.heartbeat(BUCKET, event(0, 10, "A"), "c", "s").duration());
            assertEquals(17, store.heartbeat(BUCKET, event(15, 2, "A"), "d", "s").duration());
            Event next = store.heartbeat(BUCKET, event(18, 1, "B"), "e", "s");
            assertNotEquals(first.id(), next.id());
            Event third = store.heartbeat(BUCKET, event(19, 1, "A"), "f", "s");
            assertNotEquals(first.id(), third.id());
            assertNotEquals(third.id(), store.heartbeat(BUCKET, event(30, 1, "A"), "g", "s").id());
        }
    }

    @Test void lateRecordsDoNotReplaceCurrentAndSessionBreaksContinuity() throws Exception {
        try (Database db = database(); MergedEventStore store = new MergedEventStore(db, 5)) {
            Event current = store.heartbeat(BUCKET, event(10, 1, "A"), "a", "s");
            assertNotEquals(current.id(), store.heartbeat(BUCKET, event(0, 1, "B"), "b", "s").id());
            assertEquals(current.id(), store.heartbeat(BUCKET, event(12, 1, "A"), "c", "s").id());
            assertNotEquals(current.id(), store.heartbeat(BUCKET, event(13, 1, "A"), "d", "new").id());
        }
    }

    @Test void receiptsSurviveRestartRejectConflictsAndExpire() throws Exception {
        MutableClock clock = new MutableClock(START);
        long id;
        try (Database db = database(); MergedEventStore store = new MergedEventStore(db, 5, clock)) {
            id = store.heartbeat(BUCKET, event(0, 1, "A"), "same", "s").id();
        }
        try (Database db = database(); MergedEventStore store = new MergedEventStore(db, 5, clock)) {
            assertEquals(id, store.heartbeat(BUCKET, event(0, 1, "A"), "same", "s").id());
            assertThrows(IllegalArgumentException.class,
                    () -> store.heartbeat(BUCKET, event(0, 1, "B"), "same", "s"));
            clock.now = START.plus(Duration.ofHours(25));
            store.pruneReceipts();
            try (var stmt = db.metaConnection().createStatement(); var rows = stmt.executeQuery("SELECT count(*) FROM event_receipts")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test void canonicalDataAndAtomicBatchValidation() throws Exception {
        try (Database db = database(); MergedEventStore store = new MergedEventStore(db, 5)) {
            var a = new LinkedHashMap<String, Object>(); a.put("a", 1); a.put("b", 2);
            var b = new LinkedHashMap<String, Object>(); b.put("b", 2); b.put("a", 1);
            Event first = store.heartbeat(BUCKET, new Event(START, 1, a), "a", "s");
            assertEquals(first.id(), store.heartbeat(BUCKET, new Event(START.plusSeconds(1), 1, b), "b", "s").id());
            assertThrows(IllegalArgumentException.class, () -> store.insertBatch(BUCKET, List.of(
                    new MergedEventStore.Submission(event(5, 1, "X"), "x"),
                    new MergedEventStore.Submission(event(6, Double.NaN, "X"), "y"))));
            assertEquals(1, new EventStore(db, PulseTimeConfig.DEFAULT).countByBucket(BUCKET));
            var inserted = store.insertBatch(BUCKET, List.of(
                    new MergedEventStore.Submission(event(5, 1, "X"), "x"),
                    new MergedEventStore.Submission(event(5, 1, "X"), "y")));
            assertNotEquals(inserted.get(0).id(), inserted.get(1).id());
            assertThrows(IllegalArgumentException.class, () -> store.heartbeat(BUCKET, event(0, -1, "A"), "z", "s"));
        }
    }

    @Test void concurrentHeartbeatsDoNotLoseDuration() throws Exception {
        try (Database db = database(); MergedEventStore store = new MergedEventStore(db, 5);
             var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 1; i <= 100; i++) {
                int duration = i;
                futures.add(pool.submit(() -> store.heartbeat(BUCKET, event(0, duration, "A"), "id" + duration, "s")));
            }
            for (var future : futures) future.get();
            var events = new EventStore(db, PulseTimeConfig.DEFAULT).queryAllEvents(BUCKET);
            assertEquals(1, events.size()); assertEquals(100, events.getFirst().duration());
        }
    }

    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @Test void failedBatchRollsBackEventsReceiptsAndBucketMetadata() throws Exception {
        try (Database db = database(); MergedEventStore store = new MergedEventStore(db, 5)) {
            try (var s = db.metaConnection().createStatement()) {
                s.execute("CREATE TRIGGER fail_second BEFORE INSERT ON events WHEN NEW.app='fail' BEGIN SELECT RAISE(ABORT,'injected'); END");
            }
            assertThrows(IllegalStateException.class, () -> store.insertBatch(BUCKET, List.of(
                    new MergedEventStore.Submission(event(0, 1, "ok"), "one"),
                    new MergedEventStore.Submission(event(1, 1, "fail"), "two"))));
            assertEquals(0, new EventStore(db, PulseTimeConfig.DEFAULT).countByBucket(BUCKET));
            try (var s = db.metaConnection().createStatement(); var rows = s.executeQuery("SELECT count(*) FROM event_receipts")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
            store.heartbeat(BUCKET, event(0, 1, "ok"), "one", "s");
            new EventStore(db, PulseTimeConfig.DEFAULT).deleteByBucket(BUCKET);
            assertThrows(IllegalStateException.class, () -> store.heartbeat(BUCKET, event(0, 1, "ok"), "one", "s"));
            assertEquals(0, new EventStore(db, PulseTimeConfig.DEFAULT).countByBucket(BUCKET));
        }
    }

    @Test void importFailureRollsBackNewBucketsAndRetryIsIdempotent() throws Exception {
        try (Database db = database(); MergedEventStore store = new MergedEventStore(db, 5)) {
            Bucket added = Bucket.create("new", "new", "test", "test", "test");
            Map<String, List<MergedEventStore.Submission>> batch = new LinkedHashMap<>();
            batch.put("new", List.of(new MergedEventStore.Submission(event(0, 1, "A"), "one")));
            batch.put("absent", List.of(new MergedEventStore.Submission(event(1, 1, "B"), "two")));
            assertThrows(IllegalArgumentException.class, () -> store.importBatch(List.of(added), batch));
            assertTrue(new BucketStore(db).get("new").isEmpty());
            batch.remove("absent");
            store.importBatch(List.of(added), batch); store.importBatch(List.of(added), batch);
            assertEquals(1, new EventStore(db, PulseTimeConfig.DEFAULT).countByBucket("new"));
        }
    }

    @Test void diskPressureBlocksNewWritesWithoutDeletingHistory() throws Exception {
        try (Database db = database(); MergedEventStore store = new MergedEventStore(db, 5)) {
            store.heartbeat(BUCKET, event(0, 1, "A"), "first", "s");
            store.setWritableCheck(() -> { throw new IllegalStateException("disk blocked"); });
            assertThrows(IllegalStateException.class, () -> store.heartbeat(BUCKET, event(2, 1, "A"), "second", "s"));
            var events = new EventStore(db, PulseTimeConfig.DEFAULT).queryAllEvents(BUCKET);
            assertEquals(1, events.size()); assertEquals(1, events.getFirst().duration());
        }
    }
}
