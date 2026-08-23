package com.selfanalyst.desktop.store;

import com.selfanalyst.agent.SelfAnalystAgent;
import com.selfanalyst.config.Config;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Durable saga joining AgentScope AgentState deletion with visible transcript deletion.
 * The tombstone written by {@link ChatSessionStore#beginDeletion(String)} is the commit point:
 * after it exists, recovery monotonically completes both irreversible deletes.
 */
public final class ChatSessionDeletionCoordinator {

    @FunctionalInterface
    interface DeletionExecutor {
        ChatSessionStore.DeleteResult execute(
                String sessionId,
                Runnable durableIntent,
                Supplier<ChatSessionStore.DeleteResult> transcriptDeletion);
    }

    private final ChatSessionStore store;
    private final DeletionExecutor executor;

    public ChatSessionDeletionCoordinator(
            ChatSessionStore store, SelfAnalystAgent agent, Config config) {
        this(store, defaultExecutor(agent, config));
    }

    ChatSessionDeletionCoordinator(ChatSessionStore store, DeletionExecutor executor) {
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    private static DeletionExecutor defaultExecutor(SelfAnalystAgent agent, Config config) {
        if (agent != null) {
            return (sessionId, intent, transcript) ->
                    agent.deleteChatSessionStateWithIntent(sessionId, intent, transcript);
        }
        return (sessionId, intent, transcript) -> {
            if (config == null) {
                throw new IllegalStateException(
                        "Cannot locate persisted AgentState without application config");
            }
            intent.run();
            SelfAnalystAgent.deletePersistedChatSessionState(config.memoryDir(), sessionId);
            return transcript.get();
        };
    }

    public synchronized ChatSessionStore.DeleteResult delete(String sessionId) {
        return complete(sessionId, false);
    }

    /** Complete every durable intent before the desktop routes become available. */
    public synchronized void recoverPendingDeletions() {
        RuntimeException firstFailure = null;
        for (String sessionId : store.deletionIntentIdsForRecovery()) {
            try {
                complete(sessionId, true);
            } catch (RuntimeException error) {
                if (firstFailure == null) firstFailure = error;
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    private ChatSessionStore.DeleteResult complete(String sessionId, boolean intentAlreadyDurable) {
        Runnable durableIntent = intentAlreadyDurable
                ? () -> { }
                : () -> store.beginDeletion(sessionId);
        ChatSessionStore.DeleteResult result = executor.execute(
                sessionId,
                durableIntent,
                () -> store.deletePendingTranscript(sessionId));
        store.finishDeletion(sessionId);
        return result;
    }
}
