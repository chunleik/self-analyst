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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sharded persistent store for desktop chat sessions, backed by
 * {@code {memoryDir}/chat-sessions/}: one {@code <sessionId>.json} shard per
 * session (the authoritative copy, including {@code messages}) plus an
 * {@code index.json} projection ({@code activeSessionId} + one
 * {@link SessionMeta} row per session, for the list view + search).
 * <p>
 * Each write touches only the affected shard + {@code index.json} (per-shard
 * isolation, SPEC-CSP-API-010b) and is persisted atomically via temp-file +
 * {@code ATOMIC_MOVE} (SPEC-CSP-API-010a, mirroring {@link TaskStore#save}).
 * The index is a derived projection: when missing/corrupt it is rebuilt from
 * the shards (SPEC-CSP-MODEL-005 / API-010c).
 */
public class ChatSessionStore {

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
    /** Hard UTF-8 bound for one persisted shard. */
    static final int MAX_SHARD_BYTES = 16 * 1024 * 1024;
    /** Length of the derived {@code lastMessagePreview} (SPEC-CSP-DEC-008). */
    private static final int PREVIEW_LEN = 80;
    /** Server-generated IDs are the only valid shard names. */
    private static final Pattern GENERATED_SESSION_ID = Pattern.compile("^[a-f0-9]{32}$");
    private static final Pattern GENERATED_MESSAGE_ID = Pattern.compile("^[a-f0-9]{12}$");
    private static final Pattern STORE_TEMP_FILE = Pattern.compile(
            "^\\.(?:index\\.json|index\\.state|[a-f0-9]{32}\\.json)\\..+\\.chat-tmp$");
    private static final int RECOVERY_PROTOCOL_VERSION = 1;
    private static final String TEMP_SUFFIX = ".chat-tmp";
    private static final Comparator<SessionMeta> META_ORDER = Comparator
            .comparing((SessionMeta meta) -> meta.updatedAt != null
                    ? meta.updatedAt : Instant.EPOCH)
            .reversed()
            .thenComparing(meta -> meta.id != null ? meta.id : "", Comparator.reverseOrder());

    private final Path dir;
    private final Path indexFile;
    private final Path stateFile;
    private final ChatSessionStoreIo io;
    private boolean recovered;
    private boolean recovering;

    public ChatSessionStore(Path memoryDir) {
        this(memoryDir, ChatSessionStoreIo.nio());
    }

    ChatSessionStore(Path memoryDir, ChatSessionStoreIo io) {
        this.dir = memoryDir.resolve("chat-sessions").toAbsolutePath().normalize();
        this.indexFile = dir.resolve("index.json");
        this.stateFile = dir.resolve("index.state");
        this.io = java.util.Objects.requireNonNull(io, "io");
    }

    // ── Atomic persistence ───────────────────────────────────────

    /**
     * Write {@code value} to {@code target} atomically: temp-file then
     * {@code ATOMIC_MOVE} (SPEC-CSP-API-010a). On {@link IOException} the
     * on-disk original is left untouched and a {@link RuntimeException} is
     * thrown so the controller maps it to HTTP 500.
     */
    private void writeJson(Path target, Object value) {
        Path tmp = null;
        try {
            io.createDirectories(dir);
            tmp = io.createTempFile(dir, "." + target.getFileName() + ".", TEMP_SUFFIX);
            io.writeJson(MAPPER, tmp, value);
            io.atomicReplace(tmp, target);
            tmp = null;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + target.getFileName(), e);
        } finally {
            if (tmp != null) {
                try {
                    io.deleteIfExists(tmp);
                } catch (IOException cleanupFailure) {
                    log.debug("Failed to clean chat temp {}: {}", tmp.getFileName(),
                            cleanupFailure.getMessage());
                }
            }
        }
    }

    private Path shardFile(String id) {
        requireValidSessionId(id);
        Path shard = dir.resolve(id + ".json").toAbsolutePath().normalize();
        if (!shard.startsWith(dir) || !dir.equals(shard.getParent())) {
            throw new IllegalArgumentException("Invalid chat session id");
        }
        return shard;
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

    // ── Index load / rebuild ─────────────────────────────────────

    /**
     * Load {@code index.json}. Missing → empty index. Parse failure → warn and
     * rebuild from shards (never silently delete the corrupt file).
     * (SPEC-CSP-MODEL-005, SPEC-CSP-API-010c)
     */
    private Index loadIndex() {
        ensureRecovered();
        if (!Files.exists(indexFile)) {
            return Files.isDirectory(dir) ? rebuildIndex() : new Index();
        }
        try {
            Index idx = MAPPER.readValue(indexFile.toFile(), Index.class);
            if (idx == null) idx = new Index();
            if (idx.sessions == null) idx.sessions = new ArrayList<>();
            // Public routes and storage share the same server-generated ID contract.
            idx.sessions.removeIf(meta -> meta == null || !isGeneratedSessionId(meta.id));
            if (!isGeneratedSessionId(idx.activeSessionId)
                    || !resolves(idx.sessions, idx.activeSessionId)) {
                idx.activeSessionId = null;
            }
            normalizeIndexMemoryPolicy(idx);
            return idx;
        } catch (IOException | RuntimeException e) {
            log.warn("chat-sessions index.json unreadable ({}), rebuilding from shards", e.getMessage());
            return rebuildIndex();
        }
    }

    /**
     * Rebuild {@code index.json} by scanning every {@code *.json} shard (except
     * the index itself). Per-shard parse failures are skipped with a warning,
     * never process-fatal. {@code activeSessionId} is preserved only if still
     * resolvable, else null. The rebuilt index is persisted and returned.
     */
    private Index rebuildIndex() {
        Index raw = readRawIndex();
        Index rebuilt = scanCanonicalIndex();
        rebuilt.generation = raw != null ? raw.generation : 0;
        applyActiveHint(rebuilt, raw != null ? raw.activeSessionId : null);
        writeIndex(rebuilt);
        return rebuilt;
    }

    private void ensureRecovered() {
        if (recovering) return;
        if (recovered) return;
        if (Files.notExists(dir)) {
            recovered = true;
            return;
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                    "chat-sessions path exists but is not an accessible directory: " + dir);
        }
        RecoveryStateRead stateRead = readRecoveryState();
        if (stateRead.kind == RecoveryStateKind.INVALID) {
            throw new IllegalStateException(
                    "chat-sessions index.state is invalid; refusing to overwrite recovery evidence",
                    stateRead.error);
        }
        RecoveryState state = stateRead.state;
        if (state != null && RecoveryStatus.CLEAN.name().equals(state.state)) {
            if (!recovered) cleanupTempFiles();
            recovered = true;
            return;
        }
        recovering = true;
        recovered = false;
        try {
            cleanupTempFiles();
            Index raw = readRawIndex();
            Index canonical = scanCanonicalIndex();
            canonical.generation = raw != null ? raw.generation : 0;
            String activeHint = raw != null ? raw.activeSessionId : null;
            if (state != null && RecoveryStatus.DIRTY.name().equals(state.state)) {
                MutationOperation operation = parseOperation(state.operation);
                boolean targetIsCanonical = resolves(canonical.sessions, state.sessionId);
                ChatSessionStoreIo.PathStatus targetStatus;
                try {
                    targetStatus = io.status(shardFile(state.sessionId));
                } catch (IOException error) {
                    throw new IllegalStateException(
                            "Cannot determine recovery target status", error);
                }
                activeHint = switch (operation) {
                    case CREATE -> targetIsCanonical ? state.activeAfter : state.activeBefore;
                    case DELETE -> targetStatus == ChatSessionStoreIo.PathStatus.MISSING
                            ? state.activeAfter : state.activeBefore;
                    case UPSERT -> state.activeBefore;
                };
            }
            applyActiveHint(canonical, activeHint);
            writeIndex(canonical);
            writeRecoveryState(RecoveryState.clean());
            cleanupTempFiles();
            recovered = true;
        } finally {
            recovering = false;
        }
    }

    private RecoveryStateRead readRecoveryState() {
        if (Files.notExists(stateFile)) return RecoveryStateRead.missing();
        try {
            RecoveryState state = MAPPER.readValue(stateFile.toFile(), RecoveryState.class);
            validateRecoveryState(state);
            return RecoveryStateRead.valid(state);
        } catch (IOException | RuntimeException error) {
            return RecoveryStateRead.invalid(error);
        }
    }

    private static void validateRecoveryState(RecoveryState state) {
        if (state == null) throw new IllegalArgumentException("index.state is empty");
        if (state.protocolVersion != RECOVERY_PROTOCOL_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported recovery protocol version: " + state.protocolVersion);
        }
        if (RecoveryStatus.CLEAN.name().equals(state.state)) return;
        if (!RecoveryStatus.DIRTY.name().equals(state.state)) {
            throw new IllegalArgumentException("Invalid recovery state: " + state.state);
        }
        MutationOperation operation = MutationOperation.valueOf(state.operation);
        if (!isGeneratedSessionId(state.sessionId)) {
            throw new IllegalArgumentException("Invalid recovery session id");
        }
        if (state.activeBefore != null && !isGeneratedSessionId(state.activeBefore)) {
            throw new IllegalArgumentException("Invalid recovery activeBefore");
        }
        if (state.activeAfter != null && !isGeneratedSessionId(state.activeAfter)) {
            throw new IllegalArgumentException("Invalid recovery activeAfter");
        }
        switch (operation) {
            case CREATE -> {
                if (!state.sessionId.equals(state.activeAfter)
                        || state.sessionId.equals(state.activeBefore)) {
                    throw new IllegalArgumentException("Invalid CREATE recovery intent");
                }
            }
            case UPSERT -> {
                if (!java.util.Objects.equals(state.activeBefore, state.activeAfter)) {
                    throw new IllegalArgumentException("Invalid UPSERT recovery intent");
                }
            }
            case DELETE -> {
                boolean deletingActive = state.sessionId.equals(state.activeBefore);
                if (deletingActive) {
                    if (state.sessionId.equals(state.activeAfter)) {
                        throw new IllegalArgumentException("Invalid active DELETE recovery intent");
                    }
                } else if (!java.util.Objects.equals(state.activeBefore, state.activeAfter)) {
                    throw new IllegalArgumentException("Invalid inactive DELETE recovery intent");
                }
            }
        }
    }

    private void writeRecoveryState(RecoveryState state) {
        writeJson(stateFile, state);
    }

    private Index readRawIndex() {
        if (!Files.exists(indexFile)) return null;
        try {
            Index index = MAPPER.readValue(indexFile.toFile(), Index.class);
            if (index != null && index.sessions == null) index.sessions = new ArrayList<>();
            return index;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private Index scanCanonicalIndex() {
        Index rebuilt = new Index();
        if (Files.notExists(dir)) return rebuilt;
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                    "chat-sessions path is not an accessible directory: " + dir);
        }

        List<Path> shards;
        try {
            shards = io.list(dir).stream()
                    .filter(ChatSessionStore::isShardPath).sorted().toList();
        } catch (IOException error) {
            throw new RuntimeException("Failed to scan chat-sessions directory", error);
        }
        for (Path shard : shards) {
            try {
                Session session = MAPPER.readValue(shard.toFile(), Session.class);
                String fileName = shard.getFileName().toString();
                String fileId = fileName.substring(0, fileName.length() - 5);
                if (session != null && fileId.equals(session.id)) {
                    normalizeSessionForRead(session);
                    rebuilt.sessions.add(toMeta(session));
                } else {
                    log.warn("Skipping chat shard with mismatched/invalid id: {}",
                            shard.getFileName());
                }
            } catch (IOException | RuntimeException error) {
                log.warn("Skipping unreadable chat shard {}: {}",
                        shard.getFileName(), error.getMessage());
            }
        }
        rebuilt.sessions.sort(META_ORDER);
        return rebuilt;
    }

    private static boolean isShardPath(Path path) {
        String name = path.getFileName().toString();
        return name.length() == 37 && name.endsWith(".json")
                && isGeneratedSessionId(name.substring(0, 32));
    }

    private static void applyActiveHint(Index index, String activeHint) {
        index.activeSessionId = isGeneratedSessionId(activeHint)
                && resolves(index.sessions, activeHint) ? activeHint : null;
    }

    private void writeIndex(Index index) {
        writeIndex(index, true);
    }

    private void writeIndex(Index index, boolean metadataChanged) {
        if (index.sessions == null) index.sessions = new ArrayList<>();
        index.sessions.sort(META_ORDER);
        if (metadataChanged) {
            index.generation = index.generation == Long.MAX_VALUE ? 1 : index.generation + 1;
        }
        writeJson(indexFile, index);
    }

    private void cleanupTempFiles() {
        if (!Files.isDirectory(dir)) return;
        try {
            io.list(dir).stream()
                    .filter(path -> STORE_TEMP_FILE.matcher(
                            path.getFileName().toString()).matches())
                    .forEach(path -> {
                        try {
                            io.deleteIfExists(path);
                        } catch (IOException error) {
                            log.debug("Failed to clean chat temp {}: {}", path.getFileName(),
                                    error.getMessage());
                        }
                    });
        } catch (IOException error) {
            log.debug("Failed to scan chat temp files: {}", error.getMessage());
        }
    }

    private static MutationOperation parseOperation(String value) {
        return MutationOperation.valueOf(value);
    }

    private static boolean resolves(List<SessionMeta> metas, String id) {
        return metas.stream().anyMatch(m -> id.equals(m.id));
    }

    /** Derive the index projection ({@code lastMessagePreview}/{@code messageCount}/{@code summary}) from a session. */
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

    // ── Read paths ───────────────────────────────────────────────

    /** Index with {@code sessions} sorted by {@code updatedAt} descending (SPEC-CSP-API-001). */
    public synchronized Index listIndex() {
        Index idx = loadIndex();
        idx.sessions.sort(META_ORDER);
        return idx;
    }

    /** Optional cursor-paged metadata view; the legacy no-parameter list remains unchanged. */
    public synchronized IndexPage listIndexPage(int limit, String cursor, String query) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        String normalizedQuery = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (normalizedQuery.length() > 200) {
            throw new IllegalArgumentException("q must be at most 200 characters");
        }
        Index index = listIndex();
        List<SessionMeta> filtered = index.sessions.stream()
                .filter(meta -> matchesQuery(meta, normalizedQuery))
                .toList();
        int start = 0;
        if (cursor != null) {
            PageCursor decoded = decodeCursor(cursor);
            if (!normalizedQuery.equals(decoded.query)) {
                throw new IllegalArgumentException("Cursor does not belong to this query");
            }
            if (decoded.generation.longValue() != index.generation) {
                throw new IllegalArgumentException("Cursor is stale; reload the first page");
            }
            start = findCursorPosition(filtered, decoded) + 1;
        }
        int end = Math.min(filtered.size(), start + limit);
        List<SessionMeta> page = new ArrayList<>(filtered.subList(start, end));
        boolean hasMore = end < filtered.size();
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(page.getLast(), normalizedQuery, index.generation) : null;
        return new IndexPage(index.activeSessionId, page, nextCursor, hasMore);
    }

    private static boolean matchesQuery(SessionMeta meta, String query) {
        if (query.isEmpty()) return true;
        return containsIgnoreCase(meta.title, query)
                || containsIgnoreCase(meta.lastMessagePreview, query)
                || containsIgnoreCase(meta.summary, query);
    }

    private static boolean containsIgnoreCase(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private static int findCursorPosition(List<SessionMeta> metas, PageCursor cursor) {
        for (int i = 0; i < metas.size(); i++) {
            SessionMeta meta = metas.get(i);
            String updatedAt = meta.updatedAt != null ? meta.updatedAt.toString() : "";
            if (java.util.Objects.equals(meta.id, cursor.id)
                    && updatedAt.equals(cursor.updatedAt)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Cursor is stale or invalid");
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

    /** Read a session shard, or {@code null} if absent (SPEC-CSP-API-002). */
    public synchronized Session getSession(String id) {
        ensureRecovered();
        if (id == null) return null;
        Path shard = shardFile(id);
        if (!Files.exists(shard)) return null;
        try {
            Session s = MAPPER.readValue(shard.toFile(), Session.class);
            if (s == null || !id.equals(s.id) || !isGeneratedSessionId(s.id)) {
                log.warn("Skipping chat shard with mismatched/invalid id: {}", shard.getFileName());
                return null;
            }
            normalizeSessionForRead(s);
            return s;
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to read chat shard {}: {}", id, e.getMessage());
            return null;
        }
    }

    private void commitSessionMutation(
            Session session, Index index, MutationOperation operation, boolean makeActive) {
        normalizeSessionForWrite(session);
        String activeBefore = index.activeSessionId;
        upsertMeta(index, session);
        if (makeActive) index.activeSessionId = session.id;
        RecoveryState dirty = RecoveryState.dirty(
                operation, session.id, activeBefore, index.activeSessionId);
        writeRecoveryState(dirty);
        recovered = false;
        writeJson(shardFile(session.id), session);
        finishCommittedProjection(index, dirty);
    }

    private void commitDeleteMutation(Path shard, String id, Index index, String activeBefore) {
        RecoveryState dirty = RecoveryState.dirty(
                MutationOperation.DELETE, id, activeBefore, index.activeSessionId);
        writeRecoveryState(dirty);
        recovered = false;
        try {
            io.deleteIfExists(shard);
        } catch (IOException error) {
            throw new RuntimeException("Failed to delete chat shard " + id, error);
        }
        finishCommittedProjection(index, dirty);
    }

    /**
     * The authoritative shard operation has committed. Projection/state failures are therefore
     * recoverable and must not be reported as an uncommitted mutation to callers.
     */
    private void finishCommittedProjection(Index index, RecoveryState dirty) {
        try {
            writeIndex(index);
        } catch (RuntimeException indexFailure) {
            log.warn("Chat mutation {} for session {} committed, but index refresh failed; "
                            + "DIRTY recovery will run on the next access: {}",
                    dirty.operation, dirty.sessionId, indexFailure.getMessage());
            return;
        }
        try {
            writeRecoveryState(RecoveryState.clean());
            recovered = true;
        } catch (RuntimeException cleanFailure) {
            log.warn("Chat mutation {} for session {} committed, but CLEAN marker failed; "
                            + "recovery will run on the next access: {}",
                    dirty.operation, dirty.sessionId, cleanFailure.getMessage());
        }
    }

    // ── Mutations ────────────────────────────────────────────────

    /**
     * Create a new session (SPEC-CSP-API-003). Assigns an FS-safe id +
     * timestamps, defaults {@code title}/{@code source}, applies message
     * invariants to {@code initialMessages}, writes the shard, sets it active,
     * upserts the index row. No session-count pruning (SPEC-CSP-DEC-005).
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
        Index idx = loadIndex();
        commitSessionMutation(s, idx, MutationOperation.CREATE, true);
        return s;
    }

    /**
     * Patch non-null meta fields (SPEC-CSP-API-004). Never touches
     * {@code messages}. Bumps {@code updatedAt}; rewrites shard + index row.
     * Returns the updated session, or {@code null} if absent.
     */
    public synchronized Session updateMeta(String id, String title, String contextLabel, Object contextSnapshot) {
        Session s = getSession(id);
        if (s == null) return null;
        if (title != null) s.title = title;
        if (contextLabel != null) s.contextLabel = contextLabel;
        if (contextSnapshot != null) s.contextSnapshot = contextSnapshot;
        s.updatedAt = Instant.now();
        Index idx = loadIndex();
        commitSessionMutation(s, idx, MutationOperation.UPSERT, false);
        return s;
    }

    public synchronized Session updateMemoryPolicy(String id, String memoryPolicy) {
        Session s = getSession(id);
        if (s == null) return null;
        s.memoryPolicy = normalizeMemoryPolicy(memoryPolicy);
        s.updatedAt = Instant.now();
        Index idx = loadIndex();
        commitSessionMutation(s, idx, MutationOperation.UPSERT, false);
        return s;
    }

    /**
     * Delete a session (SPEC-CSP-API-005): removes shard + index row. If it was
     * the active pointer, reselects the newest-by-{@code updatedAt} remaining
     * session (else null). Returns {@code null} if the session did not exist
     * (controller's 404 source).
     */
    public synchronized DeleteResult delete(String id) {
        ensureRecovered();
        if (id == null) return null;
        Path shard = shardFile(id);
        if (!Files.exists(shard)) return null;
        Index idx = loadIndex();
        String activeBefore = idx.activeSessionId;
        idx.sessions.removeIf(m -> id.equals(m.id));
        if (id.equals(idx.activeSessionId)) {
            idx.activeSessionId = idx.sessions.stream()
                    .max(Comparator.comparing(m -> m.updatedAt != null ? m.updatedAt : Instant.EPOCH))
                    .map(m -> m.id)
                    .orElse(null);
        }
        commitDeleteMutation(shard, id, idx, activeBefore);
        return new DeleteResult(true, id, idx.activeSessionId);
    }

    /**
     * Append messages (SPEC-CSP-API-006): assigns ids/timestamps, truncates
     * over-long content (009c), keeps newest 200 (009b), bumps
     * {@code updatedAt}, rewrites shard + refreshes index row. Returns the
     * appended messages, or {@code null} if the session is absent.
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
        Index idx = loadIndex();
        commitSessionMutation(s, idx, MutationOperation.UPSERT, false);
        java.util.Set<String> retainedIds = s.messages.stream()
                .map(message -> message.id)
                .collect(java.util.stream.Collectors.toSet());
        appended.removeIf(message -> !retainedIds.contains(message.id));
        return appended;
    }

    /**
     * Patch non-null fields of a single message (SPEC-CSP-API-007); applies
     * content truncation (009c). Bumps session {@code updatedAt}; rewrites shard
     * + index row. Returns the updated message, or {@code null} if the session
     * or message is absent.
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
        Index idx = loadIndex();
        commitSessionMutation(s, idx, MutationOperation.UPSERT, false);
        return target;
    }

    /**
     * Persist the active-session pointer (SPEC-CSP-API-008). A non-null id that
     * does not resolve to an existing shard signals invalid (caller's 400) and
     * leaves the on-disk pointer unchanged. Returns the persisted pointer.
     */
    public synchronized String setActiveSession(String idOrNull) {
        ensureRecovered();
        if (idOrNull != null && !Files.exists(shardFile(idOrNull))) {
            throw new IllegalArgumentException("Unknown session: " + idOrNull);
        }
        Index idx = loadIndex();
        idx.activeSessionId = idOrNull;
        writeIndex(idx, false);
        return idx.activeSessionId;
    }

    /**
     * Write a regenerated summary into the shard + index row only
     * (SPEC-CSP-API-011a). No-op if the session was deleted mid-flight.
     */
    public synchronized void writeSummary(String id, String summary) {
        Session s = getSession(id);
        if (s == null) return;
        s.summary = summary;
        Index idx = loadIndex();
        commitSessionMutation(s, idx, MutationOperation.UPSERT, false);
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** FS-safe id (no separators) used as the shard filename (SPEC-CSP-MODEL-001). */
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
            if (m != null) {
                m.memoryPolicy = coerceMemoryPolicy(m.memoryPolicy);
                m.title = truncateText(m.title, MAX_TITLE);
                m.source = truncateText(m.source, MAX_SOURCE);
                m.contextLabel = truncateText(m.contextLabel, MAX_CONTEXT_LABEL);
                m.summary = truncateText(m.summary, MAX_SUMMARY);
                m.lastMessagePreview = truncateText(m.lastMessagePreview, PREVIEW_LEN);
            }
        }
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

    /** Replace or append the {@link SessionMeta} row for a session (single-row mutation). */
    private static void upsertMeta(Index idx, Session s) {
        SessionMeta meta = toMeta(s);
        for (int i = 0; i < idx.sessions.size(); i++) {
            if (s.id.equals(idx.sessions.get(i).id)) {
                idx.sessions.set(i, meta);
                return;
            }
        }
        idx.sessions.add(meta);
    }

    // ── Models ───────────────────────────────────────────────────

    private enum RecoveryStatus { CLEAN, DIRTY }

    private enum MutationOperation { CREATE, UPSERT, DELETE }

    private enum RecoveryStateKind { MISSING, VALID, INVALID }

    private static final class RecoveryStateRead {
        private final RecoveryStateKind kind;
        private final RecoveryState state;
        private final Throwable error;

        private RecoveryStateRead(
                RecoveryStateKind kind, RecoveryState state, Throwable error) {
            this.kind = kind;
            this.state = state;
            this.error = error;
        }

        private static RecoveryStateRead missing() {
            return new RecoveryStateRead(RecoveryStateKind.MISSING, null, null);
        }

        private static RecoveryStateRead valid(RecoveryState state) {
            return new RecoveryStateRead(RecoveryStateKind.VALID, state, null);
        }

        private static RecoveryStateRead invalid(Throwable error) {
            return new RecoveryStateRead(RecoveryStateKind.INVALID, null, error);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RecoveryState {
        public int protocolVersion;
        public String state;
        public String operation;
        public String sessionId;
        public String activeBefore;
        public String activeAfter;

        public RecoveryState() {
        }

        private static RecoveryState clean() {
            RecoveryState state = new RecoveryState();
            state.protocolVersion = RECOVERY_PROTOCOL_VERSION;
            state.state = RecoveryStatus.CLEAN.name();
            return state;
        }

        private static RecoveryState dirty(
                MutationOperation operation,
                String sessionId,
                String activeBefore,
                String activeAfter) {
            RecoveryState state = clean();
            state.state = RecoveryStatus.DIRTY.name();
            state.operation = operation.name();
            state.sessionId = sessionId;
            state.activeBefore = activeBefore;
            state.activeAfter = activeAfter;
            return state;
        }
    }

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

    /** Projection row in {@code index.json}; never carries {@code messages}. */
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

    /** Serialized as {@code index.json}. */
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
}
