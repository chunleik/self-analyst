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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Atomicity, crash recovery and durable-deletion recovery of the single SQLite
 * store (SPEC-CSS-TST-014, -019, -020, -021). SQLite transactions + WAL replace
 * the former application-level DIRTY-state machinery.
 */
class ChatSessionStoreRecoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static ChatSessionStore.CreateRequest request(String title) {
        ChatSessionStore.CreateRequest request = new ChatSessionStore.CreateRequest();
        request.title = title;
        return request;
    }

    private static List<Message> turn(String text) {
        Message user = new Message();
        user.role = "user";
        user.status = "sent";
        user.content = text;
        Message assistant = new Message();
        assistant.role = "assistant";
        assistant.status = "pending";
        assistant.content = "thinking";
        return List.of(user, assistant);
    }

    // ── SPEC-CSS-TST-014 ──
    @Test
    void rejectedMutationLeavesPersistedDataUntouched(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session session = store.create(request("before"));
        store.appendMessages(session.id, turn("first"));
        Index before = store.listIndex();
        long generationBefore = before.generation;

        // A single turn that cannot fit the retention limits is rejected atomically.
        List<Message> huge = new ArrayList<>();
        Message anchor = new Message();
        anchor.role = "user";
        anchor.status = "sent";
        anchor.content = "😀".repeat(ChatSessionStore.MAX_CONTENT);
        huge.add(anchor);
        for (int i = 1; i < ChatSessionStore.MAX_MESSAGES; i++) {
            Message assistant = new Message();
            assistant.role = "assistant";
            assistant.status = "pending";
            assistant.content = "😀".repeat(ChatSessionStore.MAX_CONTENT);
            huge.add(assistant);
        }
        assertThrows(IllegalArgumentException.class,
                () -> store.appendMessages(session.id, huge));

        Session persisted = store.getSession(session.id);
        assertEquals(2, persisted.messages.size());
        assertEquals("first", persisted.messages.get(0).content);
        assertEquals(generationBefore, store.listIndex().generation);
    }

    @Test
    void reopeningStoreSeesCommittedDataOnly(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session session = store.create(request("committed"));
        store.appendMessages(session.id, turn("visible after restart"));
        store.close();

        ChatSessionStore reopened = new ChatSessionStore(memoryDir);
        Session persisted = reopened.getSession(session.id);
        assertEquals(2, persisted.messages.size());
        assertEquals(session.id, reopened.listIndex().activeSessionId);
    }

    // ── SPEC-CSS-TST-021 (deletion saga) ──
    @Test
    void pendingTranscriptDeletionIsIdempotentAcrossRestarts(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session session = store.create(request("doomed"));
        store.beginDeletion(session.id);
        // Intent is durable; the transcript is still present until the first
        // access completes the transcript half.
        assertTrue(store.pendingDeletionIds().contains(session.id));

        ChatSessionStore restarted = new ChatSessionStore(memoryDir);
        assertNull(restarted.getSession(session.id));
        // The intent survives until the AgentState half confirms via finishDeletion.
        assertTrue(restarted.pendingDeletionIds().contains(session.id));

        assertTrue(restarted.finishDeletion(session.id));
        assertTrue(restarted.pendingDeletionIds().isEmpty());
    }

    @Test
    void deletePendingTranscriptRequiresAnIntent(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session session = store.create(request("no intent"));

        assertThrows(IllegalStateException.class,
                () -> store.deletePendingTranscript(session.id));

        assertNotNull(store.getSession(session.id));
    }

    @Test
    void pendingDeletionOfActiveSessionReselectsActiveOnRecovery(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session a = store.create(request("A"));
        Thread.sleep(5);
        Session b = store.create(request("B")); // active
        store.beginDeletion(b.id);

        ChatSessionStore restarted = new ChatSessionStore(memoryDir);
        restarted.listIndex(); // first access completes the transcript half
        assertNull(restarted.getSession(b.id));
        assertEquals(a.id, restarted.listIndex().activeSessionId);
        assertTrue(restarted.pendingDeletionIds().contains(b.id));
    }

    // ── SPEC-CSS-TST-019 ──
    @Test
    void corruptDatabaseWithLegacyBackupIsRebuiltFromShards(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session a = store.create(request("A"));
        store.appendMessages(a.id, turn("正文A"));
        Session b = store.create(request("B"));
        String activeId = store.listIndex().activeSessionId;
        Session aBody = store.getSession(a.id);
        Session bBody = store.getSession(b.id);
        store.close();

        Path chatDir = memoryDir.resolve("chat-sessions");
        Path legacyDir = Files.createDirectories(chatDir.resolve("legacy"));
        MAPPER.writeValue(legacyDir.resolve(a.id + ".json").toFile(), aBody);
        MAPPER.writeValue(legacyDir.resolve(b.id + ".json").toFile(), bBody);
        Files.writeString(chatDir.resolve("chat.db"), "this is not a sqlite database");
        Files.deleteIfExists(chatDir.resolve("chat.db-wal"));
        Files.deleteIfExists(chatDir.resolve("chat.db-shm"));

        ChatSessionStore rebuilt = new ChatSessionStore(memoryDir);

        Index index = rebuilt.listIndex();
        assertEquals(2, index.sessions.size());
        assertEquals("正文A", rebuilt.getSession(a.id).messages.get(0).content);
        assertNotNull(rebuilt.getSession(b.id));
        assertEquals(activeId, index.activeSessionId);
        try (var paths = Files.list(chatDir)) {
            assertTrue(paths.anyMatch(path ->
                    path.getFileName().toString().startsWith("chat.db.corrupt-")));
        }
    }

    @Test
    void corruptDatabaseWithoutBackupStartsEmpty(@TempDir Path memoryDir) throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        store.create(request("lost"));
        store.close();

        Path chatDir = memoryDir.resolve("chat-sessions");
        Files.writeString(chatDir.resolve("chat.db"), "garbage");
        Files.deleteIfExists(chatDir.resolve("chat.db-wal"));
        Files.deleteIfExists(chatDir.resolve("chat.db-shm"));

        ChatSessionStore restarted = new ChatSessionStore(memoryDir);

        assertTrue(restarted.listIndex().sessions.isEmpty());
        assertNull(restarted.listIndex().activeSessionId);
        try (var paths = Files.list(chatDir)) {
            assertTrue(paths.anyMatch(path ->
                    path.getFileName().toString().startsWith("chat.db.corrupt-")));
        }
    }

    // ── SPEC-CSS-TST-020 ──
    @Test
    void wrongTypeChatDirectoryFailsClosed(@TempDir Path memoryDir) throws Exception {
        Files.writeString(memoryDir.resolve("chat-sessions"), "not a directory");

        assertThrows(IllegalStateException.class,
                () -> new ChatSessionStore(memoryDir).listIndex());
    }

    @Test
    void activePointerDanglingAfterExternalRowRemovalIsCleared(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        store.create(request("A"));
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                     "jdbc:sqlite:" + memoryDir.resolve("chat-sessions").resolve("chat.db"));
             java.sql.Statement statement = conn.createStatement()) {
            statement.executeUpdate(
                    "UPDATE metadata SET value = '" + "f".repeat(32)
                            + "' WHERE key = 'active_session_id'");
        }
        store.close();

        ChatSessionStore restarted = new ChatSessionStore(memoryDir);
        assertNull(restarted.listIndex().activeSessionId);
    }

    @Test
    void legacyIndexFilesNeverCreatedByNewStore(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session session = store.create(request("modern"));
        store.appendMessages(session.id, turn("data"));
        store.delete(session.id);

        Path chatDir = memoryDir.resolve("chat-sessions");
        assertFalse(Files.exists(chatDir.resolve("index.json")));
        assertFalse(Files.exists(chatDir.resolve("index.db")));
        assertFalse(Files.exists(chatDir.resolve("index.state")));
        assertFalse(Files.exists(chatDir.resolve("index.db.ready")));
        assertTrue(Files.isRegularFile(chatDir.resolve("chat.db")));
    }
}
