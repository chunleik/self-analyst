package com.selfanalyst.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.i18n.Lang;
import com.selfanalyst.wiki.usage.BudgetExceededException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WikiFormalEvaluationSessionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void usesProductionPlainEnvelopeUsageAndGateInAnIsolatedRoot(@TempDir Path root) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            request.set(JSON.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {"id":"synthetic","object":"chat.completion","created":1,"model":"test-model",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"synthetic answer"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        String key = "evaluation-only-secret";
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        try (var session = new WikiFormalEvaluationSession(key, endpoint, "test-model", root, 1)) {
            session.preflight();
            var result = session.completeDetailed("synthetic fact prompt", Duration.ofSeconds(5));
            assertEquals("synthetic answer", result.text());
            assertEquals(5L, result.inputTokens());
            assertEquals(2L, result.outputTokens());
            JsonNode body = request.get();
            assertEquals(.2, body.path("temperature").asDouble());
            assertFalse(body.has("max_tokens"));
            assertFalse(body.has("max_completion_tokens"));
            assertFalse(body.has("max_output_tokens"));
            assertFalse(body.has("response_format"));
            assertEquals(AgentPrompts.plainCompletionPrompt(Lang.chinese()),
                    body.path("messages").get(0).path("content").asText());
            assertEquals("synthetic fact prompt", body.path("messages").get(1).path("content").asText());
            assertThrows(BudgetExceededException.class, session::preflight);
            assertEquals(1, calls.get());
            assertEquals(7L, session.usageSnapshot().get("totalTokens"));
            String metadata = JSON.writeValueAsString(session.metadata());
            assertFalse(metadata.contains(key));
            assertFalse(metadata.contains(endpoint));
            assertFalse(metadata.contains(root.toString()));
            assertTrue(metadata.contains("systemPromptFingerprint"));
            assertEquals(1, session.metadata().get("sdkMaxAttempts"));
        } finally { server.stop(0); }
        assertFalse(Files.exists(root.resolve("config")), "evaluation does not write a credential/config file");
        assertTrue(Files.exists(root.resolve("memory/usage")), "usage belongs only to the isolated evaluation root");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(file).contains(key), file.toString());
            }
        }
    }
}
