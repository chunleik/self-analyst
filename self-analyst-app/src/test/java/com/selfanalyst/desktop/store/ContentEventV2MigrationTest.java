package com.selfanalyst.desktop.store;

import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.Database;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.PulseTimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentEventV2MigrationTest {

    private static final String FORBIDDEN = "SELF_ANALYST_FORBIDDEN_BODY_7F3A";

    @TempDir
    Path tempDir;

    @Test
    void sanitizesLegacyRowsExtractsTitleAndPhysicallyRemovesBody() throws Exception {
        Path dataDir = tempDir.resolve("events");
        Path dbPath = dataDir.resolve("events.db");
        try (Database db = new Database(dataDir)) {
            BucketStore buckets = new BucketStore(db);
            String bucketId = "aw-watcher-content_test";
            buckets.create(Bucket.create(
                    bucketId, "Content", "listening", "aw-watcher-content", "test"));
            Path legacyContentDb = dataDir.resolve(bucketId + ".db");
            Path legacyUnifiedBackup = dataDir.resolve("events.db.pre-legacy-migration-123");
            Path staleWorkFile = dataDir.resolve("events.db.migrating");
            Files.writeString(legacyContentDb, FORBIDDEN);
            Files.writeString(legacyUnifiedBackup, FORBIDDEN);
            Files.writeString(staleWorkFile, FORBIDDEN);
            insertLegacy(db, bucketId,
                    "{\"app\":\"Weixin.exe\",\"title\":\"微信\","
                            + "\"text_content\":\"微信\\n项目讨论群\\n聊天记录\\n聊天信息\\n"
                            + FORBIDDEN + "\",\"uia_chars\":500}");

            ContentEventV2Migration.Result first = ContentEventV2Migration.migrate(db);
            assertEquals(1, first.scanned());
            assertEquals(1, first.sanitized());

            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            Map<String, Object> data = events.queryAllEvents(bucketId).getFirst().data();
            assertEquals(2, ((Number) data.get("schema_version")).intValue());
            assertEquals("项目讨论群", data.get("context_title"));
            assertEquals("chat", data.get("context_kind"));
            assertFalse(data.containsKey("text_content"));
            assertFalse(data.toString().contains(FORBIDDEN));
            assertFalse(Files.exists(legacyContentDb));
            assertFalse(Files.exists(legacyUnifiedBackup));
            assertFalse(Files.exists(staleWorkFile));

            ContentEventV2Migration.Result second = ContentEventV2Migration.migrate(db);
            assertEquals(0, second.scanned());
            assertEquals(0, second.sanitized());
            try (PreparedStatement marker = db.metaConnection().prepareStatement(
                    "SELECT 1 FROM schema_migrations WHERE id = ?")) {
                marker.setString(1, ContentEventV2Migration.MIGRATION_ID);
                try (ResultSet result = marker.executeQuery()) {
                    assertTrue(result.next(), "migration marker must follow physical cleanup");
                }
            }

            insertLegacy(db, bucketId,
                    "{\"app\":\"notes.exe\",\"title\":\"后来写入\","
                            + "\"text_content\":\"" + FORBIDDEN + "\"}");
            ContentEventV2Migration.Result third = ContentEventV2Migration.migrate(db);
            assertEquals(1, third.scanned());
            assertEquals(1, third.sanitized());
            assertFalse(events.queryAllEvents(bucketId).getLast().data()
                    .containsKey("text_content"));
        }

        assertFileDoesNotContain(dbPath, FORBIDDEN);
        assertFileDoesNotContain(Path.of(dbPath + "-wal"), FORBIDDEN);
        assertFileDoesNotContain(Path.of(dbPath + "-shm"), FORBIDDEN);
    }

    @Test
    void sanitizesNonstandardBucketIdentifiedByClient() throws Exception {
        Path dataDir = tempDir.resolve("custom-client");
        try (Database db = new Database(dataDir)) {
            String bucketId = "custom-content";
            new BucketStore(db).create(Bucket.create(
                    bucketId, "Custom", "listening", "aw-watcher-content", "test"));
            insertLegacy(db, bucketId,
                    "{\"app\":\"notes.exe\",\"title\":\"设计文档\","
                            + "\"text_content\":\"" + FORBIDDEN + "\"}");

            ContentEventV2Migration.Result result = ContentEventV2Migration.migrate(db);
            assertEquals(1, result.sanitized());
            Map<String, Object> data = new EventStore(db, PulseTimeConfig.DEFAULT)
                    .queryAllEvents(bucketId).getFirst().data();
            assertEquals("设计文档", data.get("title"));
            assertEquals("window", data.get("title_source"));
            assertFalse(data.containsKey("text_content"));
        }
    }

    @Test
    void unsafeLegacyBucketIdCannotEscapeDataDirectoryOrDeleteMainDatabase() throws Exception {
        Path dataDir = tempDir.resolve("unsafe");
        Path outside = tempDir.resolve("outside.db");
        Files.writeString(outside, FORBIDDEN);
        try (Database db = new Database(dataDir)) {
            try (PreparedStatement statement = db.metaConnection().prepareStatement("""
                    INSERT INTO buckets(id, name, type, client, hostname, created, last_updated)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, "..\\outside");
                statement.setString(2, "Unsafe");
                statement.setString(3, "listening");
                statement.setString(4, "aw-watcher-content");
                statement.setString(5, "test");
                statement.setString(6, Instant.now().toString());
                statement.setString(7, Instant.now().toString());
                statement.executeUpdate();
            }

            assertThrows(IllegalStateException.class,
                    () -> ContentEventV2Migration.migrate(db));
            assertTrue(Files.exists(outside));
            assertTrue(Files.exists(dataDir.resolve("events.db")));
        }
    }

    @Test
    void invalidLegacyCandidateMetadataIsDiscardedAndReextracted() throws Exception {
        Path dataDir = tempDir.resolve("invalid-candidate");
        try (Database db = new Database(dataDir)) {
            String bucketId = "aw-watcher-content_invalid";
            new BucketStore(db).create(Bucket.create(
                    bucketId, "Content", "listening", "aw-watcher-content", "test"));
            insertLegacy(db, bucketId,
                    "{\"app\":\"Weixin.exe\",\"title\":\"微信\","
                            + "\"context_title\":\"损坏候选\",\"context_kind\":\"legacy\","
                            + "\"title_source\":\"legacy\",\"title_confidence\":\"certain\","
                            + "\"text_content\":\"微信\\n重新提取的群\\n聊天记录\\n聊天信息\"}");

            ContentEventV2Migration.migrate(db);
            Map<String, Object> data = new EventStore(db, PulseTimeConfig.DEFAULT)
                    .queryAllEvents(bucketId).getFirst().data();
            assertEquals("重新提取的群", data.get("context_title"));
            assertEquals("chat", data.get("context_kind"));
            assertEquals("uia_context", data.get("title_source"));
        }
    }

    @Test
    void semanticallyInvalidLegacyCandidateIsDiscardedAndReextracted() throws Exception {
        Path dataDir = tempDir.resolve("invalid-title");
        try (Database db = new Database(dataDir)) {
            String bucketId = "aw-watcher-content_invalid_title";
            new BucketStore(db).create(Bucket.create(
                    bucketId, "Content", "listening", "aw-watcher-content", "test"));
            insertLegacy(db, bucketId,
                    "{\"app\":\"Weixin.exe\",\"title\":\"微信\","
                            + "\"context_title\":\"https://example.com/body\","
                            + "\"context_kind\":\"article\",\"title_source\":\"uia_document\","
                            + "\"title_confidence\":\"high\","
                            + "\"text_content\":\"微信\\n有效群名\\n聊天记录\\n聊天信息\"}");

            ContentEventV2Migration.migrate(db);
            Map<String, Object> data = new EventStore(db, PulseTimeConfig.DEFAULT)
                    .queryAllEvents(bucketId).getFirst().data();
            assertEquals("有效群名", data.get("context_title"));
            assertEquals("chat", data.get("context_kind"));
        }
    }

    @Test
    void failedPhysicalCompactionLeavesDirtyStateForNextStartupRetry() throws Exception {
        Path dataDir = tempDir.resolve("compact-retry");
        Path dbPath = dataDir.resolve("events.db");
        try (Database db = new Database(dataDir)) {
            String bucketId = "aw-watcher-content_retry";
            new BucketStore(db).create(Bucket.create(
                    bucketId, "Content", "listening", "aw-watcher-content", "test"));
            ContentEventV2Migration.migrate(db); // establish a clean marker/high-water
            insertLegacy(db, bucketId,
                    "{\"app\":\"notes.exe\",\"title\":\"Retry\","
                            + "\"text_content\":\"" + FORBIDDEN + "\"}");

            assertThrows(IllegalStateException.class,
                    () -> ContentEventV2Migration.migrate(db, () -> {
                        throw new RuntimeException("simulated compact failure");
                    }));
            try (Statement state = db.metaConnection().createStatement();
                 ResultSet result = state.executeQuery("""
                         SELECT last_event_id, pending_event_id, needs_compaction
                         FROM content_event_migration_state WHERE id = 1
                         """)) {
                assertTrue(result.next());
                assertEquals(0, result.getLong("last_event_id"));
                assertTrue(result.getLong("pending_event_id") > 0);
                assertEquals(1, result.getInt("needs_compaction"));
            }

            ContentEventV2Migration.Result retry = ContentEventV2Migration.migrate(db);
            assertEquals(1, retry.scanned());
            assertEquals(0, retry.sanitized(),
                    "logical cleanup was committed before the simulated physical failure");
            try (Statement state = db.metaConnection().createStatement();
                 ResultSet result = state.executeQuery("""
                         SELECT last_event_id, pending_event_id, needs_compaction
                         FROM content_event_migration_state WHERE id = 1
                         """)) {
                assertTrue(result.next());
                assertTrue(result.getLong("last_event_id") > 0);
                assertEquals(0, result.getLong("pending_event_id"));
                assertEquals(0, result.getInt("needs_compaction"));
            }
        }
        assertFileDoesNotContain(dbPath, FORBIDDEN);
        assertFileDoesNotContain(Path.of(dbPath + "-wal"), FORBIDDEN);
        assertFileDoesNotContain(Path.of(dbPath + "-shm"), FORBIDDEN);
    }

    @Test
    void scanWatermarkAdvancesPastLargeNonContentTail() throws Exception {
        Path dataDir = tempDir.resolve("global-watermark");
        try (Database db = new Database(dataDir)) {
            ContentEventV2Migration.migrate(db);
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            for (int i = 0; i < 100; i++) {
                events.insertEvent("aw-watcher-window_test", new com.selfanalyst.aw.model.Event(
                        Instant.parse("2026-08-30T01:00:00Z").plusSeconds(i), 1,
                        Map.of("app", "editor.exe", "title", "Document " + i)));
            }

            ContentEventV2Migration.Result result = ContentEventV2Migration.migrate(db);
            assertEquals(0, result.scanned());
            try (Statement statement = db.metaConnection().createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT s.last_event_id, (SELECT MAX(id) FROM events) AS max_id
                         FROM content_event_migration_state s WHERE s.id = 1
                         """)) {
                assertTrue(rows.next());
                assertEquals(rows.getLong("max_id"), rows.getLong("last_event_id"));
            }
            assertEquals(0, ContentEventV2Migration.migrate(db).scanned());
        }
    }

    private static void insertLegacy(Database db, String bucketId, String dataJson) throws Exception {
        try (PreparedStatement statement = db.metaConnection().prepareStatement("""
                INSERT INTO events(bucket_id, timestamp, duration, datastr, app)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, bucketId);
            statement.setString(2, Instant.parse("2026-08-30T01:00:00Z").toString());
            statement.setDouble(3, 2.0);
            statement.setString(4, dataJson);
            statement.setString(5, "");
            statement.executeUpdate();
        }
    }

    private static void assertFileDoesNotContain(Path path, String marker) throws Exception {
        if (!Files.exists(path)) return;
        String bytes = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
        assertFalse(bytes.contains(marker), () -> "forbidden marker remains in " + path);
    }
}
