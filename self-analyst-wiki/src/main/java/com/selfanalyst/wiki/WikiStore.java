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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
                if (version > 5) throw new SQLException("Unsupported Wiki schema version: " + version);
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
                if (version < 5) migrateRepairs(stmt, dbPath, version > 0);
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

    private void migrateRepairs(Statement stmt, Path dbPath, boolean backup) throws SQLException {
        if (backup) {
            Path backupPath = dbPath.resolveSibling(dbPath.getFileName() + ".before-summary-repair-v5.bak");
            if (Files.exists(backupPath)) {
                verifyRepairBackup(backupPath);
                verifyRepairBackupMatches(stmt, backupPath);
            } else {
                Path temporary = backupPath.resolveSibling(backupPath.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
                try {
                    stmt.execute("VACUUM INTO '" + temporary.toAbsolutePath().toString().replace("'", "''") + "'");
                    verifyRepairBackup(temporary);
                    Files.move(temporary, backupPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.io.IOException error) {
                    throw new SQLException("WIKI_REPAIR_BACKUP_FAILED", error);
                } finally {
                    try { Files.deleteIfExists(temporary); }
                    catch (java.io.IOException ignored) { /* A precisely named incomplete backup never counts as rollback evidence. */ }
                }
            }
        }
        stmt.execute("BEGIN IMMEDIATE");
        try {
            stmt.execute("ALTER TABLE wiki_entries ADD COLUMN revision INTEGER NOT NULL DEFAULT 0");
            // Every mutation, including privacy rewrites and retry timestamps, invalidates old readers.
            stmt.execute("CREATE TRIGGER wiki_entries_revision AFTER UPDATE ON wiki_entries "
                    + "WHEN NEW.revision=OLD.revision BEGIN UPDATE wiki_entries SET revision=OLD.revision+1 WHERE id=NEW.id; END");
            stmt.execute("CREATE TABLE wiki_repair_drafts (id TEXT PRIMARY KEY, session_id TEXT NOT NULL UNIQUE, "
                    + "target_id TEXT NOT NULL, target_revision INTEGER NOT NULL, draft_revision INTEGER NOT NULL, "
                    + "state TEXT NOT NULL, payload_json TEXT NOT NULL CHECK(length(payload_json)<=131072), "
                    + "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            stmt.execute("CREATE TABLE wiki_repair_statements (id TEXT PRIMARY KEY, target_id TEXT NOT NULL, "
                    + "repair_id TEXT NOT NULL, draft_revision INTEGER NOT NULL, payload_json TEXT NOT NULL CHECK(length(payload_json)<=8192), confirmed_at TEXT NOT NULL)");
            stmt.execute("CREATE TABLE wiki_repair_revisions (id TEXT PRIMARY KEY, repair_id TEXT NOT NULL UNIQUE, "
                    + "target_id TEXT NOT NULL, idempotency_key TEXT NOT NULL UNIQUE, before_revision INTEGER NOT NULL, "
                    + "after_revision INTEGER NOT NULL, payload_json TEXT NOT NULL CHECK(length(payload_json)<=524288), committed_at TEXT NOT NULL)");
            stmt.execute("CREATE TABLE wiki_repair_followups (repair_id TEXT PRIMARY KEY, target_id TEXT NOT NULL, "
                    + "revision INTEGER NOT NULL, created_at TEXT NOT NULL)");
            for (String table : List.of("wiki_repair_statements", "wiki_repair_revisions")) {
                stmt.execute("CREATE TRIGGER " + table + "_immutable_update BEFORE UPDATE ON " + table
                        + " BEGIN SELECT RAISE(ABORT,'WIKI_REPAIR_APPEND_ONLY'); END");
                stmt.execute("CREATE TRIGGER " + table + "_immutable_delete BEFORE DELETE ON " + table
                        + " BEGIN SELECT RAISE(ABORT,'WIKI_REPAIR_APPEND_ONLY'); END");
            }
            stmt.execute("PRAGMA user_version=5");
            stmt.execute("COMMIT");
        } catch (SQLException error) {
            stmt.execute("ROLLBACK");
            throw error;
        }
    }

    private static void verifyRepairBackupMatches(Statement statement, Path backup) throws SQLException {
        statement.execute("ATTACH DATABASE '" + (backup.toUri().toASCIIString() + "?mode=ro&immutable=1").replace("'", "''") + "' AS repair_backup");
        try {
            for (String table : List.of("wiki_entries", "wiki_semantic_documents", "wiki_statistics_progress")) {
                String main = "SELECT * FROM main." + table, saved = "SELECT * FROM repair_backup." + table;
                try (ResultSet rows = statement.executeQuery("SELECT EXISTS(" + main + " EXCEPT " + saved + ") OR EXISTS(" + saved + " EXCEPT " + main + ")")) {
                    if (!rows.next() || rows.getBoolean(1)) throw new SQLException("WIKI_REPAIR_BACKUP_STALE");
                }
            }
        } finally { statement.execute("DETACH DATABASE repair_backup"); }
    }

    private static void verifyRepairBackup(Path backup) throws SQLException {
        if (!Files.isRegularFile(backup, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new SQLException("WIKI_REPAIR_BACKUP_INVALID");
        try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + backup.toUri().toASCIIString() + "?mode=ro&immutable=1");
             Statement statement = source.createStatement()) {
            try (ResultSet rows = statement.executeQuery("PRAGMA integrity_check")) {
                if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next()) throw new SQLException("WIKI_REPAIR_BACKUP_INVALID");
            }
            try (ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
                if (!rows.next() || rows.getInt(1) != 4) throw new SQLException("WIKI_REPAIR_BACKUP_INVALID");
            }
            try (ResultSet rows = statement.executeQuery("SELECT id,statistics_version,calendar_version FROM wiki_entries LIMIT 1")) { }
        }
    }

    public record VersionedEntry(WikiEntry entry, long revision) {}
    public record RepairFollowup(String repairId, String targetId, long revision) {}

    public synchronized VersionedEntry versionedEntry(String id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM wiki_entries WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next() ? new VersionedEntry(mapEntry(rows), rows.getLong("revision")) : null;
            }
        } catch (SQLException error) { throw new IllegalStateException("WIKI_VERSION_READ_FAILED", error); }
    }

    public synchronized long revision(String id) {
        VersionedEntry current = versionedEntry(id);
        return current == null ? -1 : current.revision();
    }

    synchronized WikiRepairService.Draft repairDraft(String id) {
        return readRepair("id", id);
    }

    synchronized WikiRepairService.Draft repairDraftBySession(String sessionId) {
        return readRepair("session_id", sessionId);
    }

    private WikiRepairService.Draft readRepair(String column, String value) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT payload_json FROM wiki_repair_drafts WHERE " + column + "=?")) {
            ps.setString(1, value);
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next() ? MAPPER.readValue(rows.getString(1), WikiRepairService.Draft.class) : null;
            }
        } catch (Exception error) { throw new IllegalStateException("WIKI_REPAIR_READ_FAILED", error); }
    }

    synchronized void saveRepairDraft(WikiRepairService.Draft draft, long expectedRevision) {
        String payload = repairJson(draft);
        if (payload.length() > 131072) throw new WikiRepairException("WIKI_REPAIR_TOO_LARGE");
        String sql = expectedRevision < 0
                ? "INSERT INTO wiki_repair_drafts (id,session_id,target_id,target_revision,draft_revision,state,payload_json,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)"
                : "UPDATE wiki_repair_drafts SET target_revision=?,draft_revision=?,state=?,payload_json=?,updated_at=? WHERE id=? AND session_id=? AND draft_revision=? AND state<>'committed'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (expectedRevision < 0) {
                ps.setString(1, draft.id()); ps.setString(2, draft.sessionId()); ps.setString(3, draft.targetId());
                ps.setLong(4, draft.targetRevision()); ps.setLong(5, draft.draftRevision()); ps.setString(6, draft.state());
                ps.setString(7, payload); ps.setString(8, draft.createdAt().toString()); ps.setString(9, draft.updatedAt().toString());
            } else {
                ps.setLong(1, draft.targetRevision()); ps.setLong(2, draft.draftRevision()); ps.setString(3, draft.state());
                ps.setString(4, payload); ps.setString(5, draft.updatedAt().toString()); ps.setString(6, draft.id());
                ps.setString(7, draft.sessionId()); ps.setLong(8, expectedRevision);
            }
            if (ps.executeUpdate() != 1) throw new WikiRepairException("WIKI_REPAIR_DRAFT_CONFLICT");
        } catch (SQLException error) { throw new WikiRepairException("WIKI_REPAIR_DRAFT_CONFLICT"); }
    }

    /** Atomic publish, independent source retention, immutable audit, and durable follow-up marker. */
    synchronized WikiRepairService.Draft commitRepair(WikiRepairService.Draft draft,
                                                      WikiSummarizer.SummaryResult validated, String idempotencyKey) {
        try (Statement tx = conn.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                WikiRepairService.Draft persisted = repairDraft(draft.id());
                if (persisted == null || !persisted.sessionId().equals(draft.sessionId()))
                    throw new WikiRepairException("WIKI_REPAIR_NOT_FOUND");
                if ("committed".equals(persisted.state())) {
                    if (persisted.draftRevision() != draft.draftRevision()) throw new WikiRepairException("WIKI_REPAIR_DRAFT_CONFLICT");
                    tx.execute("COMMIT");
                    return persisted;
                }
                if (persisted.draftRevision() != draft.draftRevision()) throw new WikiRepairException("WIKI_REPAIR_DRAFT_CONFLICT");
                try (PreparedStatement key = conn.prepareStatement("SELECT repair_id FROM wiki_repair_revisions WHERE idempotency_key=?")) {
                    key.setString(1, idempotencyKey);
                    try (ResultSet used = key.executeQuery()) {
                        if (used.next()) throw new WikiRepairException("WIKI_REPAIR_IDEMPOTENCY_CONFLICT");
                    }
                }
                VersionedEntry before = versionedEntry(draft.targetId());
                if (before == null || before.revision() != draft.targetRevision()) throw new WikiRepairException("WIKI_REPAIR_TARGET_CONFLICT");
                WikiEntry entry = before.entry();
                WikiEntry.WikiMetrics priorMetrics = entry.metrics() == null
                        ? new WikiEntry.WikiMetrics(0, 0, 0, List.of(), Map.of()) : entry.metrics();
                Map<String, Object> extra = new LinkedHashMap<>(priorMetrics.extra() == null ? Map.of() : priorMetrics.extra());
                Object provenance = validated.metrics().extra().get("userStatementProvenance");
                if (provenance != null) extra.put("userStatementProvenance", provenance);
                Map<String, Object> progress = new LinkedHashMap<>(WikiGenerationProgress.raw(entry));
                progress.put("state", "completed"); progress.remove("reason"); progress.remove("nextRetryAt");
                extra.put("generationProgress", progress);
                extra.put("repairRevision", before.revision() + 1);
                WikiEntry.WikiMetrics metrics = new WikiEntry.WikiMetrics(priorMetrics.activeSeconds(), priorMetrics.afkSeconds(),
                        priorMetrics.switchCount(), priorMetrics.topApps(), extra);
                if (!updateStatusIfRevision(entry.id(), before.revision(), WikiStatus.SUMMARIZED, validated.summary(),
                        validated.primaryTask(), validated.taskSegments(), metrics, entry.sourceEntryIds(), "user-repair", "summary-repair-v1",
                        entry.factBuilderVersion(), entry.projectorVersion(), entry.sourceCoverage()))
                    throw new WikiRepairException("WIKI_REPAIR_TARGET_CONFLICT");
                long afterRevision = revision(entry.id());
                Instant committedAt = draft.updatedAt();
                for (WikiRepairService.Statement statement : draft.statements()) {
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO wiki_repair_statements VALUES (?,?,?,?,?,?)")) {
                        ps.setString(1, statement.id()); ps.setString(2, statement.targetId()); ps.setString(3, draft.id());
                        ps.setLong(4, statement.draftRevision()); ps.setString(5, repairJson(statement));
                        ps.setString(6, statement.confirmedAt().toString()); ps.executeUpdate();
                    }
                }
                Map<String, Object> audit = new LinkedHashMap<>();
                audit.put("before", entry); audit.put("after", versionedEntry(entry.id()).entry());
                audit.put("statements", draft.statements()); audit.put("fingerprint", draft.fingerprint());
                audit.put("draftRevision", draft.draftRevision());
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO wiki_repair_revisions VALUES (?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, java.util.UUID.randomUUID().toString()); ps.setString(2, draft.id()); ps.setString(3, entry.id());
                    ps.setString(4, idempotencyKey); ps.setLong(5, before.revision()); ps.setLong(6, afterRevision);
                    ps.setString(7, repairJson(audit)); ps.setString(8, committedAt.toString()); ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE wiki_semantic_documents SET status='STALE' WHERE entry_id=?")) {
                    ps.setString(1, entry.id()); ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO wiki_repair_followups VALUES (?,?,?,?)")) {
                    ps.setString(1, draft.id()); ps.setString(2, entry.id()); ps.setLong(3, afterRevision);
                    ps.setString(4, committedAt.toString()); ps.executeUpdate();
                }
                WikiRepairService.Draft committed = new WikiRepairService.Draft(draft.id(), draft.sessionId(), draft.targetId(),
                        draft.targetRevision(), draft.draftRevision(), "committed", new WikiRepairService.Candidate(validated.summary(),
                        validated.primaryTask(), validated.taskSegments()), draft.statements(), List.of(), draft.fingerprint(),
                        draft.createdAt(), committedAt, afterRevision);
                saveRepairDraft(committed, draft.draftRevision());
                tx.execute("COMMIT");
                return committed;
            } catch (Exception error) {
                tx.execute("ROLLBACK");
                if (error instanceof WikiRepairException repair) throw repair;
                throw new IllegalStateException("WIKI_REPAIR_COMMIT_FAILED", error);
            }
        } catch (SQLException error) { throw new IllegalStateException("WIKI_REPAIR_COMMIT_FAILED", error); }
    }

    public synchronized int deleteUncommittedRepairs(String sessionId) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM wiki_repair_drafts WHERE session_id=? AND state<>'committed'")) {
            ps.setString(1, sessionId); return ps.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException("WIKI_REPAIR_DELETE_FAILED", error); }
    }

    public synchronized List<RepairFollowup> pendingRepairFollowups(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid follow-up limit");
        List<RepairFollowup> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT repair_id,target_id,revision FROM wiki_repair_followups ORDER BY created_at LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rows = ps.executeQuery()) { while (rows.next()) result.add(new RepairFollowup(rows.getString(1), rows.getString(2), rows.getLong(3))); }
            return List.copyOf(result);
        } catch (SQLException error) { throw new IllegalStateException("WIKI_REPAIR_FOLLOWUP_READ_FAILED", error); }
    }

    public synchronized void completeRepairFollowup(String repairId, long revision) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM wiki_repair_followups WHERE repair_id=? AND revision=?")) {
            ps.setString(1, repairId); ps.setLong(2, revision); ps.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException("WIKI_REPAIR_FOLLOWUP_WRITE_FAILED", error); }
    }

    private static String repairJson(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("WIKI_REPAIR_SERIALIZATION_FAILED", error); }
    }

    public synchronized Instant historyBefore() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT history_before FROM wiki_statistics_progress WHERE version=?")) {
            ps.setString(1, CURRENT);
            try (ResultSet rows = ps.executeQuery()) { return rows.next() ? Instant.parse(rows.getString(1)) : null; }
        } catch (SQLException error) { throw new IllegalStateException("Cannot read Wiki discovery progress", error); }
    }

    public synchronized void saveHistoryBefore(Instant cursor) {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO wiki_statistics_progress VALUES (?,?)")) {
            ps.setString(1, CURRENT); ps.setString(2, cursor.toString()); ps.executeUpdate();
        } catch (SQLException error) { throw new IllegalStateException("Cannot save Wiki discovery progress", error); }
    }

    public synchronized boolean isCurrentEntry(String id) {
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

    public synchronized void upsertSemanticDoc(SemanticDoc doc) {
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

    public synchronized List<SemanticDoc> findPendingSemanticDocs(int limit) {
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

    public synchronized List<SemanticDoc> findRetryableSemanticDocs(int limit) {
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

    public synchronized boolean markSemanticDocIndexed(String docId, String expectedTextHash) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE wiki_semantic_documents SET status='INDEXED',updated_at=?,indexed_at=?,last_error=NULL,next_retry_at=NULL WHERE doc_id=? AND text_hash=? AND status<>'STALE'")) {
            String now = Instant.now().toString(); ps.setString(1, now); ps.setString(2, now);
            ps.setString(3, docId); ps.setString(4, expectedTextHash); return ps.executeUpdate() == 1;
        } catch (SQLException error) { throw new IllegalStateException("WIKI_SEMANTIC_INDEX_WRITE_FAILED", error); }
    }

    public synchronized boolean markSemanticDocFailed(String docId, String expectedTextHash, String error, Instant nextRetryAt) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE wiki_semantic_documents SET status='FAILED',retry_count=retry_count+1,next_retry_at=?,last_error=?,updated_at=? WHERE doc_id=? AND text_hash=? AND status<>'STALE'")) {
            ps.setString(1, nextRetryAt == null ? null : nextRetryAt.toString()); ps.setString(2, error);
            ps.setString(3, Instant.now().toString()); ps.setString(4, docId); ps.setString(5, expectedTextHash);
            return ps.executeUpdate() == 1;
        } catch (SQLException failure) { throw new IllegalStateException("WIKI_SEMANTIC_FAILURE_WRITE_FAILED", failure); }
    }

    public synchronized void markSemanticDocIndexed(String docId) {
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

    public synchronized void markSemanticDocFailed(String docId, String error, Instant nextRetryAt) {
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

    public synchronized void markSemanticDocsStale(String embeddingModel, int embeddingDimensions) {
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

    public synchronized List<WikiEntry> findSummarizedWithoutSemanticDocs(int limit) {
        List<WikiEntry> results = new ArrayList<>();
        String sql = """
            SELECT * FROM wiki_entries w WHERE %s AND w.status = ?
            AND COALESCE(w.model, '') <> 'local'
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

    public synchronized List<SemanticDoc> findSemanticDocsByEntry(String entryId) {
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

    public synchronized void upsert(WikiEntry entry) {
        String sql = """
            INSERT INTO wiki_entries
            (id, level, period_start, period_end, timezone, status, summary, primary_task,
             task_segments_json, metrics_json, source_entry_ids_json, model, prompt_version,
             retry_count, next_retry_at, last_error, created_at, updated_at, summarized_at,
             fact_builder_version, projector_version, source_coverage_json, statistics_version, calendar_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO UPDATE SET
              id=excluded.id,level=excluded.level,period_start=excluded.period_start,period_end=excluded.period_end,
              timezone=excluded.timezone,status=excluded.status,summary=excluded.summary,primary_task=excluded.primary_task,
              task_segments_json=excluded.task_segments_json,metrics_json=excluded.metrics_json,
              source_entry_ids_json=excluded.source_entry_ids_json,model=excluded.model,prompt_version=excluded.prompt_version,
              retry_count=excluded.retry_count,next_retry_at=excluded.next_retry_at,last_error=excluded.last_error,
              updated_at=excluded.updated_at,summarized_at=excluded.summarized_at,fact_builder_version=excluded.fact_builder_version,
              projector_version=excluded.projector_version,source_coverage_json=excluded.source_coverage_json,
              statistics_version=excluded.statistics_version,calendar_version=excluded.calendar_version
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

    public synchronized void updateStatus(String id, WikiStatus status, String summary, String primaryTask,
                              List<WikiEntry.TaskSegment> taskSegments, WikiEntry.WikiMetrics metrics,
                              List<String> sourceEntryIds, String model, String promptVersion) {
        updateStatus(id, status, summary, primaryTask, taskSegments, metrics,
                sourceEntryIds, model, promptVersion, null, null, Map.of());
    }

    public synchronized void updateStatus(String id, WikiStatus status, String summary, String primaryTask,
                             List<WikiEntry.TaskSegment> taskSegments, WikiEntry.WikiMetrics metrics,
                             List<String> sourceEntryIds, String model, String promptVersion,
                             String factBuilderVersion, String projectorVersion,
                             Map<String, WikiEntry.SourceCoverage> sourceCoverage) {
        updateStatusIfRevision(id, revision(id), status, summary, primaryTask, taskSegments, metrics,
                sourceEntryIds, model, promptVersion, factBuilderVersion, projectorVersion, sourceCoverage);
    }

    public synchronized boolean updateStatusIfRevision(String id, long expectedRevision, WikiStatus status,
                             String summary, String primaryTask, List<WikiEntry.TaskSegment> taskSegments,
                             WikiEntry.WikiMetrics metrics, List<String> sourceEntryIds, String model, String promptVersion,
                             String factBuilderVersion, String projectorVersion,
                             Map<String, WikiEntry.SourceCoverage> sourceCoverage) {
        String sql = """
            UPDATE wiki_entries SET status=?, summary=?, primary_task=?,
            task_segments_json=?, metrics_json=?, source_entry_ids_json=?,
            model=?, prompt_version=?, updated_at=?, summarized_at=?,
            fact_builder_version=?, projector_version=?, source_coverage_json=?,next_retry_at=NULL,last_error=NULL
            WHERE id=? AND revision=?
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
            ps.setLong(15, expectedRevision);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update wiki entry status", e);
        }
    }

    public synchronized void markFailed(String id, String error, Instant nextRetryAt) {
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

    public synchronized void markSkipped(String id, String reason) {
        markSkippedIfRevision(id, revision(id), reason);
    }

    public synchronized boolean markSkippedIfRevision(String id, long expectedRevision, String reason) {
        String sql = "UPDATE wiki_entries SET status=?, last_error=?, updated_at=? WHERE id=? AND revision=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, WikiStatus.SKIPPED.name()); ps.setString(2, reason);
            ps.setString(3, Instant.now().toString()); ps.setString(4, id); ps.setLong(5, expectedRevision);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { throw new IllegalStateException("WIKI_SKIP_WRITE_FAILED", e); }
    }

    /** Local privacy rewrite for already summarized text. Does not call a model or change status. */
    public synchronized int redactPrivacy(WikiPrivacyPolicy privacy, int limit) {
        if (privacy == null || limit <= 0) return 0;
        List<WikiEntry> candidates = new ArrayList<>();
        String sql = "SELECT * FROM wiki_entries WHERE " + CURRENT
                + " AND status=? ORDER BY updated_at ASC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, WikiStatus.SUMMARIZED.name());
            ps.setInt(2, Math.max(limit * 4, limit));
            ResultSet rs = ps.executeQuery();
            while (rs.next() && candidates.size() < limit) {
                WikiEntry entry = mapEntry(rs);
                if (entry.metrics() != null && entry.metrics().extra() != null
                        && entry.metrics().extra().containsKey("privacyRedactedAt")) continue;
                candidates.add(entry);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find summaries for privacy redaction", e);
        }
        int changed = 0;
        for (WikiEntry entry : candidates) {
            if (rewritePrivacy(entry, privacy)) changed++;
        }
        return changed;
    }

    private boolean rewritePrivacy(WikiEntry entry, WikiPrivacyPolicy privacy) {
        String summary = privacy.redactText(entry.summary());
        String primary = privacy.redactText(entry.primaryTask());
        List<WikiEntry.TaskSegment> segments = entry.taskSegments().stream()
                .map(segment -> new WikiEntry.TaskSegment(
                        privacy.redactText(segment.title()),
                        privacy.redactText(segment.summary()),
                        segment.evidence(), segment.apps(), segment.confidence(),
                        segment.evidenceFactIds(), segment.claimType()))
                .toList();
        boolean same = Objects.equals(summary, entry.summary())
                && Objects.equals(primary, entry.primaryTask())
                && segments.equals(entry.taskSegments());
        Map<String, Object> extra = new LinkedHashMap<>(
                entry.metrics() == null || entry.metrics().extra() == null
                        ? Map.of() : entry.metrics().extra());
        extra.put("privacyRedactedAt", Instant.now().toString());
        WikiEntry.WikiMetrics metrics = entry.metrics() == null
                ? new WikiEntry.WikiMetrics(0, 0, 0, List.of(), extra)
                : new WikiEntry.WikiMetrics(entry.metrics().activeSeconds(), entry.metrics().afkSeconds(),
                        entry.metrics().switchCount(), entry.metrics().topApps(), extra);
        String sql = """
            UPDATE wiki_entries SET summary=?, primary_task=?, task_segments_json=?,
            metrics_json=?, updated_at=? WHERE id=?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, summary);
            ps.setString(2, primary);
            ps.setString(3, toJson(segments));
            ps.setString(4, toJson(metrics));
            ps.setString(5, Instant.now().toString());
            ps.setString(6, entry.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to redact wiki privacy", e);
        }
        return !same;
    }

    /** One atomic update; budget/config waits do not increment the ordinary retry counter. */
    public synchronized void markGenerationFailure(WikiEntry entry, WikiFactBuilder.WikiFacts facts,
                                      Map<String, Object> progress, Instant nextRetry, boolean countFailure) {
        VersionedEntry current = versionedEntry(entry.id());
        if (current == null || !current.entry().updatedAt().equals(entry.updatedAt())
                || current.entry().status() == WikiStatus.SUMMARIZED || current.entry().status() == WikiStatus.SKIPPED) return;
        markGenerationFailureIfRevision(entry, current.revision(), facts, progress, nextRetry, countFailure);
    }

    public synchronized boolean markGenerationFailureIfRevision(WikiEntry entry, long expectedRevision, WikiFactBuilder.WikiFacts facts,
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
                + "metrics_json=?,source_coverage_json=?,updated_at=? WHERE id=? AND revision=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, countFailure ? WikiStatus.FAILED.name() : WikiStatus.PENDING.name());
            ps.setInt(2, countFailure ? 1 : 0);
            ps.setString(3, nextRetry == null ? null : nextRetry.toString());
            ps.setString(4, String.valueOf(progress.get("reason")));
            ps.setString(5, toJson(metrics));
            ps.setString(6, toJson(facts != null ? facts.sourceCoverage() : entry.sourceCoverage()));
            ps.setString(7, Instant.now().toString()); ps.setString(8, entry.id());
            ps.setLong(9, expectedRevision);
            return ps.executeUpdate() == 1;
        } catch (SQLException error) { throw new IllegalStateException("WIKI_PROGRESS_WRITE_FAILED", error); }
    }

    public synchronized List<WikiEntry> findGenerationPaused() {
        List<WikiEntry> result = new ArrayList<>();
        String sql = "SELECT * FROM wiki_entries WHERE " + CURRENT
                + " AND status IN ('PENDING','FAILED') AND json_valid(metrics_json)"
                + " AND json_extract(metrics_json,'$.extra.generationProgress.state') IN ('period_budget','configuration','input')";
        try (PreparedStatement statement = conn.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(mapEntry(rows));
        } catch (SQLException error) { throw new IllegalStateException("WIKI_PROGRESS_READ_FAILED", error); }
        return result;
    }

    public synchronized void resumeGeneration(WikiEntry entry) {
        Map<String, Object> progress = new java.util.LinkedHashMap<>(WikiGenerationProgress.raw(entry));
        progress.put("state", "queued"); progress.put("reason", "WIKI_GENERATION_QUEUED");
        progress.remove("nextRetryAt");
        markGenerationFailure(entry, null, progress, null, false);
    }

    public synchronized List<WikiEntry> query(Instant start, Instant end, WikiLevel level) {
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

    /** Read-only, consistent and bounded snapshot for local semantic projections. */
    public synchronized List<WikiEntry> ontologySnapshot(int limit) {
        if (limit < 1 || limit > 10001) throw new IllegalArgumentException("Invalid snapshot limit");
        var properties = new java.util.Properties();
        properties.setProperty("open_mode", "1");
        try (var source = DriverManager.getConnection(conn.getMetaData().getURL(), properties);
             var query = source.prepareStatement("SELECT * FROM wiki_entries WHERE " + CURRENT
                     + " AND status='SUMMARIZED' ORDER BY period_start DESC,id LIMIT ?")) {
            query.setInt(1, limit);
            query.setQueryTimeout(15);
            List<WikiEntry> entries = new ArrayList<>();
            try (var rows = query.executeQuery()) { while (rows.next()) entries.add(mapEntry(rows)); }
            return List.copyOf(entries);
        } catch (SQLException e) { throw new IllegalStateException("Wiki ontology snapshot unavailable", e); }
    }
    public synchronized void exportSnapshot(Instant start, Instant end, WikiLevel level, int maxRows,
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

    public synchronized List<WikiEntry> findPending(WikiLevel level, int limit) {
        return findPending(level, limit, Instant.now());
    }

    public synchronized List<WikiEntry> findPending(WikiLevel level, int limit, Instant now) {
        return findByStatusAndLevel(WikiStatus.PENDING, level, limit, now);
    }

    public synchronized List<WikiEntry> findRetryable(int limit) {
        return findRetryable(limit, Instant.now());
    }

    public synchronized List<WikiEntry> findRetryable(int limit, Instant now) {
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

    public synchronized long countByStatus(WikiLevel level, WikiStatus status) {
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
    public synchronized void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            log.warn("Failed to close WikiStore connection", e);
        }
    }
}
