package com.selfanalyst.aw.export;

import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.ContentEventPolicyViolationException;
import com.selfanalyst.aw.store.Database;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.PulseTimeConfig;
import com.selfanalyst.aw.model.Bucket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataImporterContentPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsLegacyContentImportBeforeCreatingBucketsOrWritingRows() throws Exception {
        try (Database db = new Database(tempDir)) {
            BucketStore buckets = new BucketStore(db);
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            DataImporter importer = new DataImporter(buckets, events);
            String bucketId = "legacy-content-id";

            Map<String, Object> legal = event(Map.of(
                    "schema_version", 2,
                    "app", "Weixin.exe",
                    "title", "微信",
                    "title_source", "window"));
            Map<String, Object> forbidden = event(Map.of(
                    "app", "Weixin.exe",
                    "title", "微信",
                    "text_content", "SELF_ANALYST_FORBIDDEN_BODY_7F3A"));
            Map<String, Object> payload = Map.of(
                    "buckets", List.of(Map.of(
                            "id", bucketId,
                            "name", "Legacy content",
                            "type", "listening",
                            "client", "aw-watcher-content",
                            "hostname", "test")),
                    "events", Map.of(bucketId, List.of(legal, forbidden)));

            assertThrows(ContentEventPolicyViolationException.class,
                    () -> importer.importData(payload));
            assertFalse(buckets.get(bucketId).isPresent());
            assertEquals(0, events.countByBucket(bucketId));
        }
    }

    @Test
    void existingContentBucketMetadataCannotBeOverriddenDuringPreflight() throws Exception {
        try (Database db = new Database(tempDir.resolve("existing"))) {
            BucketStore buckets = new BucketStore(db);
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            DataImporter importer = new DataImporter(buckets, events);
            String bucketId = "custom-content";
            buckets.create(Bucket.create(
                    bucketId, "Content", "listening", "aw-watcher-content", "test"));

            Map<String, Object> payload = Map.of(
                    // Import metadata must not override the existing content client for validation.
                    "buckets", List.of(Map.of(
                            "id", bucketId, "client", "unknown", "hostname", "test")),
                    "events", Map.of(bucketId, List.of(event(Map.of(
                            "app", "Weixin.exe",
                            "title", "微信",
                            "text_content", "SELF_ANALYST_FORBIDDEN_BODY_7F3A")))));

            assertThrows(ContentEventPolicyViolationException.class,
                    () -> importer.importData(payload));
            assertEquals(0, events.countByBucket(bucketId));
        }
    }

    @Test
    void invalidEmptyBucketIsRejectedBeforeAnyBucketIsCreated() throws Exception {
        try (Database db = new Database(tempDir.resolve("invalid-id"))) {
            BucketStore buckets = new BucketStore(db);
            DataImporter importer = new DataImporter(
                    buckets, new EventStore(db, PulseTimeConfig.DEFAULT));
            Map<String, Object> payload = Map.of(
                    "buckets", List.of(
                            Map.of("id", "valid-bucket", "client", "window"),
                            Map.of("id", "..\\outside", "client", "aw-watcher-content")),
                    "events", Map.of());

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> importer.importData(payload));
            assertEquals("Invalid bucket id", error.getMessage());
            assertFalse(buckets.get("valid-bucket").isPresent());
        }
    }

    private static Map<String, Object> event(Map<String, Object> data) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "duration", 1.0,
                "data", data);
    }
}
