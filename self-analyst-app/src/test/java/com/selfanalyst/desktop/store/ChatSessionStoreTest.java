package com.selfanalyst.desktop.store;

import com.selfanalyst.desktop.store.ChatSessionStore.CreateRequest;
import com.selfanalyst.desktop.store.ChatSessionStore.DeleteResult;
import com.selfanalyst.desktop.store.ChatSessionStore.Index;
import com.selfanalyst.desktop.store.ChatSessionStore.Message;
import com.selfanalyst.desktop.store.ChatSessionStore.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sharded persistence, CRUD, invariants, isolation, index rebuild (SPEC-CSP-TST-001..014). */
class ChatSessionStoreTest {

    private static Message msg(String role, String content) {
        Message m = new Message();
        m.role = role;
        m.content = content;
        return m;
    }

    private static CreateRequest req(String title, Message... initial) {
        CreateRequest r = new CreateRequest();
        r.title = title;
        if (initial.length > 0) {
            r.initialMessages = new ArrayList<>(List.of(initial));
        }
        return r;
    }

    // ── TST-001 ──
    @Test
    void freshIndexIsEmpty(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Index idx = store.listIndex();
        assertNull(idx.activeSessionId);
        assertTrue(idx.sessions.isEmpty());
    }

    // ── TST-002 ──
    @Test
    void indexRowsAreMetaWithoutMessages(@TempDir Path memoryDir) throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("会话A"));
        store.appendMessages(s.id, List.of(msg("user", "你好世界")));

        Index idx = store.listIndex();
        assertEquals(1, idx.sessions.size());
        ChatSessionStore.SessionMeta meta = idx.sessions.get(0);
        assertEquals(1, meta.messageCount);
        assertEquals("你好世界", meta.lastMessagePreview);

        // The on-disk index.json must not carry message bodies.
        Path indexFile = memoryDir.resolve("chat-sessions").resolve("index.json");
        String json = Files.readString(indexFile);
        assertFalse(json.contains("\"messages\""), "index.json must not contain messages");
    }

    // ── TST-003 ──
    @Test
    void createAssignsIdTimestampsShardAndActive(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("会话A"));
        assertNotNull(s.id);
        assertNotNull(s.createdAt);
        assertNotNull(s.updatedAt);
        assertTrue(Files.exists(memoryDir.resolve("chat-sessions").resolve(s.id + ".json")));
        assertEquals(s.id, store.listIndex().activeSessionId);
    }

    // ── TST-004 ──
    @Test
    void appendUserAndPendingOrderedWithServerIds(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("会话A"));
        store.appendMessages(s.id, List.of(msg("user", "问题"), msg("assistant", "")));
        Session loaded = store.getSession(s.id);
        assertEquals(2, loaded.messages.size());
        assertEquals("user", loaded.messages.get(0).role);
        assertEquals("assistant", loaded.messages.get(1).role);
        assertNotNull(loaded.messages.get(0).id);
        assertNotNull(loaded.messages.get(1).id);
    }

    // ── TST-005 ──
    @Test
    void updateMessagePendingToSentAdvancesUpdatedAt(@TempDir Path memoryDir) throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("会话A"));
        Message pending = msg("assistant", "");
        pending.status = "pending";
        store.appendMessages(s.id, List.of(pending));
        String msgId = store.getSession(s.id).messages.get(0).id;
        var before = store.getSession(s.id).updatedAt;
        Thread.sleep(5);

        Message updated = store.updateMessage(s.id, msgId, "最终回复", "sent", null,
                List.of(java.util.Map.of("title", "做点事")));
        assertEquals("sent", updated.status);
        assertEquals("最终回复", updated.content);
        assertNotNull(updated.suggestedTasks);
        assertTrue(store.getSession(s.id).updatedAt.isAfter(before));
    }

    // ── TST-006 ──
    @Test
    void updateMetaChangesTitleNotMessages(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("旧标题"));
        store.appendMessages(s.id, List.of(msg("user", "hi")));
        Session updated = store.updateMeta(s.id, "新标题", null, null);
        assertEquals("新标题", updated.title);
        assertEquals(1, updated.messages.size());
        assertEquals("新标题", store.listIndex().sessions.get(0).title);
    }

    // ── TST-007 ──
    @Test
    void deleteActiveReselectsNewestAndRemovesShard(@TempDir Path memoryDir) throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session a = store.create(req("A"));
        Thread.sleep(5);
        Session b = store.create(req("B")); // b is active (created last)
        DeleteResult result = store.delete(b.id);
        assertTrue(result.deleted());
        assertEquals(a.id, result.activeSessionId()); // reselect remaining
        assertFalse(Files.exists(memoryDir.resolve("chat-sessions").resolve(b.id + ".json")));

        DeleteResult last = store.delete(a.id);
        assertNull(last.activeSessionId()); // none left
    }

    // ── TST-008 ──
    @Test
    void manySessionsAllRetainedNoPrune(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(store.create(req("S" + i)).id);
        }
        assertEquals(200, store.listIndex().sessions.size());
        for (String id : ids) {
            assertNotNull(store.getSession(id), "session " + id + " must still be readable");
        }
    }

    // ── TST-009 ──
    @Test
    void appendToAisolatesFromBshard(@TempDir Path memoryDir) throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session a = store.create(req("A"));
        Session b = store.create(req("B"));
        Path bShard = memoryDir.resolve("chat-sessions").resolve(b.id + ".json");
        byte[] before = Files.readAllBytes(bShard);

        store.appendMessages(a.id, List.of(msg("user", "只改 A")));
        byte[] after = Files.readAllBytes(bShard);
        assertEquals(new String(before), new String(after), "B's shard must be byte-identical");
    }

    // ── TST-010 ──
    @Test
    void over200MessagesKeepsNewest200(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("A"));
        List<Message> batch = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            batch.add(msg("user", "m" + i));
        }
        store.appendMessages(s.id, batch);
        Session loaded = store.getSession(s.id);
        assertEquals(200, loaded.messages.size());
        assertEquals("m50", loaded.messages.get(0).content);  // oldest 50 dropped
        assertEquals("m249", loaded.messages.get(199).content);
    }

    // ── TST-011 ──
    @Test
    void overLongContentTruncatedWithEllipsis(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("A"));
        String huge = "x".repeat(20005);
        store.appendMessages(s.id, List.of(msg("user", huge)));
        String stored = store.getSession(s.id).messages.get(0).content;
        assertEquals(20000 + 3, stored.length());
        assertTrue(stored.endsWith("..."));
    }

    // ── TST-014 ──
    @Test
    void corruptIndexRebuiltFromShards(@TempDir Path memoryDir) throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session a = store.create(req("A"));
        store.appendMessages(a.id, List.of(msg("user", "正文A")));
        Session b = store.create(req("B"));

        // Corrupt index.json.
        Path indexFile = memoryDir.resolve("chat-sessions").resolve("index.json");
        Files.writeString(indexFile, "{ this is not valid json");

        Index rebuilt = store.listIndex();
        assertEquals(2, rebuilt.sessions.size());
        // Bodies survive.
        assertEquals("正文A", store.getSession(a.id).messages.get(0).content);
        assertNotNull(store.getSession(b.id));
    }

    // ── TST-012 / TST-013 preconditions: not-found + invalid-pointer signals ──
    @Test
    void unknownIdReturnsNullSignals(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        assertNull(store.getSession("nope"));
        assertNull(store.updateMeta("nope", "x", null, null));
        assertNull(store.updateMessage("nope", "m", "c", null, null, null));
        assertNull(store.delete("nope"));
        assertNull(store.appendMessages("nope", List.of(msg("user", "x"))));
    }

    @Test
    void setActiveSessionRejectsUnknownId(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.setActiveSession("ghost"));
    }

    @Test
    void shardJsonRoundTripsOpaqueFields(@TempDir Path memoryDir) throws Exception {
        // Guards SPEC-CSP-MODEL-003/004: opaque + unknown fields survive.
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("A"));
        Path shard = memoryDir.resolve("chat-sessions").resolve(s.id + ".json");
        String json = Files.readString(shard);
        // Inject an unknown field; must not break reads.
        json = json.replaceFirst("\\{", "{ \"unknownX\": 1,");
        Files.writeString(shard, json);
        Session reloaded = new ObjectMapper().registerModule(
                new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .readValue(shard.toFile(), Session.class);
        assertEquals(s.id, reloaded.id);
    }
}
