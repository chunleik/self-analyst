package com.selfanalyst.desktop.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.events.store.ContentEventPolicy;
import com.selfanalyst.events.store.Database;
import com.selfanalyst.content.capture.ContextTitleCandidate;
import com.selfanalyst.content.capture.ContextTitleExtractor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** One-way migration from persisted window body text to title-only content events. */
public final class ContentEventV2Migration {

    public static final String MIGRATION_ID = "content-events-title-only-v2";
    private static final int PAGE_SIZE = 500;
    private static final Pattern SAFE_BUCKET_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Set<String> RESERVED_DATABASE_STEMS = Set.of("aw", "buckets");
    private static final Set<String> CONTEXT_KINDS =
            Set.of("chat", "article", "document", "page", "unknown");
    private static final Set<String> TITLE_SOURCES =
            Set.of("window", "uia_document", "uia_context", "ocr_title");
    private static final Set<String> CONFIDENCES = Set.of("high", "medium", "low");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> DATA_TYPE = new TypeReference<>() {};
    private static final String STATE_TABLE = "content_event_migration_state";

    private ContentEventV2Migration() {}

    public static Result migrate(Database database) {
        return migrate(database, () -> {});
    }

    static Result migrate(Database database, Runnable beforeCompact) {
        Connection connection = database.metaConnection();
        boolean oldAutoCommit;
        try {
            oldAutoCommit = connection.getAutoCommit();
            ensureStateTable(connection);
            boolean migrationRecorded = migrationRecorded(connection);
            MigrationState migrationState = readMigrationState(connection);
            long highWater = migrationRecorded ? migrationState.lastEventId() : 0L;
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA secure_delete=ON");
            }

            long cursor = highWater;
            int scanned = 0;
            int sanitized = 0;
            while (true) {
                List<Row> page = readPage(connection, cursor);
                if (page.isEmpty()) break;
                for (Row row : page) {
                    cursor = row.id();
                    scanned++;
                    String sanitizedJson = sanitize(row);
                    if (!sanitizedJson.equals(row.dataJson())) {
                        updateRow(connection, row.id(), sanitizedJson);
                        sanitized++;
                    }
                }
            }
            long scanWater = Math.max(cursor, globalMaxEventId(connection));

            if (sanitized > 0) {
                markCompactionPending(connection, scanWater);
            }
            connection.commit();
            connection.setAutoCommit(true);
            boolean needsCompaction = sanitized > 0
                    || migrationState.needsCompaction()
                    || !migrationRecorded;
            if (needsCompaction) {
                beforeCompact.run();
                compact(connection);
            }
            removeLegacyPersistentCopies(database, connection);
            long successfulHighWater = Math.max(scanWater, migrationState.pendingEventId());
            recordMigration(connection, successfulHighWater);
            connection.setAutoCommit(oldAutoCommit);
            return new Result(scanned, sanitized, true, null);
        } catch (Exception error) {
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (Exception rollbackError) {
                error.addSuppressed(rollbackError);
            }
            throw new IllegalStateException("Failed to migrate content events to title-only v2", error);
        }
    }

    private static List<Row> readPage(Connection connection, long afterId) throws Exception {
        String sql = """
                SELECT e.id, e.bucket_id, e.datastr, e.app
                FROM events e
                LEFT JOIN buckets b ON b.id = e.bucket_id
                WHERE e.id > ?
                  AND (e.bucket_id LIKE ? OR e.bucket_id LIKE ?
                       OR b.client = ? OR b.client = ?)
                ORDER BY e.id
                LIMIT ?
                """;
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, afterId);
            statement.setString(2, ContentEventPolicy.CONTENT_BUCKET_PREFIX + "%");
            statement.setString(3, ContentEventPolicy.LEGACY_CONTENT_BUCKET_PREFIX + "%");
            statement.setString(4, ContentEventPolicy.CONTENT_CLIENT);
            statement.setString(5, ContentEventPolicy.LEGACY_CONTENT_CLIENT);
            statement.setInt(6, PAGE_SIZE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Row(
                            result.getLong("id"),
                            result.getString("bucket_id"),
                            result.getString("datastr"),
                            result.getString("app")));
                }
            }
        }
        return rows;
    }

    private static long globalMaxEventId(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM events")) {
            return result.next() ? Math.max(0L, result.getLong(1)) : 0L;
        }
    }

    private static String sanitize(Row row) throws Exception {
        Map<String, Object> oldData;
        try {
            oldData = MAPPER.readValue(row.dataJson(), DATA_TYPE);
        } catch (Exception ignored) {
            oldData = Map.of();
        }

        String app = safeSingleLine(stringValue(oldData.get("app")), 260);
        if (app.isBlank()) app = row.app() != null ? row.app() : "";
        app = safeSingleLine(app, 260);
        String title = safeSingleLine(stringValue(oldData.get("title")), 1024);
        ContextTitleCandidate candidate = existingCandidate(oldData);
        if (candidate == null) {
            candidate = ContextTitleExtractor.extractCandidate(
                    app, stringValue(oldData.get("text_content")));
        }

        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("schema_version", 2);
        clean.put("app", app);
        clean.put("title", title);
        if (candidate != null) {
            clean.put("context_title", candidate.value());
            clean.put("context_kind", candidate.kind());
            clean.put("title_confidence", candidate.confidence());
            clean.put("title_source", candidate.source());
        } else {
            clean.put("title_source", "window");
        }
        copyNonNegativeNumber(oldData, clean, "uia_chars");
        copyNonNegativeNumber(oldData, clean, "ocr_chars");
        ContentEventPolicy.validate(row.bucketId(), ContentEventPolicy.CONTENT_CLIENT, clean);
        return MAPPER.writeValueAsString(clean);
    }

    private static ContextTitleCandidate existingCandidate(Map<String, Object> data) {
        String value = stringValue(data.get("context_title"));
        if (!ContextTitleExtractor.isValidTitleCandidate(value)) return null;
        String kind = defaultToken(data.get("context_kind"), "unknown");
        String source = defaultToken(data.get("title_source"), "uia_context");
        String confidence = defaultToken(data.get("title_confidence"), "medium");
        if (!CONTEXT_KINDS.contains(kind)
                || !TITLE_SOURCES.contains(source)
                || !CONFIDENCES.contains(confidence)) {
            return null;
        }
        try {
            return new ContextTitleCandidate(
                    value, kind, source, confidence);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String defaultToken(Object value, String fallback) {
        String text = stringValue(value);
        return text.isBlank() ? fallback : text;
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private static String safeSingleLine(String value, int maxCodePoints) {
        if (value == null || value.contains("\n") || value.contains("\r")
                || value.codePointCount(0, value.length()) > maxCodePoints) {
            return "";
        }
        return value;
    }

    private static void copyNonNegativeNumber(
            Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value instanceof Number number && number.longValue() >= 0) {
            target.put(key, number.longValue());
        }
    }

    private static void updateRow(Connection connection, long id, String dataJson) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE events SET datastr = ?, app = "
                        + "CASE WHEN json_type(?, '$.app') = 'text' "
                        + "THEN json_extract(?, '$.app') ELSE '' END WHERE id = ?")) {
            statement.setString(1, dataJson);
            statement.setString(2, dataJson);
            statement.setString(3, dataJson);
            statement.setLong(4, id);
            statement.executeUpdate();
        }
    }

    private static void recordMigration(Connection connection, long highWater) throws Exception {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT OR IGNORE INTO schema_migrations(id, completed_at)
                    VALUES (?, ?)
                    """)) {
                statement.setString(1, MIGRATION_ID);
                statement.setString(2, Instant.now().toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO content_event_migration_state(
                        id, last_event_id, pending_event_id, needs_compaction)
                    VALUES (1, ?, 0, 0)
                    ON CONFLICT(id) DO UPDATE SET
                        last_event_id = excluded.last_event_id,
                        pending_event_id = 0,
                        needs_compaction = 0
                    """)) {
                statement.setLong(1, highWater);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void ensureStateTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + STATE_TABLE + " ("
                    + "id INTEGER PRIMARY KEY CHECK(id = 1),"
                    + "last_event_id INTEGER NOT NULL DEFAULT 0,"
                    + "pending_event_id INTEGER NOT NULL DEFAULT 0,"
                    + "needs_compaction INTEGER NOT NULL DEFAULT 0)");
            if (!stateColumnExists(connection, "pending_event_id")) {
                statement.execute("ALTER TABLE " + STATE_TABLE
                        + " ADD COLUMN pending_event_id INTEGER NOT NULL DEFAULT 0");
            }
            if (!stateColumnExists(connection, "needs_compaction")) {
                statement.execute("ALTER TABLE " + STATE_TABLE
                        + " ADD COLUMN needs_compaction INTEGER NOT NULL DEFAULT 0");
            }
        }
    }

    private static boolean stateColumnExists(Connection connection, String column)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + STATE_TABLE + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) return true;
            }
            return false;
        }
    }

    private static MigrationState readMigrationState(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT last_event_id, pending_event_id, needs_compaction FROM "
                             + STATE_TABLE + " WHERE id = 1")) {
            return result.next()
                    ? new MigrationState(
                            Math.max(0L, result.getLong(1)),
                            Math.max(0L, result.getLong(2)),
                            result.getInt(3) != 0)
                    : new MigrationState(0L, 0L, false);
        }
    }

    private static void markCompactionPending(Connection connection, long pendingEventId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO content_event_migration_state(
                    id, last_event_id, pending_event_id, needs_compaction)
                VALUES (1, 0, ?, 1)
                ON CONFLICT(id) DO UPDATE SET
                    pending_event_id = MAX(pending_event_id, excluded.pending_event_id),
                    needs_compaction = 1
                """)) {
            statement.setLong(1, pendingEventId);
            statement.executeUpdate();
        }
    }

    private static boolean migrationRecorded(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE id = ?")) {
            statement.setString(1, MIGRATION_ID);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void compact(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            statement.execute("VACUUM");
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    private static void removeLegacyPersistentCopies(
            Database database, Connection connection) throws Exception {
        List<String> contentBucketIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM buckets WHERE id LIKE ? OR id LIKE ? OR client = ? OR client = ?")) {
            statement.setString(1, ContentEventPolicy.CONTENT_BUCKET_PREFIX + "%");
            statement.setString(2, ContentEventPolicy.LEGACY_CONTENT_BUCKET_PREFIX + "%");
            statement.setString(3, ContentEventPolicy.CONTENT_CLIENT);
            statement.setString(4, ContentEventPolicy.LEGACY_CONTENT_CLIENT);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) contentBucketIds.add(result.getString(1));
            }
        }
        Path dataDir = database.dataDir().toAbsolutePath().normalize();
        for (String bucketId : contentBucketIds) {
            database.closeBucket(bucketId);
            if (bucketId == null || !SAFE_BUCKET_ID.matcher(bucketId).matches()
                    || RESERVED_DATABASE_STEMS.contains(bucketId.toLowerCase())) {
                throw new IllegalStateException("Unsafe legacy content bucket id");
            }
            Path legacyPath = dataDir.resolve(bucketId + ".db").normalize();
            deleteDatabaseFiles(dataDir, legacyPath);
        }

        try (var files = Files.list(dataDir)) {
            List<Path> staleCopies = files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.equals("events.db.migrating")
                                || name.startsWith("events.db.migrating-")
                                || name.startsWith("events.db.pre-legacy-migration-");
                    })
                    .toList();
            for (Path path : staleCopies) deleteDatabaseFiles(dataDir, path);
        }
    }

    private static void deleteDatabaseFiles(Path dataDir, Path path) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(dataDir)
                || !dataDir.equals(normalized.getParent())
                || normalized.equals(dataDir.resolve("events.db"))
                || normalized.equals(dataDir.resolve("buckets.db"))) {
            throw new IllegalStateException("Refusing to delete database outside legacy cleanup scope");
        }
        Files.deleteIfExists(normalized);
        Files.deleteIfExists(normalized.resolveSibling(normalized.getFileName() + "-wal"));
        Files.deleteIfExists(normalized.resolveSibling(normalized.getFileName() + "-shm"));
    }

    private record Row(long id, String bucketId, String dataJson, String app) {}

    private record MigrationState(
            long lastEventId, long pendingEventId, boolean needsCompaction) {}

    public record Result(int scanned, int sanitized, boolean ready, String error) {
        public static Result failed(String error) {
            return new Result(0, 0, false, error);
        }
    }
}
