package com.selfanalyst.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RawEventControllerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void endpointRequiresBoundedParametersAndReturnsFieldsAndCoverage(@TempDir Path dir)
            throws Exception {
        EventServer server = new EventServer(dir, 0, "test-token");
        server.start(0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String origin = "http://127.0.0.1:" + server.port();
            post(client, origin + "/api/0/buckets/bucket",
                    "{\"client\":\"window-watcher\",\"hostname\":\"host\"}");
            post(client, origin + "/api/0/buckets/bucket/heartbeat",
                    "{\"timestamp\":\"2026-09-03T12:00:00Z\",\"duration\":2,"
                            + "\"sourceEventId\":\"stable\",\"data\":{\"app\":\"editor\"}}");

            HttpResponse<String> missing = get(client, origin + "/desktop/raw-events");
            assertEquals(400, missing.statusCode(), missing.body());
            assertEquals(400, get(client, origin + "/desktop/raw-events?bucketId=bucket&start=bad&end=bad")
                    .statusCode());
            assertEquals(400, get(client, origin + query("bucket", "2026-01-01T00:00:00Z",
                    "2026-03-01T00:00:00Z", 10)).statusCode());
            assertEquals(400, get(client, origin + query("bucket", "2026-09-01T00:00:00Z",
                    "2026-09-30T00:00:00Z", 1001)).statusCode());

            HttpResponse<String> ok = get(client, origin + query("bucket",
                    "2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z", 100));
            assertEquals(200, ok.statusCode());
            JsonNode json = MAPPER.readTree(ok.body());
            assertEquals(1, json.path("events").size());
            assertEquals("stable", json.path("events").get(0).path("sourceEventId").asText());
            assertEquals("editor", json.path("events").get(0).path("data").path("app").asText());
            assertFalse(json.path("coverage").path("partitions").isMissingNode());
        } finally {
            server.stop();
        }
    }

    private static String query(String bucket, String start, String end, int limit) {
        return "/desktop/raw-events?bucketId=" + enc(bucket) + "&start=" + enc(start)
                + "&end=" + enc(end) + "&limit=" + limit;
    }
    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("X-SelfAnalyst-Token", "test-token").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private static void post(HttpClient client, String url, String body) throws Exception {
        client.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.discarding());
    }
}
