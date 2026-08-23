package com.selfanalyst.desktop.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.Config;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionDeletionCoordinatorTest {

    @Test
    void intentWriteFailureLeavesBothStoresUntouched(@TempDir Path tempDir) {
        Config config = Config.testDefaults(tempDir);
        Path memoryDir = config.memoryDir();
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = initial.create(new ChatSessionStore.CreateRequest());
        persistAgentState(memoryDir, session.id);
        String intentName = intentName(session.id);
        ChatSessionStore failing = new ChatSessionStore(
                memoryDir, FailingIo.failMove(intentName));
        ChatSessionDeletionCoordinator coordinator = new ChatSessionDeletionCoordinator(
                failing, null, config);

        assertThrows(RuntimeException.class, () -> coordinator.delete(session.id));

        assertTrue(Files.exists(shard(memoryDir, session.id)));
        assertTrue(agentStateExists(memoryDir, session.id));
        assertFalse(Files.exists(chatDir(memoryDir).resolve(intentName)));
    }

    @Test
    void crashAfterIntentIsCompletedOnRestart(@TempDir Path tempDir) {
        Config config = Config.testDefaults(tempDir);
        Path memoryDir = config.memoryDir();
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = store.create(new ChatSessionStore.CreateRequest());
        persistAgentState(memoryDir, session.id);
        ChatSessionDeletionCoordinator crashing = new ChatSessionDeletionCoordinator(
                store, (id, intent, transcript) -> {
                    intent.run();
                    throw new SimulatedCrash();
                });

        assertThrows(SimulatedCrash.class, () -> crashing.delete(session.id));
        assertTrue(Files.exists(shard(memoryDir, session.id)));
        assertTrue(agentStateExists(memoryDir, session.id));
        assertTrue(Files.exists(chatDir(memoryDir).resolve(intentName(session.id))));

        ChatSessionStore restarted = new ChatSessionStore(memoryDir);
        ChatSessionDeletionCoordinator recovery = new ChatSessionDeletionCoordinator(
                restarted, null, config);
        recovery.recoverPendingDeletions();

        assertFalse(agentStateExists(memoryDir, session.id));
        assertFalse(Files.exists(shard(memoryDir, session.id)));
        assertFalse(Files.exists(chatDir(memoryDir).resolve(intentName(session.id))));
        assertTrue(restarted.pendingDeletionIds().isEmpty());
    }

    @Test
    void transcriptFailureAfterStateDeleteRemainsRecoverable(@TempDir Path tempDir) {
        Config config = Config.testDefaults(tempDir);
        Path memoryDir = config.memoryDir();
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = initial.create(new ChatSessionStore.CreateRequest());
        persistAgentState(memoryDir, session.id);
        ChatSessionStore failing = new ChatSessionStore(
                memoryDir, FailingIo.failDelete(session.id + ".json"));
        ChatSessionDeletionCoordinator coordinator = new ChatSessionDeletionCoordinator(
                failing, null, config);

        assertThrows(RuntimeException.class, () -> coordinator.delete(session.id));

        assertFalse(agentStateExists(memoryDir, session.id));
        assertTrue(Files.exists(shard(memoryDir, session.id)));
        assertTrue(Files.exists(chatDir(memoryDir).resolve(intentName(session.id))));

        ChatSessionStore restarted = new ChatSessionStore(memoryDir);
        ChatSessionDeletionCoordinator recovery = new ChatSessionDeletionCoordinator(
                restarted, null, config);
        recovery.recoverPendingDeletions();
        assertFalse(Files.exists(shard(memoryDir, session.id)));
        assertFalse(Files.exists(chatDir(memoryDir).resolve(intentName(session.id))));
    }

    @Test
    void tombstoneCleanupFailureIsSuccessAndNextRestartCleansIt(@TempDir Path tempDir) {
        Config config = Config.testDefaults(tempDir);
        Path memoryDir = config.memoryDir();
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = initial.create(new ChatSessionStore.CreateRequest());
        persistAgentState(memoryDir, session.id);
        ChatSessionStore failing = new ChatSessionStore(
                memoryDir, FailingIo.failDelete(intentName(session.id)));
        ChatSessionDeletionCoordinator coordinator = new ChatSessionDeletionCoordinator(
                failing, null, config);

        ChatSessionStore.DeleteResult deleted = coordinator.delete(session.id);

        assertTrue(deleted.deleted());
        assertFalse(agentStateExists(memoryDir, session.id));
        assertFalse(Files.exists(shard(memoryDir, session.id)));
        assertTrue(Files.exists(chatDir(memoryDir).resolve(intentName(session.id))));

        ChatSessionStore restarted = new ChatSessionStore(memoryDir);
        new ChatSessionDeletionCoordinator(
                restarted, null, config).recoverPendingDeletions();
        assertFalse(Files.exists(chatDir(memoryDir).resolve(intentName(session.id))));
    }

    @Test
    void malformedDeletionIntentFailsClosed(@TempDir Path tempDir) throws Exception {
        Path memoryDir = Config.testDefaults(tempDir).memoryDir();
        ChatSessionStore initial = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = initial.create(new ChatSessionStore.CreateRequest());
        Path intent = chatDir(memoryDir).resolve(intentName(session.id));
        Files.writeString(intent, "{\"protocolVersion\":99,\"state\":\"PENDING\","
                + "\"sessionId\":\"" + session.id + "\"}");
        byte[] shardBefore = Files.readAllBytes(shard(memoryDir, session.id));
        byte[] indexBefore = Files.readAllBytes(chatDir(memoryDir).resolve("index.db"));
        byte[] stateBefore = Files.readAllBytes(chatDir(memoryDir).resolve("index.state"));

        assertThrows(IllegalStateException.class,
                () -> new ChatSessionStore(memoryDir).listIndex());

        assertTrue(Files.exists(intent));
        assertNotNull(new ObjectMapper().readTree(intent.toFile()));
        assertArrayEquals(shardBefore, Files.readAllBytes(shard(memoryDir, session.id)));
        assertArrayEquals(indexBefore, Files.readAllBytes(chatDir(memoryDir).resolve("index.db")));
        assertArrayEquals(stateBefore, Files.readAllBytes(chatDir(memoryDir).resolve("index.state")));
    }

    @Test
    void exclusiveWriterLeaseRejectsSecondServerAndReleases(@TempDir Path tempDir) {
        Path memoryDir = Config.testDefaults(tempDir).memoryDir();
        ChatSessionStore first = ChatSessionStore.openExclusive(memoryDir);
        try {
            assertThrows(IllegalStateException.class,
                    () -> ChatSessionStore.openExclusive(memoryDir));
        } finally {
            first.close();
        }
        assertThrows(IllegalStateException.class, first::listIndex);
        try (ChatSessionStore reopened = ChatSessionStore.openExclusive(memoryDir)) {
            assertNotNull(reopened.listIndex());
        }
    }

    private static void persistAgentState(Path memoryDir, String sessionId) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(stateRoot(memoryDir));
        try {
            store.save("desktop", sessionId, "agent_state", AgentState.builder()
                    .userId("desktop")
                    .sessionId(sessionId)
                    .build());
        } finally {
            store.close();
        }
    }

    private static boolean agentStateExists(Path memoryDir, String sessionId) {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(stateRoot(memoryDir));
        try {
            return store.exists("desktop", sessionId);
        } finally {
            store.close();
        }
    }

    private static Path stateRoot(Path memoryDir) {
        return memoryDir.resolve("agent-state/self-analyst-chat");
    }

    private static Path chatDir(Path memoryDir) {
        return memoryDir.resolve("chat-sessions");
    }

    private static Path shard(Path memoryDir, String sessionId) {
        return chatDir(memoryDir).resolve(sessionId + ".json");
    }

    private static String intentName(String sessionId) {
        return "delete-" + sessionId + ".state";
    }

    private static final class SimulatedCrash extends Error {
    }

    private static final class FailingIo implements ChatSessionStoreIo {
        private final ChatSessionStoreIo delegate = ChatSessionStoreIo.nio();
        private final String moveTarget;
        private final String deleteTarget;

        private FailingIo(String moveTarget, String deleteTarget) {
            this.moveTarget = moveTarget;
            this.deleteTarget = deleteTarget;
        }

        static FailingIo failMove(String target) {
            return new FailingIo(target, null);
        }

        static FailingIo failDelete(String target) {
            return new FailingIo(null, target);
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
            if (target.getFileName().toString().equals(moveTarget)) {
                throw new IOException("simulated intent move failure");
            }
            delegate.atomicReplace(source, target);
        }

        @Override
        public boolean deleteIfExists(Path path) throws IOException {
            if (path.getFileName().toString().equals(deleteTarget)) {
                throw new IOException("simulated delete failure: " + deleteTarget);
            }
            return delegate.deleteIfExists(path);
        }

        @Override
        public List<Path> list(Path directory) throws IOException {
            return delegate.list(directory);
        }

        @Override
        public PathStatus status(Path path) throws IOException {
            return delegate.status(path);
        }
    }
}
