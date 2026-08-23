package com.selfanalyst.desktop.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionStoreRecoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void v1FirstAccessReconcilesOrphanGhostDuplicateAndStaleMeta(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session a = initial.create(request("A"));
        ChatSessionStore.Session b = initial.create(request("B"));
        Path chatDir = memoryDir.resolve("chat-sessions");
        Path aShard = chatDir.resolve(a.id + ".json");
        ChatSessionStore.Session authoritativeA = MAPPER.readValue(
                aShard.toFile(), ChatSessionStore.Session.class);
        authoritativeA.title = "authoritative A";
        authoritativeA.updatedAt = Instant.parse("2026-08-23T01:00:00Z");
        MAPPER.writeValue(aShard.toFile(), authoritativeA);

        ChatSessionStore.Index stale = new ChatSessionStore.Index();
        stale.activeSessionId = b.id;
        ChatSessionStore.SessionMeta staleA = meta(a.id, "stale A");
        stale.sessions = new ArrayList<>(List.of(staleA, staleA, meta("c".repeat(32), "ghost")));
        MAPPER.writeValue(chatDir.resolve("index.json").toFile(), stale);
        Files.delete(chatDir.resolve("index.db"));
        Files.delete(chatDir.resolve("index.db.ready"));
        Files.delete(chatDir.resolve("index.state"));

        ChatSessionStore.Index recovered = new ChatSessionStore(memoryDir).listIndex();

        assertEquals(2, recovered.sessions.size());
        assertEquals(2, recovered.sessions.stream().map(meta -> meta.id).distinct().count());
        assertEquals("authoritative A", recovered.sessions.stream()
                .filter(meta -> a.id.equals(meta.id)).findFirst().orElseThrow().title);
        assertEquals(b.id, recovered.activeSessionId);
        assertEquals("CLEAN", state(chatDir).get("state").asText());
    }

    @Test
    void preCommitShardMoveFailureLeavesOriginalAndRecoversDirtyIntent(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = initial.create(request("before"));
        Path chatDir = memoryDir.resolve("chat-sessions");
        Path shard = chatDir.resolve(session.id + ".json");
        byte[] before = Files.readAllBytes(shard);
        ScriptedIo io = ScriptedIo.beforeMove(session.id + ".json", 1);
        ChatSessionStore failing = new ChatSessionStore(memoryDir, io);

        assertThrows(RuntimeException.class,
                () -> failing.appendMessages(session.id, turn("new question")));

        assertArrayEquals(before, Files.readAllBytes(shard));
        assertEquals("DIRTY", state(chatDir).get("state").asText());
        ChatSessionStore restarted = new ChatSessionStore(memoryDir);
        assertTrue(restarted.getSession(session.id).messages.isEmpty());
        assertEquals("CLEAN", state(chatDir).get("state").asText());
    }

    @Test
    void postCommitIndexFailureReturnsSuccessAndRestartRepairsProjection(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = initial.create(request("append"));
        Path chatDir = memoryDir.resolve("chat-sessions");
        ChatSessionStore failing = new ChatSessionStore(
                memoryDir, ChatSessionStoreIo.nio(),
                FailingIndex.failUpsert(chatDir.resolve("index.db")));

        List<ChatSessionStore.Message> appended =
                failing.appendMessages(session.id, turn("committed once"));

        assertEquals(2, appended.size());
        assertEquals(0, new SqliteChatSessionIndex(chatDir.resolve("index.db"))
                .load().sessions.getFirst().messageCount);
        assertEquals("DIRTY", state(chatDir).get("state").asText());

        ChatSessionStore restarted = new ChatSessionStore(memoryDir);
        ChatSessionStore.Index recovered = restarted.listIndex();
        assertEquals(2, recovered.sessions.getFirst().messageCount);
        assertEquals(2, restarted.getSession(session.id).messages.size());
        assertEquals("CLEAN", state(chatDir).get("state").asText());
    }

    @Test
    void createIndexFailureRecoversNewSessionAsActive(@TempDir Path memoryDir) throws Exception {
        Path chatDir = memoryDir.resolve("chat-sessions");
        ChatSessionStore creating = new ChatSessionStore(
                memoryDir, ChatSessionStoreIo.nio(),
                FailingIndex.failUpsert(chatDir.resolve("index.db")));

        ChatSessionStore.Session created = creating.create(request("created"));

        assertTrue(Files.exists(chatDir.resolve(created.id + ".json")));
        assertEquals("DIRTY", state(chatDir).get("state").asText());
        ChatSessionStore.Index recovered = new ChatSessionStore(memoryDir).listIndex();
        assertEquals(created.id, recovered.activeSessionId);
        assertEquals(List.of(created.id), recovered.sessions.stream().map(meta -> meta.id).toList());
    }

    @Test
    void crashAfterShardMoveIsRecoveredFromAuthoritativeShard(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = initial.create(request("crash"));
        Path chatDir = memoryDir.resolve("chat-sessions");
        ScriptedIo io = ScriptedIo.afterMove(session.id + ".json", 1);
        ChatSessionStore crashing = new ChatSessionStore(memoryDir, io);

        assertThrows(SimulatedCrash.class,
                () -> crashing.appendMessages(session.id, turn("survives crash")));

        assertEquals("DIRTY", state(chatDir).get("state").asText());
        ChatSessionStore restarted = new ChatSessionStore(memoryDir);
        assertEquals(2, restarted.getSession(session.id).messages.size());
        assertEquals(2, restarted.listIndex().sessions.getFirst().messageCount);
        assertEquals("CLEAN", state(chatDir).get("state").asText());
    }

    @Test
    void deleteIndexFailureReturnsSuccessAndRestartDropsGhostAndReselectsActive(
            @TempDir Path memoryDir) throws Exception {
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session a = initial.create(request("A"));
        ChatSessionStore.Session b = initial.create(request("B"));
        Path chatDir = memoryDir.resolve("chat-sessions");
        ChatSessionStore failing = new ChatSessionStore(
                memoryDir, ChatSessionStoreIo.nio(),
                FailingIndex.failDelete(chatDir.resolve("index.db")));

        ChatSessionStore.DeleteResult result = failing.delete(b.id);

        assertTrue(result.deleted());
        assertEquals(a.id, result.activeSessionId());
        assertFalse(Files.exists(chatDir.resolve(b.id + ".json")));
        assertEquals("DIRTY", state(chatDir).get("state").asText());
        ChatSessionStore.Index recovered = new ChatSessionStore(memoryDir).listIndex();
        assertEquals(List.of(a.id), recovered.sessions.stream().map(meta -> meta.id).toList());
        assertEquals(a.id, recovered.activeSessionId);
    }

    @Test
    void preCommitDeleteFailureLeavesShardAndIndexUntouched(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = initial.create(request("delete failure"));
        Path chatDir = memoryDir.resolve("chat-sessions");
        Path shard = chatDir.resolve(session.id + ".json");
        byte[] shardBefore = Files.readAllBytes(shard);
        byte[] indexBefore = Files.readAllBytes(chatDir.resolve("index.db"));
        ChatSessionStore failing = new ChatSessionStore(
                memoryDir, ScriptedIo.beforeDelete(session.id + ".json", 1));

        assertThrows(RuntimeException.class, () -> failing.delete(session.id));

        assertArrayEquals(shardBefore, Files.readAllBytes(shard));
        assertArrayEquals(indexBefore, Files.readAllBytes(chatDir.resolve("index.db")));
        byte[] dirtyBefore = Files.readAllBytes(chatDir.resolve("index.state"));
        ChatSessionStore indeterminate = new ChatSessionStore(
                memoryDir, ScriptedIo.failStatus(session.id + ".json"));
        assertThrows(IllegalStateException.class, indeterminate::listIndex);
        assertArrayEquals(indexBefore, Files.readAllBytes(chatDir.resolve("index.db")));
        assertArrayEquals(dirtyBefore, Files.readAllBytes(chatDir.resolve("index.state")));
        ChatSessionStore restarted = new ChatSessionStore(memoryDir);
        assertNotNull(restarted.getSession(session.id));
        assertEquals(session.id, restarted.listIndex().activeSessionId);
    }

    @Test
    void cleanStateFailureIsNonFatalAndIdempotentlyRecovered(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = initial.create(request("clean marker"));
        Path chatDir = memoryDir.resolve("chat-sessions");
        ChatSessionStore failing = new ChatSessionStore(
                memoryDir, ScriptedIo.beforeMove("index.state", 2));

        assertEquals(2, failing.appendMessages(session.id, turn("committed")).size());
        assertEquals("DIRTY", state(chatDir).get("state").asText());

        ChatSessionStore restarted = new ChatSessionStore(memoryDir);
        assertEquals(2, restarted.listIndex().sessions.getFirst().messageCount);
        assertEquals("CLEAN", state(chatDir).get("state").asText());
    }

    @Test
    void firstAccessCleansOnlyRecognizedStoreTemps(@TempDir Path memoryDir) throws Exception {
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        initial.create(request("temps"));
        Path chatDir = memoryDir.resolve("chat-sessions");
        Path recognized = Files.writeString(
                chatDir.resolve(".index.json.123.chat-tmp"), "partial");
        Path unrelated = Files.writeString(chatDir.resolve("orphan.chat-tmp"), "keep");

        new ChatSessionStore(memoryDir).listIndex();

        assertFalse(Files.exists(recognized));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void malformedFutureOrIncompleteRecoveryStateFailsClosed(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = initial.create(request("state validation"));
        Path chatDir = memoryDir.resolve("chat-sessions");
        Path stateFile = chatDir.resolve("index.state");
        Path indexFile = chatDir.resolve("index.db");
        byte[] indexBefore = Files.readAllBytes(indexFile);
        String other = session.id.charAt(0) == 'a' ? "b".repeat(32) : "a".repeat(32);
        String third = "c".repeat(32);
        for (String invalid : List.of(
                "{not-json",
                "{\"protocolVersion\":2,\"state\":\"CLEAN\"}",
                "{\"protocolVersion\":1,\"state\":\"DIRTY\",\"operation\":\"CREATE\"}",
                "{\"protocolVersion\":1,\"state\":\"DIRTY\",\"operation\":\"UNKNOWN\","
                        + "\"sessionId\":\"" + "a".repeat(32) + "\"}",
                "{\"protocolVersion\":1,\"state\":\"DIRTY\",\"operation\":\"CREATE\","
                        + "\"sessionId\":\"" + session.id + "\",\"activeAfter\":\"" + other + "\"}",
                "{\"protocolVersion\":1,\"state\":\"DIRTY\",\"operation\":\"UPSERT\","
                        + "\"sessionId\":\"" + session.id + "\",\"activeBefore\":\"" + session.id
                        + "\",\"activeAfter\":\"" + other + "\"}",
                "{\"protocolVersion\":1,\"state\":\"DIRTY\",\"operation\":\"DELETE\","
                        + "\"sessionId\":\"" + other + "\",\"activeBefore\":\"" + session.id
                        + "\",\"activeAfter\":\"" + third + "\"}",
                "{\"protocolVersion\":1,\"state\":\"DIRTY\",\"operation\":\"DELETE\","
                        + "\"sessionId\":\"" + session.id + "\",\"activeBefore\":\"" + session.id
                        + "\",\"activeAfter\":\"" + session.id + "\"}")) {
            Files.writeString(stateFile, invalid);
            byte[] stateBefore = Files.readAllBytes(stateFile);

            assertThrows(IllegalStateException.class,
                    () -> new ChatSessionStore(memoryDir).listIndex(), invalid);

            assertArrayEquals(indexBefore, Files.readAllBytes(indexFile), invalid);
            assertArrayEquals(stateBefore, Files.readAllBytes(stateFile), invalid);
        }
    }

    @Test
    void directoryScanFailureDoesNotWritePartialIndexOrCleanState(@TempDir Path memoryDir)
            throws Exception {
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        initial.create(request("scan"));
        Path chatDir = memoryDir.resolve("chat-sessions");
        Path stateFile = chatDir.resolve("index.state");
        Path indexFile = chatDir.resolve("index.db");
        Files.delete(stateFile);
        byte[] indexBefore = Files.readAllBytes(indexFile);

        ChatSessionStore failing = new ChatSessionStore(
                memoryDir, ScriptedIo.failDirectoryList());
        assertThrows(RuntimeException.class, failing::listIndex);

        assertArrayEquals(indexBefore, Files.readAllBytes(indexFile));
        assertFalse(Files.exists(stateFile));
        assertEquals(1, new ChatSessionStore(memoryDir).listIndex().sessions.size());
    }

    @Test
    void wrongTypeChatDirectoryFailsClosed(@TempDir Path memoryDir) throws Exception {
        Files.writeString(memoryDir.resolve("chat-sessions"), "not a directory");

        assertThrows(IllegalStateException.class,
                () -> new ChatSessionStore(memoryDir).listIndex());
    }

    private static ChatSessionStore.CreateRequest request(String title) {
        ChatSessionStore.CreateRequest request = new ChatSessionStore.CreateRequest();
        request.title = title;
        return request;
    }

    private static List<ChatSessionStore.Message> turn(String text) {
        ChatSessionStore.Message user = new ChatSessionStore.Message();
        user.role = "user";
        user.status = "sent";
        user.content = text;
        ChatSessionStore.Message assistant = new ChatSessionStore.Message();
        assistant.role = "assistant";
        assistant.status = "pending";
        assistant.content = "thinking";
        return List.of(user, assistant);
    }

    private static ChatSessionStore.SessionMeta meta(String id, String title) {
        ChatSessionStore.SessionMeta meta = new ChatSessionStore.SessionMeta();
        meta.id = id;
        meta.title = title;
        meta.updatedAt = Instant.parse("2026-08-23T00:00:00Z");
        return meta;
    }

    private static JsonNode state(Path chatDir) throws IOException {
        return MAPPER.readTree(chatDir.resolve("index.state").toFile());
    }

    private static final class SimulatedCrash extends Error {
        private SimulatedCrash(String message) {
            super(message);
        }
    }

    private static final class FailingIndex implements ChatSessionIndex {
        private final ChatSessionIndex delegate;
        private final boolean failUpsert;
        private final boolean failDelete;

        private FailingIndex(Path database, boolean failUpsert, boolean failDelete) {
            this.delegate = new SqliteChatSessionIndex(database);
            this.failUpsert = failUpsert;
            this.failDelete = failDelete;
        }

        static FailingIndex failUpsert(Path database) {
            return new FailingIndex(database, true, false);
        }

        static FailingIndex failDelete(Path database) {
            return new FailingIndex(database, false, true);
        }

        @Override public boolean exists() { return delegate.exists(); }
        @Override public void validate() { delegate.validate(); }
        @Override public ChatSessionStore.Index load() { return delegate.load(); }
        @Override public String activeSessionId() { return delegate.activeSessionId(); }
        @Override public long generation() { return delegate.generation(); }
        @Override public boolean contains(String sessionId) { return delegate.contains(sessionId); }
        @Override public String newestSessionIdExcluding(String sessionId) {
            return delegate.newestSessionIdExcluding(sessionId);
        }
        @Override public List<ChatSessionStore.SessionMeta> page(
                int limit, String query, String updatedAt, String id) {
            return delegate.page(limit, query, updatedAt, id);
        }
        @Override public void replaceAll(ChatSessionStore.Index index) {
            delegate.replaceAll(index);
        }
        @Override public void upsert(
                ChatSessionStore.SessionMeta meta, String activeSessionId) {
            if (failUpsert) throw new IllegalStateException("simulated SQLite upsert failure");
            delegate.upsert(meta, activeSessionId);
        }
        @Override public void delete(String sessionId, String activeSessionId) {
            if (failDelete) throw new IllegalStateException("simulated SQLite delete failure");
            delegate.delete(sessionId, activeSessionId);
        }
        @Override public void setActive(String activeSessionId) {
            delegate.setActive(activeSessionId);
        }
        @Override public void resetCorrupt() { delegate.resetCorrupt(); }
    }

    private static final class ScriptedIo implements ChatSessionStoreIo {
        private final ChatSessionStoreIo delegate = ChatSessionStoreIo.nio();
        private final String targetName;
        private final int targetOccurrence;
        private final boolean afterMove;
        private final boolean deleteFailure;
        private final boolean listFailure;
        private final boolean statusFailure;
        private int matches;

        private ScriptedIo(
                String targetName, int targetOccurrence, boolean afterMove,
                boolean deleteFailure, boolean listFailure, boolean statusFailure) {
            this.targetName = targetName;
            this.targetOccurrence = targetOccurrence;
            this.afterMove = afterMove;
            this.deleteFailure = deleteFailure;
            this.listFailure = listFailure;
            this.statusFailure = statusFailure;
        }

        static ScriptedIo beforeMove(String targetName, int occurrence) {
            return new ScriptedIo(targetName, occurrence, false, false, false, false);
        }

        static ScriptedIo afterMove(String targetName, int occurrence) {
            return new ScriptedIo(targetName, occurrence, true, false, false, false);
        }

        static ScriptedIo beforeDelete(String targetName, int occurrence) {
            return new ScriptedIo(targetName, occurrence, false, true, false, false);
        }

        static ScriptedIo failDirectoryList() {
            return new ScriptedIo("", 0, false, false, true, false);
        }

        static ScriptedIo failStatus(String targetName) {
            return new ScriptedIo(targetName, 0, false, false, false, true);
        }

        @Override
        public void createDirectories(Path directory) throws IOException {
            delegate.createDirectories(directory);
        }

        @Override
        public Path createTempFile(Path directory, String prefix, String suffix)
                throws IOException {
            return delegate.createTempFile(directory, prefix, suffix);
        }

        @Override
        public void writeJson(ObjectMapper mapper, Path file, Object value) throws IOException {
            delegate.writeJson(mapper, file, value);
        }

        @Override
        public void atomicReplace(Path source, Path target) throws IOException {
            boolean fail = !deleteFailure && target.getFileName().toString().equals(targetName)
                    && ++matches == targetOccurrence;
            if (fail && !afterMove) throw new IOException("simulated move failure: " + targetName);
            delegate.atomicReplace(source, target);
            if (fail) throw new SimulatedCrash("simulated crash after move: " + targetName);
        }

        @Override
        public boolean deleteIfExists(Path path) throws IOException {
            if (deleteFailure && path.getFileName().toString().equals(targetName)
                    && ++matches == targetOccurrence) {
                throw new IOException("simulated delete failure: " + targetName);
            }
            return delegate.deleteIfExists(path);
        }

        @Override
        public List<Path> list(Path directory) throws IOException {
            if (listFailure) throw new IOException("simulated directory scan failure");
            return delegate.list(directory);
        }

        @Override
        public PathStatus status(Path path) throws IOException {
            if (statusFailure && path.getFileName().toString().equals(targetName)) {
                throw new IOException("simulated indeterminate path status: " + targetName);
            }
            return delegate.status(path);
        }
    }
}
