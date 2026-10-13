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

    @Test
    void repairCleanupFailureRetainsDurableIntentAndRetriesBeforeTranscript(@TempDir Path tempDir) {
        Config config = Config.testDefaults(tempDir);
        try (ChatSessionStore store = new ChatSessionStore(config.memoryDir())) {
            var session = store.create(new ChatSessionStore.CreateRequest());
            java.util.concurrent.atomic.AtomicInteger cleanup = new java.util.concurrent.atomic.AtomicInteger();
            var failing = new ChatSessionDeletionCoordinator(store, null, config, id -> {
                cleanup.incrementAndGet();
                throw new IllegalStateException("repair store unavailable");
            });
            assertThrows(IllegalStateException.class, () -> failing.delete(session.id));
            assertTrue(store.pendingDeletionIds().contains(session.id));
            var recovery = new ChatSessionDeletionCoordinator(store, null, config, id -> cleanup.incrementAndGet());
            recovery.recoverPendingDeletions();
            org.junit.jupiter.api.Assertions.assertEquals(2, cleanup.get());
            assertNull(store.getSession(session.id));
            assertTrue(store.pendingDeletionIds().isEmpty());
        }
    }

    @Test
    void concurrentRepairCreationCannotSlipBetweenCleanupAndTranscriptDeletion(@TempDir Path tempDir) throws Exception {
        Config config = Config.testDefaults(tempDir);
        try (ChatSessionStore store = new ChatSessionStore(config.memoryDir());
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var session = store.create(new ChatSessionStore.CreateRequest());
            var draftSessions = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
            draftSessions.add(session.id);
            var cleaned = new java.util.concurrent.CountDownLatch(1);
            var finishDeletion = new java.util.concurrent.CountDownLatch(1);
            var creationStarted = new java.util.concurrent.CountDownLatch(1);
            var coordinator = new ChatSessionDeletionCoordinator(store,
                    (id, intent, transcript) -> { intent.run(); return transcript.get(); }, id -> {
                        draftSessions.remove(id);
                        cleaned.countDown();
                        try {
                            if (!finishDeletion.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                throw new IllegalStateException("Timed out waiting for deletion barrier");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    });
            var deletion = executor.submit(() -> coordinator.delete(session.id));
            assertTrue(cleaned.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var creation = executor.submit(() -> {
                creationStarted.countDown();
                // Mirrors the repair controller's chat -> Wiki mutation boundary.
                synchronized (store) {
                    if (store.getSession(session.id) != null) draftSessions.add(session.id);
                }
            });
            try {
                assertTrue(creationStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> creation.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            } finally {
                finishDeletion.countDown();
            }
            assertTrue(deletion.get(5, java.util.concurrent.TimeUnit.SECONDS).deleted());
            creation.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(draftSessions.contains(session.id));
            assertNull(store.getSession(session.id));
            assertTrue(store.pendingDeletionIds().isEmpty());
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
