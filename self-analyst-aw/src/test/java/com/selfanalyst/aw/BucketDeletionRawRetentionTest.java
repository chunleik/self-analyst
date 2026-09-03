package com.selfanalyst.aw;

import com.selfanalyst.aw.raw.RawEvent;
import com.selfanalyst.aw.raw.RawEventIdGenerator;
import com.selfanalyst.aw.raw.RawEventQueryService;
import com.selfanalyst.aw.raw.RawEventSource;
import com.selfanalyst.aw.raw.RawEventStore;
import com.selfanalyst.aw.raw.RawIngestKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BucketDeletionRawRetentionTest {

    @Test
    void controllerDeletionRemovesProjectionOnly(@TempDir Path dir) throws Exception {
        Path rawDir = dir.resolve("raw");
        Instant time = Instant.parse("2026-09-03T12:00:00Z");
        RawEvent raw = RawEvent.create(new RawEventIdGenerator(), "stable-source", "bucket",
                RawEventSource.WINDOW, 1, RawIngestKind.HEARTBEAT,
                time, time, 5, Map.of("app", "editor"), null, null);
        Path rawDatabase;
        try (RawEventStore store = new RawEventStore(rawDir)) {
            store.append(raw);
            rawDatabase = store.partitionPath(YearMonth.of(2026, 9));
        }
        String beforeHash = RawEventStore.fileSha256(rawDatabase);

        AwServer server = new AwServer(dir.resolve("aw"), 0, null);
        server.start(0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port() + "/api/0/buckets/bucket";
            assertEquals(200, send(client, base, "POST",
                    "{\"name\":\"bucket\",\"type\":\"window\",\"client\":\"test\",\"hostname\":\"host\"}"));
            assertEquals(200, send(client, base + "/heartbeat", "POST",
                    "{\"timestamp\":\"2026-09-03T12:00:00Z\",\"duration\":5,\"data\":{\"app\":\"editor\"}}"));
            assertEquals(1, server.eventStore().countByBucket("bucket"));

            assertEquals(200, send(client, base, "DELETE", null));
            assertEquals(0, server.eventStore().countByBucket("bucket"));
        } finally {
            server.stop();
        }

        assertEquals(beforeHash, RawEventStore.fileSha256(rawDatabase));
        try (RawEventQueryService queries = new RawEventQueryService(rawDir, 31, 1000)) {
            var page = queries.query("bucket", time.minusSeconds(1), time.plusSeconds(1),
                    100, null);
            assertEquals(1, page.events().size());
            assertEquals(raw.eventId(), page.events().getFirst().eventId());
        }
    }

    private static int send(HttpClient client, String url, String method, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json");
        if ("POST".equals(method)) request.POST(HttpRequest.BodyPublishers.ofString(body));
        else request.DELETE();
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
