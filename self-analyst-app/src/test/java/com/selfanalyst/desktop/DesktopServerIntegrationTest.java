package com.selfanalyst.desktop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.aw.store.Database;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.PulseTimeConfig;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.UserConfigStore;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopServerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private final HttpClient http = HttpClient.newHttpClient();
    private Database database;
    private Javalin javalin;
    private DesktopServer desktop;
    private String baseUrl;

    @BeforeEach
    void startServer() {
        Config config = Config.testDefaults(tempDir);
        database = new Database(tempDir.resolve("events"));
        EventStore eventStore = new EventStore(database, PulseTimeConfig.DEFAULT);
        javalin = Javalin.create();
        desktop = new DesktopServer(javalin, config, null, eventStore, null, null, null,
                true, null, null, null, null,
                new UserConfigStore(tempDir.resolve("config")));
        desktop.start();
        javalin.start(0);
        baseUrl = "http://127.0.0.1:" + javalin.port();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (javalin != null) javalin.stop();
        if (desktop != null) desktop.shutdown();
        if (database != null) database.close();
    }

    @Test
    void statusReportsCurrentCollectorsWithoutRemovedAudioCollector() throws Exception {
        Map<String, Object> status = get("/desktop/status", new TypeReference<>() {});
        assertEquals("running", status.get("backend"));
        assertNotNull(status.get("aw"));
        Map<?, ?> collectors = (Map<?, ?>) status.get("collectors");
        assertEquals("disabled", collectors.get("window"));
        assertEquals("disabled", collectors.get("contextTitle"));
        assertFalse(collectors.containsKey("audio"));
    }

    @Test
    void configRoundTripPersistsModel() throws Exception {
        Map<String, Object> initial = get("/desktop/config", new TypeReference<>() {});
        assertNotNull(((Map<?, ?>) initial.get("llm")).get("model"));

        Map<String, Object> saved = put(
                "/desktop/config",
                MAPPER.writeValueAsString(Map.of(
                        "llm", Map.of("model", "integration-model"))),
                new TypeReference<>() {});
        assertEquals(Boolean.TRUE, saved.get("saved"));

        Map<String, Object> after = get("/desktop/config", new TypeReference<>() {});
        Map<?, ?> model = (Map<?, ?>) ((Map<?, ?>) after.get("llm")).get("model");
        assertEquals("integration-model", model.get("effectiveValue"));
    }

    @Test
    void taskCrudLifecycleRunsThroughHttpRoutes() throws Exception {
        Map<String, Object> created = post(
                "/desktop/tasks",
                MAPPER.writeValueAsString(Map.of(
                        "title", "Integration Task", "priority", "high")),
                new TypeReference<>() {});
        String id = (String) created.get("id");
        assertNotNull(id);

        List<Map<String, Object>> tasks = get(
                "/desktop/tasks", new TypeReference<>() {});
        assertTrue(tasks.stream().anyMatch(task -> id.equals(task.get("id"))));

        Map<String, Object> updated = put(
                "/desktop/tasks/" + id,
                MAPPER.writeValueAsString(Map.of("title", "Updated Task")),
                new TypeReference<>() {});
        assertEquals("Updated Task", updated.get("title"));

        post("/desktop/tasks/" + id + "/complete", "", new TypeReference<>() {});
        post("/desktop/tasks/" + id + "/archive", "", new TypeReference<>() {});
        delete("/desktop/tasks/" + id);

        List<Map<String, Object>> remaining = get(
                "/desktop/tasks", new TypeReference<>() {});
        assertFalse(remaining.stream().anyMatch(task -> id.equals(task.get("id"))));
    }

    @Test
    void chatWithoutAgentReturnsConfigurationMessage() throws Exception {
        Map<String, Object> response = post(
                "/desktop/chat",
                MAPPER.writeValueAsString(Map.of(
                        "message", "Hello?",
                        "context", Map.of("type", "manual"))),
                new TypeReference<>() {});
        assertTrue(String.valueOf(response.get("message")).contains("未配置"));
    }

    private <T> T get(String path, TypeReference<T> type) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(), type);
    }

    private <T> T post(String path, String body, TypeReference<T> type) throws Exception {
        HttpRequest.BodyPublisher publisher = body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(publisher)
                .build(), type);
    }

    private <T> T put(String path, String body, TypeReference<T> type) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(), type);
    }

    private void delete(String path) throws Exception {
        send(HttpRequest.newBuilder(URI.create(baseUrl + path)).DELETE().build(),
                new TypeReference<Map<String, Object>>() {});
    }

    private <T> T send(HttpRequest request, TypeReference<T> type) throws Exception {
        HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 400,
                () -> "HTTP " + response.statusCode() + ": " + response.body());
        return MAPPER.readValue(response.body(), type);
    }
}
