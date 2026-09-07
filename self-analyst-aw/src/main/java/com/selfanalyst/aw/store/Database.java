package com.selfanalyst.aw.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);
    private static final String SAFE_BUCKET_ID = "[A-Za-z0-9._-]+";
    static final String APP_SCHEMA_MIGRATION_ID = "events-app-v1";
    static final String APP_FROM_DATASTR_SQL = "CASE WHEN json_valid(datastr) THEN "
            + "CASE WHEN json_type(datastr, '$.app') = 'text' "
            + "THEN json_extract(datastr, '$.app') ELSE '' END ELSE '' END";
    private static final long SCHEMA_LOCK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final int SQLITE_BUSY_TIMEOUT_MS = 250;
    private static final long LOCK_RETRY_MS = 100;

    public static final String PROJECTION_FILENAME = "events.db";

    private final Path dataDir;
    private final Path dbPath;
    private final ConcurrentHashMap<String, Connection> bucketConns = new ConcurrentHashMap<>();
    private final Connection metaConn;

    public Database(Path dataDir) {
        this.dataDir = dataDir;
        this.dbPath = dataDir.resolve(PROJECTION_FILENAME);
        try {
            Files.createDirectories(dataDir);
            Class.forName("org.sqlite.JDBC");
            LegacyDatabaseMigrator.migrate(dataDir);
            metaConn = openConnection(dbPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database at " + dataDir, e);
        }
    }

    private Connection openConnection(Path dbPath) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try {
            prepareConnection(c, true);
            return c;
        } catch (SQLException error) {
            try {
                c.close();
            } catch (SQLException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    static void prepareConnection(Connection connection, boolean createIndexes) throws SQLException {
        long deadlineNanos = System.nanoTime() + SCHEMA_LOCK_TIMEOUT_NANOS;
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + SQLITE_BUSY_TIMEOUT_MS);
            statement.execute("PRAGMA foreign_keys=ON");
        }
        enableWal(connection, deadlineNanos);
        initializeSchema(connection, createIndexes, deadlineNanos);
    }

    private static void enableWal(Connection connection, long deadlineNanos) throws SQLException {
        while (true) {
            try (Statement statement = connection.createStatement()) {
                try (ResultSet mode = statement.executeQuery("PRAGMA journal_mode")) {
                    if (mode.next() && "wal".equalsIgnoreCase(mode.getString(1))) {
                        return;
                    }
                }
                try (ResultSet mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                    if (mode.next() && "wal".equalsIgnoreCase(mode.getString(1))) {
                        return;
                    }
                }
                throw new SQLException("SQLite refused to enable WAL mode");
            } catch (SQLException error) {
                waitForDatabaseLock(error, deadlineNanos, "enable SQLite WAL mode");
            }
        }
    }

    private static boolean isDatabaseBusy(SQLException error) {
        String message = error.getMessage();
        return error.getErrorCode() == 5
                || error.getErrorCode() == 517
                || (message != null && (message.contains("SQLITE_BUSY")
                    || message.toLowerCase().contains("database is locked")));
    }

    private static void initializeSchema(
            Connection connection, boolean createIndexes, long deadlineNanos) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new SQLException("Schema initialization requires an autocommit connection");
        }
        while (true) {
            try {
                initializeSchemaOnce(connection, createIndexes);
                return;
            } catch (SQLException error) {
                waitForDatabaseLock(error, deadlineNanos, "initialize SQLite schema");
            }
        }
    }

    private static void initializeSchemaOnce(Connection connection, boolean createIndexes)
            throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("BEGIN IMMEDIATE");
            try {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS buckets (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        client TEXT NOT NULL,
                        hostname TEXT NOT NULL,
                        created TEXT NOT NULL,
                        last_updated TEXT NOT NULL
                    )
                    """);
                stmt.execute("CREATE TABLE IF NOT EXISTS events (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "bucket_id TEXT NOT NULL," +
                        "timestamp TEXT NOT NULL," +
                        "duration REAL NOT NULL DEFAULT 0," +
                        "datastr TEXT NOT NULL DEFAULT '{}'," +
                        "app TEXT NOT NULL DEFAULT ''" +
                        ")");
                stmt.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                        + "id TEXT PRIMARY KEY, completed_at TEXT NOT NULL)");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS raw_projection_sources (
                        raw_event_id TEXT PRIMARY KEY,
                        bucket_id TEXT NOT NULL,
                        projection_event_id INTEGER NOT NULL,
                        projector_version TEXT NOT NULL,
                        projected_at TEXT NOT NULL
                    )
                    """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS projection_event_coverage (
                        projection_event_id INTEGER PRIMARY KEY,
                        bucket_id TEXT NOT NULL,
                        first_raw_event_id TEXT NOT NULL,
                        last_raw_event_id TEXT NOT NULL,
                        raw_event_count INTEGER NOT NULL CHECK (raw_event_count > 0),
                        projector_version TEXT NOT NULL
                    )
                    """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS raw_projection_checkpoints (
                        partition_month TEXT PRIMARY KEY,
                        received_at TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        projector_version TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_raw_projection_sources_event
                    ON raw_projection_sources(bucket_id, projection_event_id)
                    """);
                migrateEventAppSchema(connection);
                if (createIndexes) {
                    createEventIndexes(stmt);
                }
                stmt.execute("COMMIT");
            } catch (SQLException error) {
                try {
                    stmt.execute("ROLLBACK");
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        }
    }

    private static void waitForDatabaseLock(
            SQLException error, long deadlineNanos, String operation) throws SQLException {
        if (!isDatabaseBusy(error)) {
            throw error;
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new SQLException("Timed out waiting to " + operation, error);
        }
        long sleepMillis = Math.min(
                LOCK_RETRY_MS,
                Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            SQLException failure = new SQLException(
                    "Interrupted while waiting to " + operation, interrupted);
            failure.addSuppressed(error);
            throw failure;
        }
    }

    private static void migrateEventAppSchema(Connection connection) throws SQLException {
        if (schemaMigrationExists(connection, APP_SCHEMA_MIGRATION_ID)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            if (!eventColumnExists(connection, "app")) {
                statement.execute("ALTER TABLE events ADD COLUMN app TEXT NOT NULL DEFAULT ''");
            }
            statement.executeUpdate("UPDATE events SET app = " + APP_FROM_DATASTR_SQL);
            try (PreparedStatement migration = connection.prepareStatement(
                    "INSERT INTO schema_migrations(id, completed_at) VALUES (?, datetime('now'))")) {
                migration.setString(1, APP_SCHEMA_MIGRATION_ID);
                migration.executeUpdate();
            }
        }
    }

    static void createEventIndexes(Statement statement) throws SQLException {
        statement.execute("CREATE INDEX IF NOT EXISTS idx_events_bucket_timestamp "
                + "ON events(bucket_id, timestamp)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_events_app_timestamp "
                + "ON events(app, timestamp)");
    }

    private static boolean eventColumnExists(Connection connection, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(events)")) {
            while (columns.next()) {
                if (column.equalsIgnoreCase(columns.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean schemaMigrationExists(Connection connection, String migrationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE id = ?")) {
            statement.setString(1, migrationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    static boolean derivedAppsAreValid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet invalid = statement.executeQuery(
                     "SELECT 1 FROM events WHERE app <> " + APP_FROM_DATASTR_SQL + " LIMIT 1")) {
            return !invalid.next();
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    public Connection metaConnection() {
        return metaConn;
    }

    public Connection bucketConnection(String bucketId) {
        validateBucketId(bucketId);
        return bucketConns.computeIfAbsent(bucketId, id -> {
            try {
                boolean existed = Files.exists(dbPath);
                Connection c = openConnection(dbPath);
                if (!existed) {
                    log.info("Created {}", dbPath.getFileName());
                }
                return c;
            } catch (Exception e) {
                throw new RuntimeException("Failed to open bucket db: " + id, e);
            }
        });
    }

    static void validateBucketId(String bucketId) {
        if (bucketId == null || bucketId.isBlank() || !bucketId.matches(SAFE_BUCKET_ID)) {
            throw new IllegalArgumentException("Invalid bucket id");
        }
    }

    public void closeBucket(String bucketId) {
        Connection c = bucketConns.remove(bucketId);
        if (c != null) {
            try { c.close(); } catch (SQLException ignored) {}
        }
    }

    @Override
    public void close() throws Exception {
        for (Connection c : bucketConns.values()) {
            try { c.close(); } catch (SQLException ignored) {}
        }
        bucketConns.clear();
        if (metaConn != null && !metaConn.isClosed()) {
            metaConn.close();
        }
    }
}
