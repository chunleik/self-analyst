package com.selfanalyst.aw;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwServerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dataDir;

    private final HttpClient http = HttpClient.newHttpClient();
    private AwServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() {
        server = new AwServer(dataDir, 0, null);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port() + "/api/0";
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    void bucketAndEventHttpLifecycleSupportsTimeFiltering() throws Exception {
        createBucket("events");
        Instant start = Instant.parse("2026-06-10T10:00:00Z");
        post("/buckets/events/events", MAPPER.writeValueAsString(List.of(
                event(start.plusSeconds(100), 5, "a"),
                event(start.plusSeconds(3600), 10, "b"),
                event(start.plusSeconds(7100), 3, "c"))));

        Map<String, Object> buckets = get("/buckets/", new TypeReference<>() {});
        assertTrue(buckets.containsKey("events"));

        List<Map<String, Object>> all = get(
                "/buckets/events/events?limit=10", new TypeReference<>() {});
        assertEquals(3, all.size());

        List<Map<String, Object>> filtered = get(
                "/buckets/events/events?limit=10&start=" + start
                        + "&end=" + start.plusSeconds(3600),
                new TypeReference<>() {});
        assertEquals(2, filtered.size(), "start/end filters are inclusive");
        assertEquals("a", ((Map<?, ?>) filtered.get(0).get("data")).get("app"));
    }

    @Test
    void heartbeatMergesMatchingEventsWithinPulseTime() throws Exception {
        createBucket("heartbeat");
        Instant now = Instant.now();
        Map<String, Object> data = Map.of("app", "editor", "title", "Document");
        post("/buckets/heartbeat/heartbeat", MAPPER.writeValueAsString(Map.of(
                "timestamp", now.toString(), "duration", 10.0, "data", data)));
        post("/buckets/heartbeat/heartbeat", MAPPER.writeValueAsString(Map.of(
                "timestamp", now.plusSeconds(30).toString(), "duration", 10.0, "data", data)));

        List<Map<String, Object>> events = get(
                "/buckets/heartbeat/events?limit=10", new TypeReference<>() {});
        assertEquals(1, events.size());
    }

    @Test
    void aqlRunsAgainstEventsInsertedThroughHttp() throws Exception {
        createBucket("aql");
        Instant now = Instant.now();
        post("/buckets/aql/events", MAPPER.writeValueAsString(List.of(
                event(now, 60, "a"),
                event(now.plusSeconds(120), 30, "b"))));
        String period = now.minusSeconds(60) + "/" + now.plusSeconds(300);
        String query = MAPPER.writeValueAsString(Map.of(
                "timeperiods", List.of(period),
                "query", List.of(
                        "events = query_bucket(\"aql\");",
                        "events = sort_by_duration(events);",
                        "RETURN = events;")));

        Map<String, Object> result = post(
                "/query/", query, new TypeReference<>() {});
        assertEquals(2, ((List<?>) result.get("rows")).size());
        assertNotNull(result.get("total_duration"));
    }

    private void createBucket(String id) throws Exception {
        post("/buckets/" + id, MAPPER.writeValueAsString(Map.of(
                "name", id, "type", "test", "client", "junit", "hostname", "test")));
    }

    private static Map<String, Object> event(Instant timestamp, double duration, String app) {
        return Map.of(
                "timestamp", timestamp.toString(),
                "duration", duration,
                "data", Map.of("app", app));
    }

    private void post(String path, String body) throws Exception {
        post(path, body, new TypeReference<Map<String, Object>>() {});
    }

    private <T> T post(String path, String body, TypeReference<T> type) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 400,
                () -> "HTTP " + response.statusCode() + ": " + response.body());
        if (response.body() == null || response.body().isBlank()) return null;
        return MAPPER.readValue(response.body(), type);
    }

    private <T> T get(String path, TypeReference<T> type) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return MAPPER.readValue(response.body(), type);
    }
}
