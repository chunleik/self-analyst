package com.selfanalyst.events.raw;

import com.selfanalyst.events.store.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawFirstEnablementBoundaryTest {

    @Test
    void firstEnablementLeavesDevelopmentDatabasesUntouchedAndStartsWithNewEvents(
            @TempDir Path dir) throws Exception {
        Path awDb = dir.resolve("aw.db");
        Path legacyBucket = dir.resolve("legacy-bucket.db");
        byte[] awBefore = "existing development projection".getBytes();
        byte[] bucketBefore = "existing legacy bucket".getBytes();
        Files.write(awDb, awBefore);
        Files.write(legacyBucket, bucketBefore);
        Path rawDir = dir.resolve("raw");

        try (RawEventStore raw = new RawEventStore(rawDir)) {
            assertEquals(0, raw.count(YearMonth.of(2026, 9)));
            Instant now = Instant.parse("2026-09-03T12:00:00Z");
            raw.append(RawEvent.create(new RawEventIdGenerator(), "new", "bucket",
                    RawEventSource.WINDOW, 1, RawIngestKind.HEARTBEAT,
                    now, now, 1, Map.of("app", "editor"), null, null));
            assertEquals(1, raw.count(YearMonth.of(2026, 9)));
        }

        assertArrayEquals(awBefore, Files.readAllBytes(awDb));
        assertArrayEquals(bucketBefore, Files.readAllBytes(legacyBucket));

        try (Database db = new Database(dir)) {
            assertTrue(Files.exists(dir.resolve(Database.PROJECTION_FILENAME)));
        }
        assertArrayEquals(awBefore, Files.readAllBytes(awDb));
        assertArrayEquals(bucketBefore, Files.readAllBytes(legacyBucket));
    }
}
