package com.selfanalyst.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.DesktopServer;
import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.events.EventServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopLlmSettingsControllerTest {
    @TempDir Path dir;
    @Test void realDesktopRoutesRespectAuthAndPersistOnlyTargetFields() throws Exception {
        int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var server = new EventServer(dir.resolve("events"), port, "test-desktop-token");
        var store = new UserConfigStore(dir.resolve("config"));
        store.saveRaw("# keep\n[llm]\nmodel='old'\napi-key='private-test-key'\n");
        var desktop = new DesktopServer(server.app(), Config.testDefaults(dir), null, server.eventStore(), null,
                null, null, true, null, null, null, null, store);
        desktop.start(); server.start();
        String base = "http://127.0.0.1:" + port;
        try (var client = HttpClient.newHttpClient()) {
            for (String path : List.of("/desktop/llm-settings", "/desktop/llm-settings/presets", "/desktop/llm-settings/test", "/desktop/llm-settings/discover-models")) {
                boolean probe = path.endsWith("/test") || path.endsWith("/discover-models");
                var request = HttpRequest.newBuilder(URI.create(base + path))
                        .method(probe ? "POST" : "GET", probe ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody());
                assertEquals(401, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                request.header("X-SelfAnalyst-Token", "test-desktop-token").header("Origin", "https://evil.example");
                assertEquals(403, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            var response = send(client, base, "", "GET", null);
            assertEquals(200, response.statusCode());
            assertFalse(response.body().contains("private-test-key")); assertFalse(response.body().contains("****"));
            response = send(client, base, "", "PUT", "{\"updates\":{\"model\":\"new\",\"maxTokens\":32}}");
            assertEquals(200, response.statusCode(), response.body());
            var body = new ObjectMapper().readTree(response.body()); assertTrue(body.path("saved").asBoolean());
            assertEquals("new", body.path("settings").path("fields").path("model").path("effectiveValue").asText());
            assertTrue(store.readRaw().startsWith("# keep"));
            assertEquals("private-test-key", store.loadUser().getProperty("llm.api-key"));
            assertEquals(200, send(client, base, "/presets", "GET", null).statusCode());
            assertEquals(400, send(client, base, "", "PUT", "{\"protocol\":\"anthropic\"}").statusCode());
            String before = store.readRaw();
            response = send(client, base, "/test", "POST", "{\"baseUrl\":\"http://127.0.0.1:9\",\"model\":\"x\"}");
            assertEquals(400, response.statusCode()); assertFalse(response.body().contains("private-test-key"));
            response = send(client, base, "/test", "POST", "{\"credential\":{\"action\":\"clear\"}}");
            assertEquals("not_configured", new ObjectMapper().readTree(response.body()).path("code").asText());
            assertEquals(before, store.readRaw());
            var raw = HttpRequest.newBuilder(URI.create(base + "/desktop/config/raw"))
                    .header("X-SelfAnalyst-Token", "test-desktop-token").GET().build();
            assertTrue(client.send(raw, HttpResponse.BodyHandlers.ofString()).body().contains("private-test-key"));
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.getOutputStream().write(("GET /desktop/llm-settings HTTP/1.1\r\nHost: evil.example\r\nX-SelfAnalyst-Token: test-desktop-token\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                assertTrue(new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine().contains("403"));
            }
        } finally { desktop.shutdown(); server.stop(); }
    }
    private HttpResponse<String> send(HttpClient client, String base, String suffix, String method, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/desktop/llm-settings" + suffix))
                .header("X-SelfAnalyst-Token", "test-desktop-token").header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
