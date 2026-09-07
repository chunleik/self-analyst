package com.selfanalyst.aw;

import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.model.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AwServerContentPolicyTest {

    private static final String FORBIDDEN = "SELF_ANALYST_FORBIDDEN_BODY_7F3A";

    @Test
    void rejectsForbiddenHeartbeatAndBatchWithoutPartialWrites(@TempDir Path dataDir)
            throws Exception {
        int port = freePort();
        AwServer server = new AwServer(dataDir, port, null);
        String bucketId = "watcher-content_test";
        server.bucketStore().create(Bucket.create(
                bucketId, "Content", "listening", "watcher-content", "test"));
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String legalData = "{\"schema_version\":2,\"app\":\"Weixin.exe\","
                    + "\"title\":\"微信\",\"title_source\":\"window\"}";
            HttpResponse<String> legal = post(client, port,
                    "/api/0/buckets/" + bucketId + "/heartbeat",
                    event(legalData));
            assertEquals(200, legal.statusCode());
            assertEquals(1, server.eventStore().countByBucket(bucketId));
            assertEquals(1, server.rawEventStore().count(YearMonth.now(ZoneOffset.UTC)));

            String forbiddenData = "{\"app\":\"Weixin.exe\",\"title\":\"微信\","
                    + "\"text_content\":\"" + FORBIDDEN + "\"}";
            HttpResponse<String> heartbeat = post(client, port,
                    "/api/0/buckets/" + bucketId + "/heartbeat",
                    event(forbiddenData));
            assertEquals(422, heartbeat.statusCode());
            assertFalse(heartbeat.body().contains(FORBIDDEN));

            HttpResponse<String> batch = post(client, port,
                    "/api/0/buckets/" + bucketId + "/events",
                    "[" + event(legalData) + "," + event(forbiddenData) + "]");
            assertEquals(422, batch.statusCode());
            assertFalse(batch.body().contains(FORBIDDEN));
            assertEquals(1, server.eventStore().countByBucket(bucketId));
            assertEquals(1, server.rawEventStore().count(YearMonth.now(ZoneOffset.UTC)),
                    "禁止字段不得进入原始分区");
        } finally {
            server.stop();
        }
    }

    @Test
    void rejectsUnknownBucketWritesAndLegacyOrphanAdoption(@TempDir Path dataDir)
            throws Exception {
        int port = freePort();
        AwServer server = new AwServer(dataDir, port, null);
        String orphanId = "custom-content";
        server.eventStore().insertEvent(orphanId, new Event(
                Instant.parse("2026-08-30T01:00:00Z"), 2,
                Map.of("text_content", FORBIDDEN)));
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> unknownWrite = post(client, port,
                    "/api/0/buckets/unknown/events",
                    event("{\"anything\":\"value\"}"));
            assertEquals(404, unknownWrite.statusCode());

            String bucketBody = "{\"name\":\"Content\",\"type\":\"listening\","
                    + "\"client\":\"aw-watcher-content\",\"hostname\":\"test\"}";
            HttpResponse<String> adoption = post(client, port,
                    "/api/0/buckets/" + orphanId, bucketBody);
            assertEquals(422, adoption.statusCode());
            assertFalse(adoption.body().contains(FORBIDDEN));
            assertFalse(server.bucketStore().get(orphanId).isPresent());
        } finally {
            server.stop();
        }
    }

    private static String event(String data) {
        return "{\"timestamp\":\"2026-08-30T01:00:00Z\",\"duration\":2,\"data\":"
                + data + "}";
    }

    private static HttpResponse<String> post(
            HttpClient client, int port, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
