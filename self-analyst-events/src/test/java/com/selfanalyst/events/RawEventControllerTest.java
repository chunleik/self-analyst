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
    void retiredEndpointNeverReturnsRawData(@TempDir Path dir)
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

            for (String query : new String[]{"", "?bucketId=bucket&start=bad&end=bad",
                    "?bucketId=bucket&start=2026-09-01T00:00:00Z&end=2026-09-30T00:00:00Z"}) {
                var response = get(client, origin + "/desktop/raw-events" + query);
                assertEquals(410, response.statusCode());
                assertEquals("RAW_STORAGE_RETIRED", MAPPER.readTree(response.body()).path("error").asText());
                assertFalse(MAPPER.readTree(response.body()).has("events"));
            }
            var rebuild = client.send(HttpRequest.newBuilder(URI.create(origin + "/desktop/raw-rebuild"))
                    .header("X-SelfAnalyst-Token", "test-token").POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(410, rebuild.statusCode());
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
