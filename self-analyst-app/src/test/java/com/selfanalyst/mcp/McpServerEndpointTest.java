package com.selfanalyst.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.selfanalyst.mail.MailException;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerEndpointTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AtomicReference<String> token = new AtomicReference<>("external-test-token");
    private final AtomicInteger calls = new AtomicInteger();
    private Javalin app;
    private HttpClient client;

    @BeforeEach
    void start() {
        var endpoint = new LocalMcpHttpEndpoint(new LocalMcpHttpEndpoint.Access() {
            @Override
            public boolean authorize(String serverId, String bearerToken) {
                return token.get().equals(bearerToken);
            }

            @Override
            public boolean exposed(String serverId) {
                return "mail".equals(serverId) || "all".equals(serverId);
            }

            @Override
            public List<JsonNode> listTools(String serverId, String bearerToken) {
                assertEquals(token.get(), bearerToken);
                ObjectNode tool = JSON.createObjectNode().put("name", nameFor(serverId));
                tool.putObject("inputSchema").put("type", "object");
                return List.of(tool);
            }

            @Override
            public JsonNode callTool(String serverId, String bearerToken, String toolName, ObjectNode arguments) {
                assertEquals(token.get(), bearerToken);
                if ("unknown_outcome".equals(toolName)) {
                    throw new MailException(MailException.Code.OUTCOME_UNKNOWN);
                }
                if (!nameFor(serverId).equals(toolName)) {
                    throw new IllegalArgumentException("private-provider-debug-detail");
                }
                calls.incrementAndGet();
                var result = JSON.createObjectNode();
                result.putArray("content").addObject().put("type", "text").put("text", arguments.path("value").asText());
                result.put("isError", false);
                return result;
            }
        });
        app = Javalin.create(c -> c.showJavalinBanner = false);
        app.get(LocalMcpHttpEndpoint.PATH, endpoint::handle);
        app.post(LocalMcpHttpEndpoint.PATH, endpoint::handle);
        app.delete(LocalMcpHttpEndpoint.PATH, endpoint::handle);
        app.start("127.0.0.1", 0);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stop() {
        if (client != null) client.close();
        if (app != null) app.stop();
    }

    @Test
    void negotiatesAndExecutesPerServerAndAggregateTools() throws Exception {
        var initialize = post("mail", token.get(), """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}
                """);
        assertEquals(200, initialize.statusCode());
        assertTrue(initialize.headers().firstValue("Mcp-Session-Id").isEmpty());
        assertEquals("2025-03-26", json(initialize).at("/result/protocolVersion").asText());
        assertTrue(json(initialize).at("/result/capabilities/tools").isObject());
        assertEquals("2025-11-25", json(post("mail", token.get(), """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1900-01-01"}}
                """)).at("/result/protocolVersion").asText());

        assertEquals(202, post("mail", token.get(), """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """).statusCode());
        for (String server : List.of("mail", "all")) {
            var tools = post(server, token.get(), rpc("tools/list", "{}"));
            assertEquals(200, tools.statusCode());
            assertEquals(nameFor(server), json(tools).at("/result/tools/0/name").asText());
            var call = post(server, token.get(), rpc("tools/call",
                    "{\"name\":\"" + nameFor(server) + "\",\"arguments\":{\"value\":\"example\"}}"));
            assertEquals(200, call.statusCode());
            assertEquals("request-id", json(call).path("id").asText());
            assertEquals("example", json(call).at("/result/content/0/text").asText());
        }
        assertEquals(2, calls.get());
    }

    @Test
    void authenticatesBeforeExposureAndHonorsRotation() throws Exception {
        var missing = post("mail", null, "not json");
        assertEquals(401, missing.statusCode());
        assertEquals("Bearer", missing.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertEquals(401, post("hidden", "bad-token", rpc("tools/list", "{}")).statusCode());
        assertEquals(404, post("hidden", token.get(), rpc("tools/list", "{}")).statusCode());
        assertEquals(401, send("mail", null, "POST", "not json", Map.of(
                "X-SelfAnalyst-Token", token.get(), "Cookie", "self_analyst_session=" + token.get())).statusCode());

        String stale = token.getAndSet("rotated-token");
        assertEquals(401, post("mail", stale, rpc("tools/list", "{}")).statusCode());
        assertEquals(200, post("mail", token.get(), rpc("tools/list", "{}")).statusCode());
    }

    @Test
    void rejectsForeignOriginsAndUnsupportedHttpFeatures() throws Exception {
        assertFalse(LocalMcpHttpEndpoint.localAuthority("foreign.invalid:1234", 1234));
        assertFalse(LocalMcpHttpEndpoint.localAuthority("localhost:1235", 1234));
        assertTrue(LocalMcpHttpEndpoint.localAuthority("127.0.0.1:1234", 1234));
        for (String origin : List.of("https://foreign.invalid", "null", "http://127.0.0.1:" + app.port())) {
            var response = send("mail", token.get(), "POST", rpc("ping", "{}"), Map.of("Origin", origin));
            assertEquals(403, response.statusCode());
            assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        }
        assertEquals(405, send("mail", token.get(), "GET", "", Map.of()).statusCode());
        assertEquals(405, send("mail", token.get(), "DELETE", "", Map.of()).statusCode());
        assertEquals(400, send("mail", token.get(), "POST", rpc("ping", "{}"),
                Map.of("MCP-Protocol-Version", "1900-01-01")).statusCode());
        assertEquals(406, send("mail", token.get(), "POST", rpc("ping", "{}"),
                Map.of("Accept", "text/event-stream")).statusCode());
        assertEquals(415, send("mail", token.get(), "POST", rpc("ping", "{}"),
                Map.of("Content-Type", "text/plain")).statusCode());
    }

    @Test
    void malformedAndNotificationMessagesDoNotExecuteTools() throws Exception {
        assertEquals(-32700, error(post("mail", token.get(), "{")));
        assertEquals(-32700, error(post("mail", token.get(), rpc("ping", "{}") + "{}")));
        assertEquals(-32700, error(post("mail", token.get(), """
                {"jsonrpc":"2.0","id":1,"method":"ping","method":"tools/call"}
                """)));
        assertEquals(-32600, error(post("mail", token.get(), "[" + rpc("ping", "{}") + "]")));
        assertEquals(-32602, error(post("mail", token.get(), rpc("tools/call",
                "{\"name\":\"read\",\"arguments\":[]}"))));
        assertEquals(202, post("mail", token.get(), """
                {"jsonrpc":"2.0","method":"tools/call","params":{"name":"read"}}
                """).statusCode());
        assertEquals(413, post("mail", token.get(), " ".repeat(1024 * 1024 + 1)).statusCode());
        var unavailable = post("mail", token.get(), rpc("tools/call", "{\"name\":\"not-exposed\"}"));
        assertEquals(-32603, error(unavailable));
        assertFalse(unavailable.body().contains("private-provider-debug-detail"));
        assertEquals(0, calls.get());
    }

    @Test
    void preservesSafeUnknownWriteOutcomeAsMcpToolError() throws Exception {
        var response = post("mail", token.get(), rpc("tools/call",
                "{\"name\":\"unknown_outcome\",\"arguments\":{\"value\":\"dummy-private-body\"}}"));
        assertEquals(200, response.statusCode());
        JsonNode result = json(response).path("result");
        assertTrue(result.path("isError").asBoolean());
        String message = result.at("/content/0/text").asText();
        assertTrue(message.startsWith("OUTCOME_UNKNOWN:"));
        assertTrue(message.contains("Verify it before retrying"));
        assertFalse(response.body().contains("dummy-private-body"));
    }

    private HttpResponse<String> post(String server, String bearer, String body) throws Exception {
        return send(server, bearer, "POST", body, Map.of());
    }

    private HttpResponse<String> send(String server, String bearer, String method, String body,
                                      Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/mcp/" + server))
                .timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        request.header("Content-Type", headers.getOrDefault("Content-Type", "application/json"));
        request.header("Accept", headers.getOrDefault("Accept", "application/json, text/event-stream"));
        for (var header : headers.entrySet()) {
            if (!List.of("Content-Type", "Accept").contains(header.getKey())) {
                request.header(header.getKey(), header.getValue());
            }
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String nameFor(String server) {
        return server.equals("all") ? "mail__read" : "read";
    }

    private static String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"request-id\",\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private static int error(HttpResponse<String> response) throws Exception {
        return json(response).at("/error/code").asInt();
    }
}
