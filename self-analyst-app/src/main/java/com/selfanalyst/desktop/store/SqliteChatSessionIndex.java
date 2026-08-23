package com.selfanalyst.desktop.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/** Rebuildable SQLite projection for bounded row-level chat metadata writes. */
final class SqliteChatSessionIndex implements ChatSessionIndex {

    private static final int SCHEMA_VERSION = 1;
    private final Path database;

    SqliteChatSessionIndex(Path database) {
        this.database = database.toAbsolutePath().normalize();
    }

    @Override
    public boolean exists() {
        return Files.isRegularFile(database);
    }

    @Override
    public void validate() {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet ignored = statement.executeQuery("SELECT id FROM sessions LIMIT 1")) {
            String rawGeneration = metadata(connection, "generation");
            if (rawGeneration == null) throw new SQLException("Missing chat index generation");
            Long.parseLong(rawGeneration);
            String active = metadata(connection, "active_session_id");
            if (active != null && (!ChatSessionStore.isGeneratedSessionId(active)
                    || !contains(connection, active))) {
                throw new SQLException("Invalid chat index active session");
            }
        } catch (SQLException | RuntimeException error) {
            throw failure("validate", error);
        }
    }

    @Override
    public ChatSessionStore.Index load() {
        try (Connection connection = open()) {
            ChatSessionStore.Index index = new ChatSessionStore.Index();
            index.activeSessionId = metadata(connection, "active_session_id");
            String generation = metadata(connection, "generation");
            index.generation = generation != null ? Long.parseLong(generation) : 0L;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id,title,created_at,updated_at,source,context_label,memory_policy,
                           summary,last_message_preview,message_count
                    FROM sessions ORDER BY updated_at DESC, id DESC
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) index.sessions.add(readMeta(rows));
            }
            if (index.activeSessionId != null && !contains(connection, index.activeSessionId)) {
                index.activeSessionId = null;
            }
            return index;
        } catch (SQLException | RuntimeException error) {
            throw failure("load", error);
        }
    }

    @Override
    public String activeSessionId() {
        try (Connection connection = open()) {
            return metadata(connection, "active_session_id");
        } catch (SQLException error) {
            throw failure("read active session", error);
        }
    }

    @Override
    public long generation() {
        try (Connection connection = open()) {
            String value = metadata(connection, "generation");
            return value != null ? Long.parseLong(value) : 0L;
        } catch (SQLException | RuntimeException error) {
            throw failure("read generation", error);
        }
    }

    @Override
    public boolean contains(String sessionId) {
        try (Connection connection = open()) {
            return contains(connection, sessionId);
        } catch (SQLException error) {
            throw failure("read session metadata", error);
        }
    }

    @Override
    public String newestSessionIdExcluding(String sessionId) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id FROM sessions WHERE id <> ?
                     ORDER BY updated_at DESC, id DESC LIMIT 1
                     """)) {
            statement.setString(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        } catch (SQLException error) {
            throw failure("select replacement active session", error);
        }
    }

    @Override
    public java.util.List<ChatSessionStore.SessionMeta> page(
            int limit,
            String normalizedQuery,
            String cursorUpdatedAt,
            String cursorId) {
        String match = normalizedQuery.isEmpty() ? "" : """
                 AND (LOWER(COALESCE(title,'')) LIKE ? ESCAPE '\\'
                   OR LOWER(COALESCE(last_message_preview,'')) LIKE ? ESCAPE '\\'
                   OR LOWER(COALESCE(summary,'')) LIKE ? ESCAPE '\\')
                """;
        String cursor = cursorId == null ? "" : """
                 AND (updated_at < ? OR (updated_at = ? AND id < ?))
                """;
        String pattern = normalizedQuery.isEmpty() ? null
                : "%" + escapeLike(normalizedQuery) + "%";
        try (Connection connection = open()) {
            if (cursorId != null) {
                String anchorSql = "SELECT 1 FROM sessions WHERE id = ? AND updated_at = ?" + match;
                try (PreparedStatement anchor = connection.prepareStatement(anchorSql)) {
                    int parameter = 1;
                    anchor.setString(parameter++, cursorId);
                    anchor.setString(parameter++, cursorUpdatedAt);
                    parameter = bindPattern(anchor, parameter, pattern);
                    try (ResultSet row = anchor.executeQuery()) {
                        if (!row.next()) {
                            throw new IllegalArgumentException("Cursor is stale or invalid");
                        }
                    }
                }
            }
            String sql = """
                    SELECT id,title,created_at,updated_at,source,context_label,memory_policy,
                           summary,last_message_preview,message_count
                    FROM sessions WHERE 1=1
                    """ + match + cursor + " ORDER BY updated_at DESC, id DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameter = 1;
                parameter = bindPattern(statement, parameter, pattern);
                if (cursorId != null) {
                    statement.setString(parameter++, cursorUpdatedAt);
                    statement.setString(parameter++, cursorUpdatedAt);
                    statement.setString(parameter++, cursorId);
                }
                statement.setInt(parameter, limit);
                ArrayList<ChatSessionStore.SessionMeta> result = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) result.add(readMeta(rows));
                }
                return result;
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (SQLException error) {
            throw failure("page chat index", error);
        }
    }

    @Override
    public void replaceAll(ChatSessionStore.Index index) {
        transaction("replace projection", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM sessions");
            }
            for (ChatSessionStore.SessionMeta meta : index.sessions) upsert(connection, meta);
            setMetadata(connection, "generation", Long.toString(index.generation));
            setMetadata(connection, "active_session_id", index.activeSessionId);
        });
    }

    @Override
    public void upsert(ChatSessionStore.SessionMeta meta, String activeSessionId) {
        transaction("upsert projection row", connection -> {
            upsert(connection, meta);
            incrementGeneration(connection);
            setMetadata(connection, "active_session_id", activeSessionId);
        });
    }

    @Override
    public void delete(String sessionId, String activeSessionId) {
        transaction("delete projection row", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM sessions WHERE id = ?")) {
                statement.setString(1, sessionId);
                statement.executeUpdate();
            }
            incrementGeneration(connection);
            setMetadata(connection, "active_session_id", activeSessionId);
        });
    }

    @Override
    public void setActive(String activeSessionId) {
        transaction("set active session", connection ->
                setMetadata(connection, "active_session_id", activeSessionId));
    }

    @Override
    public void resetCorrupt() {
        if (!Files.exists(database)) return;
        Path backup = database.resolveSibling(database.getFileName()
                + ".corrupt-" + UUID.randomUUID());
        movePreserving(database, backup);
        for (String suffix : new String[]{"-journal", "-wal", "-shm"}) {
            Path sidecar = database.resolveSibling(database.getFileName() + suffix);
            if (Files.exists(sidecar)) {
                movePreserving(sidecar,
                        backup.resolveSibling(backup.getFileName() + suffix));
            }
        }
    }

    private static void movePreserving(Path source, Path target) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            try {
                Files.move(source, target);
            } catch (IOException moveFailure) {
                moveFailure.addSuppressed(atomicFailure);
                throw new IllegalStateException("Cannot preserve corrupt chat index", moveFailure);
            }
        }
    }

    private Connection open() throws SQLException {
        try {
            Files.createDirectories(database.getParent());
        } catch (IOException error) {
            throw new SQLException("Cannot create chat index directory", error);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA journal_mode=DELETE");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS sessions (
                          id TEXT PRIMARY KEY NOT NULL CHECK(length(id) = 32),
                          title TEXT,
                          created_at TEXT,
                          updated_at TEXT,
                          source TEXT,
                          context_label TEXT,
                          memory_policy TEXT,
                          summary TEXT,
                          last_message_preview TEXT,
                          message_count INTEGER NOT NULL DEFAULT 0 CHECK(message_count >= 0)
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_chat_sessions_order "
                        + "ON sessions(updated_at DESC, id DESC)");
            }
            String version = metadata(connection, "schema_version");
            if (version == null) {
                setMetadata(connection, "schema_version", Integer.toString(SCHEMA_VERSION));
                setMetadata(connection, "generation", "0");
            } else if (!Integer.toString(SCHEMA_VERSION).equals(version)) {
                throw new SQLException("Unsupported chat index schema version: " + version);
            }
            return connection;
        } catch (SQLException | RuntimeException error) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                error.addSuppressed(closeFailure);
            }
            throw error;
        }
    }

    private void transaction(String operation, SqlWork work) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (Exception error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    error.addSuppressed(rollbackFailure);
                }
                throw error;
            }
        } catch (Exception error) {
            throw failure(operation, error);
        }
    }

    private static void upsert(Connection connection, ChatSessionStore.SessionMeta meta)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sessions(id,title,created_at,updated_at,source,context_label,
                                     memory_policy,summary,last_message_preview,message_count)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                  title=excluded.title, created_at=excluded.created_at,
                  updated_at=excluded.updated_at, source=excluded.source,
                  context_label=excluded.context_label, memory_policy=excluded.memory_policy,
                  summary=excluded.summary, last_message_preview=excluded.last_message_preview,
                  message_count=excluded.message_count
                """)) {
            statement.setString(1, meta.id);
            statement.setString(2, meta.title);
            statement.setString(3, text(meta.createdAt));
            statement.setString(4, text(meta.updatedAt));
            statement.setString(5, meta.source);
            statement.setString(6, meta.contextLabel);
            statement.setString(7, meta.memoryPolicy);
            statement.setString(8, meta.summary);
            statement.setString(9, meta.lastMessagePreview);
            statement.setInt(10, meta.messageCount);
            statement.executeUpdate();
        }
    }

    private static ChatSessionStore.SessionMeta readMeta(ResultSet row) throws SQLException {
        ChatSessionStore.SessionMeta meta = new ChatSessionStore.SessionMeta();
        meta.id = row.getString("id");
        meta.title = row.getString("title");
        meta.createdAt = instant(row.getString("created_at"));
        meta.updatedAt = instant(row.getString("updated_at"));
        meta.source = row.getString("source");
        meta.contextLabel = row.getString("context_label");
        meta.memoryPolicy = row.getString("memory_policy");
        meta.summary = row.getString("summary");
        meta.lastMessagePreview = row.getString("last_message_preview");
        meta.messageCount = row.getInt("message_count");
        return meta;
    }

    private static boolean contains(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sessions WHERE id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private static String metadata(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM metadata WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }

    private static void setMetadata(Connection connection, String key, String value)
            throws SQLException {
        if (value == null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM metadata WHERE key = ?")) {
                statement.setString(1, key);
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO metadata(key,value) VALUES(?,?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                """)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private static void incrementGeneration(Connection connection) throws SQLException {
        String raw = metadata(connection, "generation");
        long current = raw != null ? Long.parseLong(raw) : 0L;
        long next = current == Long.MAX_VALUE ? 1L : current + 1L;
        setMetadata(connection, "generation", Long.toString(next));
    }

    private static int bindPattern(PreparedStatement statement, int parameter, String pattern)
            throws SQLException {
        if (pattern == null) return parameter;
        statement.setString(parameter++, pattern);
        statement.setString(parameter++, pattern);
        statement.setString(parameter++, pattern);
        return parameter;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static String text(Instant instant) {
        return instant != null ? instant.toString() : "";
    }

    private static Instant instant(String text) {
        return text == null || text.isEmpty() ? null : Instant.parse(text);
    }

    private static IllegalStateException failure(String operation, Throwable error) {
        return new IllegalStateException("Failed to " + operation + " in chat index", error);
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws Exception;
    }
}
