package com.selfanalyst.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatRawFirstIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void projectionFailureReturns202WithCommittedRawEvent(@TempDir Path dir) throws Exception {
        EventServer server = new EventServer(dir, 0, null,
                event -> { throw new IllegalStateException("injected projection failure"); });
        server.start(0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String bucket = "http://127.0.0.1:" + server.port() + "/api/0/buckets/bucket";
            send(client, bucket, """
                    {"name":"bucket","type":"window","client":"window-watcher","hostname":"host"}
                    """);
            HttpResponse<String> response = send(client, bucket + "/heartbeat", """
                    {"timestamp":"2026-09-03T12:00:00Z","duration":5,
                     "sourceEventId":"stable-1","data":{"app":"editor"}}
                    """);

            assertEquals(202, response.statusCode());
            JsonNode body = MAPPER.readTree(response.body());
            assertEquals("pending", body.path("projectionStatus").asText());
            assertFalse(body.path("rawEventId").asText().isBlank());
            assertEquals(1, server.rawEventStore().count(YearMonth.now(java.time.ZoneOffset.UTC)));
            assertEquals(0, server.eventStore().countByBucket("bucket"));
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<String> send(HttpClient client, String url, String body)
            throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
