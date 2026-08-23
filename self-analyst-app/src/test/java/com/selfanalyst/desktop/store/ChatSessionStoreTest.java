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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        Path indexFile = memoryDir.resolve("chat-sessions").resolve("index.db");
        assertTrue(Files.isRegularFile(indexFile));
        String projectionJson = new ObjectMapper().registerModule(
                new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(idx);
        assertFalse(projectionJson.contains("\"messages\""),
                "SQLite projection rows must not contain messages");
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
        Message pending = msg("assistant", "");
        pending.status = "pending";
        store.appendMessages(s.id, List.of(msg("user", "问题"), pending));
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
        store.updateMessage(s.id, msgId, "failed", "error", "old failure", null);
        var before = store.getSession(s.id).updatedAt;
        Thread.sleep(5);

        Message updated = store.updateMessage(s.id, msgId, "最终回复", "sent", null,
                List.of(java.util.Map.of("title", "做点事")));
        assertEquals("sent", updated.status);
        assertEquals("最终回复", updated.content);
        assertNull(updated.error);
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
        List<Message> first = new ArrayList<>();
        List<Message> second = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            first.add(msg("user", "m" + i));
        }
        for (int i = 150; i < 250; i++) {
            second.add(msg("user", "m" + i));
        }
        store.appendMessages(s.id, first);
        store.appendMessages(s.id, second);
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

    @Test
    void unicodeContentTruncationDoesNotSplitSurrogatePairs(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("unicode"));

        store.appendMessages(s.id, List.of(msg("user", "😀".repeat(20001))));

        String stored = store.getSession(s.id).messages.getFirst().content;
        assertEquals(20003, stored.codePointCount(0, stored.length()));
        assertTrue(stored.endsWith("..."));
        assertFalse(stored.contains("�"));
    }

    @Test
    void boundsAuxiliaryFieldsAndSuggestedTaskCollections(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        CreateRequest request = req("t".repeat(400));
        request.source = "task_context";
        request.contextLabel = "label".repeat(100);
        request.contextSnapshot = Map.of("type", "task", "payload", "x".repeat(100_000));
        Session session = store.create(request);

        Message assistant = msg("assistant", "answer");
        assistant.status = "pending";
        assistant.contextSnapshot = Map.of("type", "turn", "payload", "y".repeat(100_000));
        List<Object> suggestedTasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            suggestedTasks.add(Map.of(
                    "title", "task-" + i,
                    "payload", "z".repeat(20_000)));
        }
        Message storedAssistant = store.appendMessages(session.id, List.of(assistant)).getFirst();
        store.updateMessage(session.id, storedAssistant.id,
                "answer", "sent", null, suggestedTasks);
        Message failing = msg("assistant", "thinking");
        failing.status = "pending";
        Message storedFailing = store.appendMessages(session.id, List.of(failing)).getFirst();
        store.updateMessage(session.id, storedFailing.id,
                "failed", "error", "e".repeat(10_000), null);

        Session persisted = store.getSession(session.id);
        Message bounded = persisted.messages.getFirst();
        Message boundedError = persisted.messages.get(1);
        assertTrue(persisted.title.codePointCount(0, persisted.title.length())
                <= ChatSessionStore.MAX_TITLE);
        assertTrue(persisted.source.codePointCount(0, persisted.source.length())
                <= ChatSessionStore.MAX_SOURCE);
        assertTrue(persisted.contextLabel.codePointCount(0, persisted.contextLabel.length())
                <= ChatSessionStore.MAX_CONTEXT_LABEL);
        assertTrue(new ObjectMapper().writeValueAsBytes(persisted.contextSnapshot).length
                <= ChatSessionStore.MAX_CONTEXT_SNAPSHOT_BYTES);
        assertTrue(boundedError.error.codePointCount(0, boundedError.error.length())
                <= ChatSessionStore.MAX_ERROR);
        assertTrue(new ObjectMapper().writeValueAsBytes(bounded.contextSnapshot).length
                <= ChatSessionStore.MAX_CONTEXT_SNAPSHOT_BYTES);
        assertEquals(ChatSessionStore.MAX_SUGGESTED_TASKS, bounded.suggestedTasks.size());
        assertTrue(new ObjectMapper().writeValueAsBytes(bounded.suggestedTasks).length
                <= ChatSessionStore.MAX_SUGGESTED_TASKS_BYTES);

        CreateRequest invalidSource = req("bad source");
        invalidSource.source = "external";
        assertThrows(IllegalArgumentException.class, () -> store.create(invalidSource));
    }

    @Test
    void rejectsInvalidOrOversizedMessageBatchesBeforeWriting(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("validation"));
        Path shard = memoryDir.resolve("chat-sessions").resolve(s.id + ".json");
        byte[] before = Files.readAllBytes(shard);
        List<Message> tooMany = new ArrayList<>();
        for (int i = 0; i <= ChatSessionStore.MAX_MESSAGE_BATCH; i++) {
            tooMany.add(msg("user", "m" + i));
        }

        assertThrows(IllegalArgumentException.class,
                () -> store.appendMessages(s.id, tooMany));
        assertThrows(IllegalArgumentException.class,
                () -> store.appendMessages(s.id, List.of(msg("owner", "invalid"))));
        List<Message> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> store.appendMessages(s.id, withNull));
        CreateRequest emptyInitial = req("empty initial");
        emptyInitial.initialMessages = new ArrayList<>();
        int sessionsBefore = store.listIndex().sessions.size();
        assertThrows(IllegalArgumentException.class, () -> store.create(emptyInitial));
        assertEquals(sessionsBefore, store.listIndex().sessions.size());
        assertEquals(new String(before), new String(Files.readAllBytes(shard)));
    }

    @Test
    void assistantLifecycleRejectsUserEditsAndLateErrorsAfterSent(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("lifecycle"));
        Message user = msg("user", "question");
        user.status = "sent";
        Message pending = msg("assistant", "thinking");
        pending.status = "pending";
        List<Message> appended = store.appendMessages(s.id, List.of(user, pending));

        assertThrows(IllegalArgumentException.class,
                () -> store.updateMessage(s.id, appended.get(0).id,
                        "edited", null, null, null));
        Message sent = store.updateMessage(s.id, appended.get(1).id,
                "answer", "sent", null, List.of(Map.of("title", "task")));
        assertEquals("sent", sent.status);
        Path shard = memoryDir.resolve("chat-sessions").resolve(s.id + ".json");
        byte[] beforeLateError = Files.readAllBytes(shard);

        assertThrows(IllegalArgumentException.class,
                () -> store.updateMessage(s.id, sent.id,
                        "late failure", "error", "network", null));
        assertEquals(new String(beforeLateError), new String(Files.readAllBytes(shard)));
        assertEquals("sent", store.getSession(s.id).messages.get(1).status);
    }

    @Test
    void shardByteBudgetDropsOnlyCompleteOldTurns(@TempDir Path memoryDir) throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("bounded shard"));
        String large = "😀".repeat(ChatSessionStore.MAX_CONTENT);
        List<Message> turns = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Message user = msg("user", large);
            user.status = "sent";
            Message assistant = msg("assistant", large);
            assistant.status = "pending";
            turns.add(user);
            turns.add(assistant);
        }

        store.appendMessages(s.id, turns);

        Session persisted = store.getSession(s.id);
        Path shard = memoryDir.resolve("chat-sessions").resolve(s.id + ".json");
        assertTrue(Files.size(shard) <= ChatSessionStore.MAX_SHARD_BYTES);
        assertTrue(persisted.messages.size() < ChatSessionStore.MAX_MESSAGES);
        assertEquals("user", persisted.messages.getFirst().role);
        assertEquals(0, persisted.messages.size() % 2);
    }

    @Test
    void rejectsASingleTurnThatWouldOrphanItsUserAtMessageLimit(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("single turn"));
        List<Message> turn = new ArrayList<>();
        Message user = msg("user", "question");
        user.status = "sent";
        turn.add(user);
        for (int i = 0; i < ChatSessionStore.MAX_MESSAGES - 1; i++) {
            Message assistant = msg("assistant", "reasoning-" + i);
            assistant.status = "pending";
            turn.add(assistant);
        }
        store.appendMessages(s.id, turn);
        Path shard = memoryDir.resolve("chat-sessions").resolve(s.id + ".json");
        byte[] before = Files.readAllBytes(shard);
        Message extraAssistant = msg("assistant", "one too many");
        extraAssistant.status = "pending";

        assertThrows(IllegalArgumentException.class,
                () -> store.appendMessages(s.id, List.of(extraAssistant)));

        assertEquals(new String(before), new String(Files.readAllBytes(shard)));
        assertEquals("user", store.getSession(s.id).messages.getFirst().role);
        assertEquals(ChatSessionStore.MAX_MESSAGES, store.getSession(s.id).messages.size());
    }

    @Test
    void rejectsASingleTurnThatCannotFitTheShardByteBudget(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("single huge turn"));
        Path shard = memoryDir.resolve("chat-sessions").resolve(s.id + ".json");
        byte[] before = Files.readAllBytes(shard);
        String large = "😀".repeat(ChatSessionStore.MAX_CONTENT);
        List<Message> turn = new ArrayList<>();
        Message user = msg("user", large);
        user.status = "sent";
        turn.add(user);
        for (int i = 1; i < ChatSessionStore.MAX_MESSAGES; i++) {
            Message assistant = msg("assistant", large);
            assistant.status = "pending";
            turn.add(assistant);
        }

        assertThrows(IllegalArgumentException.class,
                () -> store.appendMessages(s.id, turn));

        assertEquals(new String(before), new String(Files.readAllBytes(shard)));
        assertTrue(store.getSession(s.id).messages.isEmpty());
    }

    @Test
    void opaqueDepthAndNodeOverflowUseABoundedRootMarker(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        CreateRequest wide = req("wide");
        List<List<Integer>> moreThanNodeBudget = new ArrayList<>();
        for (int group = 0; group < 16; group++) {
            moreThanNodeBudget.add(
                    java.util.stream.IntStream.range(0, 200).boxed().toList());
        }
        wide.contextSnapshot = Map.of("values", moreThanNodeBudget);
        Session wideSession = store.create(wide);
        assertEquals(true, ((Map<?, ?>) wideSession.contextSnapshot).get("_truncated"));

        Object deep = "leaf";
        for (int i = 0; i < ChatSessionStore.MAX_OPAQUE_DEPTH + 2; i++) {
            deep = Map.of("child", deep);
        }
        CreateRequest nested = req("nested");
        nested.contextSnapshot = deep;
        Session nestedSession = store.create(nested);
        assertEquals(true, ((Map<?, ?>) nestedSession.contextSnapshot).get("_truncated"));
    }

    @Test
    void oversizedLegacyShardIsReadableThenConvergesOnMutation(@TempDir Path memoryDir)
            throws Exception {
        Path chatDir = memoryDir.resolve("chat-sessions");
        Files.createDirectories(chatDir);
        String id = "d".repeat(32);
        Session legacy = new Session();
        legacy.id = id;
        legacy.title = "legacy".repeat(100);
        legacy.source = "manual";
        legacy.createdAt = java.time.Instant.parse("2026-01-01T00:00:00Z");
        legacy.updatedAt = legacy.createdAt;
        legacy.messages = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            Message user = msg("user", "u" + i);
            user.status = "sent";
            legacy.messages.add(user);
            if (i < 100) {
                Message assistant = msg("assistant", "a" + i);
                assistant.status = "sent";
                legacy.messages.add(assistant);
            }
        }
        new ObjectMapper().registerModule(
                new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValue(chatDir.resolve(id + ".json").toFile(), legacy);
        ChatSessionStore store = new ChatSessionStore(memoryDir);

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
        assertTrue(Files.size(chatDir.resolve(id + ".json")) <= ChatSessionStore.MAX_SHARD_BYTES);
    }

    @Test
    void indexPreviewTruncatesEmojiByCodePoint(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("preview"));
        store.appendMessages(s.id, List.of(msg("user", "😀".repeat(100))));

        String preview = store.listIndex().sessions.getFirst().lastMessagePreview;
        assertEquals(80, preview.codePointCount(0, preview.length()));
        assertFalse(preview.contains("�"));
    }

    @Test
    void cursorPagesAreStableAndSearchCoversTheWholeIndex(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < 5; i++) sessions.add(store.create(req("session-" + i)));
        Path chatDir = memoryDir.resolve("chat-sessions");
        Instant sameTime = Instant.parse("2026-08-23T00:00:00Z");
        ObjectMapper mapper = new ObjectMapper().registerModule(
                new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        for (int i = 0; i < sessions.size(); i++) {
            Path shard = chatDir.resolve(sessions.get(i).id + ".json");
            Session raw = mapper.readValue(shard.toFile(), Session.class);
            raw.updatedAt = sameTime;
            if (i == 4) raw.summary = "needle in an older page";
            mapper.writeValue(shard.toFile(), raw);
        }
        Files.delete(chatDir.resolve("index.state"));
        ChatSessionStore restarted = new ChatSessionStore(memoryDir);
        List<String> fullOrder = restarted.listIndex().sessions.stream()
                .map(meta -> meta.id).toList();
        List<String> expectedTieOrder = sessions.stream().map(session -> session.id)
                .sorted(Comparator.reverseOrder()).toList();
        assertEquals(expectedTieOrder, fullOrder);
        List<String> pagedOrder = new ArrayList<>();
        String cursor = null;
        do {
            ChatSessionStore.IndexPage page = restarted.listIndexPage(2, cursor, null);
            pagedOrder.addAll(page.sessions().stream().map(meta -> meta.id).toList());
            cursor = page.nextCursor();
            if (!page.hasMore()) break;
        } while (true);

        assertEquals(fullOrder, pagedOrder);
        assertEquals(5, pagedOrder.stream().distinct().count());
        ChatSessionStore.IndexPage search = restarted.listIndexPage(2, null, "NEEDLE");
        assertEquals(1, search.sessions().size());
        assertEquals("needle in an older page", search.sessions().getFirst().summary);
        assertThrows(IllegalArgumentException.class,
                () -> restarted.listIndexPage(2, "not-base64", null));
        assertThrows(IllegalArgumentException.class,
                () -> restarted.listIndexPage(2, "", null));
        String missingGeneration = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"id\":\"" + fullOrder.getFirst()
                        + "\",\"updatedAt\":\"" + sameTime + "\",\"query\":\"\"}")
                        .getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class,
                () -> restarted.listIndexPage(2, missingGeneration, null));

        ChatSessionStore.IndexPage first = restarted.listIndexPage(2, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> restarted.listIndexPage(2, first.nextCursor(), "different"));
        String unseenId = fullOrder.get(3);
        restarted.updateMeta(unseenId, "moved ahead", null, null);
        assertThrows(IllegalArgumentException.class,
                () -> restarted.listIndexPage(2, first.nextCursor(), null));
    }

    // ── TST-014 ──
    @Test
    void corruptIndexRebuiltFromShards(@TempDir Path memoryDir) throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session a = store.create(req("A"));
        store.appendMessages(a.id, List.of(msg("user", "正文A")));
        Session b = store.create(req("B"));

        Files.writeString(memoryDir.resolve("chat-sessions").resolve("index.json"), """
                {"activeSessionId":null,"sessions":[{"id":"%s","title":"stale"}]}
                """.formatted("f".repeat(32)));
        // Corrupt the rebuildable SQLite projection.
        Path indexFile = memoryDir.resolve("chat-sessions").resolve("index.db");
        Files.writeString(indexFile, "{ this is not valid json");

        Index rebuilt = store.listIndex();
        assertEquals(2, rebuilt.sessions.size());
        // Bodies survive.
        assertEquals("正文A", store.getSession(a.id).messages.get(0).content);
        assertNotNull(store.getSession(b.id));
        try (var paths = Files.list(indexFile.getParent())) {
            assertTrue(paths.anyMatch(path ->
                    path.getFileName().toString().startsWith("index.db.corrupt-")));
        }
    }

    @Test
    void invalidSqliteMigrationMarkerFailsClosed(@TempDir Path memoryDir) throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session session = store.create(req("marker"));
        Path chatDir = memoryDir.resolve("chat-sessions");
        Path marker = chatDir.resolve("index.db.ready");
        Files.writeString(marker, "{\"protocolVersion\":99,\"projection\":\"future\"}");
        byte[] databaseBefore = Files.readAllBytes(chatDir.resolve("index.db"));
        byte[] shardBefore = Files.readAllBytes(chatDir.resolve(session.id + ".json"));
        byte[] stateBefore = Files.readAllBytes(chatDir.resolve("index.state"));

        assertThrows(IllegalStateException.class,
                () -> new ChatSessionStore(memoryDir).listIndex());

        assertArrayEquals(databaseBefore, Files.readAllBytes(chatDir.resolve("index.db")));
        assertArrayEquals(shardBefore, Files.readAllBytes(chatDir.resolve(session.id + ".json")));
        assertArrayEquals(stateBefore, Files.readAllBytes(chatDir.resolve("index.state")));
    }

    // ── TST-012 / TST-013 preconditions: not-found + invalid-pointer signals ──
    @Test
    void unknownIdReturnsNullSignals(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        String missing = "f".repeat(32);
        assertNull(store.getSession(missing));
        assertNull(store.updateMeta(missing, "x", null, null));
        assertNull(store.updateMessage(missing, "m", "c", null, null, null));
        assertNull(store.delete(missing));
        assertNull(store.appendMessages(missing, List.of(msg("user", "x"))));
        assertNull(store.updateMemoryPolicy(missing, "off"));
    }

    @Test
    void rejectsTraversalAndUnsafeSessionIdsWithoutTouchingOutsideFiles(
            @TempDir Path memoryDir) throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Path sentinel = Files.writeString(memoryDir.resolve("outside.json"), "keep");

        for (String id : List.of(
                ".", "..", "../outside", "..\\outside", "a/b", "a\\b",
                "session.json", "legacy_session", "nope", "x".repeat(65), "会话")) {
            assertThrows(IllegalArgumentException.class, () -> store.getSession(id), id);
            assertThrows(IllegalArgumentException.class, () -> store.delete(id), id);
        }

        assertEquals("keep", Files.readString(sentinel));
        assertTrue(ChatSessionStore.isGeneratedSessionId("a".repeat(32)));
        assertFalse(ChatSessionStore.isGeneratedSessionId("legacy_session"));
    }

    @Test
    void setActiveSessionRejectsUnknownId(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.setActiveSession("e".repeat(32)));
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

    @Test
    void sessionDefaultsMemoryPolicyToSmart(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("memory"));

        assertEquals("smart", s.memoryPolicy);
        assertEquals("smart", store.listIndex().sessions.get(0).memoryPolicy);
    }

    @Test
    void sessionHonorsProvidedAndBlankMemoryPolicies(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        CreateRequest off = req("off");
        off.memoryPolicy = "off";
        CreateRequest blank = req("blank");
        blank.memoryPolicy = " ";

        Session offSession = store.create(off);
        Session blankSession = store.create(blank);

        assertEquals("off", offSession.memoryPolicy);
        assertEquals("off", store.getSession(offSession.id).memoryPolicy);
        assertEquals("smart", blankSession.memoryPolicy);
    }

    @Test
    void updateMemoryPolicyPersistsToShardAndIndex(@TempDir Path memoryDir) throws Exception {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        Session s = store.create(req("memory"));
        var before = s.updatedAt;
        Thread.sleep(5);

        Session updated = store.updateMemoryPolicy(s.id, "confirm_all");

        assertEquals("confirm_all", updated.memoryPolicy);
        assertEquals("confirm_all", store.getSession(s.id).memoryPolicy);
        assertEquals("confirm_all", store.listIndex().sessions.get(0).memoryPolicy);
        assertTrue(updated.updatedAt.isAfter(before));
        assertThrows(IllegalArgumentException.class, () -> store.updateMemoryPolicy(s.id, "always"));
    }

    @Test
    void createRejectsInvalidMemoryPolicy(@TempDir Path memoryDir) {
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        CreateRequest bad = req("bad policy");
        bad.memoryPolicy = "always";

        assertThrows(IllegalArgumentException.class, () -> store.create(bad));
        assertTrue(store.listIndex().sessions.isEmpty());
    }

    @Test
    void legacyShardAndIndexDefaultMemoryPolicyOnRead(@TempDir Path memoryDir) throws Exception {
        Path chatDir = memoryDir.resolve("chat-sessions");
        Files.createDirectories(chatDir);
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
        byte[] legacyIndex = Files.readAllBytes(chatDir.resolve("index.json"));
        store.updateMeta(id, "SQLite row update", null, null);
        assertArrayEquals(legacyIndex, Files.readAllBytes(chatDir.resolve("index.json")),
                "normal mutations must not rewrite the migrated v1 JSON projection");
        assertEquals("SQLite row update", store.listIndex().sessions.getFirst().title);
        assertTrue(Files.isRegularFile(chatDir.resolve("index.db")));
        assertTrue(Files.isRegularFile(chatDir.resolve("index.db.ready")));
    }

    @Test
    void invalidMemoryPolicyInShardDefaultsToSmartOnRead(@TempDir Path memoryDir) throws Exception {
        Path chatDir = memoryDir.resolve("chat-sessions");
        Files.createDirectories(chatDir);
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
    void ghostMetaWithoutShardIsDroppedDuringV1Recovery(@TempDir Path memoryDir) throws Exception {
        Path chatDir = memoryDir.resolve("chat-sessions");
        Files.createDirectories(chatDir);
        String id = "c".repeat(32);
        Files.writeString(chatDir.resolve("index.json"), """
                {
                  "activeSessionId": "%s",
                  "sessions": [
                    {
                      "id": "%s",
                      "title": "Future",
                      "createdAt": "2026-01-01T00:00:00Z",
                      "updatedAt": "2026-01-01T00:00:00Z",
                      "source": "manual",
                      "memoryPolicy": "future_policy",
                      "messageCount": 0
                    }
                  ]
                }
                """.formatted(id, id));

        ChatSessionStore store = new ChatSessionStore(memoryDir);

        Index recovered = store.listIndex();
        assertTrue(recovered.sessions.isEmpty());
        assertNull(recovered.activeSessionId);
    }
}
