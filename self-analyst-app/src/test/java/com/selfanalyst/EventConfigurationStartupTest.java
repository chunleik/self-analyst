package com.selfanalyst;

import com.selfanalyst.config.Config;
import com.selfanalyst.events.projection.EventProjector;
import com.selfanalyst.events.raw.*;
import com.selfanalyst.events.store.Database;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.PulseTimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.ServerSocket;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EventConfigurationStartupTest {

    @Test
    void allScopeFailureStopsApplicationBeforePublishingPort(@TempDir Path dir) throws Exception {
        Path rawDir = dir.resolve("raw");
        Path older;
        try (var raw = new RawEventStore(rawDir)) {
            for (String month : List.of("2026-08", "2026-09")) {
                Instant at = Instant.parse(month + "-03T12:00:00Z");
                raw.append(RawEvent.create(new RawEventIdGenerator(), month, "rename-test",
                        RawEventSource.WINDOW, 1, RawIngestKind.HEARTBEAT, at, at, 5,
                        Map.of("app", "synthetic-editor"), null, null));
            }
            older = raw.partitionPath(YearMonth.of(2026, 8));
        }
        Path manifest = RawEventStore.manifestPath(older);
        Files.writeString(manifest, "{}");
        String hash = RawEventStore.fileSha256(older);
        var builder = backend(dir);
        builder.environment().put("EVENTS_RAW_INTEGRITY_STARTUP_SCOPE", "all");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            String log = Files.readString(dir.resolve("process.log"));
            assertTrue(log.contains("2026-08"), log);
            assertFalse(Files.exists(dir.resolve("port.txt")));
            assertFalse(Files.exists(dir.resolve("events")));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
        assertEquals("{}", Files.readString(manifest));
        assertEquals(hash, RawEventStore.fileSha256(older));
        try (var catalog = new RawPartitionCatalog(rawDir)) {
            assertEquals(RawPartitionStatus.QUARANTINED, catalog.find("2026-08").orElseThrow().status());
        }
    }
    private static final List<String> OLD_ENVIRONMENT = List.of(
            "AW_MODE",
            "AW_BASE_URL",
            "AW_TIMEOUT",
            "AW_DATA_DIR",
            "AW_RAW_DIR",
            "AW_RAW_QUERY_MAX_RANGE_DAYS",
            "AW_RAW_QUERY_MAX_PAGE_SIZE",
            "AW_RAW_LOW_DISK_WARN_BYTES",
            "AW_RAW_LOW_DISK_BLOCK_BYTES",
            "AW_RAW_INTEGRITY_VERIFY_ON_STARTUP",
            "AW_RAW_PROJECTOR_BATCH_SIZE",
            "AW_COLLECTION_WINDOW",
            "AW_COLLECTION_AFK",
            "AW_COLLECTION_CONTENT",
            "AW_CONTENT_POLL_MS");

    private static ProcessBuilder backend(Path dir) {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var builder = new ProcessBuilder(java, "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), App.class.getName());
        builder.directory(dir.toFile()).redirectErrorStream(true).redirectOutput(dir.resolve("process.log").toFile());
        var env = builder.environment();
        OLD_ENVIRONMENT.forEach(env::remove);
        env.put("MEMORY_DIR", dir.resolve("memory").toString());
        env.put("EVENTS_DATA_DIR", dir.resolve("events").toString());
        env.put("EVENTS_RAW_DIR", dir.resolve("raw").toString());
        env.put("EVENTS_MODE", "embedded");
        env.put("OPENAI_API_KEY", "");
        for (String key : List.of("EVENTS_COLLECTION_WINDOW", "EVENTS_COLLECTION_AFK",
                "EVENTS_COLLECTION_TITLE_ENABLED", "FILE_WATCH_ENABLED", "WIKI_ENABLED",
                "EMBEDDING_ENABLED", "WEBSEARCH_ENABLED")) env.put(key, "false");
        env.put("SELF_ANALYST_DESKTOP_TOKEN", "rename-config-test");
        env.put("SELF_ANALYST_DESKTOP_PORT_FILE", dir.resolve("port.txt").toString());
        return builder;
    }

    @Test
    void removedInputsExitBeforeInitializingDataOrListening(@TempDir Path root) throws Exception {
        for (boolean useEnvironment : List.of(false, true)) {
            Path dir = Files.createDirectory(root.resolve(useEnvironment ? "environment" : "toml"));
            Path config = Files.createDirectories(dir.resolve("data/config")).resolve("config.toml");
            String original = useEnvironment ? "events.mode='embedded'\n" : "aw.mode='private-old-value'\n";
            Files.writeString(config, original);
            var builder = backend(dir);
            if (useEnvironment) builder.environment().put("AW_MODE", "private-old-value");
            Process process = builder.start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "旧名称启动没有及时终止");
                assertNotEquals(0, process.exitValue());
                String log = Files.readString(dir.resolve("process.log"));
                assertTrue(log.contains(useEnvironment ? "EVENTS_MODE" : "events.mode"), log);
                assertFalse(log.contains("private-old-value"), log);
                assertEquals(original, Files.readString(config));
                for (String path : List.of("events", "raw", "memory", "data/events", "port.txt")) {
                    assertFalse(Files.exists(dir.resolve(path)), path);
                }
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void renamedConfigurationReopensExistingRawAndProjection(@TempDir Path dir) throws Exception {
        Path eventsDir = dir.resolve("events");
        Path rawDir = dir.resolve("raw");
        Instant at = Instant.parse("2026-09-03T12:00:00Z");
        RawEvent event = RawEvent.create(new RawEventIdGenerator(), "rename-one", "rename-test",
                RawEventSource.WINDOW, 1, RawIngestKind.HEARTBEAT, at, at, 5,
                Map.of("app", "test-editor", "title", "synthetic-title"), null, null);
        Path partition;
        try (var raw = new RawEventStore(rawDir)) {
            raw.append(event);
            partition = raw.partitionPath(YearMonth.of(2026, 9));
        }
        try (var database = new Database(eventsDir)) {
            new EventProjector(database, 30, 1000, "v1").projectBatch(List.of(event));
        }
        String hash = RawEventStore.fileSha256(partition);
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path configDir = Files.createDirectories(dir.resolve("data/config"));
        Files.writeString(configDir.resolve("config.toml"), "[events]\nport=" + port
                + "\ndata-dir='" + eventsDir + "'\n[events.raw]\ndir='" + rawDir + "'\n");
        Process process = backend(dir).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(dir.resolve("port.txt")) && process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(dir.resolve("port.txt")), Files.readString(dir.resolve("process.log")));
            assertEquals(Integer.toString(port), Files.readString(dir.resolve("port.txt")));
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/0/buckets/rename-test/events?limit=5"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("synthetic-title"), response.body());
            var shutdown = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/desktop/lifecycle/shutdown"))
                    .timeout(Duration.ofSeconds(5)).header("X-SelfAnalyst-Token", "rename-config-test")
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(202, client.send(shutdown, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(dir.resolve("process.log")));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
        assertEquals(hash, RawEventStore.fileSha256(partition));
        try (var raw = new RawEventStore(rawDir); var database = new Database(eventsDir)) {
            assertEquals(1, raw.count(YearMonth.of(2026, 9)));
            assertEquals(1, new EventStore(database, PulseTimeConfig.DEFAULT).countByBucket("rename-test"));
        }
        try (var entries = Files.list(eventsDir)) {
            assertFalse(entries.anyMatch(p -> p.getFileName().toString().startsWith("events.db.rebuilding-")
                    || p.getFileName().toString().startsWith("events.db.backup-")));
        }
        assertFalse(Files.exists(eventsDir.resolve("aw.db")));
    }
}
