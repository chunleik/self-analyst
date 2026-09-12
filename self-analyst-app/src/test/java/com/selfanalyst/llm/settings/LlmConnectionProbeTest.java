package com.selfanalyst.llm.settings;

import com.selfanalyst.config.LlmSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LlmConnectionProbeTest {
    @Test void discoveryAndGenerationAreSeparateAndBounded() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var response = new AtomicReference<>("{\"data\":[{\"id\":\"a\"},{\"id\":\"a\"},{\"id\":null},{\"id\":\"<model>\"}]}");
        var request = new AtomicReference<String>();
        server.createContext("/", e -> {
            request.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.start();
        try {
            var connection = new LlmSettings("private-key", "http://127.0.0.1:" + server.getAddress().getPort(), "test", .7, 2048);
            var probe = new LlmConnectionProbe();
            assertEquals(java.util.List.of("a", "<model>"), probe.run(connection, true).models());
            assertEquals("protocol", probe.run(connection, false).code());
            assertTrue(request.get().contains("Reply OK.")); assertTrue(request.get().contains("\"max_tokens\":16"));
            response.set("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
            assertTrue(probe.run(connection, false).ok());
            response.set("not json private-key");
            assertEquals("protocol", probe.run(connection, false).code());
            response.set("x".repeat(1024 * 1024 + 1));
            assertEquals("response_limit", probe.run(connection, true).code());
            response.set("{\"data\":[" + String.join(",", java.util.stream.IntStream.range(0, 501)
                    .mapToObj(i -> "{\"id\":\"m" + i + "\"}").toList()) + "]}");
            var result = probe.run(connection, true);
            assertEquals(500, result.models().size()); assertTrue(result.truncated());
        } finally { server.stop(0); }
    }
    @Test void statusErrorsRedirectsAndSlowBodies() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mode = new AtomicReference<>(401);
        var pool = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(pool);
        server.createContext("/", e -> {
            try {
                e.getResponseHeaders().set("Location", "/redirected");
                e.sendResponseHeaders(mode.get(), 0);
                if (mode.get() == 200) {
                    e.getResponseBody().write('{'); e.getResponseBody().flush();
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            } finally { e.close(); }
        });
        server.start();
        try {
            var connection = new LlmSettings("key", "http://127.0.0.1:" + server.getAddress().getPort(), "m", 0, 16);
            var probe = new LlmConnectionProbe(Duration.ofMillis(300));
            assertEquals("authentication", probe.run(connection, false).code());
            mode.set(403); assertEquals("permission", probe.run(connection, false).code());
            mode.set(429); assertEquals("rate_limit", probe.run(connection, false).code());
            mode.set(400); assertEquals("parameters", probe.run(connection, false).code());
            mode.set(302); assertEquals("http", probe.run(connection, false).code());
            mode.set(404); assertEquals("unsupported_discovery", probe.run(connection, true).code());
            mode.set(200); assertEquals("timeout", probe.run(connection, false).code());
        } finally { server.stop(0); pool.shutdownNow(); }
    }
}
