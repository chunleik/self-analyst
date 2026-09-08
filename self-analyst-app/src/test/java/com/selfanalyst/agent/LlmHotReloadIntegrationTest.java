package com.selfanalyst.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.*;
import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.usage.UsageMeter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmHotReloadIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "0123456789abcdef0123456789abcdef";

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
        final List<JsonNode> calls = new CopyOnWriteArrayList<>();
        final List<String> keys = new CopyOnWriteArrayList<>();
        final UserConfigStore store;
        final SelfAnalystAgent agent;
        final UsageMeter meter;
        final AtomicReference<java.util.function.Function<JsonNode, String>> answer = new AtomicReference<>();
        final AtomicBoolean failWrites = new AtomicBoolean();
        final AtomicInteger status = new AtomicInteger(200);
        Fixture(Path dir, String key) throws Exception { this(dir, key, Map.of()); }
        Fixture(Path dir, String key, Map<String, String> initialOverrides) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(requests);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    JsonNode request = JSON.readTree(exchange.getRequestBody());
                    calls.add(request);
                    keys.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    String text = answer.get() == null ? request.path("model").asText() : answer.get().apply(request);
                    boolean stream = request.path("stream").asBoolean();
                    String body = status.get() == 200 ? (text.startsWith("data: ") ? text : stream ? sse(text) : response(text)) : "{\"error\":\"unauthorized\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(status.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            server.start();
            store = new UserConfigStore(dir.resolve("config")) {
                @Override public void saveRaw(String text) throws java.io.IOException {
                    if (failWrites.get()) throw new java.io.IOException("simulated write failure");
                    super.saveRaw(text);
                }
            };
            store.saveRaw("""
                    memory.dir = '%s'
                    [llm]
                    api-key = '%s'
                    base-url = 'http://127.0.0.1:%s/v1'
                    model = 'old'
                    max-tokens = 64
                    [agent.compaction]
                    enabled = false
                    [websearch]
                    enabled = false
                    """.formatted(dir.toString(), key, server.getAddress().getPort()));
            if (!initialOverrides.isEmpty()) {
                Properties user = store.loadUser();
                initialOverrides.forEach(user::setProperty);
                store.save(user);
            }
            Config config = Config.load(store.filePath().getParent());
            meter = new UsageMeter(config, dir);
            agent = new SelfAnalystAgent(config, null, null, store, null, meter);
        }
        ConfigApplicationService config() { return agent.configuration(); }
        public void close() {
            agent.close();
            server.stop(0);
            requests.shutdownNow();
        }
    }
    @Test void oldAnswerAndNewSummaryUseTheirOwnVersions(@TempDir Path dir) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture f = new Fixture(dir, "old-key")) {
            f.answer.set(request -> {
                if (request.path("stream").asBoolean() && request.path("model").asText().equals("old")) {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return request.path("model").asText();
            });
            try {
                var reply = f.agent.chat(SESSION, "000000000001", "first").toFuture();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var update = f.config().update(Map.of("llm.model", "new", "llm.api-key", "new-key", "llm.max-tokens", "32"));
                assertEquals("draining", ((Map<?, ?>) update.application().get("llm")).get("status"));
                assertEquals("new", f.agent.completePlain("summary", Duration.ofSeconds(5)));
                release.countDown();
                assertEquals("old", reply.get(10, TimeUnit.SECONDS));
                assertEquals("new", f.agent.chat(SESSION, "000000000002", "second").block(Duration.ofSeconds(10)));
                assertEquals(List.of("Bearer old-key", "Bearer new-key", "Bearer new-key"), f.keys);
                assertEquals(64, f.calls.get(0).path("max_tokens").asInt());
                assertEquals(32, f.calls.get(1).path("max_tokens").asInt());
                assertEquals(.2, f.calls.get(1).path("temperature").asDouble());
                assertEquals(21, f.meter.totalTokens());
                assertTrue(f.calls.get(2).path("messages").toString().contains("first"));
            } finally { release.countDown(); }
        }
    }
    @Test void firstSetupClearAndRestoreKeepSameMemoryAndUsage(@TempDir Path dir) throws Exception {
        try (Fixture f = new Fixture(dir, "")) {
            var memory = f.agent.memory();
            assertFalse(f.agent.isLlmAvailable());
            assertThrows(IllegalStateException.class, () -> f.agent.completePlain("missing", Duration.ofSeconds(2)));
            f.config().update(Map.of("llm.api-key", "new-key"));
            assertTrue(f.agent.isLlmAvailable());
            assertEquals("old", f.agent.completePlain("first", Duration.ofSeconds(5)));
            long usage = f.meter.totalTokens();
            f.config().saveRaw(f.store.readRaw().replace("new-key", ""));
            assertFalse(f.agent.isLlmAvailable());
            f.config().update(Map.of("llm.api-key", "restored-key", "llm.model", "restored"));
            assertSame(memory, f.agent.memory());
            assertEquals(usage, f.meter.totalTokens());
            assertEquals("restored", f.agent.completePlain("again", Duration.ofSeconds(5)));
        }
    }
    @Test void taskLeaseAndDeferredChatBindAtCorrectBoundary(@TempDir Path dir) throws Exception {
        try (Fixture f = new Fixture(dir, "key")) {
            var deferred = f.agent.chat(SESSION, "000000000003", "later");
            try (var task = f.agent.plainTask()) {
                assertEquals("old", task.complete("one", Duration.ofSeconds(5)));
                f.config().update(Map.of("llm.model", "new"));
                assertEquals("old", task.complete("two", Duration.ofSeconds(5)));
                assertEquals("new", deferred.block(Duration.ofSeconds(10)));
            }
            var application = (Map<?, ?>) f.config().effectivePayload().get("application");
            assertEquals("applied", ((Map<?, ?>) application.get("llm")).get("status"));
        }
    }
    @Test void authenticationFailureNeverFallsBackAndShutdownRejectsSave(@TempDir Path dir) throws Exception {
        try (Fixture f = new Fixture(dir, "key")) {
            f.config().update(Map.of("llm.model", "new", "llm.api-key", "invalid-key"));
            f.status.set(401);
            assertThrows(RuntimeException.class, () -> f.agent.completePlain("no fallback", Duration.ofSeconds(5)));
            assertTrue(f.calls.stream().allMatch(call -> call.path("model").asText().equals("new")));
            assertTrue(f.keys.stream().allMatch(key -> key.equals("Bearer invalid-key")));
            f.agent.beginShutdown();
            assertThrows(IllegalStateException.class, () -> f.config().update(Map.of("llm.model", "closed")));
        }
    }
    @Test void configurationToolKeepsWholeTurnOnOldModelWithoutDeadlock(@TempDir Path dir) throws Exception {
        try (Fixture f = new Fixture(dir, "key")) {
            AtomicInteger call = new AtomicInteger();
            f.answer.set(request -> {
                if (call.getAndIncrement() != 0) return request.path("model").asText();
                String tool = null;
                for (JsonNode definition : request.path("tools")) {
                    String name = definition.path("function").path("name").asText();
                    if (name.contains("setConfigValue")) tool = name;
                }
                if (tool == null) throw new IllegalStateException("configuration tool missing");
                var delta = Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0,
                        "id", "change-model", "type", "function", "function", Map.of("name", tool,
                                "arguments", "{\"key\":\"llm.model\",\"value\":\"new\"}"))));
                return "data: " + JSON.valueToTree(Map.of("id", "test", "object", "chat.completion.chunk",
                        "created", 1, "model", "old", "choices", List.of(Map.of("index", 0,
                                "delta", delta, "finish_reason", "tool_calls"))))
                        + "\n\ndata: [DONE]\n\n";
            });
            assertEquals("old", f.agent.chat(SESSION, "000000000010", "change model").block(Duration.ofSeconds(10)));
            assertEquals("new", f.agent.llmSettings().model());
            assertEquals("old", f.calls.get(0).path("model").asText());
            assertEquals("old", f.calls.get(1).path("model").asText());
            assertTrue(f.calls.get(1).path("messages").toString().contains("change-model"));
            assertEquals("new", f.agent.chat(SESSION, "000000000011", "after change").block(Duration.ofSeconds(10)));
            assertTrue(f.agent.hasChatSessionState(SESSION));
        }
    }

    @Test void cancelledOldWorkReleasesVersionAndSharedStateSurvives(@TempDir Path dir) throws Exception {
        try (Fixture f = new Fixture(dir, "key")) {
            var stream = f.agent.runExclusiveDesktopChatStream(SESSION, "000000000020",
                    () -> reactor.core.publisher.Flux.never());
            var subscription = stream.subscribe();
            f.config().update(Map.of("llm.model", "new"));
            var app = (Map<?, ?>) f.config().effectivePayload().get("application");
            assertEquals("draining", ((Map<?, ?>) app.get("llm")).get("status"));
            subscription.dispose();
            assertEquals("new", f.agent.chat(SESSION, "000000000021", "state still works").block(Duration.ofSeconds(10)));
            f.agent.deleteChatSessionState(SESSION);
            assertFalse(f.agent.hasChatSessionState(SESSION));
        }
    }

    @Test void modelSwitchDoesNotApplyPendingAgentParameters(@TempDir Path dir) throws Exception {
        try (Fixture f = new Fixture(dir, "key")) {
            var field = SelfAnalystAgent.class.getDeclaredField("stateCompactor");
            field.setAccessible(true);
            assertNull(field.get(f.agent));
            f.config().update(Map.of("llm.model", "new", "agent.compaction.enabled", "true",
                    "agent.compaction.triggerMessages", "3", "llm.agent.maxIters", "1"));
            assertEquals("new", f.agent.chat(SESSION, "000000000022", "frozen settings").block(Duration.ofSeconds(10)));
            assertNull(field.get(f.agent), "pending compaction must not be activated by changing model");
            assertTrue(((Map<?, ?>) f.config().effectivePayload().get("application")).containsKey("application"));
        }
    }

    @Test void failedCandidateDisposalKeepsToolkitAndStateStoreOpen(@TempDir Path dir) throws Exception {
        try (Fixture f = new Fixture(dir, "key")) {
            assertEquals("old", f.agent.chat(SESSION, "000000000030", "before").block(Duration.ofSeconds(10)));
            String original = f.store.readRaw();
            f.failWrites.set(true);
            assertThrows(java.io.IOException.class, () -> f.config().update(Map.of("llm.model", "discard")));
            assertEquals(original, f.store.readRaw());
            assertEquals("old", f.agent.chat(SESSION, "000000000031", "after").block(Duration.ofSeconds(10)));
            assertTrue(f.calls.get(1).path("messages").toString().contains("before"));
            assertTrue(f.calls.get(1).path("tools").size() > 0);
            f.agent.deleteChatSessionState(SESSION);
        }
    }

    @Test void compactionAndFollowingReasoningKeepTheSameVersion(@TempDir Path dir) throws Exception {
        CountDownLatch compacting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture f = new Fixture(dir, "key", Map.of("agent.compaction.enabled", "true",
                "agent.compaction.triggerMessages", "3", "agent.compaction.triggerTokens", "0",
                "agent.compaction.keepMessages", "2"))) {
            f.answer.set(request -> {
                if (!request.path("stream").asBoolean() && request.path("model").asText().equals("old")) {
                    compacting.countDown();
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
                return request.path("model").asText();
            });
            List<io.agentscope.core.message.Msg> history = new ArrayList<>();
            for (int i = 0; i < 4; i++) history.add(io.agentscope.core.message.Msg.builder()
                    .name(i % 2 == 0 ? "user" : "assistant")
                    .role(i % 2 == 0 ? io.agentscope.core.message.MsgRole.USER : io.agentscope.core.message.MsgRole.ASSISTANT)
                    .textContent("history-" + i).build());
            try {
                var reply = f.agent.chat(SESSION, "000000000040", "after compaction", () -> history).toFuture();
                assertTrue(compacting.await(5, TimeUnit.SECONDS));
                f.config().update(Map.of("llm.model", "new"));
                release.countDown();
                assertEquals("old", reply.get(10, TimeUnit.SECONDS));
                assertEquals("old", f.calls.get(0).path("model").asText());
                assertEquals("old", f.calls.get(1).path("model").asText());
                assertFalse(f.calls.get(0).path("stream").asBoolean());
                assertTrue(f.calls.get(1).path("stream").asBoolean());
                assertEquals("new", f.agent.chat(SESSION, "000000000041", "next").block(Duration.ofSeconds(10)));
            } finally { release.countDown(); }
        }
    }

    @Test void changingModelCannotResetAnExceededBudget(@TempDir Path dir) throws Exception {
        try (Fixture f = new Fixture(dir, "key", Map.of("llm.budget.mode", "block", "llm.budget.dailyTokens", "1"))) {
            assertEquals("old", f.agent.completePlain("use budget", Duration.ofSeconds(5)));
            assertTrue(f.agent.isBudgetBlocked());
            f.config().update(Map.of("llm.model", "new"));
            assertTrue(f.agent.isBudgetBlocked());
            f.agent.chat(SESSION, "000000000042", "blocked").block(Duration.ofSeconds(10));
            assertEquals(1, f.calls.size());
            assertEquals(7, f.meter.totalTokens());
        }
    }

    private static String response(String text) throws java.io.IOException {
        return JSON.writeValueAsString(Map.of("id", "test", "object", "chat.completion", "created", 1, "model", "test",
                "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", text),
                        "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 5, "completion_tokens", 2, "total_tokens", 7)));
    }
    private static String sse(String text) throws java.io.IOException {
        return "data: " + JSON.writeValueAsString(Map.of("id", "test", "object", "chat.completion.chunk",
                "created", 1, "model", "test",
                "choices", List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", text),
                        "finish_reason", "stop")))) + "\n\n"
                + "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\","
                + "\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}\n\n"
                + "data: [DONE]\n\n";
    }
}
