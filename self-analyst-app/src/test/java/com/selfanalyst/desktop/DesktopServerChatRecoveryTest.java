package com.selfanalyst.desktop;

import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.ChatSessionStore;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DesktopServerChatRecoveryTest {

    @Test
    void constructorRecoversPendingCrossStoreDeletionBeforeRoutesStart(
            @TempDir Path tempDir) {
        Config config = Config.testDefaults(tempDir);
        ChatSessionStore setup = new ChatSessionStore(config.memoryDir());
        ChatSessionStore.Session session = setup.create(new ChatSessionStore.CreateRequest());
        JsonFileAgentStateStore stateStore = new JsonFileAgentStateStore(
                config.memoryDir().resolve("agent-state/self-analyst-chat"));
        stateStore.save("desktop", session.id, "agent_state", AgentState.builder()
                .userId("desktop")
                .sessionId(session.id)
                .build());
        stateStore.close();
        setup.beginDeletion(session.id);
        setup.close();

        DesktopServer server = new DesktopServer(
                Javalin.create(), config, null, null, null, null, null);
        try {
            assertFalse(Files.exists(config.memoryDir().resolve("chat-sessions")
                    .resolve(session.id + ".json")));
            assertFalse(Files.exists(config.memoryDir().resolve("chat-sessions")
                    .resolve("delete-" + session.id + ".state")));
            assertFalse(Files.exists(config.memoryDir().resolve("agent-state/self-analyst-chat")
                    .resolve("desktop").resolve(session.id)));
        } finally {
            server.shutdown();
        }

        try (ChatSessionStore reopened = ChatSessionStore.openExclusive(config.memoryDir())) {
            assertFalse(reopened.pendingDeletionIds().contains(session.id));
        }
    }
}
