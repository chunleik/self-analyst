package com.selfanalyst.aw.store;

import com.selfanalyst.aw.model.Event;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
            events.insertEvent("bucket-non-string", new Event(
                    Instant.parse("2026-07-06T03:00:00Z"), 3.0, Map.of("app", true)));
            events.insertEvent("bucket-json-string", new Event(
                    Instant.parse("2026-07-06T04:00:00Z"), 4.0, Map.of("app", 'Z')));

            assertEquals(1, events.countByBucket("bucket-a"));
            assertEquals("A", events.queryAllEvents("bucket-a").get(0).data().get("app"));
            assertEquals("A", storedApp(db.metaConnection(), "bucket-a"));
            assertEquals(1, events.countByBucket("bucket-b"));
            assertEquals("B", events.queryAllEvents("bucket-b").get(0).data().get("app"));
            assertEquals("B", storedApp(db.metaConnection(), "bucket-b"));
            assertEquals("", storedApp(db.metaConnection(), "bucket-non-string"));
            assertEquals("Z", storedApp(db.metaConnection(), "bucket-json-string"));
            assertTrue(indexExists(db.metaConnection(), "idx_events_app_timestamp"));
            assertEquals(List.of("app", "timestamp"),
                    indexColumns(db.metaConnection(), "idx_events_app_timestamp"));
        }

        assertTrue(Files.exists(dataDir.resolve("aw.db")));
        assertFalse(Files.exists(dataDir.resolve("buckets.db")));
        assertFalse(Files.exists(dataDir.resolve("bucket-a.db")));
        assertFalse(Files.exists(dataDir.resolve("bucket-b.db")));
    }

    @Test
    void addsAndBackfillsAppColumnForExistingUnifiedDatabase(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("aw-data");
        Files.createDirectories(dataDir);
        Class.forName("org.sqlite.JDBC");
        try (Connection old = DriverManager.getConnection(
                "jdbc:sqlite:" + dataDir.resolve("aw.db").toAbsolutePath());
             Statement statement = old.createStatement()) {
            statement.execute("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "bucket_id TEXT NOT NULL, timestamp TEXT NOT NULL, "
                    + "duration REAL NOT NULL DEFAULT 0, datastr TEXT NOT NULL DEFAULT '{}')");
            statement.execute("INSERT INTO events(bucket_id, timestamp, duration, datastr) VALUES "
                    + "('content', '2026-08-29T00:00:00Z', 1, "
                    + "'{\"app\":\"WeChat.exe\",\"title\":\"聊天\"}'), "
                    + "('no-app', '2026-08-29T00:00:01Z', 1, '{\"status\":\"afk\"}'), "
                    + "('boolean-app', '2026-08-29T00:00:02Z', 1, '{\"app\":true}'), "
                    + "('number-app', '2026-08-29T00:00:03Z', 1, '{\"app\":42}'), "
                    + "('object-app', '2026-08-29T00:00:04Z', 1, '{\"app\":{\"name\":\"x\"}}'), "
                    + "('invalid-json', '2026-08-29T00:00:05Z', 1, 'not-json')");
        }

        try (Database db = new Database(dataDir)) {
            assertEquals("WeChat.exe", storedApp(db.metaConnection(), "content"));
            assertEquals("", storedApp(db.metaConnection(), "no-app"));
            assertEquals("", storedApp(db.metaConnection(), "boolean-app"));
            assertEquals("", storedApp(db.metaConnection(), "number-app"));
            assertEquals("", storedApp(db.metaConnection(), "object-app"));
            assertEquals("", storedApp(db.metaConnection(), "invalid-json"));
            assertTrue(indexExists(db.metaConnection(), "idx_events_app_timestamp"));
        }
    }

    @RepeatedTest(5)
    void serializesConcurrentSchemaUpgrade(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("aw-data");
        createOldUnifiedDatabase(dataDir, 5_000);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> openDatabaseTogether(dataDir, ready, start));
            Future<?> second = executor.submit(() -> openDatabaseTogether(dataDir, ready, start));
            ready.await();
            start.countDown();
            first.get();
            second.get();
        }

        try (Database db = new Database(dataDir)) {
            assertEquals(5_000, countStoredApps(db.metaConnection(), "ConcurrentApp.exe"));
        }
    }

    @Test
    void waitsForSchemaLockBeyondSqliteBusyTimeout(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("aw-data");
        createOldUnifiedDatabase(dataDir, 10);
        try (Connection holder = DriverManager.getConnection(
                "jdbc:sqlite:" + dataDir.resolve("aw.db").toAbsolutePath());
             Statement lock = holder.createStatement();
             var executor = Executors.newSingleThreadExecutor()) {
            lock.execute("PRAGMA journal_mode=WAL");
            lock.execute("BEGIN IMMEDIATE");
            Future<?> opening = executor.submit(() -> {
                try (Database ignored = new Database(dataDir)) {
                    // Opening must wait for the cross-instance schema lock.
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            Thread.sleep(6_000);
            assertFalse(opening.isDone(), "schema open must still be waiting after five seconds");
            lock.execute("COMMIT");
            opening.get();
        }

        try (Database db = new Database(dataDir)) {
            assertEquals(10, countStoredApps(db.metaConnection(), "ConcurrentApp.exe"));
        }
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
            assertEquals("LegacyApp.exe", storedApp(db.metaConnection(), "legacy-bucket"));
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
        downgradeWorkfileToPreAppSchema(dataDir.resolve("aw.db.migrating"));

        try (Database db = new Database(dataDir)) {
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            assertEquals(1_205, events.countByBucket("resume-bucket"));
            assertEquals(1_205, countStoredApps(db.metaConnection(), "LegacyApp.exe"));
        }
        assertFalse(Files.exists(dataDir.resolve("aw.db.migrating")));
    }

    @Test
    void rebuildsACompletionMarkedWorkfileWhenDerivedAppsAreDamaged(@TempDir Path dir)
            throws Exception {
        Path dataDir = dir.resolve("aw-data");
        createLegacyLayout(dataDir, "app-recovery-bucket", 25);
        try (Database ignored = new Database(dataDir)) {
            // Produce a fully validated migrated database first.
        }

        Path destination = dataDir.resolve("aw.db");
        Path working = dataDir.resolve("aw.db.migrating");
        Files.copy(destination, working, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(destination);
        try (Connection corrupt = DriverManager.getConnection("jdbc:sqlite:" + working.toAbsolutePath());
             Statement statement = corrupt.createStatement()) {
            statement.executeUpdate("UPDATE events SET app = 'wrong-app'");
        }

        try (Database db = new Database(dataDir)) {
            assertEquals(25, countStoredApps(db.metaConnection(), "LegacyApp.exe"));
            assertEquals(0, countStoredApps(db.metaConnection(), "wrong-app"));
        }
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
                    insert.setString(3, "{\"app\":\"LegacyApp.exe\",\"value\":\"legacy-" + i + "\"}");
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
            insert.setString(3, "{\"app\":\"LegacyApp.exe\",\"value\":\"legacy-" + index + "\"}");
            insert.executeUpdate();
        }
    }

    private static void createOldUnifiedDatabase(Path dataDir, int eventCount) throws Exception {
        Files.createDirectories(dataDir);
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + dataDir.resolve("aw.db").toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "bucket_id TEXT NOT NULL, timestamp TEXT NOT NULL, "
                    + "duration REAL NOT NULL DEFAULT 0, datastr TEXT NOT NULL DEFAULT '{}')");
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO events(bucket_id, timestamp, duration, datastr) VALUES (?, ?, ?, ?)")) {
                for (int i = 0; i < eventCount; i++) {
                    insert.setString(1, "concurrent");
                    insert.setString(2, Instant.parse("2026-08-29T00:00:00Z")
                            .plusSeconds(i).toString());
                    insert.setDouble(3, 1.0);
                    insert.setString(4, "{\"app\":\"ConcurrentApp.exe\"}");
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        }
    }

    private static void openDatabaseTogether(
            Path dataDir, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await();
            try (Database ignored = new Database(dataDir)) {
                // Opening performs the schema upgrade.
            }
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static void downgradeWorkfileToPreAppSchema(Path workfile) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + workfile.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM schema_migrations WHERE id = 'events-app-v1'");
            statement.execute("ALTER TABLE events DROP COLUMN app");
        }
    }

    private static String storedApp(Connection connection, String bucketId) throws Exception {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT app FROM events WHERE bucket_id = ? ORDER BY id LIMIT 1")) {
            query.setString(1, bucketId);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static boolean indexExists(Connection connection, String indexName) throws Exception {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?")) {
            query.setString(1, indexName);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private static int countStoredApps(Connection connection, String app) throws Exception {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM events WHERE app = ?")) {
            query.setString(1, app);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static List<String> indexColumns(Connection connection, String indexName)
            throws Exception {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT name FROM pragma_index_info(?) ORDER BY seqno")) {
            query.setString(1, indexName);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    columns.add(result.getString(1));
                }
            }
        }
        return columns;
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
