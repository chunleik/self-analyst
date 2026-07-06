package com.selfanalyst.aw.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

public class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);
    private static final String SAFE_BUCKET_ID = "[A-Za-z0-9._-]+";

    private final Path dataDir;
    private final Path dbPath;
    private final ConcurrentHashMap<String, Connection> bucketConns = new ConcurrentHashMap<>();
    private final Connection metaConn;

    public Database(Path dataDir) {
        this.dataDir = dataDir;
        this.dbPath = dataDir.resolve("aw.db");
        try {
            Files.createDirectories(dataDir);
            Class.forName("org.sqlite.JDBC");
            metaConn = openConnection(dbPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database at " + dataDir, e);
        }
    }

    private Connection openConnection(Path dbPath) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement stmt = c.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA foreign_keys=ON");
            stmt.execute("PRAGMA busy_timeout=5000");
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
                    "datastr TEXT NOT NULL DEFAULT '{}'" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_events_bucket_timestamp " +
                    "ON events(bucket_id, timestamp)");
        }
        return c;
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

    private static void validateBucketId(String bucketId) {
        if (bucketId == null || bucketId.isBlank() || !bucketId.matches(SAFE_BUCKET_ID)) {
            throw new IllegalArgumentException("Invalid bucket id: " + bucketId);
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
