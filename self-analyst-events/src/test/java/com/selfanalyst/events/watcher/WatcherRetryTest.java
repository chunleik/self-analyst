package com.selfanalyst.events.watcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.events.model.Event;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WatcherRetryTest {

    @Test
    void networkRetriesReuseSourceIdAndDoNotCollectANewLogicalHeartbeat() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/0/buckets/bucket/heartbeat", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = requests.incrementAndGet() < 3 ? 500 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            TestWatcher watcher = new TestWatcher(
                    "http://127.0.0.1:" + server.getAddress().getPort());
            watcher.processOnce();
            watcher.processOnce();
            watcher.processOnce();

            assertEquals(1, watcher.collections.get());
            assertEquals(3, bodies.size());
            ObjectMapper mapper = new ObjectMapper();
            String sourceId = mapper.readTree(bodies.getFirst()).path("sourceEventId").asText();
            assertFalse(sourceId.isBlank());
            for (String body : bodies) {
                assertEquals(sourceId,
                        mapper.readTree(body).path("sourceEventId").asText());
            }
        } finally {
            server.stop(0);
        }
    }

    private static final class TestWatcher extends Watcher {
        private final AtomicInteger collections = new AtomicInteger();

        private TestWatcher(String serverUrl) {
            super("test", "bucket", 1000, serverUrl);
        }

        @Override
        protected Event collect() {
            collections.incrementAndGet();
            return new Event(Instant.EPOCH, 1, Map.of("app", "editor"));
        }
    }
}
