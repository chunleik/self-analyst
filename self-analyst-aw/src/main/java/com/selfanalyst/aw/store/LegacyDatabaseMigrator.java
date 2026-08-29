package com.selfanalyst.aw.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class LegacyDatabaseMigrator {
    static final int BATCH_SIZE = 1_000;
    private static final Logger log = LoggerFactory.getLogger(LegacyDatabaseMigrator.class);
    private static final String MIGRATION_ID = "legacy-layout-v1";

    private LegacyDatabaseMigrator() {
    }

    static void migrate(Path dataDir) throws Exception {
        migrate(dataDir, () -> { });
    }

    static void migrate(Path dataDir, Runnable batchCommitted) throws Exception {
        Path legacyMetadata = dataDir.resolve("buckets.db");
        Path destination = dataDir.resolve("aw.db");
        Path working = dataDir.resolve("aw.db.migrating");
        if (!Files.isRegularFile(legacyMetadata) || isMigrationComplete(destination)) {
            return;
        }
        List<LegacyBucket> legacyBuckets = readLegacyBuckets(legacyMetadata);
        if (!Files.exists(destination) && isMigrationComplete(working)) {
            Path backup = latestUnifiedBackup(dataDir);
            Path unifiedSource = backup != null ? backup : destination;
            normalizeSourceFiles(unifiedSource, legacyMetadata, legacyBuckets, dataDir);
            String expectedManifest = sourceManifest(unifiedSource, legacyMetadata, legacyBuckets, dataDir);
            try {
                validateCompletedWorkfile(working, expectedManifest);
                moveReplacing(working, destination);
                return;
            } catch (Exception invalidWorkfile) {
                log.warn("Completed migration workfile failed recovery validation; rebuilding it", invalidWorkfile);
                deleteDatabaseFiles(working);
                if (backup != null) {
                    moveReplacing(backup, destination);
                }
            }
        }

        if (legacyBuckets.isEmpty()) {
            return;
        }
        normalizeSourceFiles(destination, legacyMetadata, legacyBuckets, dataDir);
        String manifest = sourceManifest(destination, legacyMetadata, legacyBuckets, dataDir);
        if (Files.exists(working) && !manifest.equals(readManifest(working))) {
            log.warn("Legacy migration sources changed; rebuilding the temporary database");
            deleteDatabaseFiles(working);
        }
        if (!Files.exists(working)) {
            ensureFreeSpace(dataDir, destination, legacyMetadata, legacyBuckets);
            try (Connection target = open(working)) {
                createTargetSchema(target);
                try (PreparedStatement statement = target.prepareStatement(
                        "INSERT INTO migration_state(id, manifest) VALUES (1, ?)")) {
                    statement.setString(1, manifest);
                    statement.executeUpdate();
                }
            }
        }

        log.info("Migrating legacy ActivityWatch data in batches of {}", BATCH_SIZE);
        try (Connection target = open(working)) {
            createTargetSchema(target);
            if (Files.isRegularFile(destination)) {
                try (Connection current = open(destination)) {
                    beginReadSnapshot(current);
                    copyBuckets(current, target, false);
                    for (String bucketId : unifiedBucketIds(current)) {
                        copyEvents(current, target, "unified", bucketId, true, batchCommitted);
                    }
                }
            }
            try (Connection metadata = open(legacyMetadata)) {
                beginReadSnapshot(metadata);
                copyBuckets(metadata, target, true);
            }
            for (LegacyBucket bucket : legacyBuckets) {
                Path sourcePath = dataDir.resolve(bucket.id() + ".db");
                if (Files.isRegularFile(sourcePath)) {
                    try (Connection source = open(sourcePath)) {
                        beginReadSnapshot(source);
                        copyEvents(source, target, "legacy", bucket.id(), false, batchCommitted);
                    }
                }
            }
            validateProgress(target);
            validateTargetCounts(target);
            String currentManifest = sourceManifest(destination, legacyMetadata, legacyBuckets, dataDir);
            if (!manifest.equals(currentManifest)) {
                throw new SQLException("Migration source files changed while data was being copied");
            }
            try (Statement statement = target.createStatement()) {
                Database.createEventIndexes(statement);
                try (ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
                    if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                        throw new SQLException("Migrated database failed SQLite integrity_check");
                    }
                }
                statement.execute("INSERT OR REPLACE INTO schema_migrations(id, completed_at) "
                        + "VALUES ('" + MIGRATION_ID + "', datetime('now'))");
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                statement.execute("PRAGMA journal_mode=DELETE");
            }
        }

        swapIntoPlace(destination, working);
        log.info("Legacy ActivityWatch migration completed; source databases were retained");
    }

    private static void createTargetSchema(Connection connection) throws SQLException {
        Database.prepareConnection(connection, false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS migration_state (id INTEGER PRIMARY KEY CHECK(id = 1), "
                    + "manifest TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS migration_progress (source_kind TEXT NOT NULL, "
                    + "bucket_id TEXT NOT NULL, last_source_id INTEGER NOT NULL DEFAULT 0, copied_count INTEGER NOT NULL DEFAULT 0, "
                    + "expected_count INTEGER NOT NULL, PRIMARY KEY(source_kind, bucket_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS migration_expected_buckets (bucket_id TEXT PRIMARY KEY)");
        }
    }

    private static void copyBuckets(Connection source, Connection target, boolean ignoreExisting) throws SQLException {
        String insertSql = (ignoreExisting ? "INSERT OR IGNORE" : "INSERT OR REPLACE")
                + " INTO buckets(id, name, type, client, hostname, created, last_updated) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Statement query = source.createStatement();
             ResultSet rows = query.executeQuery("SELECT id, name, type, client, hostname, created, last_updated FROM buckets");
             PreparedStatement insert = target.prepareStatement(insertSql);
             PreparedStatement expectedBucket = target.prepareStatement(
                     "INSERT OR IGNORE INTO migration_expected_buckets(bucket_id) VALUES (?)")) {
            while (rows.next()) {
                for (int column = 1; column <= 7; column++) {
                    insert.setString(column, rows.getString(column));
                }
                insert.addBatch();
                expectedBucket.setString(1, rows.getString(1));
                expectedBucket.addBatch();
            }
            insert.executeBatch();
            expectedBucket.executeBatch();
        }
    }

    private static void copyEvents(Connection source, Connection target, String sourceKind,
                                   String bucketId, boolean unifiedSource, Runnable batchCommitted) throws SQLException {
        long expected = countEvents(source, bucketId, unifiedSource);
        try (PreparedStatement initialize = target.prepareStatement(
                "INSERT OR IGNORE INTO migration_progress(source_kind, bucket_id, expected_count) VALUES (?, ?, ?)")) {
            initialize.setString(1, sourceKind);
            initialize.setString(2, bucketId);
            initialize.setLong(3, expected);
            initialize.executeUpdate();
        }
        long lastId;
        long copied;
        try (PreparedStatement progress = target.prepareStatement(
                "SELECT last_source_id, copied_count, expected_count FROM migration_progress "
                        + "WHERE source_kind = ? AND bucket_id = ?")) {
            progress.setString(1, sourceKind);
            progress.setString(2, bucketId);
            try (ResultSet row = progress.executeQuery()) {
                if (!row.next() || row.getLong(3) != expected) {
                    throw new SQLException("Migration source count changed for " + sourceKind + ":" + bucketId);
                }
                lastId = row.getLong(1);
                copied = row.getLong(2);
            }
        }

        String querySql = unifiedSource
                ? "SELECT id, timestamp, duration, datastr, " + Database.APP_FROM_DATASTR_SQL
                        + " FROM events WHERE bucket_id = ? AND id > ? ORDER BY id LIMIT ?"
                : "SELECT id, timestamp, duration, datastr, " + Database.APP_FROM_DATASTR_SQL
                        + " FROM events WHERE id > ? ORDER BY id LIMIT ?";
        while (copied < expected) {
            long batchLastId = lastId;
            int batchCount = 0;
            target.setAutoCommit(false);
            try (PreparedStatement query = source.prepareStatement(querySql);
                 PreparedStatement insert = target.prepareStatement(
                         "INSERT INTO events(bucket_id, timestamp, duration, datastr, app) "
                                 + "VALUES (?, ?, ?, ?, ?)");
                 PreparedStatement update = target.prepareStatement(
                         "UPDATE migration_progress SET last_source_id = ?, copied_count = ? "
                                 + "WHERE source_kind = ? AND bucket_id = ?")) {
                int parameter = 1;
                if (unifiedSource) {
                    query.setString(parameter++, bucketId);
                }
                query.setLong(parameter++, lastId);
                query.setInt(parameter, BATCH_SIZE);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        batchLastId = rows.getLong(1);
                        insert.setString(1, bucketId);
                        insert.setString(2, rows.getString(2));
                        insert.setDouble(3, rows.getDouble(3));
                        insert.setString(4, rows.getString(4));
                        insert.setString(5, rows.getString(5));
                        insert.addBatch();
                        batchCount++;
                    }
                }
                if (batchCount == 0) {
                    throw new SQLException("Migration source ended early for " + sourceKind + ":" + bucketId);
                }
                insert.executeBatch();
                copied += batchCount;
                update.setLong(1, batchLastId);
                update.setLong(2, copied);
                update.setString(3, sourceKind);
                update.setString(4, bucketId);
                update.executeUpdate();
                target.commit();
                lastId = batchLastId;
                log.info("Migrated {} / {} events from {}:{}", copied, expected, sourceKind, bucketId);
                batchCommitted.run();
            } catch (SQLException error) {
                target.rollback();
                throw error;
            } finally {
                target.setAutoCommit(true);
            }
        }
    }

    private static long countEvents(Connection source, String bucketId, boolean unifiedSource) throws SQLException {
        String sql = unifiedSource ? "SELECT COUNT(*) FROM events WHERE bucket_id = ?" : "SELECT COUNT(*) FROM events";
        try (PreparedStatement statement = source.prepareStatement(sql)) {
            if (unifiedSource) {
                statement.setString(1, bucketId);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private static List<String> unifiedBucketIds(Connection connection) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT DISTINCT bucket_id FROM events ORDER BY bucket_id")) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }

    private static void validateProgress(Connection target) throws SQLException {
        try (Statement statement = target.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT source_kind, bucket_id, copied_count, expected_count FROM migration_progress "
                             + "WHERE copied_count <> expected_count")) {
            if (rows.next()) {
                throw new SQLException("Incomplete migration for " + rows.getString(1) + ":" + rows.getString(2));
            }
        }
    }

    private static void validateTargetCounts(Connection target) throws SQLException {
        Map<String, Long> expectedEvents = new TreeMap<>();
        Map<String, Long> actualEvents = new TreeMap<>();
        try (Statement statement = target.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT bucket_id, SUM(expected_count) FROM migration_progress GROUP BY bucket_id")) {
            while (result.next()) {
                expectedEvents.put(result.getString(1), result.getLong(2));
            }
        }
        try (Statement statement = target.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT bucket_id, COUNT(*) FROM events GROUP BY bucket_id")) {
            while (result.next()) {
                actualEvents.put(result.getString(1), result.getLong(2));
            }
        }
        if (!actualEvents.equals(expectedEvents)) {
            throw new SQLException("Migrated per-bucket event counts do not match migration progress");
        }

        Set<String> expectedBuckets = new TreeSet<>();
        Set<String> actualBuckets = new TreeSet<>();
        try (Statement statement = target.createStatement();
             ResultSet result = statement.executeQuery("SELECT bucket_id FROM migration_expected_buckets")) {
            while (result.next()) {
                expectedBuckets.add(result.getString(1));
            }
        }
        try (Statement statement = target.createStatement();
             ResultSet result = statement.executeQuery("SELECT id FROM buckets")) {
            while (result.next()) {
                actualBuckets.add(result.getString(1));
            }
        }
        if (!actualBuckets.equals(expectedBuckets)) {
            throw new SQLException("Migrated bucket metadata set does not match the source bucket set");
        }
    }

    private static void validateCompletedWorkfile(Path working, String expectedManifest) throws SQLException {
        try (Connection target = open(working)) {
            createTargetSchema(target);
            String storedManifest;
            try (Statement statement = target.createStatement();
                 ResultSet result = statement.executeQuery("SELECT manifest FROM migration_state WHERE id = 1")) {
                storedManifest = result.next() ? result.getString(1) : null;
            }
            if (!expectedManifest.equals(storedManifest)) {
                throw new SQLException("Completed migration source manifest no longer matches");
            }
            validateProgress(target);
            validateTargetCounts(target);
            if (!Database.derivedAppsAreValid(target)) {
                throw new SQLException("Completed migration workfile has inconsistent derived app values");
            }
            try (Statement statement = target.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
                if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                    throw new SQLException("Completed migration workfile failed SQLite integrity_check");
                }
            }
        }
    }

    private static void beginReadSnapshot(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement();
             ResultSet ignored = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master")) {
            ignored.next();
        }
    }

    private static List<LegacyBucket> readLegacyBuckets(Path metadataPath) throws SQLException {
        List<LegacyBucket> result = new ArrayList<>();
        try (Connection connection = open(metadataPath);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id FROM buckets ORDER BY id")) {
            while (rows.next()) {
                String id = rows.getString(1);
                if (id != null && id.matches("[A-Za-z0-9._-]+")) {
                    result.add(new LegacyBucket(id));
                } else {
                    throw new SQLException("Unsafe legacy bucket id: " + id);
                }
            }
        }
        return result;
    }

    private static String sourceManifest(Path destination, Path metadata, List<LegacyBucket> buckets,
                                         Path dataDir) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        addDatabaseFingerprint(digest, "unified", destination);
        addDatabaseFingerprint(digest, "metadata", metadata);
        for (LegacyBucket bucket : buckets) {
            addDatabaseFingerprint(digest, "legacy:" + bucket.id(), dataDir.resolve(bucket.id() + ".db"));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void addDatabaseFingerprint(MessageDigest digest, String label, Path path) throws IOException {
        addFileFingerprint(digest, label, path);
        addFileFingerprint(digest, label + ":wal", path.resolveSibling(path.getFileName() + "-wal"));
    }

    private static void normalizeSourceFiles(Path unified, Path metadata, List<LegacyBucket> buckets,
                                             Path dataDir) throws SQLException {
        checkpointSource(unified);
        checkpointSource(metadata);
        for (LegacyBucket bucket : buckets) {
            checkpointSource(dataDir.resolve(bucket.id() + ".db"));
        }
    }

    private static void checkpointSource(Path path) throws SQLException {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (Connection connection = open(path);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (result.next() && result.getInt(1) != 0) {
                throw new SQLException("Migration source database is busy: " + path);
            }
        }
    }

    private static void addFileFingerprint(MessageDigest digest, String label, Path path) throws IOException {
        String value = label + "|" + Files.exists(path) + "|"
                + (Files.exists(path) ? Files.size(path) : 0) + "|"
                + (Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0) + "\n";
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readManifest(Path path) {
        try (Connection connection = open(path);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT manifest FROM migration_state WHERE id = 1")) {
            return result.next() ? result.getString(1) : null;
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static boolean isMigrationComplete(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try (Connection connection = open(path);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM schema_migrations WHERE id = ?")) {
            statement.setString(1, MIGRATION_ID);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

    private static void ensureFreeSpace(Path dataDir, Path destination, Path metadata,
                                        List<LegacyBucket> buckets) throws IOException {
        long sourceBytes = size(destination) + size(metadata);
        for (LegacyBucket bucket : buckets) {
            sourceBytes = Math.addExact(sourceBytes, size(dataDir.resolve(bucket.id() + ".db")));
        }
        long reserve = Math.max(64L * 1024 * 1024, sourceBytes / 5);
        long required = Math.addExact(sourceBytes, reserve);
        long usable = Files.getFileStore(dataDir).getUsableSpace();
        if (usable < required) {
            throw new IOException("Insufficient disk space for migration: need " + required + ", available " + usable);
        }
    }

    private static long size(Path path) throws IOException {
        return Files.isRegularFile(path) ? Files.size(path) : 0;
    }

    private static void swapIntoPlace(Path destination, Path working) throws Exception {
        Path backup = null;
        if (Files.exists(destination)) {
            checkpoint(destination);
            backup = destination.resolveSibling("aw.db.pre-legacy-migration-" + System.currentTimeMillis());
            moveReplacing(destination, backup);
        }
        try {
            moveReplacing(working, destination);
            deleteSidecars(working);
        } catch (Exception error) {
            if (backup != null && !Files.exists(destination)) {
                moveReplacing(backup, destination);
            }
            throw error;
        }
    }

    private static Path latestUnifiedBackup(Path dataDir) throws IOException {
        try (var files = Files.list(dataDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("aw.db.pre-legacy-migration-"))
                    .max((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()))
                    .orElse(null);
        }
    }

    private static void checkpoint(Path path) throws SQLException, IOException {
        try (Connection connection = open(path); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            statement.execute("PRAGMA journal_mode=DELETE");
        }
        deleteSidecars(path);
    }

    private static Connection open(Path path) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteDatabaseFiles(Path path) throws IOException {
        Files.deleteIfExists(path);
        deleteSidecars(path);
    }

    private static void deleteSidecars(Path path) throws IOException {
        Files.deleteIfExists(path.resolveSibling(path.getFileName() + "-wal"));
        Files.deleteIfExists(path.resolveSibling(path.getFileName() + "-shm"));
    }

    private record LegacyBucket(String id) {
    }
}
