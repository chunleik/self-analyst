package com.selfanalyst.desktop.store;

import com.selfanalyst.config.Config;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durable deletion saga over the single SQLite store (SPEC-CSS-DEC-004,
 * SPEC-CSS-TST-020/-021). The durable intent now lives in the
 * {@code pending_deletions} table instead of a tombstone file.
 */
class ChatSessionDeletionCoordinatorTest {

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
        // The intent is durable; both stores are untouched by the crash itself.
        assertTrue(store.pendingDeletionIds().contains(session.id));
        assertTrue(agentStateExists(memoryDir, session.id));

        ChatSessionStore restarted = new ChatSessionStore(memoryDir);
        ChatSessionDeletionCoordinator recovery = new ChatSessionDeletionCoordinator(
                restarted, null, config);
        recovery.recoverPendingDeletions();

        assertFalse(agentStateExists(memoryDir, session.id));
        assertNull(restarted.getSession(session.id));
        assertTrue(restarted.pendingDeletionIds().isEmpty());
    }

    @Test
    void completedDeleteClearsTranscriptAgentStateAndIntent(@TempDir Path tempDir) {
        Config config = Config.testDefaults(tempDir);
        Path memoryDir = config.memoryDir();
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = store.create(new ChatSessionStore.CreateRequest());
        persistAgentState(memoryDir, session.id);
        ChatSessionDeletionCoordinator coordinator = new ChatSessionDeletionCoordinator(
                store, null, config);

        ChatSessionStore.DeleteResult result = coordinator.delete(session.id);

        assertTrue(result.deleted());
        assertNull(store.getSession(session.id));
        assertFalse(agentStateExists(memoryDir, session.id));
        assertTrue(store.pendingDeletionIds().isEmpty());
    }

    @Test
    void deleteWithoutAgentDeletesPersistedStateDirectly(@TempDir Path tempDir) {
        Config config = Config.testDefaults(tempDir);
        Path memoryDir = config.memoryDir();
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = store.create(new ChatSessionStore.CreateRequest());
        persistAgentState(memoryDir, session.id);
        // agent == null: the coordinator must open the state store directly.
        ChatSessionDeletionCoordinator coordinator = new ChatSessionDeletionCoordinator(
                store, null, config);

        coordinator.delete(session.id);

        assertFalse(agentStateExists(memoryDir, session.id));
        assertNull(store.getSession(session.id));
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

    private static final class SimulatedCrash extends Error {
    }
}
