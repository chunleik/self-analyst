package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAnalystAgentSessionStateTest {

    private static final String SESSION_A = "a".repeat(32);
    private static final String SESSION_B = "b".repeat(32);
    private static final String SESSION_C = "c".repeat(32);
    private static final String SESSION_D = "d".repeat(32);
    private static final String MESSAGE_A1 = "1".repeat(12);

    @Test
    void isolatesPersistsRestoresAndDeletesDesktopSessionState(@TempDir Path tempDir)
            throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger sequence = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            int n = sequence.incrementAndGet();
            byte[] response = sseResponse("reply-" + n).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        Config config = withLlm(Config.testDefaults(tempDir),
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model");
        try {
            try (SelfAnalystAgent first = new SelfAnalystAgent(config)) {
                List<Msg> preAgentStateTranscript = List.of(
                        message("8".repeat(12), MsgRole.USER, "legacy question"),
                        message("9".repeat(12), MsgRole.ASSISTANT, "legacy answer"));
                assertEquals("reply-1",
                        first.chat(SESSION_A, MESSAGE_A1, "alpha fact",
                                () -> preAgentStateTranscript).block());
                assertEquals("reply-1",
                        first.chat(SESSION_A, MESSAGE_A1, "alpha fact retry",
                                () -> preAgentStateTranscript).block());
                assertEquals(1, requests.size(), "idempotent retry must not call the model again");
                assertEquals("reply-2",
                        first.chat(SESSION_B, "2".repeat(12), "beta fact").block());
                assertEquals("reply-3",
                        first.chat(SESSION_A, "3".repeat(12), "alpha follow-up").block());

                assertTrue(first.hasChatSessionState(SESSION_A));
                assertTrue(first.hasChatSessionState(SESSION_B));

                // AgentScope stores assistant reasoning messages before tool execution. A retry
                // must return the last assistant in the turn, not the intermediate one.
                ReActAgent reactAgent = reactAgent(first);
                AgentState toolTurn = reactAgent.getAgentState("desktop", SESSION_C);
                String toolUserId = "6".repeat(12);
                toolTurn.contextMutable().addAll(List.of(
                        message(toolUserId, MsgRole.USER, "use a tool"),
                        message("7".repeat(12), MsgRole.ASSISTANT,
                                "intermediate tool preface")
                                .withGenerateReason(GenerateReason.TOOL_CALLS),
                        message("d".repeat(12), MsgRole.ASSISTANT, "terminal answer")));
                reactAgent.saveAgentState("desktop", SESSION_C);
                int requestsBeforeRetry = requests.size();
                assertEquals("terminal answer",
                        first.chat(SESSION_C, toolUserId, "retry tool turn").block());
                assertEquals(requestsBeforeRetry, requests.size());
            }

            assertTrue(requests.get(0).contains("legacy question"));
            assertTrue(requests.get(0).contains("legacy answer"));
            assertTrue(requests.get(0).contains("alpha fact"));
            assertTrue(requests.get(1).contains("beta fact"));
            assertFalse(requests.get(1).contains("alpha fact"));
            assertFalse(requests.get(1).contains("reply-1"));
            assertTrue(requests.get(2).contains("alpha fact"));
            assertTrue(requests.get(2).contains("reply-1"));

            try (SelfAnalystAgent restarted = new SelfAnalystAgent(config)) {
                assertEquals("reply-4",
                        restarted.chat(SESSION_A, "4".repeat(12), "after restart").block());
                assertTrue(requests.get(3).contains("alpha fact"));
                assertTrue(requests.get(3).contains("reply-3"));

                restarted.deleteChatSessionState(SESSION_B);
                assertFalse(restarted.hasChatSessionState(SESSION_B));
                assertEquals("reply-5",
                        restarted.chat(SESSION_B, "5".repeat(12), "beta fresh").block());
                assertFalse(requests.get(4).contains("beta fact"));
                assertFalse(requests.get(4).contains("reply-2"));

                int beforeMissingSession = requests.size();
                assertThrows(SelfAnalystAgent.ChatSessionUnavailableException.class,
                        () -> restarted.chat(SESSION_A, "e".repeat(12), "must not run",
                                () -> null).block());
                assertEquals(beforeMissingSession, requests.size());
            }

            Path stateRoot = config.memoryDir().resolve("agent-state/self-analyst-chat/desktop");
            assertTrue(Files.exists(stateRoot.resolve(SESSION_A).resolve("agent_state.json")));
            assertTrue(Files.exists(stateRoot.resolve(SESSION_B).resolve("agent_state.json")));
            assertTrue(Files.exists(stateRoot.resolve(SESSION_C).resolve("agent_state.json")));
            assertFalse(Files.exists(stateRoot.resolve("legacy_default")));

            Path corruptState = stateRoot.resolve(SESSION_D).resolve("agent_state.json");
            Files.createDirectories(corruptState.getParent());
            Files.writeString(corruptState, "{not-json", StandardCharsets.UTF_8);
            int beforeCorruptState = requests.size();
            try (SelfAnalystAgent corruptionGuard = new SelfAnalystAgent(config)) {
                assertThrows(RuntimeException.class,
                        () -> corruptionGuard.chat(
                                SESSION_D, "a".repeat(12), "must not overwrite", List::of)
                                .block());
            }
            assertEquals(beforeCorruptState, requests.size());
            assertEquals("{not-json", Files.readString(corruptState, StandardCharsets.UTF_8));

            // Privacy cleanup remains available when SelfAnalystAgent initialization failed/null.
            SelfAnalystAgent.deletePersistedChatSessionState(config.memoryDir(), SESSION_B);
            assertFalse(Files.exists(stateRoot.resolve(SESSION_B)));
        } finally {
            server.stop(0);
        }
    }

    private static Msg message(String id, MsgRole role, String text) {
        return Msg.builder()
                .id(id)
                .name(role == MsgRole.USER ? "user" : "assistant")
                .role(role)
                .textContent(text)
                .build();
    }

    private static ReActAgent reactAgent(SelfAnalystAgent owner) throws Exception {
        Field field = SelfAnalystAgent.class.getDeclaredField("agent");
        field.setAccessible(true);
        return (ReActAgent) field.get(owner);
    }

    private static String sseResponse(String text) {
        return "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"role\":\"assistant\",\"content\":\"" + text
                + "\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,"
                + "\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"test-model\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,"
                + "\"total_tokens\":7}}\n\n"
                + "data: [DONE]\n\n";
    }

    private static Config withLlm(Config base, String apiKey, String baseUrl, String model)
            throws Exception {
        RecordComponent[] components = Config.class.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            types[i] = component.getType();
            values[i] = switch (component.getName()) {
                case "llmApiKey" -> apiKey;
                case "llmBaseUrl" -> baseUrl;
                case "llmModel" -> model;
                default -> component.getAccessor().invoke(base);
            };
        }
        return Config.class.getDeclaredConstructor(types).newInstance(values);
    }
}
