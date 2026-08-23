package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
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
            assertEquals("pong", agent.completePlain("ping", Duration.ofSeconds(5)));
            assertEquals("Bearer test-key", authorization.get());
            assertTrue(requestBody.get().contains("\"model\":\"test-model\""));
            assertEquals(7L, meter.totalTokens());
        } finally {
            meter.flush();
            server.stop(0);
        }
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
