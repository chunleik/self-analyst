package com.selfanalyst.aw.store;

import com.selfanalyst.aw.model.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

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

    @Test
    void migratesLargeLegacyLayoutWithoutLosingNewDatabaseData(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("aw-data");
        try (Database db = new Database(dataDir)) {
            BucketStore buckets = new BucketStore(db);
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            buckets.create(new com.selfanalyst.aw.model.Bucket(
                    "new-bucket", "New", "test", "client", "host",
                    Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")));
            events.insertEvent("new-bucket", new Event(
                    Instant.parse("2026-01-01T00:00:01Z"), 1.0, Map.of("source", "new")));
        }

        createLegacyLayout(dataDir, "legacy-bucket", 1_205);

        try (Database db = new Database(dataDir)) {
            BucketStore buckets = new BucketStore(db);
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            assertTrue(buckets.get("legacy-bucket").isPresent());
            assertTrue(buckets.get("new-bucket").isPresent());
            assertEquals(1_205, events.countByBucket("legacy-bucket"));
            assertEquals("legacy-1204", events.queryAllEvents("legacy-bucket").get(1_204).data().get("value"));
            assertEquals(1, events.countByBucket("new-bucket"));
        }

        assertTrue(Files.exists(dataDir.resolve("buckets.db")), "legacy metadata must remain as backup");
        assertTrue(Files.exists(dataDir.resolve("legacy-bucket.db")), "legacy events must remain as backup");
        assertFalse(Files.exists(dataDir.resolve("aw.db.migrating")));
        try (var files = Files.list(dataDir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("aw.db.pre-legacy-migration-")));
        }
    }

    @Test
    void resumesFromTheLastCommittedMigrationBatch(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("aw-data");
        createLegacyLayout(dataDir, "resume-bucket", 1_205);
        int[] committedBatches = {0};

        assertThrows(PlannedInterruption.class, () -> LegacyDatabaseMigrator.migrate(dataDir, () -> {
            if (++committedBatches[0] == 1) {
                throw new PlannedInterruption();
            }
        }));
        assertTrue(Files.exists(dataDir.resolve("aw.db.migrating")));

        try (Database db = new Database(dataDir)) {
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            assertEquals(1_205, events.countByBucket("resume-bucket"));
        }
        assertFalse(Files.exists(dataDir.resolve("aw.db.migrating")));
    }

    @Test
    void rebuildsACompletionMarkedWorkfileWhenItsCountsAreDamaged(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("aw-data");
        createLegacyLayout(dataDir, "recovery-bucket", 25);
        try (Database ignored = new Database(dataDir)) {
            // Produce a fully validated migrated database first.
        }

        Path destination = dataDir.resolve("aw.db");
        Path working = dataDir.resolve("aw.db.migrating");
        Files.copy(destination, working, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(destination);
        try (Connection corrupt = DriverManager.getConnection("jdbc:sqlite:" + working.toAbsolutePath());
             Statement statement = corrupt.createStatement()) {
            statement.executeUpdate("DELETE FROM events WHERE id = (SELECT MAX(id) FROM events)");
        }

        try (Database db = new Database(dataDir)) {
            assertEquals(25, new EventStore(db, PulseTimeConfig.DEFAULT).countByBucket("recovery-bucket"));
        }
    }

    @Test
    void rebuildsACompletionMarkedWorkfileWhenBucketMetadataIsMissing(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("aw-data");
        createLegacyLayout(dataDir, "events-bucket", 2);
        appendLegacyBucketMetadata(dataDir.resolve("buckets.db"), "empty-bucket");
        try (Database ignored = new Database(dataDir)) {
            // Produce a complete workfile with a zero-event bucket.
        }

        Path destination = dataDir.resolve("aw.db");
        Path working = dataDir.resolve("aw.db.migrating");
        Files.copy(destination, working, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(destination);
        try (Connection corrupt = DriverManager.getConnection("jdbc:sqlite:" + working.toAbsolutePath());
             PreparedStatement statement = corrupt.prepareStatement("DELETE FROM buckets WHERE id = ?")) {
            statement.setString(1, "empty-bucket");
            statement.executeUpdate();
        }

        try (Database db = new Database(dataDir)) {
            assertTrue(new BucketStore(db).get("empty-bucket").isPresent());
        }
    }

    @Test
    void restartsMigrationIfALegacySourceChangesDuringCopy(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("aw-data");
        createLegacyLayout(dataDir, "changing-bucket", 1_205);
        boolean[] changed = {false};

        assertThrows(Exception.class, () -> LegacyDatabaseMigrator.migrate(dataDir, () -> {
            if (!changed[0]) {
                changed[0] = true;
                try {
                    appendLegacyEvent(dataDir.resolve("changing-bucket.db"), 1_205);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            }
        }));

        try (Database db = new Database(dataDir)) {
            assertEquals(1_206, new EventStore(db, PulseTimeConfig.DEFAULT).countByBucket("changing-bucket"));
        }
    }

    private static void createLegacyLayout(Path dataDir, String bucketId, int eventCount) throws Exception {
        Files.createDirectories(dataDir);
        Class.forName("org.sqlite.JDBC");
        try (Connection metadata = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("buckets.db").toAbsolutePath());
             Statement statement = metadata.createStatement()) {
            statement.execute("CREATE TABLE buckets (id TEXT PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, "
                    + "client TEXT NOT NULL, hostname TEXT NOT NULL, created TEXT NOT NULL, last_updated TEXT NOT NULL)");
            statement.execute("INSERT INTO buckets VALUES ('" + bucketId
                    + "', 'Legacy', 'test', 'legacy-client', 'legacy-host', "
                    + "'2025-01-01T00:00:00Z', '2025-01-02T00:00:00Z')");
        }
        try (Connection events = DriverManager.getConnection(
                "jdbc:sqlite:" + dataDir.resolve(bucketId + ".db").toAbsolutePath());
             Statement statement = events.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT NOT NULL, "
                    + "duration REAL NOT NULL DEFAULT 0, datastr TEXT NOT NULL DEFAULT '{}')");
            events.setAutoCommit(false);
            try (PreparedStatement insert = events.prepareStatement(
                    "INSERT INTO events(timestamp, duration, datastr) VALUES (?, ?, ?)")) {
                for (int i = 0; i < eventCount; i++) {
                    insert.setString(1, Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i).toString());
                    insert.setDouble(2, i / 10.0);
                    insert.setString(3, "{\"value\":\"legacy-" + i + "\"}");
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            events.commit();
        }
    }

    private static void appendLegacyEvent(Path eventDb, int index) throws Exception {
        try (Connection events = DriverManager.getConnection("jdbc:sqlite:" + eventDb.toAbsolutePath());
             PreparedStatement insert = events.prepareStatement(
                     "INSERT INTO events(timestamp, duration, datastr) VALUES (?, ?, ?)")) {
            insert.setString(1, Instant.parse("2025-01-01T00:00:00Z").plusSeconds(index).toString());
            insert.setDouble(2, index / 10.0);
            insert.setString(3, "{\"value\":\"legacy-" + index + "\"}");
            insert.executeUpdate();
        }
    }

    private static void appendLegacyBucketMetadata(Path metadataDb, String bucketId) throws Exception {
        try (Connection metadata = DriverManager.getConnection("jdbc:sqlite:" + metadataDb.toAbsolutePath());
             PreparedStatement insert = metadata.prepareStatement(
                     "INSERT INTO buckets VALUES (?, 'Empty', 'test', 'legacy-client', 'legacy-host', "
                             + "'2025-01-01T00:00:00Z', '2025-01-02T00:00:00Z')")) {
            insert.setString(1, bucketId);
            insert.executeUpdate();
        }
    }

    private static final class PlannedInterruption extends RuntimeException {
    }
}
