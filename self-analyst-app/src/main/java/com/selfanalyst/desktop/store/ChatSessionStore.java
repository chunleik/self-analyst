package com.selfanalyst.desktop.store;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
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
    private static final int MAX_MESSAGES = 200;
    /** Single-message content length cap (SPEC-CSP-API-009c). */
    private static final int MAX_CONTENT = 20000;
    /** Length of the derived {@code lastMessagePreview} (SPEC-CSP-DEC-008). */
    private static final int PREVIEW_LEN = 80;

    private final Path dir;
    private final Path indexFile;

    public ChatSessionStore(Path memoryDir) {
        this.dir = memoryDir.resolve("chat-sessions");
        this.indexFile = dir.resolve("index.json");
    }

    // ── Atomic persistence ───────────────────────────────────────

    /**
     * Write {@code value} to {@code target} atomically: temp-file then
     * {@code ATOMIC_MOVE} (SPEC-CSP-API-010a). On {@link IOException} the
     * on-disk original is left untouched and a {@link RuntimeException} is
     * thrown so the controller maps it to HTTP 500.
     */
    private void writeJson(Path target, Object value) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(target.getFileName() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), value);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + target.getFileName(), e);
        }
    }

    private Path shardFile(String id) {
        return dir.resolve(id + ".json");
    }

    // ── Index load / rebuild ─────────────────────────────────────

    /**
     * Load {@code index.json}. Missing → empty index. Parse failure → warn and
     * rebuild from shards (never silently delete the corrupt file).
     * (SPEC-CSP-MODEL-005, SPEC-CSP-API-010c)
     */
    private Index loadIndex() {
        if (!Files.exists(indexFile)) {
            return Files.isDirectory(dir) ? rebuildIndex() : new Index();
        }
        try {
            Index idx = MAPPER.readValue(indexFile.toFile(), Index.class);
            if (idx == null) idx = new Index();
            if (idx.sessions == null) idx.sessions = new ArrayList<>();
            normalizeIndexMemoryPolicy(idx);
            return idx;
        } catch (IOException e) {
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
        Index rebuilt = new Index();
        if (!Files.isDirectory(dir)) {
            return rebuilt;
        }
        // Preserve the previous activeSessionId if the (possibly corrupt) index still parses.
        String prevActive = null;
        if (Files.exists(indexFile)) {
            try {
                Index old = MAPPER.readValue(indexFile.toFile(), Index.class);
                if (old != null) prevActive = old.activeSessionId;
            } catch (IOException ignored) {
                // corrupt index → no pointer to preserve
            }
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".json") && !n.equals("index.json");
                    })
                    .forEach(p -> {
                        try {
                            Session s = MAPPER.readValue(p.toFile(), Session.class);
                            if (s != null && s.id != null) {
                                rebuilt.sessions.add(toMeta(s));
                            }
                        } catch (IOException e) {
                            log.warn("Skipping unreadable chat shard {}: {}", p.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to scan chat-sessions dir: {}", e.getMessage());
        }
        if (prevActive != null && resolves(rebuilt.sessions, prevActive)) {
            rebuilt.activeSessionId = prevActive;
        }
        writeJson(indexFile, rebuilt);
        return rebuilt;
    }

    private static boolean resolves(List<SessionMeta> metas, String id) {
        return metas.stream().anyMatch(m -> id.equals(m.id));
    }

    /** Derive the index projection ({@code lastMessagePreview}/{@code messageCount}/{@code summary}) from a session. */
    private static SessionMeta toMeta(Session s) {
        SessionMeta m = new SessionMeta();
        m.id = s.id;
        m.title = s.title;
        m.createdAt = s.createdAt;
        m.updatedAt = s.updatedAt;
        m.source = s.source;
        m.contextLabel = s.contextLabel;
        m.memoryPolicy = normalizeMemoryPolicy(s.memoryPolicy);
        m.summary = s.summary;
        m.messageCount = s.messages != null ? s.messages.size() : 0;
        m.lastMessagePreview = lastPreview(s);
        return m;
    }

    private static String lastPreview(Session s) {
        if (s.messages == null || s.messages.isEmpty()) return null;
        String content = s.messages.get(s.messages.size() - 1).content;
        if (content == null) return null;
        content = content.strip();
        return content.length() > PREVIEW_LEN ? content.substring(0, PREVIEW_LEN) : content;
    }

    // ── Read paths ───────────────────────────────────────────────

    /** Index with {@code sessions} sorted by {@code updatedAt} descending (SPEC-CSP-API-001). */
    public synchronized Index listIndex() {
        Index idx = loadIndex();
        idx.sessions.sort(Comparator.comparing(
                (SessionMeta m) -> m.updatedAt != null ? m.updatedAt : Instant.EPOCH).reversed());
        return idx;
    }

    /** Read a session shard, or {@code null} if absent (SPEC-CSP-API-002). */
    public synchronized Session getSession(String id) {
        Path shard = shardFile(id);
        if (id == null || !Files.exists(shard)) return null;
        try {
            Session s = MAPPER.readValue(shard.toFile(), Session.class);
            normalizeSessionMemoryPolicy(s);
            return s;
        } catch (IOException e) {
            log.warn("Failed to read chat shard {}: {}", id, e.getMessage());
            return null;
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
        s.source = (req != null && req.source != null && !req.source.isBlank()) ? req.source : "manual";
        s.memoryPolicy = normalizeMemoryPolicy(req != null ? req.memoryPolicy : null);
        if (req != null) {
            s.contextLabel = req.contextLabel;
            s.contextSnapshot = req.contextSnapshot;
        }
        s.messages = new ArrayList<>();
        if (req != null && req.initialMessages != null) {
            for (Message m : req.initialMessages) {
                stamp(m);
                s.messages.add(m);
            }
            truncateMessages(s);
        }
        writeJson(shardFile(s.id), s);
        Index idx = loadIndex();
        upsertMeta(idx, s);
        idx.activeSessionId = s.id;
        writeJson(indexFile, idx);
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
        writeJson(shardFile(id), s);
        Index idx = loadIndex();
        upsertMeta(idx, s);
        writeJson(indexFile, idx);
        return s;
    }

    public synchronized Session updateMemoryPolicy(String id, String memoryPolicy) {
        Session s = getSession(id);
        if (s == null) return null;
        s.memoryPolicy = normalizeMemoryPolicy(memoryPolicy);
        s.updatedAt = Instant.now();
        writeJson(shardFile(id), s);
        Index idx = loadIndex();
        upsertMeta(idx, s);
        writeJson(indexFile, idx);
        return s;
    }

    /**
     * Delete a session (SPEC-CSP-API-005): removes shard + index row. If it was
     * the active pointer, reselects the newest-by-{@code updatedAt} remaining
     * session (else null). Returns {@code null} if the session did not exist
     * (controller's 404 source).
     */
    public synchronized DeleteResult delete(String id) {
        Path shard = shardFile(id);
        if (id == null || !Files.exists(shard)) return null;
        try {
            Files.deleteIfExists(shard);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete chat shard " + id, e);
        }
        Index idx = loadIndex();
        idx.sessions.removeIf(m -> id.equals(m.id));
        if (id.equals(idx.activeSessionId)) {
            idx.activeSessionId = idx.sessions.stream()
                    .max(Comparator.comparing(m -> m.updatedAt != null ? m.updatedAt : Instant.EPOCH))
                    .map(m -> m.id)
                    .orElse(null);
        }
        writeJson(indexFile, idx);
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
        List<Message> appended = new ArrayList<>();
        if (incoming != null) {
            for (Message m : incoming) {
                stamp(m);
                s.messages.add(m);
                appended.add(m);
            }
        }
        truncateMessages(s);
        s.updatedAt = Instant.now();
        writeJson(shardFile(id), s);
        Index idx = loadIndex();
        upsertMeta(idx, s);
        writeJson(indexFile, idx);
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
        if (content != null) target.content = truncateContent(content);
        if (status != null) target.status = status;
        if (error != null) target.error = error;
        if (suggestedTasks != null) target.suggestedTasks = suggestedTasks;
        s.updatedAt = Instant.now();
        writeJson(shardFile(id), s);
        Index idx = loadIndex();
        upsertMeta(idx, s);
        writeJson(indexFile, idx);
        return target;
    }

    /**
     * Persist the active-session pointer (SPEC-CSP-API-008). A non-null id that
     * does not resolve to an existing shard signals invalid (caller's 400) and
     * leaves the on-disk pointer unchanged. Returns the persisted pointer.
     */
    public synchronized String setActiveSession(String idOrNull) {
        if (idOrNull != null && !Files.exists(shardFile(idOrNull))) {
            throw new IllegalArgumentException("Unknown session: " + idOrNull);
        }
        Index idx = loadIndex();
        idx.activeSessionId = idOrNull;
        writeJson(indexFile, idx);
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
        writeJson(shardFile(id), s);
        Index idx = loadIndex();
        upsertMeta(idx, s);
        writeJson(indexFile, idx);
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
        if (content != null && content.length() > MAX_CONTENT) {
            return content.substring(0, MAX_CONTENT) + "...";
        }
        return content;
    }

    private static String normalizeMemoryPolicy(String value) {
        if (value == null || value.isBlank()) return "smart";
        return switch (value) {
            case "smart", "confirm_all", "off" -> value;
            default -> throw new IllegalArgumentException("Invalid memoryPolicy: " + value);
        };
    }

    private static void normalizeSessionMemoryPolicy(Session s) {
        if (s != null) {
            s.memoryPolicy = normalizeMemoryPolicy(s.memoryPolicy);
        }
    }

    private static void normalizeIndexMemoryPolicy(Index idx) {
        for (SessionMeta m : idx.sessions) {
            if (m != null) {
                m.memoryPolicy = normalizeMemoryPolicy(m.memoryPolicy);
            }
        }
    }

    /** Keep only the newest {@link #MAX_MESSAGES} messages (SPEC-CSP-API-009b). */
    private static void truncateMessages(Session s) {
        if (s.messages != null && s.messages.size() > MAX_MESSAGES) {
            int from = s.messages.size() - MAX_MESSAGES;
            s.messages = new ArrayList<>(s.messages.subList(from, s.messages.size()));
        }
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
