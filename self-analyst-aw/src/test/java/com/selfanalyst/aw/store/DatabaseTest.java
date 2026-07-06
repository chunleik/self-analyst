package com.selfanalyst.aw.store;

import com.selfanalyst.aw.model.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    @Test
    void rejectsBucketIdsThatWouldEscapeDataDirectory(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("aw-data");
        try (Database db = new Database(dataDir)) {
            assertThrows(IllegalArgumentException.class,
                    () -> db.bucketConnection("..\\outside"));
            assertThrows(IllegalArgumentException.class,
                    () -> db.bucketConnection("../outside"));
        }
        assertTrue(Files.notExists(dir.resolve("outside.db")));
    }

    @Test
    void storesBucketEventsInSingleAwDatabase(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("aw-data");
        try (Database db = new Database(dataDir)) {
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            events.insertEvent("bucket-a", new Event(
                    Instant.parse("2026-07-06T01:00:00Z"), 1.0, Map.of("app", "A")));
            events.insertEvent("bucket-b", new Event(
                    Instant.parse("2026-07-06T02:00:00Z"), 2.0, Map.of("app", "B")));

            assertEquals(1, events.countByBucket("bucket-a"));
            assertEquals("A", events.queryAllEvents("bucket-a").get(0).data().get("app"));
            assertEquals(1, events.countByBucket("bucket-b"));
            assertEquals("B", events.queryAllEvents("bucket-b").get(0).data().get("app"));
        }

        assertTrue(Files.exists(dataDir.resolve("aw.db")));
        assertFalse(Files.exists(dataDir.resolve("buckets.db")));
        assertFalse(Files.exists(dataDir.resolve("bucket-a.db")));
        assertFalse(Files.exists(dataDir.resolve("bucket-b.db")));
    }
}
