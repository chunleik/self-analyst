package com.selfanalyst.desktop.controller;

import com.selfanalyst.agent.SelfAnalystAgent;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.ChatSessionStore;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopChatSessionLifecycleControllerTest {

    @Test
    void busyAgentIsAnHttpConflictNotASuccessfulAssistantReply(@TempDir Path memoryDir)
            throws Exception {
        Config config = Config.testDefaults(memoryDir);
        ChatSessionStore store = new ChatSessionStore(config.memoryDir());
        ChatSessionStore.Session session = store.create(new ChatSessionStore.CreateRequest());
        ChatSessionStore.Message user = new ChatSessionStore.Message();
        user.role = "user";
        user.content = "queued turn";
        user.status = "sent";
        user = store.appendMessages(session.id, java.util.List.of(user)).getFirst();

        try (SelfAnalystAgent agent = new SelfAnalystAgent(config)) {
            CountDownLatch gateEntered = new CountDownLatch(1);
            CountDownLatch releaseGate = new CountDownLatch(1);
            String userId = user.id;
            CompletableFuture<Throwable> running = CompletableFuture.supplyAsync(() -> {
                try {
                    agent.chat(session.id, userId, "held", () -> {
                        gateEntered.countDown();
                        try {
                            if (!releaseGate.await(2, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test gate timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return null;
                    }).block();
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertTrue(gateEntered.await(2, TimeUnit.SECONDS));

            CapturedContext captured = new CapturedContext("""
                    {"message":"queued turn","sessionId":"%s","userMessageId":"%s"}
                    """.formatted(session.id, user.id), null);
            DesktopAgentController controller = new DesktopAgentController(
                    null, null, agent, null, config, null, store);

            controller.chat(captured.context());

            assertEquals(409, captured.status);
            assertTrue(captured.json instanceof Map<?, ?>);
            assertTrue(((Map<?, ?>) captured.json).containsKey("error"));
            releaseGate.countDown();
            assertNotNull(running.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void deleteRemovesAgentStateEvenWhenAgentInitializationIsUnavailable(
            @TempDir Path memoryDir) {
        Config config = Config.testDefaults(memoryDir);
        ChatSessionStore store = new ChatSessionStore(config.memoryDir());
        ChatSessionStore.Session session = store.create(new ChatSessionStore.CreateRequest());
        Path stateRoot = config.memoryDir().resolve("agent-state/self-analyst-chat");
        JsonFileAgentStateStore stateStore = new JsonFileAgentStateStore(stateRoot);
        stateStore.save("desktop", session.id, "agent_state", AgentState.builder()
                .userId("desktop")
                .sessionId(session.id)
                .build());
        stateStore.close();
        Path stateDir = stateRoot.resolve("desktop").resolve(session.id);
        assertTrue(Files.exists(stateDir.resolve("agent_state.json")));

        CapturedContext captured = new CapturedContext("", session.id);
        DesktopChatSessionController controller = new DesktopChatSessionController(
                store, null, null, config, null);

        controller.deleteSession(captured.context());

        assertEquals(200, captured.status);
        assertNull(store.getSession(session.id));
        assertFalse(Files.exists(stateDir));
    }

    @Test
    void chatRejectsOversizedOrWronglyTypedBodiesBeforeAgentExecution(@TempDir Path memoryDir) {
        Config config = Config.testDefaults(memoryDir);
        DesktopAgentController controller = new DesktopAgentController(
                null, null, null, null, config, null, null);

        CapturedContext oversized = new CapturedContext(
                "x".repeat(DesktopChatJson.MAX_BODY_BYTES + 1), null);
        controller.chat(oversized.context());
        assertEquals(413, oversized.status);

        CapturedContext wrongType = new CapturedContext(
                "{\"message\":{\"nested\":true}}", null);
        controller.chat(wrongType.context());
        assertEquals(400, wrongType.status);

        CapturedContext userIdOnly = new CapturedContext(
                "{\"message\":\"q\",\"userMessageId\":\"%s\"}"
                        .formatted("a".repeat(12)), null);
        controller.chat(userIdOnly.context());
        assertEquals(400, userIdOnly.status);

        CapturedContext sessionIdOnly = new CapturedContext(
                "{\"message\":\"q\",\"sessionId\":\"%s\"}"
                        .formatted("b".repeat(32)), null);
        controller.chat(sessionIdOnly.context());
        assertEquals(400, sessionIdOnly.status);
    }

    @Test
    void olderVisibleUserTurnIsAConflict(@TempDir Path memoryDir) {
        Config config = Config.testDefaults(memoryDir);
        ChatSessionStore store = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session session = store.create(new ChatSessionStore.CreateRequest());
        ChatSessionStore.Message firstUser = message("user", "first", "sent");
        ChatSessionStore.Message firstPending = message("assistant", "thinking", "pending");
        var first = store.appendMessages(session.id, java.util.List.of(firstUser, firstPending));
        store.updateMessage(session.id, first.get(1).id, "first answer", "sent", null, null);
        ChatSessionStore.Message secondUser = message("user", "second", "sent");
        ChatSessionStore.Message secondPending = message("assistant", "thinking", "pending");
        store.appendMessages(session.id, java.util.List.of(secondUser, secondPending));
        DesktopAgentController controller = new DesktopAgentController(
                null, null, null, null, config, null, store);
        CapturedContext captured = new CapturedContext("""
                {"message":"first","sessionId":"%s","userMessageId":"%s"}
                """.formatted(session.id, first.get(0).id), null);

        controller.chat(captured.context());

        assertEquals(409, captured.status);
        assertTrue(captured.json instanceof Map<?, ?>);
    }

    private static ChatSessionStore.Message message(String role, String content, String status) {
        ChatSessionStore.Message message = new ChatSessionStore.Message();
        message.role = role;
        message.content = content;
        message.status = status;
        return message;
    }

    private static final class CapturedContext {
        private final String body;
        private final String pathId;
        private int status = 200;
        private Object json;
        private final Context context;

        private CapturedContext(String body, String pathId) {
            this.body = body;
            this.pathId = pathId;
            this.context = (Context) Proxy.newProxyInstance(
                    Context.class.getClassLoader(),
                    new Class<?>[]{Context.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "body" -> this.body;
                        case "pathParam" -> this.pathId;
                        case "status" -> {
                            if (args != null && args.length > 0 && args[0] instanceof Integer code) {
                                this.status = code;
                            }
                            yield proxy;
                        }
                        case "json" -> {
                            this.json = args != null && args.length > 0 ? args[0] : null;
                            yield proxy;
                        }
                        default -> defaultValue(method.getReturnType(), proxy);
                    });
        }

        private Context context() {
            return context;
        }

        private static Object defaultValue(Class<?> type, Object proxy) {
            if (type.isInstance(proxy)) return proxy;
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == char.class) return '\0';
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0F;
            return 0D;
        }
    }
}
