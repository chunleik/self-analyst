package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.config.LlmSettings;
import com.selfanalyst.usage.UsageMeter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.RecordComponent;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAnalystAgentOpenAiCompatibilityTest {

    @Test
    void completesAgainstOpenAiCompatibleEndpointAndRecordsUsage(@TempDir Path tempDir)
            throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] response = """
                    {"id":"chatcmpl-test","object":"chat.completion","created":1,
                     "model":"test-model","choices":[{"index":0,
                     "message":{"role":"assistant","content":"pong"},
                     "finish_reason":"stop"}],
                     "usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        Config config = withLlm(Config.testDefaults(tempDir),
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model");
        UsageMeter meter = new UsageMeter(config, tempDir);
        try (SelfAnalystAgent agent =
                     new SelfAnalystAgent(config, null, null, null, null, meter)) {
            try (var task = agent.plainTask()) {
                var result = task.completeDetailed("ping", Duration.ofSeconds(5));
                assertEquals("pong", result.text());
                assertEquals(5L, result.inputTokens());
                assertEquals(2L, result.outputTokens());
            }
            assertEquals("Bearer test-key", authorization.get());
            assertTrue(requestBody.get().contains("\"model\":\"test-model\""));
            assertTrue(requestBody.get().contains("\"temperature\":0.2"));
            assertFalse(requestBody.get().contains("max_tokens"));
            assertFalse(requestBody.get().contains("max_completion_tokens"));
            assertFalse(requestBody.get().contains("max_output_tokens"));
            assertEquals(7L, meter.totalTokens());
        } finally {
            meter.flush();
            server.stop(0);
        }
    }

    @Test
    void plainIdentityIsStableAndSeparatesSemanticConfiguration() {
        var original = new LlmSettings("private-key", "https://EXAMPLE.test:443/v1/", "model-a", .7);
        var rotated = new LlmSettings("rotated-key", "https://example.test/v1", "model-a", 1.3);
        String fingerprint = SelfAnalystAgent.plainCacheIdentity(original, "zh", "摘要系统提示");
        assertEquals(fingerprint, SelfAnalystAgent.plainCacheIdentity(rotated, "zh", "摘要系统提示"));
        assertFalse(fingerprint.contains("private-key"));
        assertFalse(fingerprint.contains("example.test"));
        assertNotEquals(fingerprint, SelfAnalystAgent.plainCacheIdentity(
                new LlmSettings("private-key", "https://other.test/v1", "model-a", .7), "zh", "摘要系统提示"));
        assertNotEquals(fingerprint, SelfAnalystAgent.plainCacheIdentity(
                new LlmSettings("private-key", "https://example.test/v1", "model-b", .7), "zh", "摘要系统提示"));
        assertNotEquals(fingerprint, SelfAnalystAgent.plainCacheIdentity(original, "en", "摘要系统提示"));
        assertNotEquals(fingerprint, SelfAnalystAgent.plainCacheIdentity(original, "zh", "修改后的系统提示"));
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
