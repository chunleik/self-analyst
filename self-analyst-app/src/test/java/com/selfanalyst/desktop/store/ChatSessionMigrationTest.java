package com.selfanalyst.desktop.store;

import com.selfanalyst.desktop.store.ChatSessionStore.Index;
import com.selfanalyst.desktop.store.ChatSessionStore.Message;
import com.selfanalyst.desktop.store.ChatSessionStore.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Legacy shard/index storage migration into the single SQLite database
 * (SPEC-CSS-TST-015, -016; SPEC-CSS-API-003).
 */
class ChatSessionMigrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static Message msg(String role, String content, String status) {
        Message m = new Message();
        m.role = role;
        m.content = content;
        m.status = status;
        return m;
    }

    private static Session legacySession(String id, String title, String updatedAt) {
        Session s = new Session();
        s.id = id;
        s.title = title;
        s.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        s.updatedAt = Instant.parse(updatedAt);
        s.source = "manual";
        s.messages = new ArrayList<>();
        return s;
    }

    private static void writeShard(Path chatDir, Session session) throws Exception {
        MAPPER.writeValue(chatDir.resolve(session.id + ".json").toFile(), session);
    }

    private static void writeLegacyIndexDb(Path chatDir, String activeSessionId) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                     "jdbc:sqlite:" + chatDir.resolve("index.db"));
             Statement statement = conn.createStatement()) {
            statement.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            if (activeSessionId != null) {
                statement.execute("INSERT INTO metadata(key,value) VALUES('active_session_id','"
                        + activeSessionId + "')");
            }
        }
    }

    // ── SPEC-CSS-TST-015 ──
    @Test
    void legacyShardsMigrateIntoChatDbAndMoveToLegacyDir(@TempDir Path memoryDir)
            throws Exception {
        Path chatDir = Files.createDirectories(memoryDir.resolve("chat-sessions"));
        Session a = legacySession("a".repeat(32), "会话甲", "2026-01-02T00:00:00Z");
        a.messages.add(msg("user", "正文甲", "sent"));
        Session b = legacySession("b".repeat(32), "会话乙", "2026-01-03T00:00:00Z");
        writeShard(chatDir, a);
        writeShard(chatDir, b);
        writeLegacyIndexDb(chatDir, a.id);

        ChatSessionStore store = new ChatSessionStore(memoryDir);

        Index index = store.listIndex();
        assertEquals(2, index.sessions.size());
        assertEquals(a.id, index.activeSessionId);
        assertEquals("正文甲", store.getSession(a.id).messages.getFirst().content);
        assertTrue(Files.isRegularFile(chatDir.resolve("chat.db")));
        assertFalse(Files.exists(chatDir.resolve(a.id + ".json")));
        assertFalse(Files.exists(chatDir.resolve("index.db")));
        assertTrue(Files.isRegularFile(chatDir.resolve("legacy").resolve(a.id + ".json")));
        assertTrue(Files.isRegularFile(chatDir.resolve("legacy").resolve("index.db")));
    }

    @Test
    void migrationIsNotRepeatedAndLegacyDirIsUntouched(@TempDir Path memoryDir)
            throws Exception {
        Path chatDir = Files.createDirectories(memoryDir.resolve("chat-sessions"));
        Session a = legacySession("a".repeat(32), "甲", "2026-01-02T00:00:00Z");
        writeShard(chatDir, a);
        ChatSessionStore first = new ChatSessionStore(memoryDir);
        assertEquals(1, first.listIndex().sessions.size());
        first.close();
        byte[] legacyShard = Files.readAllBytes(
                chatDir.resolve("legacy").resolve(a.id + ".json"));

        ChatSessionStore second = new ChatSessionStore(memoryDir);
        assertEquals(1, second.listIndex().sessions.size());
        assertArrayEquals(legacyShard,
                Files.readAllBytes(chatDir.resolve("legacy").resolve(a.id + ".json")));

        // Mutations must not touch the archived legacy files either.
        second.updateMeta(a.id, "新标题", null, null);
        assertArrayEquals(legacyShard,
                Files.readAllBytes(chatDir.resolve("legacy").resolve(a.id + ".json")));
        assertEquals("新标题", second.listIndex().sessions.getFirst().title);
    }

    @Test
    void activePointerFallsBackToIndexJsonThenNewest(@TempDir Path memoryDir) throws Exception {
        Path chatDir = Files.createDirectories(memoryDir.resolve("chat-sessions"));
        Session a = legacySession("a".repeat(32), "甲", "2026-01-02T00:00:00Z");
        Session b = legacySession("b".repeat(32), "乙", "2026-01-03T00:00:00Z");
        writeShard(chatDir, a);
        writeShard(chatDir, b);
        Files.writeString(chatDir.resolve("index.json"), """
                {"activeSessionId":"%s","sessions":[]}
                """.formatted(a.id));

        ChatSessionStore store = new ChatSessionStore(memoryDir);
        assertEquals(a.id, store.listIndex().activeSessionId);
        store.close();

        // No index pointer at all → newest by updatedAt.
        Path secondDir = Files.createDirectories(
                memoryDir.resolve("other").resolve("chat-sessions"));
        writeShard(secondDir, legacySession("c".repeat(32), "丙", "2026-01-02T00:00:00Z"));
        writeShard(secondDir, legacySession("d".repeat(32), "丁", "2026-01-05T00:00:00Z"));
        ChatSessionStore second = new ChatSessionStore(memoryDir.resolve("other"));
        assertEquals("d".repeat(32), second.listIndex().activeSessionId);
    }

    // ── SPEC-CSS-TST-016 ──
    @Test
    void legacyTombstonesMigrateIntoPendingDeletions(@TempDir Path memoryDir) throws Exception {
        Path chatDir = Files.createDirectories(memoryDir.resolve("chat-sessions"));
        writeShard(chatDir, legacySession("a".repeat(32), "甲", "2026-01-02T00:00:00Z"));
        String tombstoned = "e".repeat(32);
        Files.writeString(chatDir.resolve("delete-" + tombstoned + ".state"),
                "{\"protocolVersion\":1,\"state\":\"PENDING\",\"sessionId\":\"" + tombstoned
                        + "\",\"requestedAt\":\"2026-01-01T00:00:00Z\"}");

        ChatSessionStore store = new ChatSessionStore(memoryDir);

        assertTrue(store.pendingDeletionIds().contains(tombstoned));
        assertEquals(1, store.listIndex().sessions.size());
    }

    @Test
    void oversizedLegacyShardIsReadableThenConvergesOnMutation(@TempDir Path memoryDir)
            throws Exception {
        Path chatDir = Files.createDirectories(memoryDir.resolve("chat-sessions"));
        String id = "d".repeat(32);
        Session legacy = legacySession(id, "legacy".repeat(100), "2026-01-01T00:00:00Z");
        for (int i = 0; i < 101; i++) {
            legacy.messages.add(msg("user", "u" + i, "sent"));
            if (i < 100) {
                legacy.messages.add(msg("assistant", "a" + i, "sent"));
            }
        }
        writeShard(chatDir, legacy);

        ChatSessionStore store = new ChatSessionStore(memoryDir);

        // Migration preserves the raw body for reads (SPEC-CSP 兼容读取语义).
        Session readable = store.getSession(id);
        assertEquals(201, readable.messages.size());
        assertEquals(600, readable.title.length());
        Index rebuilt = store.listIndex();
        assertTrue(rebuilt.sessions.getFirst().title.codePointCount(
                0, rebuilt.sessions.getFirst().title.length()) <= ChatSessionStore.MAX_TITLE);

        Session converged = store.updateMeta(id, "repaired", null, null);
        assertEquals("repaired", converged.title);
        assertTrue(converged.messages.size() <= ChatSessionStore.MAX_MESSAGES);
        assertEquals("user", converged.messages.getFirst().role);
    }

    @Test
    void legacyShardAndIndexDefaultMemoryPolicyOnRead(@TempDir Path memoryDir) throws Exception {
        Path chatDir = Files.createDirectories(memoryDir.resolve("chat-sessions"));
        String id = "a".repeat(32);
        Files.writeString(chatDir.resolve(id + ".json"), """
                {
                  "id": "%s",
                  "title": "Legacy",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-01T00:00:00Z",
                  "source": "manual",
                  "messages": []
                }
                """.formatted(id));
        Files.writeString(chatDir.resolve("index.json"), """
                {
                  "activeSessionId": "%s",
                  "sessions": [
                    {
                      "id": "%s",
                      "title": "Legacy",
                      "createdAt": "2026-01-01T00:00:00Z",
                      "updatedAt": "2026-01-01T00:00:00Z",
                      "source": "manual",
                      "messageCount": 0
                    }
                  ]
                }
                """.formatted(id, id));

        ChatSessionStore store = new ChatSessionStore(memoryDir);

        assertEquals("smart", store.getSession(id).memoryPolicy);
        assertEquals("smart", store.listIndex().sessions.get(0).memoryPolicy);
        byte[] legacyIndex = Files.readAllBytes(chatDir.resolve("legacy").resolve("index.json"));
        store.updateMeta(id, "renamed", null, null);
        assertArrayEquals(legacyIndex,
                Files.readAllBytes(chatDir.resolve("legacy").resolve("index.json")),
                "normal mutations must not rewrite the archived v1 JSON");
        assertEquals("renamed", store.listIndex().sessions.getFirst().title);
        assertTrue(Files.isRegularFile(chatDir.resolve("chat.db")));
    }

    @Test
    void invalidMemoryPolicyInLegacyShardDefaultsToSmartOnRead(@TempDir Path memoryDir)
            throws Exception {
        Path chatDir = Files.createDirectories(memoryDir.resolve("chat-sessions"));
        String id = "b".repeat(32);
        Files.writeString(chatDir.resolve(id + ".json"), """
                {
                  "id": "%s",
                  "title": "Future",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-01T00:00:00Z",
                  "source": "manual",
                  "memoryPolicy": "future_policy",
                  "messages": []
                }
                """.formatted(id));

        ChatSessionStore store = new ChatSessionStore(memoryDir);

        assertEquals("smart", store.getSession(id).memoryPolicy);
    }

    @Test
    void ghostMetaWithoutShardIsDroppedDuringMigration(@TempDir Path memoryDir)
            throws Exception {
        Path chatDir = Files.createDirectories(memoryDir.resolve("chat-sessions"));
        String id = "c".repeat(32);
        Files.writeString(chatDir.resolve("index.json"), """
                {
                  "activeSessionId": "%s",
                  "sessions": [
                    {
                      "id": "%s",
                      "title": "Ghost",
                      "createdAt": "2026-01-01T00:00:00Z",
                      "updatedAt": "2026-01-01T00:00:00Z",
                      "source": "manual",
                      "messageCount": 0
                    }
                  ]
                }
                """.formatted(id, id));

        ChatSessionStore store = new ChatSessionStore(memoryDir);

        Index migrated = store.listIndex();
        assertTrue(migrated.sessions.isEmpty());
        assertNull(migrated.activeSessionId);
    }

    @Test
    void unreadableOrMismatchedShardsAreSkipped(@TempDir Path memoryDir) throws Exception {
        Path chatDir = Files.createDirectories(memoryDir.resolve("chat-sessions"));
        writeShard(chatDir, legacySession("a".repeat(32), "好", "2026-01-02T00:00:00Z"));
        Files.writeString(chatDir.resolve("b".repeat(32) + ".json"), "{ not json");
        // Filename id must match the body id.
        Session mismatched = legacySession("c".repeat(32), "错", "2026-01-02T00:00:00Z");
        MAPPER.writeValue(chatDir.resolve("d".repeat(32) + ".json").toFile(), mismatched);

        ChatSessionStore store = new ChatSessionStore(memoryDir);

        Index migrated = store.listIndex();
        assertEquals(List.of("a".repeat(32)),
                migrated.sessions.stream().map(meta -> meta.id).toList());
        assertNotNull(store.getSession("a".repeat(32)));
        assertNull(store.getSession("b".repeat(32)));
    }
}
