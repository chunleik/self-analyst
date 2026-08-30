package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.usage.UsageMeter;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    private static final String SESSION_E = "e".repeat(32);
    private static final String SESSION_F = "f".repeat(32);
    private static final String SESSION_G = "0".repeat(32);
    private static final String SESSION_H = "9".repeat(32);
    private static final String SESSION_I = "2".repeat(32);
    private static final String MESSAGE_A1 = "1".repeat(12);

    @Test
    void streamsIncrementalTextAndCanonicalResult(@TempDir Path tempDir) throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            modelCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = sseResponseChunks(List.of("Hello", " streaming"))
                    .getBytes(StandardCharsets.UTF_8);
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
        try (SelfAnalystAgent agent = new SelfAnalystAgent(config)) {
            List<SelfAnalystAgent.ChatStreamEvent> events = agent.chatStream(
                            SESSION_A,
                            MESSAGE_A1,
                            () -> new SelfAnalystAgent.PersistedDesktopTurn(
                                    "stream this", null, List.of()))
                    .collectList()
                    .block();

            assertEquals(List.of(
                            SelfAnalystAgent.ChatStreamEventType.DELTA,
                            SelfAnalystAgent.ChatStreamEventType.DELTA,
                            SelfAnalystAgent.ChatStreamEventType.RESULT),
                    events.stream().map(SelfAnalystAgent.ChatStreamEvent::type).toList());
            assertEquals(List.of("Hello", " streaming", "Hello streaming"),
                    events.stream().map(SelfAnalystAgent.ChatStreamEvent::text).toList());

            Disposable replayWindow = agent.runExclusiveDesktopChatStream(
                    SESSION_A, MESSAGE_A1, Flux::never).subscribe();
            assertTrue(agent.cancelChat(SESSION_A, MESSAGE_A1));
            replayWindow.dispose();
            assertEquals("Hello streaming",
                    agent.chat(SESSION_A, MESSAGE_A1, "stream this again").block());
            assertEquals(1, modelCalls.get(),
                    "cancelling a canonical replay must not roll back its completed turn");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emptyModelResultFailsAndSameTurnCanBeRetried(@TempDir Path tempDir) throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int call = modelCalls.incrementAndGet();
            String body = call == 1
                    ? sseResponseChunks(List.of())
                    : sseResponse("recovered answer");
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
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
        String messageId = "8".repeat(12);
        try (SelfAnalystAgent agent = new SelfAnalystAgent(config)) {
            assertThrows(SelfAnalystAgent.EmptyAgentResponseException.class,
                    () -> agent.chat(
                            SESSION_G,
                            messageId,
                            () -> new SelfAnalystAgent.PersistedDesktopTurn(
                                    "answer me", null, List.of()))
                            .block());

            assertEquals("recovered answer", agent.chat(
                    SESSION_G,
                    messageId,
                    () -> new SelfAnalystAgent.PersistedDesktopTurn(
                            "answer me", null, List.of()))
                    .block());
            assertEquals(2, modelCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emptyCanonicalResultFallsBackToAlreadyStreamedText() {
        assertEquals("partial answer",
                SelfAnalystAgent.canonicalResponseText("", "partial answer"));
        assertEquals("canonical answer",
                SelfAnalystAgent.canonicalResponseText("canonical answer", "partial answer"));
        assertThrows(SelfAnalystAgent.EmptyAgentResponseException.class,
                () -> SelfAnalystAgent.canonicalResponseText("  ", "\n"));
    }

    @Test
    void deltaFallbackIsPersistedAsAnIdempotentTerminalReply(@TempDir Path tempDir)
            throws Exception {
        Config config = Config.testDefaults(tempDir);
        String userMessageId = "9".repeat(12);
        Msg blankFinal = message("a".repeat(12), MsgRole.ASSISTANT, "");
        try (SelfAnalystAgent owner = new SelfAnalystAgent(config)) {
            ReActAgent reactAgent = reactAgent(owner);
            AgentState state = reactAgent.getAgentState("desktop", SESSION_H);
            state.contextMutable().addAll(List.of(
                    message(userMessageId, MsgRole.USER, "question"),
                    blankFinal));
            reactAgent.saveAgentState("desktop", SESSION_H);

            assertEquals("partial answer", owner.finalizeChatResponse(
                    SESSION_H, userMessageId, blankFinal, "partial answer"));
            reactAgent.clearStateCache("desktop", SESSION_H);

            assertEquals("partial answer", owner.chat(
                    SESSION_H,
                    userMessageId,
                    () -> new SelfAnalystAgent.PersistedDesktopTurn(
                            "must not call model", null, List.of()))
                    .block());
        }
    }

    @Test
    void legacyDeltaFallbackUsesTheLatestUserTurnWithoutAMessageId(@TempDir Path tempDir)
            throws Exception {
        Config config = Config.testDefaults(tempDir);
        Msg blankFinal = message("d".repeat(12), MsgRole.ASSISTANT, "");
        try (SelfAnalystAgent owner = new SelfAnalystAgent(config)) {
            ReActAgent reactAgent = reactAgent(owner);
            AgentState state = reactAgent.getAgentState("desktop", SESSION_I);
            state.contextMutable().addAll(List.of(
                    message("1".repeat(12), MsgRole.USER, "older question"),
                    message("2".repeat(12), MsgRole.ASSISTANT, "older answer"),
                    message("3".repeat(12), MsgRole.USER, "current question"),
                    blankFinal));
            reactAgent.saveAgentState("desktop", SESSION_I);

            assertEquals("partial legacy answer", owner.finalizeChatResponse(
                    SESSION_I, null, blankFinal, "partial legacy answer"));
            assertEquals("older answer", state.getContext().get(1).getTextContent());
            assertEquals("partial legacy answer", state.getContext().getLast().getTextContent());
        }
    }

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

                AgentState cancelledTurn = reactAgent.getAgentState("desktop", SESSION_E);
                String cancelledUserId = "b".repeat(12);
                cancelledTurn.contextMutable().addAll(List.of(
                        message(cancelledUserId, MsgRole.USER, "cancel this"),
                        message("c".repeat(12), MsgRole.ASSISTANT, "cancelled recovery")
                                .withGenerateReason(GenerateReason.INTERRUPTED)));
                reactAgent.saveAgentState("desktop", SESSION_E);
                first.rollbackCancelledTurn(SESSION_E, cancelledUserId);
                assertTrue(reactAgent.getAgentState("desktop", SESSION_E).getContext().isEmpty(),
                        "a cancelled turn must remain retryable instead of looking completed");
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

    @Test
    void compactsPersistedHistoryAndKeepsDesktopContextTransient(@TempDir Path tempDir)
            throws Exception {
        List<String> mainRequests = new CopyOnWriteArrayList<>();
        AtomicInteger chatSequence = new AtomicInteger();
        AtomicInteger summaryCalls = new AtomicInteger();
        AtomicInteger totalCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            totalCalls.incrementAndGet();
            boolean summary = body.contains("Context Extraction Assistant");
            byte[] response;
            if (summary) {
                summaryCalls.incrementAndGet();
                response = jsonResponse("summary-of-older-turns")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            } else {
                mainRequests.add(body);
                response = sseResponse("chat-" + chatSequence.incrementAndGet())
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        Config config = withOverrides(Config.testDefaults(tempDir), Map.of(
                "llmApiKey", "test-key",
                "llmBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "llmModel", "test-model",
                "agentCompactionEnabled", true,
                "agentCompactionTriggerMessages", 5,
                "agentCompactionTriggerTokens", 0,
                "agentCompactionKeepMessages", 2,
                "agentCompactionKeepTokens", 0));
        try {
            try (SelfAnalystAgent first = new SelfAnalystAgent(config)) {
                for (int turn = 1; turn <= 4; turn++) {
                    String id = Integer.toHexString(turn).repeat(12);
                    String text = "turn-" + turn;
                    String marker = "ctx-" + turn;
                    assertEquals("chat-" + turn, first.chat(SESSION_E, id,
                            () -> new SelfAnalystAgent.PersistedDesktopTurn(
                                    text, Map.of("marker", marker), List.of())).block());
                }
            }

            assertEquals(1, summaryCalls.get());
            assertEquals(4, mainRequests.size());
            assertTrue(mainRequests.get(0).contains("ctx-1"));
            assertTrue(mainRequests.get(3).contains("summary-of-older-turns"));
            assertTrue(mainRequests.get(3).contains("ctx-4"));

            Path stateRoot = config.memoryDir().resolve("agent-state/self-analyst-chat");
            JsonFileAgentStateStore store = new JsonFileAgentStateStore(stateRoot);
            AgentState compacted;
            try {
                compacted = store.get("desktop", SESSION_E, "agent_state", AgentState.class)
                        .orElseThrow();
            } finally {
                store.close();
            }
            assertEquals("summary-of-older-turns", compacted.getSummary());
            assertEquals(4, compacted.getContext().size());
            List<String> persistedText = compacted.getContext().stream()
                    .map(Msg::getTextContent).toList();
            assertFalse(persistedText.contains("turn-1"));
            assertTrue(persistedText.contains("turn-3"));
            assertTrue(persistedText.contains("turn-4"));
            String stateJson = Files.readString(stateRoot.resolve("desktop")
                    .resolve(SESSION_E).resolve("agent_state.json"));
            assertFalse(stateJson.contains("ctx-"),
                    "per-turn desktop snapshots must not inflate persisted AgentState");

            try (SelfAnalystAgent restarted = new SelfAnalystAgent(config)) {
                String latestId = "5".repeat(12);
                assertEquals("chat-5", restarted.chat(SESSION_E, latestId,
                        () -> new SelfAnalystAgent.PersistedDesktopTurn(
                                "turn-5", Map.of("marker", "ctx-5"), List.of())).block());
                assertTrue(mainRequests.getLast().contains("summary-of-older-turns"));
                assertTrue(mainRequests.getLast().contains("ctx-5"));

                int callsBeforeRetry = totalCalls.get();
                int summariesBeforeRetry = summaryCalls.get();
                assertEquals("chat-5", restarted.chat(SESSION_E, latestId,
                        () -> new SelfAnalystAgent.PersistedDesktopTurn(
                                "spoofed retry", Map.of("marker", "changed"), List.of())).block());
                assertEquals(callsBeforeRetry, totalCalls.get(),
                        "completed idempotent retry must not compact or call the chat model");
                assertEquals(summariesBeforeRetry, summaryCalls.get());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stopsBeforeMainModelWhenCompactionCrossesDailyBudget(@TempDir Path tempDir)
            throws Exception {
        AtomicInteger summaryCalls = new AtomicInteger();
        AtomicInteger mainCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            byte[] response;
            if (body.contains("Context Extraction Assistant")) {
                summaryCalls.incrementAndGet();
                response = jsonResponse("budget-crossing-summary")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            } else {
                mainCalls.incrementAndGet();
                response = sseResponse("must-not-run").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        Config config = withOverrides(Config.testDefaults(tempDir), Map.ofEntries(
                Map.entry("llmApiKey", "test-key"),
                Map.entry("llmBaseUrl",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                Map.entry("llmModel", "test-model"),
                Map.entry("agentCompactionEnabled", true),
                Map.entry("agentCompactionTriggerMessages", 5),
                Map.entry("agentCompactionTriggerTokens", 0),
                Map.entry("agentCompactionKeepMessages", 2),
                Map.entry("agentCompactionKeepTokens", 0),
                Map.entry("budgetMode", "block"),
                Map.entry("budgetDailyTokens", 20L)));
        UsageMeter meter = new UsageMeter(config, tempDir);
        try {
            try (SelfAnalystAgent owner = new SelfAnalystAgent(
                    config, null, null, null, null, meter)) {
                ReActAgent reactAgent = reactAgent(owner);
                AgentState state = reactAgent.getAgentState("desktop", SESSION_F);
                state.contextMutable().addAll(List.of(
                        message("1".repeat(12), MsgRole.USER, "question-1"),
                        message("2".repeat(12), MsgRole.ASSISTANT, "answer-1"),
                        message("3".repeat(12), MsgRole.USER, "question-2"),
                        message("4".repeat(12), MsgRole.ASSISTANT, "answer-2"),
                        message("5".repeat(12), MsgRole.USER, "question-3"),
                        message("6".repeat(12), MsgRole.ASSISTANT, "answer-3")));
                reactAgent.saveAgentState("desktop", SESSION_F);
                reactAgent.clearStateCache("desktop", SESSION_F);

                String reply = owner.chat(SESSION_F, "7".repeat(12),
                        () -> new SelfAnalystAgent.PersistedDesktopTurn(
                                "new question", Map.of(), List.of())).block();

                assertTrue(reply.contains("token"));
                assertTrue(meter.isBlocked());
                assertEquals(1, summaryCalls.get());
                assertEquals(0, mainCalls.get(),
                        "a summary call that consumes the remaining budget must stop the turn");
            }
        } finally {
            meter.flush();
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
        return sseResponseChunks(List.of(text));
    }

    private static String sseResponseChunks(List<String> chunks) {
        StringBuilder response = new StringBuilder();
        for (String text : chunks) {
            response.append("data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",")
                    .append("\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,")
                    .append("\"delta\":{\"role\":\"assistant\",\"content\":\"")
                    .append(text)
                    .append("\"},\"finish_reason\":null}]}\n\n");
        }
        response.append("data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",")
                .append("\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,")
                .append("\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                .append("data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",")
                .append("\"created\":1,\"model\":\"test-model\",\"choices\":[],")
                .append("\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,")
                .append("\"total_tokens\":7}}\n\n")
                .append("data: [DONE]\n\n");
        return response.toString();
    }

    private static String jsonResponse(String text) {
        return "{\"id\":\"chatcmpl-summary\",\"object\":\"chat.completion\","
                + "\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + text
                + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,"
                + "\"completion_tokens\":4,\"total_tokens\":24}}";
    }

    private static Config withLlm(Config base, String apiKey, String baseUrl, String model)
            throws Exception {
        return withOverrides(base, Map.of(
                "llmApiKey", apiKey,
                "llmBaseUrl", baseUrl,
                "llmModel", model));
    }

    private static Config withOverrides(Config base, Map<String, Object> overrides)
            throws Exception {
        RecordComponent[] components = Config.class.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            types[i] = component.getType();
            values[i] = overrides.containsKey(component.getName())
                    ? overrides.get(component.getName())
                    : component.getAccessor().invoke(base);
        }
        return Config.class.getDeclaredConstructor(types).newInstance(values);
    }
}
