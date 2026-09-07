package com.selfanalyst.events.export;

import com.selfanalyst.events.projection.EventProjector;
import com.selfanalyst.events.raw.RawEventStore;
import com.selfanalyst.events.store.BucketStore;
import com.selfanalyst.events.store.Database;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.PulseTimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataImporterRawTest {

    @Test
    void retryUsesStableDerivedSessionAndDoesNotDuplicateRawOrProjection(@TempDir Path dir)
            throws Exception {
        try (Database db = new Database(dir.resolve("aw"));
             RawEventStore raw = new RawEventStore(dir.resolve("raw"))) {
            BucketStore buckets = new BucketStore(db);
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            DataImporter importer = new DataImporter(buckets, events, raw,
                    new EventProjector(db, 120, 1000, "v1"));
            Map<String, Object> payload = Map.of(
                    "buckets", List.of(Map.of(
                            "id", "bucket", "client", "window", "hostname", "host")),
                    "events", Map.of("bucket", List.of(Map.of(
                            "timestamp", "2026-09-03T12:00:00Z",
                            "duration", 1,
                            "data", Map.of("app", "editor")))));

            Map<String, Object> first = importer.importData(payload);
            Map<String, Object> retry = importer.importData(payload);

            assertEquals(first.get("import_session_id"), retry.get("import_session_id"));
            assertEquals(1, raw.count(YearMonth.now(ZoneOffset.UTC)));
            assertEquals(1, events.countByBucket("bucket"));
        }
    }
}
