package com.selfanalyst.chatgpt;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChatGptModelCatalogTest {
    @Test void readsAccountSpecificSchemaInServerOrderWithoutExposingCredential() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            assertEquals("Bearer private-token", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                {"models":[{"slug":"b","display_name":"B","visibility":"list"},
                {"slug":"hidden","display_name":"Hidden","visibility":"hide"},
                {"slug":"a","display_name":"A","visibility":"list"},
                {"slug":"b","display_name":"Duplicate","visibility":"list"},
                {"slug":"private-token","display_name":"Secret","visibility":"list"}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var result = catalog(server, "/models").fetch(() -> "private-token");
            assertTrue(result.ok());
            assertEquals(java.util.List.of("b", "a"), result.models().stream().map(ChatGptModelCatalog.Entry::slug).toList());
            assertEquals("B", result.models().getFirst().displayName());
            assertFalse(result.toString().contains("private-token"));
        } finally { server.stop(0); }
    }
    @Test void doesNotFollowRedirectOrAcceptChatCompletionsCatalog() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var redirected = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/target", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/legacy", exchange -> {
            byte[] bytes = "{\"data\":[{\"id\":\"legacy\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            assertFalse(catalog(server, "/redirect").fetch(() -> "private-token").ok());
            assertEquals(0, redirected.get());
            assertEquals("protocol", catalog(server, "/legacy").fetch(() -> "private-token").code());
        } finally { server.stop(0); }
    }
    @Test void boundsResponseAndSanitizesErrors() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large", exchange -> {
            byte[] bytes = new byte[1024 * 1024 + 1];
            try { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
            catch (java.io.IOException ignored) {} finally { exchange.close(); }
        });
        server.createContext("/unauthorized", exchange -> {
            byte[] bytes = "private-token".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            assertEquals("response_limit", catalog(server, "/large").fetch(() -> "private-token").code());
            assertEquals("authentication", catalog(server, "/unauthorized").fetch(() -> "private-token").code());
            var failure = catalog(server, "/none").fetch(() -> { throw new IllegalStateException("private-token"); });
            assertEquals("network", failure.code());
            assertFalse(failure.toString().contains("private-token"));
        } finally { server.stop(0); }
    }
    @Test void distinguishesRetryableTokenFailuresFromReauthorizationWithoutSendingRequests() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/models", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = "{\"models\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var catalog = catalog(server, "/models");
            var cases = java.util.Map.of(
                    "chatgpt_sign_in_required", "authentication", "chatgpt_unknown_account", "authentication",
                    "chatgpt_plan_permission_required", "permission", "chatgpt_request_timeout", "timeout",
                    "chatgpt_network_error", "network", "chatgpt_refresh_failed", "network",
                    "chatgpt_refresh_not_yet_allowed", "network", "chatgpt_request_interrupted", "network",
                    "chatgpt_discovery_failed", "network", "chatgpt_service_closed", "network");
            cases.forEach((localCode, expected) -> {
                var failure = catalog.fetch(() -> { throw new IllegalStateException(localCode, new RuntimeException("private-token")); });
                assertEquals(expected, failure.code(), localCode);
                assertFalse(failure.ok());
                assertFalse(failure.toString().contains("private-token"));
            });
            assertEquals("network", catalog.fetch(() -> { throw new IllegalStateException(); }).code());
            assertEquals(0, requests.get());
            var supplies = new AtomicInteger();
            java.util.function.Supplier<String> recovering = () -> {
                if (supplies.getAndIncrement() == 0) throw new IllegalStateException("chatgpt_refresh_failed");
                return "private-token";
            };
            assertEquals("network", catalog.fetch(recovering).code());
            assertTrue(catalog.fetch(recovering).ok());
            assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }
    private ChatGptModelCatalog catalog(HttpServer server, String path) {
        return new ChatGptModelCatalog(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path), Duration.ofSeconds(2));
    }
}
