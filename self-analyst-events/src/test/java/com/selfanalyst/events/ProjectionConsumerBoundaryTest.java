package com.selfanalyst.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.events.query.AqlContext;
import com.selfanalyst.events.query.AqlInterpreter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProjectionConsumerBoundaryTest {

    @Test
    void eventsApiReturnsMergedProjectionAndAqlRegistersNoRawFunction(@TempDir Path dir)
            throws Exception {
        EventServer server = new EventServer(dir, 0, null);
        server.start(0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String bucket = "http://127.0.0.1:" + server.port() + "/api/0/buckets/bucket";
            post(client, bucket, "{\"client\":\"window-watcher\",\"hostname\":\"host\"}");
            post(client, bucket + "/heartbeat", event("stable-1", "2026-09-03T12:00:00Z"));
            post(client, bucket + "/heartbeat", event("stable-2", "2026-09-03T12:00:10Z"));

            String events = client.send(HttpRequest.newBuilder(URI.create(bucket + "/events?limit=100"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            assertEquals(1, new ObjectMapper().readTree(events).size());
            assertEquals(2, server.rawEventStore().count(YearMonth.now(ZoneOffset.UTC)));

            AqlInterpreter interpreter = new AqlInterpreter(new AqlContext(
                    server.eventStore(), server.bucketStore(), null, null));
            Field field = AqlInterpreter.class.getDeclaredField("functions");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> functions = (Map<String, ?>) field.get(interpreter);
            assertFalse(functions.keySet().stream().anyMatch(name -> name.contains("raw")));
        } finally {
            server.stop();
        }
    }

    private static String event(String sourceId, String timestamp) {
        return "{\"timestamp\":\"" + timestamp + "\",\"duration\":5,"
                + "\"sourceEventId\":\"" + sourceId + "\",\"data\":{\"app\":\"editor\"}}";
    }

    private static void post(HttpClient client, String url, String body) throws Exception {
        client.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.discarding());
    }
}
