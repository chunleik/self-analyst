package com.selfanalyst.desktop.store;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite-backed persistent store for desktop chat sessions
 * (SPEC-CSS-*): a single {@code {memoryDir}/chat-sessions/chat.db} (WAL,
 * synchronous=FULL, foreign keys on) is the one authoritative copy of both
 * session bodies and list/search metadata. Every mutation commits in one
 * SQLite transaction (SPEC-CSS-API-001), so the application-level
 * shard/projection/DIRTY-state machinery of the previous implementation is
 * gone; crash recovery is SQLite's WAL. Search uses an FTS5 trigram index
 * over title/summary/lastMessagePreview with a LIKE fallback
 * (SPEC-CSS-DEC-005). The public API is unchanged from the sharded
 * implementation (SPEC-CSS-DEC-003).
 * <p>
 * Connections are opened per operation (try-with-resources), so a store
 * instance holds no database resources between calls; {@link #close()} only
 * releases the optional writer lease.
 */
public class ChatSessionStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Single-session message cap (SPEC-CSP-API-009b). */
    static final int MAX_MESSAGES = 200;
    /** Maximum messages accepted by one create/append mutation. */
    static final int MAX_MESSAGE_BATCH = 200;
    /** Single-message content length cap (SPEC-CSP-API-009c). */
    static final int MAX_CONTENT = 20000;
    static final int MAX_TITLE = 256;
    static final int MAX_SOURCE = 32;
    static final int MAX_CONTEXT_LABEL = 256;
    static final int MAX_SUMMARY = 512;
    static final int MAX_ERROR = 2048;
    static final int MAX_CONTEXT_SNAPSHOT_BYTES = 64 * 1024;
    static final int MAX_SUGGESTED_TASKS = 20;
    static final int MAX_SUGGESTED_TASK_BYTES = 8 * 1024;
    static final int MAX_SUGGESTED_TASKS_BYTES = 64 * 1024;
    static final int MAX_OPAQUE_DEPTH = 16;
    static final int MAX_OPAQUE_NODES = 2048;
    static final int MAX_CONTAINER_ITEMS = 256;
    static final int MAX_JSON_KEY = 128;
    static final int MAX_JSON_STRING = 8192;
    /** Hard UTF-8 bound for one persisted session (formerly one shard). */
    static final int MAX_SHARD_BYTES = 16 * 1024 * 1024;
    /** Length of the derived {@code lastMessagePreview}. */
    private static final int PREVIEW_LEN = 80;
    /** Server-generated IDs are the only valid session/message ids. */
    private static final Pattern GENERATED_SESSION_ID = Pattern.compile("^[a-f0-9]{32}$");
    private static final Pattern GENERATED_MESSAGE_ID = Pattern.compile("^[a-f0-9]{12}$");
    private static final Pattern LEGACY_SHARD_NAME = Pattern.compile("^[a-f0-9]{32}\\.json$");
    private static final Pattern LEGACY_DELETION_NAME = Pattern.compile(
            "^delete-[a-f0-9]{32}\\.state$");
    private static final int SCHEMA_VERSION = 2;
    /** Queries shorter than this use the LIKE fallback instead of FTS5 trigram. */
    private static final int FTS_MIN_QUERY_CODEPOINTS = 3;
    private static final Comparator<SessionMeta> META_ORDER = Comparator
            .comparing((SessionMeta meta) -> meta.updatedAt != null
                    ? meta.updatedAt : Instant.EPOCH)
            .reversed()
            .thenComparing(meta -> meta.id != null ? meta.id : "", Comparator.reverseOrder());

    private final Path dir;
    private final Path dbFile;
    private final FileChannel writerLockChannel;
    private final FileLock writerLock;
    private final boolean ftsEnabled;
    private boolean pendingTranscriptsRecovered;
    private boolean recoveringDeletions;
    private boolean closed;

    public ChatSessionStore(Path memoryDir) {
        this(memoryDir, null, null);
    }

    private ChatSessionStore(Path memoryDir, FileChannel writerLockChannel, FileLock writerLock) {
        this.dir = memoryDir.resolve("chat-sessions").toAbsolutePath().normalize();
        this.dbFile = dir.resolve("chat.db");
        this.writerLockChannel = writerLockChannel;
        this.writerLock = writerLock;
        try {
            Files.createDirectories(dir);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot create chat-sessions directory", error);
        }
        migrateLegacyIfNeeded();
        this.ftsEnabled = openWithCorruptionRecovery();
    }

    /** Open the production store with a process-wide exclusive writer lease. */
    public static ChatSessionStore openExclusive(Path memoryDir) {
        Path storeDir = memoryDir.resolve("chat-sessions").toAbsolutePath().normalize();
        FileChannel channel = null;
        FileLock lock = null;
        try {
            Files.createDirectories(storeDir);
            channel = FileChannel.open(storeDir.resolve(".writer.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                throw new IllegalStateException(
                        "Another SelfAnalyst process is already writing " + storeDir);
            }
            return new ChatSessionStore(memoryDir, channel, lock);
        } catch (IOException | OverlappingFileLockException error) {
            releaseQuietly(lock, channel);
            throw new IllegalStateException(
                    "Cannot acquire the chat-session writer lock for " + storeDir, error);
        } catch (RuntimeException error) {
            releaseQuietly(lock, channel);
            throw error;
        }
    }

    private static void releaseQuietly(FileLock lock, FileChannel channel) {
        if (lock != null) {
            try { lock.release(); } catch (IOException ignored) { }
        }
        if (channel != null) {
            try { channel.close(); } catch (IOException ignored) { }
        }
    }

    public static boolean isValidSessionId(String id) {
        return isGeneratedSessionId(id);
    }

    public static boolean isGeneratedSessionId(String id) {
        return id != null && GENERATED_SESSION_ID.matcher(id).matches();
    }

    public static boolean isGeneratedMessageId(String id) {
        return id != null && GENERATED_MESSAGE_ID.matcher(id).matches();
    }

    private static void requireValidSessionId(String id) {
        if (!isValidSessionId(id)) {
            throw new IllegalArgumentException("Invalid chat session id");
        }
    }

    // ── Database bootstrap ───────────────────────────────────────

    /** Initialize the schema and return whether FTS5 trigram is available. */
    private boolean openWithCorruptionRecovery() {
        try {
            return initializeDatabase(dbFile);
        } catch (IllegalStateException corrupt) {
            log.warn("chat.db is unreadable ({}); preserving a backup", corrupt.getMessage());
            preserveCorruptDatabase();
            Path legacyDir = dir.resolve("legacy");
            if (Files.isDirectory(legacyDir) && containsLegacyShards(legacyDir)) {
                log.warn("Rebuilding chat.db from legacy shard backup");
                Path migrating = dir.resolve("chat.db.migrating");
                importLegacyDirectory(legacyDir, migrating);
                movePreserving(migrating, dbFile);
            } else {
                log.error("No legacy backup available; starting with an empty chat.db");
            }
            return initializeDatabase(dbFile);
        }
    }

    private static boolean initializeDatabase(Path database) {
        try (Connection conn = openConnection(database)) {
            return initializeSchema(conn);
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot initialize chat database " + database, error);
        }
    }

    private void preserveCorruptDatabase() {
        if (!Files.exists(dbFile)) return;
        Path backup = dbFile.resolveSibling(dbFile.getFileName()
                + ".corrupt-" + UUID.randomUUID());
        movePreserving(dbFile, backup);
        for (String suffix : new String[]{"-journal", "-wal", "-shm"}) {
            Path sidecar = dbFile.resolveSibling(dbFile.getFileName() + suffix);
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
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                moveFailure.addSuppressed(atomicFailure);
                throw new IllegalStateException("Cannot preserve " + source.getFileName(), moveFailure);
            }
        }
    }

    private static Connection openConnection(Path database) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = conn.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException error) {
            try {
                conn.close();
            } catch (SQLException closeFailure) {
                error.addSuppressed(closeFailure);
            }
            throw error;
        }
        return conn;
    }

    private Connection connect() {
        try {
            return openConnection(dbFile);
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot open chat database " + dbFile, error);
        }
    }

    /**
     * Create tables/FTS/triggers and validate the schema version. Returns
     * whether the FTS5 trigram index is available (SPEC-CSS-DEC-005).
     */
    private static boolean initializeSchema(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS metadata(
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sessions(
                      id TEXT PRIMARY KEY NOT NULL CHECK(length(id) = 32),
                      title TEXT,
                      created_at TEXT,
                      updated_at TEXT,
                      source TEXT,
                      context_label TEXT,
                      context_snapshot TEXT,
                      memory_policy TEXT,
                      summary TEXT,
                      last_message_preview TEXT,
                      message_count INTEGER NOT NULL DEFAULT 0 CHECK(message_count >= 0)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_chat_sessions_order "
                    + "ON sessions(updated_at DESC, id DESC)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS messages(
                      id TEXT PRIMARY KEY NOT NULL,
                      session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                      seq INTEGER NOT NULL,
                      role TEXT NOT NULL,
                      content TEXT,
                      created_at TEXT,
                      status TEXT,
                      error TEXT,
                      context_snapshot TEXT,
                      suggested_tasks TEXT,
                      UNIQUE(session_id, seq)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS pending_deletions(
                      session_id TEXT PRIMARY KEY NOT NULL,
                      requested_at TEXT NOT NULL
                    )
                    """);
            String version = metadata(conn, "schema_version");
            if (version == null) {
                setMetadata(conn, "schema_version", Integer.toString(SCHEMA_VERSION));
                if (metadata(conn, "generation") == null) {
                    setMetadata(conn, "generation", "0");
                }
            } else if (!Integer.toString(SCHEMA_VERSION).equals(version)) {
                throw new IllegalStateException(
                        "Unsupported chat database schema version: " + version);
            }
        }
        return initializeFts(conn);
    }

    private static boolean initializeFts(Connection conn) {
        try (Statement statement = conn.createStatement()) {
            statement.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS sessions_fts USING fts5(
                      title, summary, last_message_preview,
                      content='sessions', content_rowid='rowid',
                      tokenize='trigram case_sensitive 0'
                    )
                    """);
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS sessions_ai AFTER INSERT ON sessions BEGIN
                      INSERT INTO sessions_fts(rowid, title, summary, last_message_preview)
                      VALUES (new.rowid, new.title, new.summary, new.last_message_preview);
                    END
                    """);
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS sessions_ad AFTER DELETE ON sessions BEGIN
                      INSERT INTO sessions_fts(sessions_fts, rowid, title, summary,
                                             last_message_preview)
                      VALUES('delete', old.rowid, old.title, old.summary,
                             old.last_message_preview);
                    END
                    """);
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS sessions_au AFTER UPDATE ON sessions BEGIN
                      INSERT INTO sessions_fts(sessions_fts, rowid, title, summary,
                                             last_message_preview)
                      VALUES('delete', old.rowid, old.title, old.summary,
                             old.last_message_preview);
                      INSERT INTO sessions_fts(rowid, title, summary, last_message_preview)
                      VALUES (new.rowid, new.title, new.summary, new.last_message_preview);
                    END
                    """);
            // Sync any rows inserted before the triggers existed (e.g. imports).
            statement.execute("INSERT INTO sessions_fts(sessions_fts) VALUES('rebuild')");
            return true;
        } catch (SQLException error) {
            log.warn("FTS5 trigram unavailable ({}); falling back to LIKE search",
                    error.getMessage());
            return false;
        }
    }

    // ── Legacy migration (SPEC-CSS-DEC-006 / SPEC-CSS-API-003) ───

    private void migrateLegacyIfNeeded() {
        Path migrating = dir.resolve("chat.db.migrating");
        try {
            Files.deleteIfExists(migrating);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot remove stale chat.db.migrating", error);
        }
        if (Files.exists(dbFile)) {
            // A crash between the atomic rename and the archival step leaves
            // legacy files behind; finish that cleanup idempotently.
            if (containsLegacyShards(dir) || Files.exists(dir.resolve("index.db"))
                    || Files.exists(dir.resolve("index.json"))) {
                moveLegacyArtifacts(dir.resolve("legacy"));
            }
            return;
        }
        if (!containsLegacyShards(dir) && !Files.exists(dir.resolve("index.db"))
                && !Files.exists(dir.resolve("index.json"))) {
            return; // fresh install
        }
        log.info("Migrating legacy chat-sessions storage into chat.db");
        importLegacyDirectory(dir, migrating);
        movePreserving(migrating, dbFile);
        moveLegacyArtifacts(dir.resolve("legacy"));
    }

    private static boolean containsLegacyShards(Path directory) {
        if (!Files.isDirectory(directory)) return false;
        try (var paths = Files.list(directory)) {
            return paths.anyMatch(path -> LEGACY_SHARD_NAME.matcher(
                    path.getFileName().toString()).matches());
        } catch (IOException error) {
            throw new IllegalStateException("Cannot scan " + directory, error);
        }
    }

    /**
     * Build a complete chat database at {@code target} from the legacy shard
     * layout in {@code sourceDir}: every valid shard is imported in one
     * transaction together with the active pointer (old index.db, then
     * index.json, then newest session) and any deletion tombstones.
     */
    private void importLegacyDirectory(Path sourceDir, Path target) {
        List<Session> sessions = new ArrayList<>();
        if (Files.isDirectory(sourceDir)) {
            List<Path> shards;
            try (var paths = Files.list(sourceDir)) {
                shards = paths.filter(path -> LEGACY_SHARD_NAME.matcher(
                        path.getFileName().toString()).matches()).sorted().toList();
            } catch (IOException error) {
                throw new IllegalStateException("Cannot scan legacy shards in " + sourceDir, error);
            }
            for (Path shard : shards) {
                try {
                    Session session = MAPPER.readValue(shard.toFile(), Session.class);
                    String fileName = shard.getFileName().toString();
                    String fileId = fileName.substring(0, fileName.length() - 5);
                    if (session != null && fileId.equals(session.id)
                            && isGeneratedSessionId(session.id)) {
                        normalizeSessionForRead(session);
                        // The messages table requires non-null unique ids;
                        // tolerate legacy/malformed shards that lack them.
                        if (session.messages != null) {
                            session.messages.removeIf(java.util.Objects::isNull);
                            for (Message message : session.messages) {
                                if (!isGeneratedMessageId(message.id)) {
                                    message.id = UUID.randomUUID().toString()
                                            .replace("-", "").substring(0, 12);
                                }
                                if (message.createdAt == null) {
                                    message.createdAt = Instant.now();
                                }
                            }
                        }
                        sessions.add(session);
                    } else {
                        log.warn("Skipping legacy shard with mismatched/invalid id: {}", fileName);
                    }
                } catch (IOException | RuntimeException error) {
                    log.warn("Skipping unreadable legacy shard {}: {}",
                            shard.getFileName(), error.getMessage());
                }
            }
        }
        String active = readLegacyActivePointer(sourceDir, sessions);
        Set<String> tombstones = new TreeSet<>();
        if (Files.isDirectory(sourceDir)) {
            try (var paths = Files.list(sourceDir)) {
                paths.filter(path -> LEGACY_DELETION_NAME.matcher(
                        path.getFileName().toString()).matches())
                        .forEach(path -> tombstones.add(path.getFileName().toString()
                                .substring("delete-".length(), "delete-".length() + 32)));
            } catch (IOException error) {
                throw new IllegalStateException("Cannot scan legacy tombstones", error);
            }
        }
        try (Connection conn = openConnection(target)) {
            initializeSchema(conn);
            conn.setAutoCommit(false);
            try {
                for (Session session : sessions) {
                    writeSessionRows(conn, session);
                }
                setMetadata(conn, "active_session_id", active);
                setMetadata(conn, "generation",
                        Long.toString(freshProjectionGeneration(0)));
                try (PreparedStatement statement = conn.prepareStatement(
                        "INSERT OR IGNORE INTO pending_deletions(session_id, requested_at)"
                                + " VALUES(?,?)")) {
                    for (String tombstone : tombstones) {
                        statement.setString(1, tombstone);
                        statement.setString(2, Instant.now().toString());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                conn.commit();
            } catch (Exception error) {
                rollbackQuietly(conn, error);
                throw error;
            } finally {
                conn.setAutoCommit(true);
            }
            // Fold the WAL back so the target is one self-contained file.
            try (Statement statement = conn.createStatement()) {
                statement.execute("PRAGMA journal_mode=DELETE");
            }
        } catch (Exception error) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException deleteFailure) {
                error.addSuppressed(deleteFailure);
            }
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Failed to import legacy chat sessions", error);
        }
    }

    private String readLegacyActivePointer(Path sourceDir, List<Session> sessions) {
        String active = null;
        Path legacyDb = sourceDir.resolve("index.db");
        if (Files.isRegularFile(legacyDb)) {
            try (Connection legacy = DriverManager.getConnection("jdbc:sqlite:" + legacyDb);
                 PreparedStatement statement = legacy.prepareStatement(
                         "SELECT value FROM metadata WHERE key = 'active_session_id'");
                 ResultSet row = statement.executeQuery()) {
                if (row.next()) active = row.getString(1);
            } catch (SQLException | RuntimeException error) {
                log.warn("Legacy index.db unreadable for active pointer: {}", error.getMessage());
            }
        }
        if (!isGeneratedSessionId(active)) {
            Path legacyJson = sourceDir.resolve("index.json");
            if (Files.isRegularFile(legacyJson)) {
                try {
                    JsonNode root = MAPPER.readTree(legacyJson.toFile());
                    String candidate = root != null ? root.path("activeSessionId").asText(null) : null;
                    if (isGeneratedSessionId(candidate)) active = candidate;
                } catch (IOException | RuntimeException error) {
                    log.warn("Legacy index.json unreadable for active pointer: {}",
                            error.getMessage());
                }
            }
        }
        String finalActive = isGeneratedSessionId(active) ? active : null;
        boolean resolves = sessions.stream().anyMatch(s -> s.id.equals(finalActive));
        if (resolves) return finalActive;
        return sessions.stream()
                .max(Comparator.comparing(s -> s.updatedAt != null
                        ? s.updatedAt : Instant.EPOCH))
                .map(s -> s.id)
                .orElse(null);
    }

    /** Move all legacy storage files into {@code legacyDir} (created if needed). */
    private void moveLegacyArtifacts(Path legacyDir) {
        List<Path> artifacts = new ArrayList<>();
        try (var paths = Files.list(dir)) {
            paths.forEach(path -> {
                String name = path.getFileName().toString();
                if (LEGACY_SHARD_NAME.matcher(name).matches()
                        || LEGACY_DELETION_NAME.matcher(name).matches()
                        || name.equals("index.db") || name.equals("index.json")
                        || name.equals("index.state") || name.equals("index.db.ready")
                        || name.startsWith("index.db-")) {
                    artifacts.add(path);
                }
            });
        } catch (IOException error) {
            throw new IllegalStateException("Cannot list legacy chat artifacts", error);
        }
        if (artifacts.isEmpty()) return;
        try {
            Files.createDirectories(legacyDir);
            for (Path artifact : artifacts) {
                Files.move(artifact, legacyDir.resolve(artifact.getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot archive legacy chat artifacts", error);
        }
    }

    private static long freshProjectionGeneration(long previous) {
        long wallClock = System.currentTimeMillis();
        if (previous == Long.MAX_VALUE) return Math.max(1L, wallClock);
        return Math.max(previous + 1L, wallClock);
    }

    // ── SQL helpers ──────────────────────────────────────────────

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("ChatSessionStore is closed");
    }

    private void ensureReady() {
        ensureOpen();
        if (pendingTranscriptsRecovered || recoveringDeletions) return;
        pendingTranscriptsRecovered = true;
        // The transcript half of durable deletion intents is idempotently
        // completed on first access; the intent row itself is kept until the
        // coordinator confirms the AgentState half (SPEC-CSS-DEC-004).
        for (String id : pendingDeletionIds()) {
            try {
                deletePendingTranscript(id);
            } catch (RuntimeException error) {
                pendingTranscriptsRecovered = false;
                throw error;
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection conn) throws Exception;
    }

    /** Run {@code work} inside one SQLite transaction (SPEC-CSS-API-001). */
    private void transaction(String operation, SqlWork work) {
        ensureOpen();
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                work.run(conn);
                conn.commit();
            } catch (Exception error) {
                rollbackQuietly(conn, error);
                throw error;
            }
        } catch (Exception error) {
            if (error instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalStateException("Failed to " + operation, error);
        }
    }

    private static void rollbackQuietly(Connection conn, Throwable original) {
        try {
            conn.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static String metadata(Connection conn, String key) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT value FROM metadata WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }

    private static void setMetadata(Connection conn, String key, String value)
            throws SQLException {
        if (value == null) {
            try (PreparedStatement statement = conn.prepareStatement(
                    "DELETE FROM metadata WHERE key = ?")) {
                statement.setString(1, key);
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement statement = conn.prepareStatement("""
                INSERT INTO metadata(key,value) VALUES(?,?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                """)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private static void incrementGeneration(Connection conn) throws SQLException {
        String raw = metadata(conn, "generation");
        long current = raw != null ? Long.parseLong(raw) : 0L;
        long next = current == Long.MAX_VALUE ? 1L : current + 1L;
        setMetadata(conn, "generation", Long.toString(next));
    }

    private static String text(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private static Instant instant(String text) {
        return text == null || text.isEmpty() ? null : Instant.parse(text);
    }

    private static String jsonOf(Object value) {
        if (value == null) return null;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException error) {
            throw new IllegalArgumentException("Chat payload is not serializable", error);
        }
    }

    private static Object parseJson(String json) {
        if (json == null) return null;
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (IOException error) {
            throw new IllegalStateException("Stored chat payload is not parseable", error);
        }
    }

    private static List<Object> parseJsonList(String json) {
        if (json == null) return null;
        try {
            @SuppressWarnings("unchecked")
            List<Object> parsed = MAPPER.readValue(json, List.class);
            return parsed;
        } catch (IOException error) {
            throw new IllegalStateException("Stored chat payload is not parseable", error);
        }
    }

    private static SessionMeta readMeta(ResultSet row) throws SQLException {
        SessionMeta meta = new SessionMeta();
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

    private static final String META_COLUMNS = """
            id,title,created_at,updated_at,source,context_label,memory_policy,
            summary,last_message_preview,message_count
            """;

    /** Write the session metadata row + all message rows (caller holds tx). */
    private static void writeSessionRows(Connection conn, Session session) throws SQLException {
        SessionMeta meta = toMeta(session);
        try (PreparedStatement statement = conn.prepareStatement("""
                INSERT INTO sessions(id,title,created_at,updated_at,source,context_label,
                                     context_snapshot,memory_policy,summary,
                                     last_message_preview,message_count)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                  title=excluded.title, created_at=excluded.created_at,
                  updated_at=excluded.updated_at, source=excluded.source,
                  context_label=excluded.context_label,
                  context_snapshot=excluded.context_snapshot,
                  memory_policy=excluded.memory_policy, summary=excluded.summary,
                  last_message_preview=excluded.last_message_preview,
                  message_count=excluded.message_count
                """)) {
            statement.setString(1, session.id);
            statement.setString(2, session.title);
            statement.setString(3, text(session.createdAt));
            statement.setString(4, text(session.updatedAt));
            statement.setString(5, session.source);
            statement.setString(6, session.contextLabel);
            statement.setString(7, jsonOf(session.contextSnapshot));
            statement.setString(8, session.memoryPolicy);
            statement.setString(9, session.summary);
            statement.setString(10, meta.lastMessagePreview);
            statement.setInt(11, meta.messageCount);
            statement.executeUpdate();
        }
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM messages WHERE session_id = ?")) {
            delete.setString(1, session.id);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO messages(id,session_id,seq,role,content,created_at,status,error,
                                     context_snapshot,suggested_tasks)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """)) {
            List<Message> messages = session.messages != null ? session.messages : List.of();
            for (int i = 0; i < messages.size(); i++) {
                Message message = messages.get(i);
                insert.setString(1, message.id);
                insert.setString(2, session.id);
                insert.setInt(3, i);
                insert.setString(4, message.role);
                insert.setString(5, message.content);
                insert.setString(6, text(message.createdAt));
                insert.setString(7, message.status);
                insert.setString(8, message.error);
                insert.setString(9, jsonOf(message.contextSnapshot));
                insert.setString(10, jsonOf(message.suggestedTasks));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static Session readSessionRow(Connection conn, String id) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("""
                SELECT id,title,created_at,updated_at,source,context_label,context_snapshot,
                       memory_policy,summary
                FROM sessions WHERE id = ?
                """)) {
            statement.setString(1, id);
            Session session;
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                session = new Session();
                session.id = row.getString("id");
                session.title = row.getString("title");
                session.createdAt = instant(row.getString("created_at"));
                session.updatedAt = instant(row.getString("updated_at"));
                session.source = row.getString("source");
                session.contextLabel = row.getString("context_label");
                session.contextSnapshot = parseJson(row.getString("context_snapshot"));
                session.memoryPolicy = row.getString("memory_policy");
                session.summary = row.getString("summary");
            }
            session.messages = new ArrayList<>();
            try (PreparedStatement messages = conn.prepareStatement("""
                    SELECT id,role,content,created_at,status,error,context_snapshot,
                           suggested_tasks
                    FROM messages WHERE session_id = ? ORDER BY seq ASC
                    """)) {
                messages.setString(1, id);
                try (ResultSet rows = messages.executeQuery()) {
                    while (rows.next()) {
                        Message message = new Message();
                        message.id = rows.getString("id");
                        message.role = rows.getString("role");
                        message.content = rows.getString("content");
                        message.createdAt = instant(rows.getString("created_at"));
                        message.status = rows.getString("status");
                        message.error = rows.getString("error");
                        message.contextSnapshot = parseJson(rows.getString("context_snapshot"));
                        message.suggestedTasks = parseJsonList(rows.getString("suggested_tasks"));
                        session.messages.add(message);
                    }
                }
            }
            return session;
        }
    }

    /** Persist a normalized session + generation/active updates in one tx. */
    private void commitSessionMutation(Session session, boolean makeActive) {
        normalizeSessionForWrite(session);
        transaction("write chat session " + session.id, conn -> {
            writeSessionRows(conn, session);
            incrementGeneration(conn);
            setMetadata(conn, "active_session_id",
                    makeActive ? session.id : metadata(conn, "active_session_id"));
        });
    }

    private void commitDeleteMutation(String id, String activeAfter) {
        transaction("delete chat session " + id, conn -> {
            try (PreparedStatement statement = conn.prepareStatement(
                    "DELETE FROM sessions WHERE id = ?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            incrementGeneration(conn);
            setMetadata(conn, "active_session_id", activeAfter);
        });
    }

    // ── Deletion saga (SPEC-CSS-DEC-004) ─────────────────────────

    /** Durably commits a monotonic delete request before either backing store is changed. */
    public synchronized void beginDeletion(String id) {
        ensureReady();
        requireValidSessionId(id);
        transaction("record chat deletion intent", conn -> {
            try (PreparedStatement statement = conn.prepareStatement(
                    "INSERT OR IGNORE INTO pending_deletions(session_id, requested_at)"
                            + " VALUES(?,?)")) {
                statement.setString(1, id);
                statement.setString(2, Instant.now().toString());
                statement.executeUpdate();
            }
        });
    }

    /** Pending intents are retained until AgentState and transcript deletion both complete. */
    public synchronized Set<String> pendingDeletionIds() {
        ensureOpen();
        try (Connection conn = connect();
             PreparedStatement statement = conn.prepareStatement(
                     "SELECT session_id FROM pending_deletions ORDER BY session_id");
             ResultSet rows = statement.executeQuery()) {
            Set<String> ids = new TreeSet<>();
            while (rows.next()) ids.add(rows.getString(1));
            return java.util.Collections.unmodifiableSet(ids);
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to read chat deletion intents", error);
        }
    }

    synchronized Set<String> deletionIntentIdsForRecovery() {
        ensureOpen();
        return pendingDeletionIds();
    }

    /**
     * Completes the transcript half of a durable deletion intent. The operation is idempotent:
     * a missing session still scrubs stale metadata and returns a successful delete result.
     */
    public synchronized DeleteResult deletePendingTranscript(String id) {
        ensureOpen();
        requireValidSessionId(id);
        if (!pendingDeletionIds().contains(id)) {
            throw new IllegalStateException("No pending deletion intent for session " + id);
        }
        boolean previousRecovery = recoveringDeletions;
        recoveringDeletions = true;
        try (Connection conn = connect()) {
            String activeBefore = metadata(conn, "active_session_id");
            boolean present;
            try (PreparedStatement statement = conn.prepareStatement(
                    "SELECT 1 FROM sessions WHERE id = ?")) {
                statement.setString(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    present = row.next();
                }
            }
            String activeAfter = activeBefore;
            if (id.equals(activeBefore)) {
                try (PreparedStatement statement = conn.prepareStatement("""
                        SELECT id FROM sessions WHERE id <> ?
                        ORDER BY updated_at DESC, id DESC LIMIT 1
                        """)) {
                    statement.setString(1, id);
                    try (ResultSet row = statement.executeQuery()) {
                        activeAfter = row.next() ? row.getString(1) : null;
                    }
                }
            }
            if (present || id.equals(activeBefore)) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement statement = conn.prepareStatement(
                            "DELETE FROM sessions WHERE id = ?")) {
                        statement.setString(1, id);
                        statement.executeUpdate();
                    }
                    incrementGeneration(conn);
                    setMetadata(conn, "active_session_id", activeAfter);
                    conn.commit();
                } catch (Exception error) {
                    rollbackQuietly(conn, error);
                    throw error;
                }
            }
            return new DeleteResult(true, id, activeAfter);
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "Failed to complete pending transcript deletion " + id, error);
        } finally {
            recoveringDeletions = previousRecovery;
        }
    }

    /** Best-effort intent cleanup after both authoritative stores confirm deletion. */
    public synchronized boolean finishDeletion(String id) {
        ensureOpen();
        requireValidSessionId(id);
        try {
            transaction("clear chat deletion intent", conn -> {
                try (PreparedStatement statement = conn.prepareStatement(
                        "DELETE FROM pending_deletions WHERE session_id = ?")) {
                    statement.setString(1, id);
                    statement.executeUpdate();
                }
            });
            return true;
        } catch (RuntimeException error) {
            log.warn("Chat session {} is fully deleted, but its intent cleanup failed: {}",
                    id, error.getMessage());
            return false;
        }
    }

    // ── Read paths ───────────────────────────────────────────────

    /** Index with {@code sessions} sorted by {@code updatedAt} descending (SPEC-CSP-API-001). */
    public synchronized Index listIndex() {
        ensureReady();
        try (Connection conn = connect()) {
            Index index = new Index();
            index.activeSessionId = metadata(conn, "active_session_id");
            String generation = metadata(conn, "generation");
            index.generation = generation != null ? Long.parseLong(generation) : 0L;
            try (PreparedStatement statement = conn.prepareStatement(
                         "SELECT " + META_COLUMNS + "FROM sessions "
                                 + "ORDER BY updated_at DESC, id DESC");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) index.sessions.add(readMeta(rows));
            }
            if (index.activeSessionId != null && !containsSession(conn, index.activeSessionId)) {
                index.activeSessionId = null;
            }
            normalizeIndexMemoryPolicy(index);
            index.sessions.sort(META_ORDER);
            return index;
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to list chat sessions", error);
        }
    }

    private static boolean containsSession(Connection conn, String sessionId)
            throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT 1 FROM sessions WHERE id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    /** Optional cursor-paged metadata view; the legacy no-parameter list remains unchanged. */
    public synchronized IndexPage listIndexPage(int limit, String cursor, String query) {
        ensureReady();
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        String normalizedQuery = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (normalizedQuery.length() > 200) {
            throw new IllegalArgumentException("q must be at most 200 characters");
        }
        try (Connection conn = connect()) {
            String rawGeneration = metadata(conn, "generation");
            long generation = rawGeneration != null ? Long.parseLong(rawGeneration) : 0L;
            PageCursor decoded = null;
            if (cursor != null) {
                decoded = decodeCursor(cursor);
                if (!normalizedQuery.equals(decoded.query)) {
                    throw new IllegalArgumentException("Cursor does not belong to this query");
                }
                if (decoded.generation.longValue() != generation) {
                    throw new IllegalArgumentException("Cursor is stale; reload the first page");
                }
            }
            List<SessionMeta> page = new ArrayList<>(page(conn,
                    limit + 1, normalizedQuery,
                    decoded != null ? decoded.updatedAt : null,
                    decoded != null ? decoded.id : null));
            boolean hasMore = page.size() > limit;
            if (hasMore) page.removeLast();
            String nextCursor = hasMore && !page.isEmpty()
                    ? encodeCursor(page.getLast(), normalizedQuery, generation) : null;
            for (SessionMeta meta : page) normalizeMeta(meta);
            return new IndexPage(metadata(conn, "active_session_id"), page, nextCursor, hasMore);
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to page chat sessions", error);
        }
    }

    private List<SessionMeta> page(
            Connection conn, int limit, String normalizedQuery,
            String cursorUpdatedAt, String cursorId) throws SQLException {
        boolean useFts = ftsEnabled && !normalizedQuery.isEmpty()
                && normalizedQuery.codePointCount(0, normalizedQuery.length())
                >= FTS_MIN_QUERY_CODEPOINTS;
        String match;
        int matchParams;
        if (normalizedQuery.isEmpty()) {
            match = "";
            matchParams = 0;
        } else if (useFts) {
            match = """
                     AND rowid IN (SELECT rowid FROM sessions_fts WHERE sessions_fts MATCH ?)
                    """;
            matchParams = 1;
        } else {
            match = likeMatch();
            matchParams = 3;
        }
        try {
            return pageWithMatch(conn, limit, normalizedQuery, cursorUpdatedAt, cursorId,
                    match, matchParams, useFts);
        } catch (SQLException ftsFailure) {
            if (!useFts) throw ftsFailure;
            log.warn("FTS search failed ({}); falling back to LIKE", ftsFailure.getMessage());
            return pageWithMatch(conn, limit, normalizedQuery, cursorUpdatedAt, cursorId,
                    likeMatch(), 3, false);
        }
    }

    private static String likeMatch() {
        return """
                 AND (LOWER(COALESCE(title,'')) LIKE ? ESCAPE '\\'
                   OR LOWER(COALESCE(last_message_preview,'')) LIKE ? ESCAPE '\\'
                   OR LOWER(COALESCE(summary,'')) LIKE ? ESCAPE '\\')
                """;
    }

    private static List<SessionMeta> pageWithMatch(
            Connection conn, int limit, String normalizedQuery,
            String cursorUpdatedAt, String cursorId,
            String match, int matchParams, boolean fts) throws SQLException {
        if (cursorId != null) {
            String anchorSql = "SELECT 1 FROM sessions WHERE id = ? AND updated_at = ?" + match;
            try (PreparedStatement anchor = conn.prepareStatement(anchorSql)) {
                int parameter = 1;
                anchor.setString(parameter++, cursorId);
                anchor.setString(parameter++, cursorUpdatedAt);
                bindMatch(anchor, parameter, normalizedQuery, matchParams, fts);
                try (ResultSet row = anchor.executeQuery()) {
                    if (!row.next()) {
                        throw new IllegalArgumentException("Cursor is stale or invalid");
                    }
                }
            }
        }
        String cursorClause = cursorId == null ? "" : """
                 AND (updated_at < ? OR (updated_at = ? AND id < ?))
                """;
        String sql = "SELECT " + META_COLUMNS + "FROM sessions WHERE 1=1"
                + match + cursorClause + " ORDER BY updated_at DESC, id DESC LIMIT ?";
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            int parameter = 1;
            parameter = bindMatch(statement, parameter, normalizedQuery, matchParams, fts);
            if (cursorId != null) {
                statement.setString(parameter++, cursorUpdatedAt);
                statement.setString(parameter++, cursorUpdatedAt);
                statement.setString(parameter++, cursorId);
            }
            statement.setInt(parameter, limit);
            ArrayList<SessionMeta> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(readMeta(rows));
            }
            return result;
        }
    }

    private static int bindMatch(PreparedStatement statement, int parameter,
                                 String normalizedQuery, int matchParams, boolean fts)
            throws SQLException {
        if (matchParams == 0) return parameter;
        if (fts) {
            statement.setString(parameter++, ftsPhrase(normalizedQuery));
            return parameter;
        }
        String pattern = "%" + escapeLike(normalizedQuery) + "%";
        for (int i = 0; i < matchParams; i++) {
            statement.setString(parameter++, pattern);
        }
        return parameter;
    }

    /** Quote a raw query as one FTS5 phrase (substring match under trigram). */
    private static String ftsPhrase(String query) {
        return "\"" + query.replace("\"", "\"\"") + "\"";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static String encodeCursor(SessionMeta meta, String query, long generation) {
        PageCursor cursor = new PageCursor();
        cursor.id = meta.id;
        cursor.updatedAt = meta.updatedAt != null ? meta.updatedAt.toString() : "";
        cursor.query = query;
        cursor.generation = generation;
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MAPPER.writeValueAsBytes(cursor));
        } catch (IOException error) {
            throw new IllegalStateException("Failed to encode chat cursor", error);
        }
    }

    private static PageCursor decodeCursor(String cursor) {
        if (cursor.length() > 2048) throw new IllegalArgumentException("Cursor is too long");
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor.getBytes(StandardCharsets.US_ASCII));
            PageCursor parsed = MAPPER.readValue(decoded, PageCursor.class);
            if (parsed == null || !isGeneratedSessionId(parsed.id)
                    || parsed.updatedAt == null || parsed.query == null
                    || parsed.generation == null || parsed.generation < 0) {
                throw new IllegalArgumentException("Invalid chat cursor");
            }
            return parsed;
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid chat cursor", error);
        }
    }

    /** Read a session with all messages, or {@code null} if absent (SPEC-CSP-API-002). */
    public synchronized Session getSession(String id) {
        ensureReady();
        if (id == null) return null;
        requireValidSessionId(id);
        try (Connection conn = connect()) {
            Session session = readSessionRow(conn, id);
            if (session != null) normalizeSessionForRead(session);
            return session;
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to read chat session " + id, error);
        }
    }

    // ── Mutations ────────────────────────────────────────────────

    /**
     * Create a new session (SPEC-CSP-API-003). Assigns an id + timestamps,
     * defaults {@code title}/{@code source}, applies message invariants to
     * {@code initialMessages}, writes the rows, sets it active.
     * No session-count pruning (SPEC-CSP-DEC-005).
     */
    public synchronized Session create(CreateRequest req) {
        Session s = new Session();
        s.id = newId();
        Instant now = Instant.now();
        s.createdAt = now;
        s.updatedAt = now;
        s.title = (req != null && req.title != null && !req.title.isBlank()) ? req.title : "新会话";
        s.source = normalizeSource(req != null ? req.source : null);
        s.memoryPolicy = normalizeMemoryPolicy(req != null ? req.memoryPolicy : null);
        if (req != null) {
            s.contextLabel = req.contextLabel;
            s.contextSnapshot = req.contextSnapshot;
        }
        s.messages = new ArrayList<>();
        if (req != null && req.initialMessages != null) {
            requireValidBatch(req.initialMessages);
            for (Message m : req.initialMessages) {
                Message stored = copyIncomingMessage(m);
                stamp(stored);
                s.messages.add(stored);
            }
        }
        ensureReady();
        commitSessionMutation(s, true);
        return s;
    }

    /**
     * Patch non-null meta fields (SPEC-CSP-API-004). Never touches
     * {@code messages}. Bumps {@code updatedAt}. Returns the updated
     * session, or {@code null} if absent.
     */
    public synchronized Session updateMeta(String id, String title, String contextLabel, Object contextSnapshot) {
        Session s = getSession(id);
        if (s == null) return null;
        if (title != null) s.title = title;
        if (contextLabel != null) s.contextLabel = contextLabel;
        if (contextSnapshot != null) s.contextSnapshot = contextSnapshot;
        s.updatedAt = Instant.now();
        commitSessionMutation(s, false);
        return s;
    }

    public synchronized Session updateMemoryPolicy(String id, String memoryPolicy) {
        Session s = getSession(id);
        if (s == null) return null;
        s.memoryPolicy = normalizeMemoryPolicy(memoryPolicy);
        s.updatedAt = Instant.now();
        commitSessionMutation(s, false);
        return s;
    }

    /**
     * Delete a session (SPEC-CSP-API-005): removes the row (messages cascade).
     * If it was the active pointer, reselects the newest-by-{@code updatedAt}
     * remaining session (else null). Returns {@code null} if absent.
     */
    public synchronized DeleteResult delete(String id) {
        ensureReady();
        if (id == null) return null;
        requireValidSessionId(id);
        try (Connection conn = connect()) {
            if (!containsSession(conn, id)) return null;
            String activeBefore = metadata(conn, "active_session_id");
            String activeAfter = activeBefore;
            if (id.equals(activeBefore)) {
                try (PreparedStatement statement = conn.prepareStatement("""
                        SELECT id FROM sessions WHERE id <> ?
                        ORDER BY updated_at DESC, id DESC LIMIT 1
                        """)) {
                    statement.setString(1, id);
                    try (ResultSet row = statement.executeQuery()) {
                        activeAfter = row.next() ? row.getString(1) : null;
                    }
                }
            }
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement statement = conn.prepareStatement(
                        "DELETE FROM sessions WHERE id = ?")) {
                    statement.setString(1, id);
                    statement.executeUpdate();
                }
                incrementGeneration(conn);
                setMetadata(conn, "active_session_id", activeAfter);
                conn.commit();
            } catch (Exception error) {
                rollbackQuietly(conn, error);
                throw error;
            }
            return new DeleteResult(true, id, activeAfter);
        } catch (Exception error) {
            if (error instanceof IllegalArgumentException illegal) throw illegal;
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Failed to delete chat session " + id, error);
        }
    }

    /**
     * Append messages (SPEC-CSP-API-006): assigns ids/timestamps, truncates
     * over-long content (009c), keeps newest 200 (009b), bumps
     * {@code updatedAt}. Returns the appended messages, or {@code null} if
     * the session is absent.
     */
    public synchronized List<Message> appendMessages(String id, List<Message> incoming) {
        Session s = getSession(id);
        if (s == null) return null;
        if (s.messages == null) s.messages = new ArrayList<>();
        requireValidBatch(incoming);
        List<Message> appended = new ArrayList<>();
        for (Message m : incoming) {
            Message stored = copyIncomingMessage(m);
            stamp(stored);
            s.messages.add(stored);
            appended.add(stored);
        }
        s.updatedAt = Instant.now();
        commitSessionMutation(s, false);
        java.util.Set<String> retainedIds = s.messages.stream()
                .map(message -> message.id)
                .collect(java.util.stream.Collectors.toSet());
        appended.removeIf(message -> !retainedIds.contains(message.id));
        return appended;
    }

    /**
     * Patch non-null fields of a single message (SPEC-CSP-API-007); applies
     * content truncation (009c). Bumps session {@code updatedAt}. Returns the
     * updated message, or {@code null} if the session or message is absent.
     */
    public synchronized Message updateMessage(String id, String msgId, String content,
                                              String status, String error, List<Object> suggestedTasks) {
        Session s = getSession(id);
        if (s == null || s.messages == null) return null;
        Message target = null;
        for (Message m : s.messages) {
            if (msgId != null && msgId.equals(m.id)) {
                target = m;
                break;
            }
        }
        if (target == null) return null;
        if (!"assistant".equals(target.role)) {
            throw new IllegalArgumentException("Only assistant messages can be updated");
        }
        if (content != null) target.content = truncateContent(content);
        if (status != null) {
            requireValidStatus(status);
            if (!isAllowedStatusTransition(target.status, status)) {
                throw new IllegalArgumentException(
                        "Invalid assistant status transition: " + target.status + " -> " + status);
            }
            target.status = status;
            if ("pending".equals(status) || "sent".equals(status)) {
                target.error = null;
            }
            if ("pending".equals(status) || "error".equals(status)) {
                target.suggestedTasks = null;
            }
        }
        if (error != null) {
            if (!"error".equals(target.status)) {
                throw new IllegalArgumentException("error text requires assistant status=error");
            }
            target.error = error;
        }
        if (suggestedTasks != null) {
            if (!"sent".equals(target.status) && !suggestedTasks.isEmpty()) {
                throw new IllegalArgumentException("suggestedTasks require assistant status=sent");
            }
            target.suggestedTasks = new ArrayList<>(suggestedTasks);
        }
        s.updatedAt = Instant.now();
        commitSessionMutation(s, false);
        return target;
    }

    /**
     * Persist the active-session pointer (SPEC-CSP-API-008). A non-null id that
     * does not resolve to an existing session signals invalid (caller's 400) and
     * leaves the persisted pointer unchanged. Returns the persisted pointer.
     */
    public synchronized String setActiveSession(String idOrNull) {
        ensureReady();
        if (idOrNull != null) {
            requireValidSessionId(idOrNull);
            try (Connection conn = connect()) {
                if (!containsSession(conn, idOrNull)) {
                    throw new IllegalArgumentException("Unknown session: " + idOrNull);
                }
            } catch (SQLException error) {
                throw new IllegalStateException("Failed to validate chat session", error);
            }
        }
        transaction("set active chat session", conn ->
                setMetadata(conn, "active_session_id", idOrNull));
        return idOrNull;
    }

    /**
     * Write a regenerated summary into the session row only
     * (SPEC-CSP-API-011a). No-op if the session was deleted mid-flight.
     */
    public synchronized void writeSummary(String id, String summary) {
        Session s = getSession(id);
        if (s == null) return;
        s.summary = summary;
        commitSessionMutation(s, false);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Assign server-owned id/createdAt and apply the content cap to a message. */
    private static void stamp(Message m) {
        m.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        m.createdAt = Instant.now();
        if (m.content != null) m.content = truncateContent(m.content);
    }

    private static String truncateContent(String content) {
        if (content == null || codePoints(content) <= MAX_CONTENT) return content;
        return prefixCodePoints(content, MAX_CONTENT) + "...";
    }

    private static String normalizeMemoryPolicy(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return "smart";
        return switch (normalized) {
            case "smart", "confirm_all", "off" -> normalized;
            default -> throw new IllegalArgumentException("Invalid memoryPolicy: " + value);
        };
    }

    private static String coerceMemoryPolicy(String value) {
        try {
            return normalizeMemoryPolicy(value);
        } catch (IllegalArgumentException ignored) {
            return "smart";
        }
    }

    private static void normalizeIndexMemoryPolicy(Index idx) {
        for (SessionMeta m : idx.sessions) {
            if (m != null) normalizeMeta(m);
        }
    }

    private static void normalizeMeta(SessionMeta m) {
        m.memoryPolicy = coerceMemoryPolicy(m.memoryPolicy);
        m.title = truncateText(m.title, MAX_TITLE);
        m.source = truncateText(m.source, MAX_SOURCE);
        m.contextLabel = truncateText(m.contextLabel, MAX_CONTEXT_LABEL);
        m.summary = truncateText(m.summary, MAX_SUMMARY);
        m.lastMessagePreview = truncateText(m.lastMessagePreview, PREVIEW_LEN);
    }

    /** Derive the list-row fields ({@code lastMessagePreview}/{@code messageCount}) from a session. */
    private static SessionMeta toMeta(Session s) {
        SessionMeta m = new SessionMeta();
        m.id = s.id;
        m.title = truncateText(s.title, MAX_TITLE);
        m.createdAt = s.createdAt;
        m.updatedAt = s.updatedAt;
        m.source = truncateText(s.source, MAX_SOURCE);
        m.contextLabel = truncateText(s.contextLabel, MAX_CONTEXT_LABEL);
        m.memoryPolicy = coerceMemoryPolicy(s.memoryPolicy);
        m.summary = truncateText(s.summary, MAX_SUMMARY);
        m.messageCount = s.messages != null ? s.messages.size() : 0;
        m.lastMessagePreview = lastPreview(s);
        return m;
    }

    private static String lastPreview(Session s) {
        if (s.messages == null || s.messages.isEmpty()) return null;
        String content = s.messages.get(s.messages.size() - 1).content;
        if (content == null) return null;
        content = content.strip();
        return codePoints(content) > PREVIEW_LEN
                ? prefixCodePoints(content, PREVIEW_LEN) : content;
    }

    /** Keep only the newest complete-turn tail within {@link #MAX_MESSAGES}. */
    private static void truncateMessages(Session s) {
        if (s.messages != null && s.messages.size() > MAX_MESSAGES) {
            int from = s.messages.size() - MAX_MESSAGES;
            int nextUser = firstUserAtOrAfter(s.messages, from);
            if (nextUser >= 0) {
                s.messages = new ArrayList<>(s.messages.subList(nextUser, s.messages.size()));
                return;
            }
            int lastUser = lastUserIndex(s.messages);
            if (lastUser >= 0 && s.messages.size() - lastUser > MAX_MESSAGES) {
                throw new IllegalArgumentException(
                        "The newest chat turn exceeds the message retention limit");
            }
            s.messages = new ArrayList<>(s.messages.subList(from, s.messages.size()));
        }
    }

    private static void normalizeSessionForRead(Session s) {
        if (s != null) s.memoryPolicy = coerceMemoryPolicy(s.memoryPolicy);
    }

    private static void normalizeSessionForWrite(Session s) {
        if (s == null) return;
        s.title = truncateText(s.title, MAX_TITLE);
        s.source = truncateText(s.source, MAX_SOURCE);
        s.contextLabel = truncateText(s.contextLabel, MAX_CONTEXT_LABEL);
        s.summary = truncateText(s.summary, MAX_SUMMARY);
        s.memoryPolicy = coerceMemoryPolicy(s.memoryPolicy);
        s.contextSnapshot = normalizeOpaque(s.contextSnapshot, MAX_CONTEXT_SNAPSHOT_BYTES, false);
        if (s.messages == null) s.messages = new ArrayList<>();
        List<Message> normalized = new ArrayList<>(s.messages.size());
        for (Message message : s.messages) {
            if (message == null) continue;
            normalizeMessage(message);
            normalized.add(message);
        }
        s.messages = normalized;
        truncateMessages(s);
        while (serializedBytes(s) > MAX_SHARD_BYTES && !s.messages.isEmpty()) {
            dropOldestTurn(s.messages);
        }
        if (serializedBytes(s) > MAX_SHARD_BYTES) {
            throw new IllegalArgumentException("Chat session exceeds the shard size limit");
        }
    }

    private static void normalizeMessage(Message message) {
        message.content = truncateContent(message.content);
        message.error = truncateText(message.error, MAX_ERROR);
        message.contextSnapshot = normalizeOpaque(
                message.contextSnapshot, MAX_CONTEXT_SNAPSHOT_BYTES, false);
        message.suggestedTasks = normalizeSuggestedTasks(message.suggestedTasks);
    }

    private static Message copyIncomingMessage(Message incoming) {
        if (incoming == null) throw new IllegalArgumentException("Chat message must not be null");
        requireValidRole(incoming.role);
        requireValidStatus(incoming.status);
        Message copy = new Message();
        copy.role = incoming.role;
        copy.content = incoming.content;
        copy.status = incoming.status;
        copy.error = incoming.error;
        copy.contextSnapshot = incoming.contextSnapshot;
        copy.suggestedTasks = incoming.suggestedTasks == null
                ? null : new ArrayList<>(incoming.suggestedTasks);
        normalizeMessage(copy);
        return copy;
    }

    private static void requireValidBatch(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("At least one chat message is required");
        }
        if (messages.size() > MAX_MESSAGE_BATCH) {
            throw new IllegalArgumentException(
                    "A chat message batch may contain at most " + MAX_MESSAGE_BATCH + " messages");
        }
        for (Message message : messages) {
            if (message == null) throw new IllegalArgumentException("Chat message must not be null");
            requireValidRole(message.role);
            requireValidStatus(message.status);
            requireValidIncomingLifecycle(message);
        }
    }

    private static void requireValidRole(String role) {
        if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) {
            throw new IllegalArgumentException("Invalid chat message role: " + role);
        }
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) return "manual";
        return switch (source) {
            case "manual", "agent_context", "task_context" -> source;
            default -> throw new IllegalArgumentException("Invalid chat session source: " + source);
        };
    }

    private static void requireValidIncomingLifecycle(Message message) {
        if ("assistant".equals(message.role)) {
            if (!"pending".equals(message.status)) {
                throw new IllegalArgumentException(
                        "New assistant messages must start with status=pending");
            }
            if (message.error != null || message.suggestedTasks != null) {
                throw new IllegalArgumentException(
                        "A pending assistant cannot contain error or suggestedTasks");
            }
        } else if ("user".equals(message.role)) {
            if (message.status != null && !"sent".equals(message.status)) {
                throw new IllegalArgumentException("User messages may only use status=sent");
            }
            if (message.error != null || message.suggestedTasks != null) {
                throw new IllegalArgumentException(
                        "User messages cannot contain error or suggestedTasks");
            }
        } else if (message.status != null || message.error != null
                || message.suggestedTasks != null) {
            throw new IllegalArgumentException(
                    "System messages cannot contain lifecycle fields");
        }
    }

    private static void requireValidStatus(String status) {
        if (status != null && !"pending".equals(status)
                && !"sent".equals(status) && !"error".equals(status)) {
            throw new IllegalArgumentException("Invalid chat message status: " + status);
        }
    }

    private static boolean isAllowedStatusTransition(String from, String to) {
        if (from == null || from.equals(to)) return true;
        if ("pending".equals(from)) return "sent".equals(to) || "error".equals(to);
        if ("error".equals(from)) return "pending".equals(to) || "sent".equals(to);
        return false;
    }

    private static List<Object> normalizeSuggestedTasks(List<Object> tasks) {
        if (tasks == null) return null;
        List<Object> normalized = new ArrayList<>();
        for (Object task : tasks) {
            if (task == null || normalized.size() >= MAX_SUGGESTED_TASKS) break;
            Object bounded = normalizeOpaque(task, MAX_SUGGESTED_TASK_BYTES, true);
            List<Object> candidate = new ArrayList<>(normalized);
            candidate.add(bounded);
            if (serializedBytes(candidate) > MAX_SUGGESTED_TASKS_BYTES) break;
            normalized.add(bounded);
        }
        return normalized;
    }

    private static Object normalizeOpaque(Object value, int maxBytes, boolean task) {
        if (value == null) return null;
        JsonNode original;
        try {
            original = MAPPER.valueToTree(value);
        } catch (IllegalArgumentException error) {
            return truncatedMarker(null, task);
        }
        OpaqueBudget budget = new OpaqueBudget();
        JsonNode bounded = boundNode(original, 0, budget);
        if (!budget.truncated && bounded != null && serializedBytes(bounded) <= maxBytes) {
            return MAPPER.convertValue(bounded, Object.class);
        }
        return truncatedMarker(original, task);
    }

    private static JsonNode boundNode(JsonNode node, int depth, OpaqueBudget budget) {
        if (depth >= MAX_OPAQUE_DEPTH || !budget.takeNode()) {
            budget.truncated = true;
            return null;
        }
        if (node == null || node.isNull()) return com.fasterxml.jackson.databind.node.NullNode.instance;
        if (node.isTextual()) {
            if (codePoints(node.textValue()) > MAX_JSON_STRING) budget.truncated = true;
            return TextNode.valueOf(truncateText(node.textValue(), MAX_JSON_STRING));
        }
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            int count = 0;
            while (fields.hasNext()) {
                if (count >= MAX_CONTAINER_ITEMS || budget.remainingNodes == 0) {
                    budget.truncated = true;
                    break;
                }
                Map.Entry<String, JsonNode> field = fields.next();
                if (codePoints(field.getKey()) > MAX_JSON_KEY) budget.truncated = true;
                JsonNode child = boundNode(field.getValue(), depth + 1, budget);
                if (child == null) break;
                out.set(truncateText(field.getKey(), MAX_JSON_KEY), child);
                count++;
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (int i = 0; i < node.size(); i++) {
                if (i >= MAX_CONTAINER_ITEMS || budget.remainingNodes == 0) {
                    budget.truncated = true;
                    break;
                }
                JsonNode child = boundNode(node.get(i), depth + 1, budget);
                if (child == null) break;
                out.add(child);
            }
            return out;
        }
        return node.deepCopy();
    }

    private static final class OpaqueBudget {
        private int remainingNodes = MAX_OPAQUE_NODES;
        private boolean truncated;

        private boolean takeNode() {
            if (remainingNodes <= 0) return false;
            remainingNodes--;
            return true;
        }
    }

    private static Map<String, Object> truncatedMarker(JsonNode original, boolean task) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("_truncated", true);
        if (original != null && original.isObject()) {
            for (String key : List.of("type", "title", "label", "source", "priority", "dueAt")) {
                JsonNode value = original.get(key);
                if (value != null && value.isValueNode() && !value.isNull()) {
                    marker.put(key, truncateText(value.asText(), MAX_TITLE));
                }
            }
        } else if (task && original != null && original.isTextual()) {
            marker.put("title", truncateText(original.asText(), MAX_TITLE));
        }
        if (task && !marker.containsKey("title")) marker.put("title", "...");
        return marker;
    }

    private static void dropOldestTurn(List<Message> messages) {
        if (messages.isEmpty()) return;
        int nextUser = firstUserAtOrAfter(messages, 1);
        if (nextUser >= 0) {
            messages.subList(0, nextUser).clear();
            return;
        }
        if (lastUserIndex(messages) >= 0) {
            throw new IllegalArgumentException(
                    "The newest chat turn exceeds the shard size limit");
        }
        messages.removeFirst();
    }

    private static int firstUserAtOrAfter(List<Message> messages, int start) {
        for (int i = Math.max(0, start); i < messages.size(); i++) {
            if ("user".equals(messages.get(i).role)) return i;
        }
        return -1;
    }

    private static int lastUserIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role)) return i;
        }
        return -1;
    }

    private static int serializedBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value).length;
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Chat session payload is not serializable", error);
        }
    }

    private static String truncateText(String value, int maxCodePoints) {
        if (value == null || codePoints(value) <= maxCodePoints) return value;
        int prefix = Math.max(0, maxCodePoints - 3);
        return prefixCodePoints(value, prefix) + "...";
    }

    private static String prefixCodePoints(String value, int count) {
        if (value == null || count <= 0) return "";
        int end = value.offsetByCodePoints(0, Math.min(count, codePoints(value)));
        return value.substring(0, end);
    }

    private static int codePoints(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    // ── Models ───────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class PageCursor {
        public String id;
        public String updatedAt;
        public String query;
        public Long generation;

        public PageCursor() {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Session {
        public String id;
        public String title;

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant createdAt;

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant updatedAt;

        public String source;          // manual | agent_context | task_context
        public String contextLabel;
        public Object contextSnapshot; // opaque, round-tripped (SPEC-CSP-MODEL-003)
        public String memoryPolicy; // smart | confirm_all | off
        public String summary;
        public List<Message> messages = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        public String id;
        public String role;            // user | assistant | system
        public String content;
        public String status;          // pending | sent | error
        public String error;

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant createdAt;

        public Object contextSnapshot;       // opaque (SPEC-CSP-MODEL-003)
        public List<Object> suggestedTasks;  // opaque (SPEC-CSP-MODEL-003)
    }

    /** List-row metadata; never carries {@code messages}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionMeta {
        public String id;
        public String title;

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant createdAt;

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant updatedAt;

        public String source;
        public String contextLabel;
        public String memoryPolicy; // smart | confirm_all | off
        public String summary;
        public String lastMessagePreview;
        public int messageCount;
    }

    /** Full metadata listing. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Index {
        public String activeSessionId;
        public List<SessionMeta> sessions = new ArrayList<>();
        public long generation;
    }

    /** Cursor-paged index response; active remains global even when absent from this page. */
    public record IndexPage(
            String activeSessionId,
            List<SessionMeta> sessions,
            String nextCursor,
            boolean hasMore) {
    }

    /** Create-session request body (SPEC-CSP-API-003). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CreateRequest {
        public String title;
        public String source;
        public String contextLabel;
        public Object contextSnapshot;
        public String memoryPolicy; // smart | confirm_all | off
        public List<Message> initialMessages;
    }

    /** Result of {@link #delete(String)} (SPEC-CSP-API-005). */
    public record DeleteResult(boolean deleted, String id, String activeSessionId) {
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        IOException failure = null;
        if (writerLock != null) {
            try {
                writerLock.release();
            } catch (IOException error) {
                failure = error;
            }
        }
        if (writerLockChannel != null) {
            try {
                writerLockChannel.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            throw new IllegalStateException("Failed to release chat-session writer lock", failure);
        }
    }
}
