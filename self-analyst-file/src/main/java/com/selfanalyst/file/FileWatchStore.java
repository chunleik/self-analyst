package com.selfanalyst.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQLite store for watched-file metadata (SPEC-FILE-010).
 *
 * <p>Schema v2 is intentionally unable to store file bodies or any derivative
 * of their content. Opening a v1 database rebuilds and vacuums it so legacy
 * hashes, summaries, topics, prompts, and model fields are physically removed.
 */
public class FileWatchStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileWatchStore.class);
    private static final int SCHEMA_VERSION = 2;

    private final Runnable beforeVacuum;
    private Connection conn;

    public FileWatchStore(Path dbPath) {
        this(dbPath, () -> {});
    }

    FileWatchStore(Path dbPath, Runnable beforeVacuum) {
        this.beforeVacuum = beforeVacuum != null ? beforeVacuum : () -> {};
        try {
            if (dbPath.getParent() != null) Files.createDirectories(dbPath.getParent());
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA busy_timeout=5000");
                final int version;
                try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
                    version = rs.getInt(1);
                }
                if (version == 0) {
                    if (tableExists(stmt, "file_index")) {
                        migrateV1ToV2();
                    } else if (tableExists(stmt, "file_metadata")) {
                        finishV2Migration();
                    } else {
                        createV2Schema(stmt);
                        stmt.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                    }
                } else if (version < SCHEMA_VERSION) {
                    if (tableExists(stmt, "file_index")) {
                        migrateV1ToV2();
                    } else if (tableExists(stmt, "file_metadata")) {
                        finishV2Migration();
                    } else {
                        throw new SQLException("Legacy file metadata table is missing");
                    }
                }
                stmt.execute("PRAGMA journal_mode=WAL");
            }
        } catch (Exception e) {
            try {
                if (conn != null && !conn.isClosed()) conn.close();
            } catch (SQLException ignored) {}
            throw new RuntimeException("Failed to initialize FileWatchStore", e);
        }
    }

    private static boolean tableExists(Statement stmt, String tableName) throws SQLException {
        try (PreparedStatement ps = stmt.getConnection().prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void createV2Schema(Statement stmt) throws SQLException {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS file_metadata (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              absolute_path TEXT NOT NULL UNIQUE,
              relative_path TEXT,
              watch_root TEXT,
              extension TEXT,
              size_bytes INTEGER NOT NULL DEFAULT 0,
              file_created_at TEXT,
              last_modified TEXT,
              first_seen_at TEXT NOT NULL,
              last_collected_at TEXT,
              status TEXT NOT NULL,
              retry_count INTEGER NOT NULL DEFAULT 0,
              next_retry_at TEXT,
              last_error TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL
            )
            """);
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_metadata_status "
                + "ON file_metadata(status, updated_at)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_metadata_status_retry "
                + "ON file_metadata(status, next_retry_at)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_metadata_last_modified "
                + "ON file_metadata(last_modified)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_metadata_watch_root "
                + "ON file_metadata(watch_root)");
    }

    private void migrateV1ToV2() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException ignored) {
                // A database that never entered WAL mode has nothing to checkpoint.
            }
            stmt.execute("PRAGMA journal_mode=DELETE");
            stmt.execute("PRAGMA secure_delete=ON");
        }

        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            createV2Schema(stmt);
            stmt.executeUpdate("""
                INSERT INTO file_metadata
                  (id, absolute_path, relative_path, watch_root, extension, size_bytes,
                   file_created_at, last_modified, first_seen_at, last_collected_at,
                   status, retry_count, next_retry_at, last_error, created_at, updated_at)
                SELECT id, absolute_path, relative_path, watch_root, extension, size_bytes,
                       NULL, last_modified, first_seen_at, last_indexed_at,
                       CASE status WHEN 'INDEXED' THEN 'COLLECTED' ELSE status END,
                       retry_count, next_retry_at, last_error, created_at, updated_at
                  FROM file_index
                """);
            stmt.execute("DROP TABLE file_index");
            conn.commit();
        } catch (SQLException migrationFailure) {
            conn.rollback();
            throw migrationFailure;
        } finally {
            conn.setAutoCommit(true);
        }

        finishV2Migration();
    }

    private void finishV2Migration() throws SQLException {
        beforeVacuum.run();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("VACUUM");
            stmt.execute("PRAGMA user_version=" + SCHEMA_VERSION);
        }
    }

    public synchronized void upsertPending(String absolutePath, String relativePath,
                                           String watchRoot, String extension) {
        String now = Instant.now().toString();
        String sql = """
            INSERT INTO file_metadata
              (absolute_path, relative_path, watch_root, extension,
               first_seen_at, status, retry_count, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
            ON CONFLICT(absolute_path) DO UPDATE SET
              relative_path=excluded.relative_path,
              watch_root=excluded.watch_root,
              extension=excluded.extension,
              status='PENDING',
              next_retry_at=NULL,
              updated_at=excluded.updated_at
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            ps.setString(2, relativePath);
            ps.setString(3, watchRoot);
            ps.setString(4, lower(extension));
            ps.setString(5, now);
            ps.setString(6, now);
            ps.setString(7, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert pending file " + absolutePath, e);
        }
    }

    public synchronized void updateWatchLocation(String absolutePath, String relativePath,
                                                 String watchRoot, String extension) {
        String sql = "UPDATE file_metadata SET relative_path=?, watch_root=?, extension=?, "
                + "updated_at=? WHERE absolute_path=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, relativePath);
            ps.setString(2, watchRoot);
            ps.setString(3, lower(extension));
            ps.setString(4, Instant.now().toString());
            ps.setString(5, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update watch location " + absolutePath, e);
        }
    }

    /** Record only filesystem metadata; no file byte stream is accepted by this API. */
    public synchronized void updateCollected(String absolutePath, long sizeBytes,
                                             Instant fileCreatedAt, Instant lastModified) {
        String now = Instant.now().toString();
        String sql = """
            UPDATE file_metadata SET
              status='COLLECTED', size_bytes=?, file_created_at=?, last_modified=?,
              last_collected_at=?, retry_count=0, last_error=NULL,
              next_retry_at=NULL, updated_at=?
            WHERE absolute_path=?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sizeBytes);
            ps.setString(2, text(fileCreatedAt));
            ps.setString(3, text(lastModified));
            ps.setString(4, now);
            ps.setString(5, now);
            ps.setString(6, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update collected metadata " + absolutePath, e);
        }
    }

    public synchronized void markDeleted(String absolutePath) {
        updateStatus(absolutePath, "DELETED", null, null);
    }

    /** Mark one deleted file or every tracked descendant of a deleted directory. */
    public synchronized void markDeletedTree(String absolutePath) {
        String separator = java.nio.file.FileSystems.getDefault().getSeparator();
        String descendantPattern = escapeLike(absolutePath + separator) + "%";
        String sql = "UPDATE file_metadata SET status='DELETED', last_error=NULL, "
                + "next_retry_at=NULL, updated_at=? WHERE absolute_path=? "
                + "OR absolute_path LIKE ? ESCAPE '\\'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, absolutePath);
            ps.setString(3, descendantPattern);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark deleted tree " + absolutePath, e);
        }
    }

    public synchronized void markSkipped(String absolutePath, String reason) {
        updateStatus(absolutePath, "SKIPPED", reason, null);
    }

    public synchronized void markFailed(String absolutePath, String error, Instant nextRetryAt) {
        String sql = """
            UPDATE file_metadata SET status='FAILED', retry_count=retry_count+1,
              next_retry_at=?, last_error=?, updated_at=? WHERE absolute_path=?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, text(nextRetryAt));
            ps.setString(2, error);
            ps.setString(3, Instant.now().toString());
            ps.setString(4, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark failed " + absolutePath, e);
        }
    }

    private void updateStatus(String absolutePath, String status, String error, Instant retryAt) {
        String sql = "UPDATE file_metadata SET status=?, last_error=?, next_retry_at=?, "
                + "updated_at=? WHERE absolute_path=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, error);
            ps.setString(3, text(retryAt));
            ps.setString(4, Instant.now().toString());
            ps.setString(5, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update status for " + absolutePath, e);
        }
    }

    public synchronized FileRecord findByPath(String absolutePath) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM file_metadata WHERE absolute_path=?")) {
            ps.setString(1, absolutePath);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? map(rs) : null;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find file " + absolutePath, e);
        }
    }

    public synchronized boolean isHealthy() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1")) {
            return !conn.isClosed() && ps.executeQuery().next();
        } catch (SQLException ignored) {
            return false;
        }
    }

    public synchronized List<FileRecord> findPending(int limit) {
        return queryList("SELECT * FROM file_metadata WHERE status='PENDING' "
                + "ORDER BY updated_at ASC LIMIT ?", ps -> ps.setInt(1, limit));
    }

    public synchronized List<FileRecord> findPending(List<String> watchRoots, int limit) {
        return findByStatusAndRoots("PENDING", watchRoots, null, "updated_at ASC", limit);
    }

    public synchronized List<FileRecord> findRetryable(int limit) {
        String sql = "SELECT * FROM file_metadata WHERE status='FAILED' "
                + "AND (next_retry_at IS NULL OR next_retry_at <= ?) "
                + "ORDER BY retry_count ASC LIMIT ?";
        String now = Instant.now().toString();
        return queryList(sql, ps -> {
            ps.setString(1, now);
            ps.setInt(2, limit);
        });
    }

    public synchronized List<FileRecord> findRetryable(List<String> watchRoots, int limit) {
        return findByStatusAndRoots("FAILED", watchRoots, Instant.now().toString(),
                "retry_count ASC", limit);
    }

    public synchronized List<FileRecord> findActiveByWatchRoot(String watchRoot) {
        String sql = "SELECT * FROM file_metadata WHERE watch_root=? AND status<>'DELETED'";
        return queryList(sql, ps -> ps.setString(1, watchRoot));
    }

    public synchronized List<FileRecord> findRecentlyCollected(int limit) {
        String sql = "SELECT * FROM file_metadata WHERE status='COLLECTED' "
                + "AND last_collected_at IS NOT NULL ORDER BY last_collected_at DESC LIMIT ?";
        return queryList(sql, ps -> ps.setInt(1, Math.max(1, limit)));
    }

    public synchronized List<FileRecord> findRecentlyCollected(List<String> watchRoots, int limit) {
        if (watchRoots == null || watchRoots.isEmpty()) return List.of();
        String placeholders = String.join(",", watchRoots.stream().map(ignored -> "?").toList());
        String sql = "SELECT * FROM file_metadata WHERE status='COLLECTED' "
                + "AND last_collected_at IS NOT NULL AND watch_root IN (" + placeholders + ") "
                + "ORDER BY last_collected_at DESC LIMIT ?";
        return queryList(sql, ps -> {
            int index = 1;
            for (String root : watchRoots) ps.setString(index++, root);
            ps.setInt(index, Math.max(1, limit));
        });
    }

    public synchronized List<FileRecord> queryByTime(Instant start, Instant end,
                                                     String watchRoot, String extension, int limit) {
        return queryMetadataInternal(null, start, end, watchRoot, null, extension, limit);
    }

    /** Local path/title search. It never reads a file or calls an embedding service. */
    public synchronized List<FileRecord> queryMetadata(String query, Instant start, Instant end,
                                                       String watchRoot, String extension, int limit) {
        return queryMetadataInternal(query, start, end, watchRoot, null, extension, limit);
    }

    public synchronized List<FileRecord> queryMetadataForRoots(String query, Instant start,
                                                               Instant end, List<String> watchRoots,
                                                               String extension, int limit) {
        if (watchRoots == null || watchRoots.isEmpty()) return List.of();
        return queryMetadataInternal(query, start, end, null, watchRoots, extension, limit);
    }

    private List<FileRecord> queryMetadataInternal(String query, Instant start, Instant end,
                                                   String watchRoot, List<String> watchRoots,
                                                   String extension, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM file_metadata WHERE status='COLLECTED' AND last_modified IS NOT NULL");
        List<Object> params = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            sql.append(" AND (LOWER(relative_path) LIKE ? ESCAPE '\\' "
                    + "OR LOWER(absolute_path) LIKE ? ESCAPE '\\')");
            String pattern = "%" + escapeLike(query.toLowerCase(Locale.ROOT)) + "%";
            params.add(pattern);
            params.add(pattern);
        }
        if (start != null) {
            sql.append(" AND last_modified >= ?");
            params.add(start.toString());
        }
        if (end != null) {
            sql.append(" AND last_modified <= ?");
            params.add(end.toString());
        }
        if (watchRoot != null && !watchRoot.isBlank()) {
            sql.append(" AND watch_root = ?");
            params.add(watchRoot);
        } else if (watchRoots != null) {
            sql.append(" AND watch_root IN (")
                    .append(String.join(",", watchRoots.stream().map(ignored -> "?").toList()))
                    .append(")");
            params.addAll(watchRoots);
        }
        if (extension != null && !extension.isBlank()) {
            sql.append(" AND extension = ?");
            params.add(extension.toLowerCase(Locale.ROOT));
        }
        sql.append(" ORDER BY last_modified DESC LIMIT ?");
        params.add(Math.max(1, limit));
        return queryList(sql.toString(), ps -> {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Integer n) ps.setInt(i + 1, n);
                else ps.setString(i + 1, (String) p);
            }
        });
    }

    public synchronized Map<String, Map<String, Long>> statusCountsByWatchRoot() {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        String sql = "SELECT watch_root, status, COUNT(*) AS c FROM file_metadata "
                + "GROUP BY watch_root, status";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String root = rs.getString("watch_root");
                if (root == null) root = "";
                out.computeIfAbsent(root, ignored -> new LinkedHashMap<>())
                        .put(rs.getString("status"), rs.getLong("c"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count file statuses", e);
        }
        return out;
    }

    private List<FileRecord> findByStatusAndRoots(String status, List<String> watchRoots,
                                                  String retryBefore, String orderBy, int limit) {
        if (watchRoots == null || watchRoots.isEmpty()) return List.of();
        String placeholders = String.join(",", watchRoots.stream().map(ignored -> "?").toList());
        String retryClause = retryBefore == null ? ""
                : " AND (next_retry_at IS NULL OR next_retry_at <= ?)";
        String sql = "SELECT * FROM file_metadata WHERE status=? AND watch_root IN ("
                + placeholders + ")" + retryClause + " ORDER BY " + orderBy + " LIMIT ?";
        return queryList(sql, ps -> {
            int index = 1;
            ps.setString(index++, status);
            for (String root : watchRoots) ps.setString(index++, root);
            if (retryBefore != null) ps.setString(index++, retryBefore);
            ps.setInt(index, Math.max(1, limit));
        });
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<FileRecord> queryList(String sql, Binder binder) {
        List<FileRecord> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) results.add(map(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + sql, e);
        }
        return results;
    }

    private FileRecord map(ResultSet rs) throws SQLException {
        return new FileRecord(
                rs.getLong("id"), rs.getString("absolute_path"),
                rs.getString("relative_path"), rs.getString("watch_root"),
                rs.getString("extension"), rs.getLong("size_bytes"),
                parseInstantNullable(rs.getString("file_created_at")),
                parseInstantNullable(rs.getString("last_modified")),
                parseInstantNullable(rs.getString("first_seen_at")),
                parseInstantNullable(rs.getString("last_collected_at")),
                FileStatus.valueOf(rs.getString("status")), rs.getInt("retry_count"),
                parseInstantNullable(rs.getString("next_retry_at")), rs.getString("last_error"),
                parseInstantNullable(rs.getString("created_at")),
                parseInstantNullable(rs.getString("updated_at")));
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Instant parseInstantNullable(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.length() == 19 && value.charAt(10) == ' ') {
            return Instant.parse(value.replace(' ', 'T') + "Z");
        }
        return Instant.parse(value);
    }

    @Override
    public synchronized void close() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException e) {
            log.warn("Failed to close FileWatchStore", e);
        }
    }
}
