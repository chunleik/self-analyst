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
    private static final String CURRENT = "statistics_version='" + com.selfanalyst.events.statistics.ActivityStatistics.VERSION
            + "' AND calendar_version='" + com.selfanalyst.events.statistics.ActivityCalendar.VERSION + "'";

    public WikiStore(Path dbPath) {
        Connection opened = null;
        try {
            Files.createDirectories(dbPath.getParent());
            Class.forName("org.sqlite.JDBC");
            opened = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            conn = opened;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                int version = stmt.executeQuery("PRAGMA user_version").getInt(1);
                if (version > 4) throw new SQLException("Unsupported Wiki schema version: " + version);
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
                if (version < 4) migrateStatistics(stmt, dbPath, version > 0);
            }
        } catch (Exception e) {
            if (opened != null) {
                try { opened.close(); } catch (SQLException closeError) { e.addSuppressed(closeError); }
            }
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

    private void migrateStatistics(Statement stmt, Path dbPath, boolean backup) throws SQLException {
        if (backup) {
            Path backupPath = dbPath.resolveSibling(dbPath.getFileName() + ".before-statistics-v4.bak");
            if (!Files.exists(backupPath)) stmt.execute("VACUUM INTO '"
                    + backupPath.toAbsolutePath().toString().replace("'", "''") + "'");
        }
        String schema;
        try (ResultSet rows = stmt.executeQuery("SELECT sql FROM sqlite_master WHERE name='wiki_entries'")) {
            if (!rows.next()) throw new SQLException("Missing Wiki schema");
            schema = rows.getString(1);
        }
        List<String> oldColumns = new ArrayList<>();
        try (ResultSet columns = stmt.executeQuery("PRAGMA table_info(wiki_entries)")) {
            while (columns.next()) oldColumns.add(columns.getString("name"));
        }
        stmt.execute("BEGIN IMMEDIATE");
        try {
            String replacement = schema.replaceFirst("wiki_entries", "wiki_entries_v4")
                    .replace("UNIQUE(level, period_start, period_end, timezone)",
                            "statistics_version TEXT NOT NULL DEFAULT 'legacy', calendar_version TEXT NOT NULL DEFAULT 'legacy', "
                            + "UNIQUE(level, period_start, period_end, timezone, statistics_version, calendar_version)");
            if (!replacement.contains("statistics_version")) throw new SQLException("Unrecognized Wiki uniqueness constraint");
            stmt.execute(replacement);
            stmt.execute("INSERT INTO wiki_entries_v4 (" + String.join(",", oldColumns)
                    + ",statistics_version,calendar_version) SELECT " + String.join(",", oldColumns)
                    + ", 'legacy', 'legacy' FROM wiki_entries");
            stmt.execute("DROP TABLE wiki_entries");
            stmt.execute("ALTER TABLE wiki_entries_v4 RENAME TO wiki_entries");
            stmt.execute("CREATE INDEX idx_wiki_entries_period ON wiki_entries(level,period_start,period_end)");
            stmt.execute("CREATE INDEX idx_wiki_entries_status_retry ON wiki_entries(statistics_version,calendar_version,status,next_retry_at)");
            stmt.execute("UPDATE wiki_semantic_documents SET status='STALE'");
            stmt.execute("CREATE TABLE IF NOT EXISTS wiki_statistics_progress (version TEXT PRIMARY KEY, history_before TEXT NOT NULL)");
            stmt.execute("PRAGMA user_version=4");
            stmt.execute("COMMIT");
        } catch (SQLException error) {
            stmt.execute("ROLLBACK");
            throw error;
        }
    }

    public Instant historyBefore() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT history_before FROM wiki_statistics_progress WHERE version=?")) {
            ps.setString(1, CURRENT);
            try (ResultSet rows = ps.executeQuery()) { return rows.next() ? Instant.parse(rows.getString(1)) : null; }
        } catch (SQLException error) { throw new IllegalStateException("Cannot read Wiki discovery progress", error); }
    }

    public void saveHistoryBefore(Instant cursor) {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO wiki_statistics_progress VALUES (?,?)")) {
            ps.setString(1, CURRENT); ps.setString(2, cursor.toString()); ps.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException("Cannot save Wiki discovery progress", error); }
    }

    public boolean isCurrentEntry(String id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM wiki_entries WHERE id=? AND " + CURRENT)) {
            ps.setString(1, id);
            try (ResultSet rows = ps.executeQuery()) { return rows.next(); }
        } catch (SQLException error) { return false; }
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
            SELECT * FROM wiki_semantic_documents WHERE EXISTS (SELECT 1 FROM wiki_entries WHERE id=entry_id AND %s) AND status = ?
            ORDER BY created_at ASC LIMIT ?
            """.formatted(CURRENT);
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
            SELECT * FROM wiki_semantic_documents WHERE EXISTS (SELECT 1 FROM wiki_entries WHERE id=entry_id AND %s) AND status = ?
            AND (next_retry_at IS NULL OR next_retry_at <= ?)
            ORDER BY retry_count ASC LIMIT ?
            """.formatted(CURRENT);
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
            SELECT * FROM wiki_entries w WHERE %s AND w.status = ?
            AND NOT EXISTS (
              SELECT 1 FROM wiki_semantic_documents s
              WHERE s.entry_id = w.id AND s.status IN (?, ?)
            )
            ORDER BY w.period_start ASC LIMIT ?
            """.formatted(CURRENT);
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
             fact_builder_version, projector_version, source_coverage_json, statistics_version, calendar_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.setString(23, entry.statisticsVersion());
            ps.setString(24, entry.calendarVersion());
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
            fact_builder_version=?, projector_version=?, source_coverage_json=?,next_retry_at=NULL,last_error=NULL
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

    /** One atomic update; budget/config waits do not increment the ordinary retry counter. */
    public void markGenerationFailure(WikiEntry entry, WikiFactBuilder.WikiFacts facts,
                                      Map<String, Object> progress, Instant nextRetry, boolean countFailure) {
        WikiEntry.WikiMetrics prior = entry.metrics();
        Map<String, Object> extra = new java.util.LinkedHashMap<>(facts != null ? facts.statistics()
                : prior != null && prior.extra() != null ? prior.extra() : Map.of());
        extra.put("generationProgress", progress);
        var metrics = new WikiEntry.WikiMetrics(facts != null ? facts.activeSeconds() : prior != null ? prior.activeSeconds() : 0,
                facts != null ? facts.afkSeconds() : prior != null ? prior.afkSeconds() : 0,
                facts != null ? facts.switchCount() : prior != null ? prior.switchCount() : 0,
                facts != null ? facts.topApps() : prior != null ? prior.topApps() : List.of(), extra);
        String sql = "UPDATE wiki_entries SET status=?, retry_count=retry_count+?, next_retry_at=?, last_error=?, "
                + "metrics_json=?,source_coverage_json=?,updated_at=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, countFailure ? WikiStatus.FAILED.name() : WikiStatus.PENDING.name());
            ps.setInt(2, countFailure ? 1 : 0);
            ps.setString(3, nextRetry == null ? null : nextRetry.toString());
            ps.setString(4, String.valueOf(progress.get("reason")));
            ps.setString(5, toJson(metrics));
            ps.setString(6, toJson(facts != null ? facts.sourceCoverage() : entry.sourceCoverage()));
            ps.setString(7, Instant.now().toString()); ps.setString(8, entry.id());
            ps.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException("WIKI_PROGRESS_WRITE_FAILED", error); }
    }

    public List<WikiEntry> findGenerationPaused() {
        List<WikiEntry> result = new ArrayList<>();
        String sql = "SELECT * FROM wiki_entries WHERE " + CURRENT
                + " AND status IN ('PENDING','FAILED') AND json_valid(metrics_json)"
                + " AND json_extract(metrics_json,'$.extra.generationProgress.state') IN ('period_budget','configuration','input')";
        try (PreparedStatement statement = conn.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(mapEntry(rows));
        } catch (SQLException error) { throw new IllegalStateException("WIKI_PROGRESS_READ_FAILED", error); }
        return result;
    }

    public void resumeGeneration(WikiEntry entry) {
        Map<String, Object> progress = new java.util.LinkedHashMap<>(WikiGenerationProgress.raw(entry));
        progress.put("state", "queued"); progress.put("reason", "WIKI_GENERATION_QUEUED");
        progress.remove("nextRetryAt");
        markGenerationFailure(entry, null, progress, null, false);
    }

    public List<WikiEntry> query(Instant start, Instant end, WikiLevel level) {
        List<WikiEntry> results = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM wiki_entries WHERE " + CURRENT);
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

    public void exportSnapshot(Instant start, Instant end, WikiLevel level, int maxRows,
                               java.util.function.Consumer<WikiEntry> consume, Runnable checkpoint) {
        if (start == null || end == null || !start.isBefore(end) || maxRows < 1 || maxRows > 100_000)
            throw new IllegalArgumentException("导出条件无效");
        try {
            var properties = new java.util.Properties(); properties.setProperty("open_mode", "1");
            try (var snapshot = DriverManager.getConnection(conn.getMetaData().getURL(), properties);
                 var query = snapshot.prepareStatement("SELECT * FROM wiki_entries WHERE period_end>=? AND period_start<?"
                         + (level == null ? "" : " AND level=?") + " ORDER BY substr(period_start,1,19),substr(replace(substr(period_start,21),'Z','')||'000000000',1,9),id")) {
                query.setQueryTimeout(120);
                query.setString(1, start.toString().substring(0, 19)); query.setString(2, end.plusSeconds(1).toString().substring(0, 19));
                if (level != null) query.setString(3, level.name());
                try (var rows = query.executeQuery()) {
                    int count = 0;
                    while (rows.next()) {
                        checkpoint.run(); var entry = mapEntry(rows);
                        if (!entry.periodEnd().isAfter(start) || !entry.periodStart().isBefore(end)) continue;
                        if (++count > maxRows) throw new IllegalArgumentException("Wiki 记录超过导出上限"); consume.accept(entry);
                    }
                }
            }
        } catch (SQLException failure) { throw new IllegalStateException("Wiki 快照导出失败", failure); }
    }

    public List<WikiEntry> findPending(WikiLevel level, int limit) {
        return findPending(level, limit, Instant.now());
    }

    public List<WikiEntry> findPending(WikiLevel level, int limit, Instant now) {
        return findByStatusAndLevel(WikiStatus.PENDING, level, limit, now);
    }

    public List<WikiEntry> findRetryable(int limit) {
        return findRetryable(limit, Instant.now());
    }

    public List<WikiEntry> findRetryable(int limit, Instant now) {
        List<WikiEntry> results = new ArrayList<>();
        String sql = """
            SELECT * FROM wiki_entries WHERE %s AND status = ?
            AND (next_retry_at IS NULL OR next_retry_at <= ?)
            ORDER BY retry_count ASC LIMIT ?
            """.formatted(CURRENT);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, WikiStatus.FAILED.name());
            ps.setString(2, now.toString());
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
        String sql = "SELECT COUNT(*) FROM wiki_entries WHERE level = ? AND status = ? AND " + CURRENT;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, level.name());
            ps.setString(2, status.name());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private List<WikiEntry> findByStatusAndLevel(WikiStatus status, WikiLevel level, int limit, Instant now) {
        List<WikiEntry> results = new ArrayList<>();
        String sql = "SELECT * FROM wiki_entries WHERE " + CURRENT + " AND status = ? AND level = ?"
                + " AND (next_retry_at IS NULL OR next_retry_at <= ?) ORDER BY period_start DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, level.name());
            ps.setString(3, now.toString());
            ps.setInt(4, limit);
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
                parseCoverage(rs.getString("source_coverage_json")),
                rs.getString("statistics_version"), rs.getString("calendar_version"));
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
