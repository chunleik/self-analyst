package com.selfanalyst.file;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite store for watched files (SPEC-FILE-010).
 *
 * <p>FileWatcher and FileIndexWorker write across threads; every write is
 * {@code synchronized} on this instance and WAL + busy_timeout are enabled so
 * {@code SQLITE_BUSY} never surfaces (SPEC-FILE-010a / SPEC-FILE-010b).
 */
public class FileWatchStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileWatchStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Connection conn;

    public FileWatchStore(Path dbPath) {
        try {
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");          // SPEC-FILE-010a
                stmt.execute("PRAGMA busy_timeout=5000");          // SPEC-FILE-010a
                int version = stmt.executeQuery("PRAGMA user_version").getInt(1);
                if (version < 1) {
                    createV1Schema(stmt);
                    stmt.execute("PRAGMA user_version=1");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize FileWatchStore", e);
        }
    }

    private void createV1Schema(Statement stmt) throws SQLException {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS file_index (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              absolute_path TEXT NOT NULL UNIQUE,
              relative_path TEXT,
              watch_root TEXT,
              extension TEXT,
              size_bytes INTEGER NOT NULL DEFAULT 0,
              file_hash TEXT,
              last_modified TEXT,
              first_seen_at TEXT NOT NULL,
              last_indexed_at TEXT,
              status TEXT NOT NULL,
              summary TEXT,
              main_topics_json TEXT,
              model TEXT,
              prompt_version TEXT,
              retry_count INTEGER NOT NULL DEFAULT 0,
              next_retry_at TEXT,
              last_error TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL
            )
            """);
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_index_status ON file_index(status, updated_at)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_index_status_retry ON file_index(status, next_retry_at)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_index_last_modified ON file_index(last_modified)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_index_watch_root ON file_index(watch_root)");
    }

    // ── Writes (SPEC-FILE-010b: cross-thread, synchronized) ──

    /**
     * Register intent to (re-)index a file (SPEC-FILE-004 path A, idempotent).
     * Sets status=PENDING; leaves the last-indexed snapshot
     * (size/hash/last_modified/summary) intact so the worker can still detect
     * "no real change" (SPEC-FILE-004c / SPEC-FILE-010d).
     */
    public synchronized void upsertPending(String absolutePath, String relativePath,
                                           String watchRoot, String extension) {
        String now = Instant.now().toString();
        String ext = extension != null ? extension.toLowerCase() : null; // SPEC-FILE-010f
        String sql = """
            INSERT INTO file_index
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
            ps.setString(4, ext);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.setString(7, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert pending file " + absolutePath, e);
        }
    }

    /** ENTRY_DELETE → mark terminal DELETED (SPEC-FILE-004). No-op if unknown. */
    public synchronized void markDeleted(String absolutePath) {
        String sql = "UPDATE file_index SET status='DELETED', updated_at=? WHERE absolute_path=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark deleted " + absolutePath, e);
        }
    }

    /** Successful summarization: write summary + new last-indexed snapshot, status=INDEXED. */
    public synchronized void updateIndexed(String absolutePath, long sizeBytes, Instant lastModified,
                                           String fileHash, String summary, List<String> mainTopics,
                                           String model, String promptVersion) {
        String now = Instant.now().toString();
        String sql = """
            UPDATE file_index SET
              status='INDEXED', size_bytes=?, last_modified=?, file_hash=?,
              summary=?, main_topics_json=?, model=?, prompt_version=?,
              last_indexed_at=?, last_error=NULL, next_retry_at=NULL, updated_at=?
            WHERE absolute_path=?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sizeBytes);
            ps.setString(2, lastModified != null ? lastModified.toString() : null);
            ps.setString(3, fileHash);
            ps.setString(4, summary);
            ps.setString(5, toJson(mainTopics));
            ps.setString(6, model);
            ps.setString(7, promptVersion);
            ps.setString(8, now);
            ps.setString(9, now);
            ps.setString(10, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update indexed " + absolutePath, e);
        }
    }

    /**
     * Content unchanged (hash matches / cheap prefilter hit): refresh the
     * snapshot and flip back to INDEXED without re-summarizing (SPEC-FILE-010d).
     */
    public synchronized void updateChecksum(String absolutePath, long sizeBytes,
                                            Instant lastModified, String fileHash) {
        String sql = """
            UPDATE file_index SET
              status='INDEXED', size_bytes=?, last_modified=?, file_hash=?,
              last_error=NULL, next_retry_at=NULL, updated_at=?
            WHERE absolute_path=?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sizeBytes);
            ps.setString(2, lastModified != null ? lastModified.toString() : null);
            ps.setString(3, fileHash);
            ps.setString(4, Instant.now().toString());
            ps.setString(5, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update checksum " + absolutePath, e);
        }
    }

    public synchronized void markSkipped(String absolutePath, String reason) {
        String sql = "UPDATE file_index SET status='SKIPPED', last_error=?, updated_at=? WHERE absolute_path=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, reason);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark skipped " + absolutePath, e);
        }
    }

    public synchronized void markFailed(String absolutePath, String error, Instant nextRetryAt) {
        String sql = """
            UPDATE file_index SET status='FAILED', retry_count=retry_count+1,
              next_retry_at=?, last_error=?, updated_at=?
            WHERE absolute_path=?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nextRetryAt != null ? nextRetryAt.toString() : null);
            ps.setString(2, error);
            ps.setString(3, Instant.now().toString());
            ps.setString(4, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark failed " + absolutePath, e);
        }
    }

    // ── Reads ──

    public synchronized FileRecord findByPath(String absolutePath) {
        String sql = "SELECT * FROM file_index WHERE absolute_path=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? map(rs) : null;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find file " + absolutePath, e);
        }
    }

    /** Oldest PENDING rows first (SPEC-FILE-013, worker takes one per round). */
    public synchronized List<FileRecord> findPending(int limit) {
        String sql = "SELECT * FROM file_index WHERE status='PENDING' ORDER BY updated_at ASC LIMIT ?";
        return queryList(sql, ps -> ps.setInt(1, limit));
    }

    /** FAILED rows whose backoff has elapsed (SPEC-FILE-010e). */
    public synchronized List<FileRecord> findRetryable(int limit) {
        String sql = """
            SELECT * FROM file_index WHERE status='FAILED'
              AND (next_retry_at IS NULL OR next_retry_at <= ?)
            ORDER BY retry_count ASC LIMIT ?
            """;
        String now = Instant.now().toString();
        return queryList(sql, ps -> { ps.setString(1, now); ps.setInt(2, limit); });
    }

    /** All INDEXED rows (used by FileEmbeddingWorker startup reconcile). */
    public synchronized List<FileRecord> findIndexed(int limit) {
        String sql = "SELECT * FROM file_index WHERE status='INDEXED' ORDER BY last_indexed_at ASC LIMIT ?";
        return queryList(sql, ps -> ps.setInt(1, limit));
    }

    /** Most recently completed indexes first, for desktop visibility. */
    public synchronized List<FileRecord> findRecentlyIndexed(int limit) {
        String sql = "SELECT * FROM file_index WHERE status='INDEXED' "
                + "AND last_indexed_at IS NOT NULL ORDER BY last_indexed_at DESC LIMIT ?";
        return queryList(sql, ps -> ps.setInt(1, Math.max(1, limit)));
    }

    /** Most recently completed indexes restricted to the currently configured roots. */
    public synchronized List<FileRecord> findRecentlyIndexed(List<String> watchRoots, int limit) {
        if (watchRoots == null || watchRoots.isEmpty()) return List.of();
        String placeholders = String.join(",", watchRoots.stream().map(ignored -> "?").toList());
        String sql = "SELECT * FROM file_index WHERE status='INDEXED' "
                + "AND last_indexed_at IS NOT NULL AND watch_root IN (" + placeholders + ") "
                + "ORDER BY last_indexed_at DESC LIMIT ?";
        return queryList(sql, ps -> {
            int index = 1;
            for (String root : watchRoots) ps.setString(index++, root);
            ps.setInt(index, Math.max(1, limit));
        });
    }

    /**
     * Time-axis / recent-files query (SPEC-FILE-010c, SPEC-FILE-017 listRecentFiles).
     * Only INDEXED rows are returned; null filters are ignored.
     */
    public synchronized List<FileRecord> queryByTime(Instant start, Instant end,
                                                     String watchRoot, String extension, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM file_index WHERE status='INDEXED' AND last_modified IS NOT NULL");
        List<Object> params = new ArrayList<>();
        if (start != null) { sql.append(" AND last_modified >= ?"); params.add(start.toString()); }
        if (end != null)   { sql.append(" AND last_modified <= ?"); params.add(end.toString()); }
        if (watchRoot != null && !watchRoot.isBlank()) {
            sql.append(" AND watch_root = ?"); params.add(watchRoot);
        }
        if (extension != null && !extension.isBlank()) {
            sql.append(" AND extension = ?"); params.add(extension.toLowerCase());
        }
        sql.append(" ORDER BY last_modified DESC LIMIT ?");
        params.add(limit);
        return queryList(sql.toString(), ps -> {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Integer n) ps.setInt(i + 1, n);
                else ps.setString(i + 1, (String) p);
            }
        });
    }

    /** Per-watchRoot status counts (SPEC-FILE-017 fileIndexStatus). */
    public synchronized Map<String, Map<String, Long>> statusCountsByWatchRoot() {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        String sql = "SELECT watch_root, status, COUNT(*) AS c FROM file_index GROUP BY watch_root, status";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String root = rs.getString("watch_root");
                if (root == null) root = "";
                out.computeIfAbsent(root, k -> new LinkedHashMap<>())
                   .put(rs.getString("status"), rs.getLong("c"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count file statuses", e);
        }
        return out;
    }

    // ── helpers ──

    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

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
                rs.getLong("id"),
                rs.getString("absolute_path"),
                rs.getString("relative_path"),
                rs.getString("watch_root"),
                rs.getString("extension"),
                rs.getLong("size_bytes"),
                rs.getString("file_hash"),
                parseInstantNullable(rs.getString("last_modified")),
                parseInstantNullable(rs.getString("first_seen_at")),
                parseInstantNullable(rs.getString("last_indexed_at")),
                FileStatus.valueOf(rs.getString("status")),
                rs.getString("summary"),
                parseStringList(rs.getString("main_topics_json")),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getInt("retry_count"),
                parseInstantNullable(rs.getString("next_retry_at")),
                rs.getString("last_error"),
                parseInstantNullable(rs.getString("created_at")),
                parseInstantNullable(rs.getString("updated_at")));
    }

    private static String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static List<String> parseStringList(String json) {
        if (json == null) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Instant parseInstantNullable(String s) {
        if (s == null || s.isEmpty()) return null;
        return Instant.parse(normalizeSqliteTs(s));
    }

    /** SQLite datetime() returns "2026-06-15 06:06:33" — no T, no Z. Fix both. */
    private static String normalizeSqliteTs(String s) {
        if (s.length() == 19 && s.charAt(10) == ' ') {
            return s.replace(' ', 'T') + "Z";
        }
        return s;
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
