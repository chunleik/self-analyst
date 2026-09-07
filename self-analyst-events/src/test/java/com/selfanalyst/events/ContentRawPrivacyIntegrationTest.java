package com.selfanalyst.events;

import com.selfanalyst.events.raw.RawEvent;
import com.selfanalyst.events.raw.RawEventIdGenerator;
import com.selfanalyst.events.raw.RawEventSource;
import com.selfanalyst.events.raw.RawEventStore;
import com.selfanalyst.events.raw.RawIngestKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ContentRawPrivacyIntegrationTest {
    private static final String SECRET = "SELF_ANALYST_CONTENT_SECRET_91D4";

    @Test
    void forbiddenContentNeverReachesAnyPersistentOrDiagnosticSurface(@TempDir Path dir)
            throws Exception {
        seedSafeSealedManifest(dir.resolve("raw"));
        EventServer server = new EventServer(dir, dir.resolve("raw"), 0,
                31, 1000, 1, 1, 1000);
        server.start(0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String origin = "http://127.0.0.1:" + server.port();
            post(client, origin + "/api/0/buckets/watcher-content_test",
                    "{\"client\":\"watcher-content\",\"hostname\":\"host\"}");
            post(client, origin + "/api/0/buckets/aw-watcher-content_imported",
                    "{\"client\":\"aw-watcher-content\",\"hostname\":\"host\"}");
            String forbidden = "{\"timestamp\":\"2026-09-03T12:00:00Z\",\"duration\":1,"
                    + "\"data\":{\"app\":\"editor\",\"title\":\"safe\","
                    + "\"text_content\":\"" + SECRET + "\"}}";
            List<HttpResponse<String>> responses = List.of(
                    post(client, origin + "/api/0/buckets/watcher-content_test/heartbeat",
                            forbidden),
                    post(client, origin + "/api/0/buckets/watcher-content_test/events",
                            "[" + forbidden + "]"),
                    post(client, origin + "/api/0/import", "{\"buckets\":[{\"id\":"
                            + "\"aw-watcher-content_imported\",\"client\":\"aw-watcher-content\"}],"
                            + "\"events\":{\"aw-watcher-content_imported\":[" + forbidden + "]}}"));
            responses.forEach(response -> {
                assertEquals(422, response.statusCode());
                assertFalse(response.body().contains(SECRET));
            });
            assertFalse(server.serverLog().getEntries().toString().contains(SECRET));
        } finally {
            server.stop();
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertFalse(bytes.contains(SECRET), file.toString());
            }
        }
    }

    private static void seedSafeSealedManifest(Path rawDir) throws Exception {
        RawEventIdGenerator ids = new RawEventIdGenerator();
        try (RawEventStore store = new RawEventStore(rawDir)) {
            for (String time : List.of("2026-07-31T23:59:59Z", "2026-08-01T00:00:00Z")) {
                Instant instant = Instant.parse(time);
                store.append(RawEvent.create(ids, time, "safe", RawEventSource.CONTENT, 2,
                        RawIngestKind.HEARTBEAT, instant, instant, 1,
                        Map.of("title", "safe"), null, null));
            }
        }
    }

    private static HttpResponse<String> post(HttpClient client, String url, String body)
            throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
