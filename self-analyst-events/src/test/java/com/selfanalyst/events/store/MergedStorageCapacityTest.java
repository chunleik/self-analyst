package com.selfanalyst.events.store;

import com.selfanalyst.events.model.*;
import com.selfanalyst.events.raw.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MergedStorageCapacityTest {
    @TempDir Path root;

    @Test void hundredThousandHeartbeatsHaveBoundedReceiptsAndCompactPermanentHistory() throws Exception {
        Instant start = Instant.parse("2026-09-16T00:00:00Z");
        var clock = new MergedEventStoreTest.MutableClock(start);
        Path compact = root.resolve("compact");
        try (Database db = new Database(compact); MergedEventStore store = new MergedEventStore(db, 5, clock)) {
            new BucketStore(db).create(Bucket.create("test", "test", "test", "test", "test"));
            for (int i = 0; i < 100_000; i++) {
                store.heartbeat("test", new Event(start.plusMillis(i * 500L), 2,
                        Map.of("app", "editor", "title", "capacity fixture")), "sample-" + i, "session");
            }
            assertEquals(1, new EventStore(db, PulseTimeConfig.DEFAULT).countByBucket("test"));
            clock.now = start.plus(Duration.ofHours(25)); store.pruneReceipts();
            try (var s = db.metaConnection().createStatement(); var r = s.executeQuery("SELECT count(*) FROM event_receipts")) {
                assertTrue(r.next()); assertEquals(0, r.getInt(1));
            }
            for (int day = 2; day <= 3; day++) {
                clock.now = start.plus(Duration.ofDays(day));
                for (int i = 0; i < 1000; i++) store.heartbeat("test",
                        new Event(clock.now.plusMillis(i * 500L), 2, Map.of("app", "editor", "title", "capacity fixture")),
                        "day-" + day + "-" + i, "session");
            }
            clock.now = start.plus(Duration.ofDays(5)); store.pruneReceipts();
            assertEquals(3, new EventStore(db, PulseTimeConfig.DEFAULT).countByBucket("test"));
        }
        long compactBytes = Files.size(compact.resolve("events.db"));
        Path legacy = root.resolve("legacy");
        try (RawEventStore raw = new RawEventStore(legacy.resolve("raw")); Database projection = new Database(legacy)) {
            projection.metaConnection().setAutoCommit(false);
            try (var source = projection.metaConnection().prepareStatement("INSERT INTO raw_projection_sources VALUES(?,?,?,?,?)")) {
            var ids = new RawEventIdGenerator();
            for (int batch = 0; batch < 100; batch++) {
                var events = new ArrayList<RawEvent>();
                for (int offset = 0; offset < 1000; offset++) {
                    int i = batch * 1000 + offset;
                    Instant at = start.plusMillis(i * 500L);
                    events.add(RawEvent.create(ids, "sample-" + i, "test", RawEventSource.WINDOW, 1,
                            RawIngestKind.HEARTBEAT, at, at, 2,
                            Map.of("app", "editor", "title", "capacity fixture"), null, null));
                }
                raw.appendBatch(events);
                // 使用旧版实际 schema 和相同事件 ID，批量构建永久映射；这里只比较容量，不比较旧版吞吐。
                for (var event : events) {
                    source.setString(1, event.eventId()); source.setString(2, "test"); source.setLong(3, 1);
                    source.setString(4, "v1"); source.setString(5, event.receivedAt().toString()); source.addBatch();
                }
                source.executeBatch();
            }
            }
            projection.metaConnection().commit();
        }
        long rawBytes;
        try (var paths = Files.walk(legacy)) {
            rawBytes = paths.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (Exception e) { throw new IllegalStateException(e); }
            }).sum();
        }
        System.out.printf("MERGED_CAPACITY samples=100000 compactBytes=%d legacyDualLayerBytes=%d ratio=%.4f%n",
                compactBytes, rawBytes, compactBytes / (double) rawBytes);
        assertTrue(compactBytes <= rawBytes * 0.20, "compact=" + compactBytes + ", raw=" + rawBytes);
    }
}
