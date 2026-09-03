package com.selfanalyst.wiki;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WikiStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WikiStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final Connection conn;

    public WikiStore(Path dbPath) {
        try {
            Files.createDirectories(dbPath.getParent());
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                int version = stmt.executeQuery("PRAGMA user_version").getInt(1);
                if (version < 1) {
                    createV1Schema(stmt);
                    stmt.execute("PRAGMA user_version=1");
                }
                if (version < 2) {
                    createV2Schema(stmt);
                    stmt.execute("PRAGMA user_version=2");
                }
                if (version < 3) {
                    createV3Schema(stmt);
                    stmt.execute("PRAGMA user_version=3");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize WikiStore", e);
        }
    }

    private void createV1Schema(Statement stmt) throws SQLException {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS wiki_entries (
              id TEXT PRIMARY KEY,
              level TEXT NOT NULL,
              period_start TEXT NOT NULL,
              period_end TEXT NOT NULL,
              timezone TEXT NOT NULL,
              status TEXT NOT NULL,
              summary TEXT,
              primary_task TEXT,
              task_segments_json TEXT,
              metrics_json TEXT,
              source_entry_ids_json TEXT,
              model TEXT,
              prompt_version TEXT,
              retry_count INTEGER NOT NULL DEFAULT 0,
              next_retry_at TEXT,
              last_error TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              summarized_at TEXT,
              UNIQUE(level, period_start, period_end, timezone)
            )
            """);
        stmt.execute("""
            CREATE INDEX IF NOT EXISTS idx_wiki_entries_period
              ON wiki_entries(level, period_start, period_end)
            """);
        stmt.execute("""
            CREATE INDEX IF NOT EXISTS idx_wiki_entries_status_retry
              ON wiki_entries(status, next_retry_at)
            """);
    }

    private void createV2Schema(Statement stmt) throws SQLException {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS wiki_semantic_documents (
              doc_id TEXT PRIMARY KEY,
              entry_id TEXT NOT NULL,
              doc_type TEXT NOT NULL,
              level TEXT NOT NULL,
              period_start TEXT NOT NULL,
              period_end TEXT NOT NULL,
              text_hash TEXT NOT NULL,
              embedding_model TEXT NOT NULL,
              embedding_dimensions INTEGER NOT NULL,
              status TEXT NOT NULL,
              retry_count INTEGER NOT NULL DEFAULT 0,
              next_retry_at TEXT,
              last_error TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              indexed_at TEXT,
              UNIQUE(entry_id, doc_type, text_hash, embedding_model, embedding_dimensions)
            )
            """);
        stmt.execute("""
            CREATE INDEX IF NOT EXISTS idx_wiki_semantic_documents_status_retry
              ON wiki_semantic_documents(status, next_retry_at)
            """);
        stmt.execute("""
            CREATE INDEX IF NOT EXISTS idx_wiki_semantic_documents_entry
              ON wiki_semantic_documents(entry_id, doc_type)
            """);
    }

    private void createV3Schema(Statement stmt) throws SQLException {
        stmt.execute("ALTER TABLE wiki_entries ADD COLUMN fact_builder_version TEXT");
        stmt.execute("ALTER TABLE wiki_entries ADD COLUMN projector_version TEXT");
        stmt.execute("ALTER TABLE wiki_entries ADD COLUMN source_coverage_json TEXT");
    }

    // ── Semantic document CRUD ──

    public enum SemanticDocStatus { PENDING, INDEXED, FAILED, STALE }

    public record SemanticDoc(
            String docId,
            String entryId,
            String docType,
            String level,
            String periodStart,
            String periodEnd,
            String textHash,
            String embeddingModel,
            int embeddingDimensions,
            SemanticDocStatus status,
            int retryCount,
            Instant nextRetryAt,
            String lastError,
            Instant createdAt,
            Instant updatedAt,
            Instant indexedAt) {}

    public void upsertSemanticDoc(SemanticDoc doc) {
        String sql = """
            INSERT OR REPLACE INTO wiki_semantic_documents
            (doc_id, entry_id, doc_type, level, period_start, period_end,
             text_hash, embedding_model, embedding_dimensions, status,
             retry_count, next_retry_at, last_error, created_at, updated_at, indexed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, doc.docId());
            ps.setString(2, doc.entryId());
            ps.setString(3, doc.docType());
            ps.setString(4, doc.level());
            ps.setString(5, doc.periodStart());
            ps.setString(6, doc.periodEnd());
            ps.setString(7, doc.textHash());
            ps.setString(8, doc.embeddingModel());
            ps.setInt(9, doc.embeddingDimensions());
            ps.setString(10, doc.status().name());
            ps.setInt(11, doc.retryCount());
            ps.setString(12, doc.nextRetryAt() != null ? doc.nextRetryAt().toString() : null);
            ps.setString(13, doc.lastError());
            ps.setString(14, doc.createdAt().toString());
            ps.setString(15, doc.updatedAt().toString());
            ps.setString(16, doc.indexedAt() != null ? doc.indexedAt().toString() : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert semantic doc", e);
        }
    }

    public List<SemanticDoc> findPendingSemanticDocs(int limit) {
        List<SemanticDoc> results = new ArrayList<>();
        String sql = """
            SELECT * FROM wiki_semantic_documents WHERE status = ?
            ORDER BY created_at ASC LIMIT ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SemanticDocStatus.PENDING.name());
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) results.add(mapSemanticDoc(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find pending semantic docs", e);
        }
        return results;
    }

    public List<SemanticDoc> findRetryableSemanticDocs(int limit) {
        List<SemanticDoc> results = new ArrayList<>();
        String sql = """
            SELECT * FROM wiki_semantic_documents WHERE status = ?
            AND (next_retry_at IS NULL OR next_retry_at <= ?)
            ORDER BY retry_count ASC LIMIT ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SemanticDocStatus.FAILED.name());
            ps.setString(2, Instant.now().toString());
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) results.add(mapSemanticDoc(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find retryable semantic docs", e);
        }
        return results;
    }

    public void markSemanticDocIndexed(String docId) {
        String sql = """
            UPDATE wiki_semantic_documents SET status=?, updated_at=?, indexed_at=?
            WHERE doc_id=?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SemanticDocStatus.INDEXED.name());
            String now = Instant.now().toString();
            ps.setString(2, now);
            ps.setString(3, now);
            ps.setString(4, docId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark semantic doc indexed", e);
        }
    }

    public void markSemanticDocFailed(String docId, String error, Instant nextRetryAt) {
        String sql = """
            UPDATE wiki_semantic_documents SET status=?, retry_count=retry_count+1,
            next_retry_at=?, last_error=?, updated_at=?
            WHERE doc_id=?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SemanticDocStatus.FAILED.name());
            ps.setString(2, nextRetryAt.toString());
            ps.setString(3, error);
            ps.setString(4, Instant.now().toString());
            ps.setString(5, docId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark semantic doc failed", e);
        }
    }

    public void markSemanticDocsStale(String embeddingModel, int embeddingDimensions) {
        String sql = """
            UPDATE wiki_semantic_documents SET status=?, updated_at=?
            WHERE (embedding_model != ? OR embedding_dimensions != ?)
            AND status = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SemanticDocStatus.STALE.name());
            ps.setString(2, Instant.now().toString());
            ps.setString(3, embeddingModel);
            ps.setInt(4, embeddingDimensions);
            ps.setString(5, SemanticDocStatus.INDEXED.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark semantic docs stale", e);
        }
    }

    public List<WikiEntry> findSummarizedWithoutSemanticDocs(int limit) {
        List<WikiEntry> results = new ArrayList<>();
        String sql = """
            SELECT * FROM wiki_entries w WHERE w.status = ?
            AND NOT EXISTS (
              SELECT 1 FROM wiki_semantic_documents s
              WHERE s.entry_id = w.id AND s.status IN (?, ?)
            )
            ORDER BY w.period_start ASC LIMIT ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, WikiStatus.SUMMARIZED.name());
            ps.setString(2, SemanticDocStatus.PENDING.name());
            ps.setString(3, SemanticDocStatus.INDEXED.name());
            ps.setInt(4, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) results.add(mapEntry(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find entries without semantic docs", e);
        }
        return results;
    }

    public List<SemanticDoc> findSemanticDocsByEntry(String entryId) {
        List<SemanticDoc> results = new ArrayList<>();
        String sql = "SELECT * FROM wiki_semantic_documents WHERE entry_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entryId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) results.add(mapSemanticDoc(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find semantic docs by entry", e);
        }
        return results;
    }

    private SemanticDoc mapSemanticDoc(ResultSet rs) throws SQLException {
        return new SemanticDoc(
                rs.getString("doc_id"),
                rs.getString("entry_id"),
                rs.getString("doc_type"),
                rs.getString("level"),
                rs.getString("period_start"),
                rs.getString("period_end"),
                rs.getString("text_hash"),
                rs.getString("embedding_model"),
                rs.getInt("embedding_dimensions"),
                SemanticDocStatus.valueOf(rs.getString("status")),
                rs.getInt("retry_count"),
                parseInstantNullable(rs.getString("next_retry_at")),
                rs.getString("last_error"),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("updated_at")),
                parseInstantNullable(rs.getString("indexed_at")));
    }

    public void upsert(WikiEntry entry) {
        String sql = """
            INSERT OR REPLACE INTO wiki_entries
            (id, level, period_start, period_end, timezone, status, summary, primary_task,
             task_segments_json, metrics_json, source_entry_ids_json, model, prompt_version,
             retry_count, next_retry_at, last_error, created_at, updated_at, summarized_at,
             fact_builder_version, projector_version, source_coverage_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.id());
            ps.setString(2, entry.level().name());
            ps.setString(3, entry.periodStart().toString());
            ps.setString(4, entry.periodEnd().toString());
            ps.setString(5, entry.timezone());
            ps.setString(6, entry.status().name());
            ps.setString(7, entry.summary());
            ps.setString(8, entry.primaryTask());
            ps.setString(9, toJson(entry.taskSegments()));
            ps.setString(10, toJson(entry.metrics()));
            ps.setString(11, toJson(entry.sourceEntryIds()));
            ps.setString(12, entry.model());
            ps.setString(13, entry.promptVersion());
            ps.setInt(14, entry.retryCount());
            ps.setString(15, entry.nextRetryAt() != null ? entry.nextRetryAt().toString() : null);
            ps.setString(16, entry.lastError());
            ps.setString(17, entry.createdAt().toString());
            ps.setString(18, entry.updatedAt().toString());
            ps.setString(19, entry.summarizedAt() != null ? entry.summarizedAt().toString() : null);
            ps.setString(20, entry.factBuilderVersion());
            ps.setString(21, entry.projectorVersion());
            ps.setString(22, toJson(entry.sourceCoverage()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert wiki entry", e);
        }
    }

    public void updateStatus(String id, WikiStatus status, String summary, String primaryTask,
                              List<WikiEntry.TaskSegment> taskSegments, WikiEntry.WikiMetrics metrics,
                              List<String> sourceEntryIds, String model, String promptVersion) {
        updateStatus(id, status, summary, primaryTask, taskSegments, metrics,
                sourceEntryIds, model, promptVersion, null, null, Map.of());
    }

    public void updateStatus(String id, WikiStatus status, String summary, String primaryTask,
                             List<WikiEntry.TaskSegment> taskSegments, WikiEntry.WikiMetrics metrics,
                             List<String> sourceEntryIds, String model, String promptVersion,
                             String factBuilderVersion, String projectorVersion,
                             Map<String, WikiEntry.SourceCoverage> sourceCoverage) {
        String sql = """
            UPDATE wiki_entries SET status=?, summary=?, primary_task=?,
            task_segments_json=?, metrics_json=?, source_entry_ids_json=?,
            model=?, prompt_version=?, updated_at=?, summarized_at=?,
            fact_builder_version=?, projector_version=?, source_coverage_json=?
            WHERE id=?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, summary);
            ps.setString(3, primaryTask);
            ps.setString(4, toJson(taskSegments));
            ps.setString(5, toJson(metrics));
            ps.setString(6, toJson(sourceEntryIds));
            ps.setString(7, model);
            ps.setString(8, promptVersion);
            String now = Instant.now().toString();
            ps.setString(9, now);
            ps.setString(10, status == WikiStatus.SUMMARIZED ? now : null);
            ps.setString(11, factBuilderVersion);
            ps.setString(12, projectorVersion);
            ps.setString(13, toJson(sourceCoverage));
            ps.setString(14, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update wiki entry status", e);
        }
    }

    public void markFailed(String id, String error, Instant nextRetryAt) {
        String sql = """
            UPDATE wiki_entries SET status=?, retry_count=retry_count+1,
            next_retry_at=?, last_error=?, updated_at=?
            WHERE id=?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, WikiStatus.FAILED.name());
            ps.setString(2, nextRetryAt.toString());
            ps.setString(3, error);
            ps.setString(4, Instant.now().toString());
            ps.setString(5, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark entry as failed", e);
        }
    }

    public void markSkipped(String id, String reason) {
        String sql = "UPDATE wiki_entries SET status=?, last_error=?, updated_at=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, WikiStatus.SKIPPED.name());
            ps.setString(2, reason);
            ps.setString(3, Instant.now().toString());
            ps.setString(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark entry as skipped", e);
        }
    }

    public List<WikiEntry> query(Instant start, Instant end, WikiLevel level) {
        List<WikiEntry> results = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM wiki_entries WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (start != null) {
            sql.append(" AND period_end > ?");
            params.add(start.toString());
        }
        if (end != null) {
            sql.append(" AND period_start < ?");
            params.add(end.toString());
        }
        if (level != null) {
            sql.append(" AND level = ?");
            params.add(level.name());
        }
        sql.append(" ORDER BY period_start ASC");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, (String) params.get(i));
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(mapEntry(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query wiki entries", e);
        }
        return results;
    }

    public List<WikiEntry> findPending(WikiLevel level, int limit) {
        return findByStatusAndLevel(WikiStatus.PENDING, level, limit);
    }

    public List<WikiEntry> findRetryable(int limit) {
        List<WikiEntry> results = new ArrayList<>();
        String sql = """
            SELECT * FROM wiki_entries WHERE status = ?
            AND (next_retry_at IS NULL OR next_retry_at <= ?)
            ORDER BY retry_count ASC LIMIT ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, WikiStatus.FAILED.name());
            ps.setString(2, Instant.now().toString());
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(mapEntry(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find retryable entries", e);
        }
        return results;
    }

    public long countByStatus(WikiLevel level, WikiStatus status) {
        String sql = "SELECT COUNT(*) FROM wiki_entries WHERE level = ? AND status = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, level.name());
            ps.setString(2, status.name());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private List<WikiEntry> findByStatusAndLevel(WikiStatus status, WikiLevel level, int limit) {
        List<WikiEntry> results = new ArrayList<>();
        String sql = "SELECT * FROM wiki_entries WHERE status = ? AND level = ? ORDER BY period_start ASC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, level.name());
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(mapEntry(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find entries by status and level", e);
        }
        return results;
    }

    private WikiEntry mapEntry(ResultSet rs) throws SQLException {
        return new WikiEntry(
                rs.getString("id"),
                WikiLevel.valueOf(rs.getString("level")),
                parseInstant(rs.getString("period_start")),
                parseInstant(rs.getString("period_end")),
                rs.getString("timezone"),
                WikiStatus.valueOf(rs.getString("status")),
                rs.getString("summary"),
                rs.getString("primary_task"),
                parseTaskSegments(rs.getString("task_segments_json")),
                parseMetrics(rs.getString("metrics_json")),
                parseStringList(rs.getString("source_entry_ids_json")),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getInt("retry_count"),
                parseInstantNullable(rs.getString("next_retry_at")),
                rs.getString("last_error"),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("updated_at")),
                parseInstantNullable(rs.getString("summarized_at")),
                rs.getString("fact_builder_version"),
                rs.getString("projector_version"),
                parseCoverage(rs.getString("source_coverage_json")));
    }

    private static String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Instant parseInstant(String s) {
        return s != null ? Instant.parse(normalizeSqliteTs(s)) : Instant.EPOCH;
    }

    private static Instant parseInstantNullable(String s) {
        return (s != null && !s.isEmpty()) ? Instant.parse(normalizeSqliteTs(s)) : null;
    }

    /** SQLite datetime() returns "2026-06-15 06:06:33" — no T, no Z. Fix both. */
    private static String normalizeSqliteTs(String s) {
        if (s.length() == 19 && s.charAt(10) == ' ') {
            return s.replace(' ', 'T') + "Z";
        }
        return s;
    }

    private static List<WikiEntry.TaskSegment> parseTaskSegments(String json) {
        if (json == null) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<WikiEntry.TaskSegment>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static WikiEntry.WikiMetrics parseMetrics(String json) {
        if (json == null) return new WikiEntry.WikiMetrics(0, 0, 0, List.of(), Map.of());
        try {
            return MAPPER.readValue(json, WikiEntry.WikiMetrics.class);
        } catch (Exception e) {
            return new WikiEntry.WikiMetrics(0, 0, 0, List.of(), Map.of());
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

    private static Map<String, WikiEntry.SourceCoverage> parseCoverage(String json) {
        if (json == null) return Map.of();
        try {
            return MAPPER.readValue(json,
                    new TypeReference<Map<String, WikiEntry.SourceCoverage>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            log.warn("Failed to close WikiStore connection", e);
        }
    }
}
