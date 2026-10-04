package com.selfanalyst.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.*;
import com.selfanalyst.desktop.controller.DesktopNeo4jController;
import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.ontology.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DesktopNeo4jHttpTest {
    @TempDir Path root;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private String base;

    private HttpRequest request(String method, String path, String body, boolean auth) {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (auth) builder.header("X-SelfAnalyst-Token", "neo4j-test-token");
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private HttpResponse<String> send(String method, String path, String body, boolean auth) throws Exception {
        return client.send(request(method, path, body, auth), HttpResponse.BodyHandlers.ofString());
    }
    private OntologyService ontology() {
        return new OntologyService(new OntologyStore(root.resolve("ontology.db")),
                () -> new Ontology.Snapshot(List.of(), List.of(), Map.of("wiki", "current")));
    }
    private EventServer server(Neo4jSyncService sync) {
        var server = new EventServer(root.resolve("events"), 0, "neo4j-test-token");
        new DesktopNeo4jController(sync).register(server.app()); server.start();
        base = "http://localhost:" + server.port() + "/desktop/ontology/neo4j";
        return server;
    }
    private Neo4jSyncConfig config() {
        return new Neo4jSyncConfig(true, "bolt://127.0.0.1:7687", "neo4j", "neo4j", "SYNTHETIC_PASSWORD", "synthetic-a", 15);
    }
    private String confirm(String fingerprint) throws Exception { return json.writeValueAsString(Map.of("confirmedTarget", fingerprint)); }

    @Test void authLocalStatusConfirmationAndLiveSavedTargetNeverImplicitlyWrite() throws Exception {
        var store = new UserConfigStore(root.resolve("config"));
        var configuration = new ConfigApplicationService(store, ConfigResolver.resolve(store.loadUser(), Map.of()).config(), Map.of());
        AtomicInteger writes = new AtomicInteger();
        try (var ontology = ontology()) {
            var sync = new Neo4jSyncService(ontology, () -> Neo4jConfigResolver.from(configuration.saved().properties()),
                    ignored -> "synthetic-password", (target, password, payload) -> writes.incrementAndGet());
            var server = server(sync);
            try {
                assertEquals(401, send("GET", "/status", null, false).statusCode());
                assertEquals(401, send("POST", "/sync", "{}", false).statusCode());
                var disabled = send("GET", "/status", null, true);
                assertEquals("no-store", disabled.headers().firstValue("Cache-Control").orElseThrow());
                assertEquals("disabled", json.readTree(disabled.body()).path("state").asText());
                assertFalse(disabled.body().contains("synthetic-password"));
                configuration.saveRaw("[neo4j]\nenabled=true\nuri='bolt://127.0.0.1:7687'\nnamespace='synthetic-a'\n");
                var status = json.readTree(send("GET", "/status", null, true).body());
                String fingerprint = status.path("targetFingerprint").asText();
                assertEquals("ready", status.path("state").asText()); assertEquals(0, writes.get());
                for (String body : List.of("{}", "{invalid", "{\"confirmedTarget\":false}", confirm("stale"),
                        "{\"confirmedTarget\":\"valid\",\"uri\":\"bolt://another-target\"}")) {
                    assertEquals(409, send("POST", "/sync", body, true).statusCode());
                }
                assertEquals(0, writes.get());
                configuration.update(Map.of("neo4j.namespace", "synthetic-b"));
                assertEquals(409, send("POST", "/sync", confirm(fingerprint), true).statusCode());
                assertEquals(0, writes.get());
                String current = json.readTree(send("GET", "/status", null, true).body()).path("targetFingerprint").asText();
                var done = send("POST", "/sync", confirm(current), true);
                assertEquals(200, done.statusCode()); assertEquals(1, writes.get());
                assertEquals("success", json.readTree(done.body()).path("state").asText());
                assertTrue(json.readTree(done.body()).path("lastResult").path("success").asBoolean());
                assertFalse(done.body().contains("synthetic-password"));
                configuration.update(Map.of("neo4j.enabled", "false"));
                assertEquals(409, send("POST", "/sync", confirm(current), true).statusCode()); assertEquals(1, writes.get());
            } finally { server.stop(); }
        }
    }

    @Test void repeatedRequestIsConflictAndDriverErrorsStayRedacted() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        var config = config();
        try (var ontology = ontology()) {
            var sync = new Neo4jSyncService(ontology, () -> config, ignored -> "synthetic-password", (target, password, payload) -> {
                writes.incrementAndGet(); entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); } catch (InterruptedException interrupted) { throw new IllegalStateException(); }
                throw new IllegalStateException("synthetic-password private graph content");
            });
            var server = server(sync);
            try {
                String body = confirm(config.fingerprint());
                var pending = client.sendAsync(request("POST", "/sync", body, true), HttpResponse.BodyHandlers.ofString());
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals("running", json.readTree(send("GET", "/status", null, true).body()).path("state").asText());
                var duplicate = send("POST", "/sync", body, true);
                assertEquals(409, duplicate.statusCode()); assertTrue(duplicate.body().contains("neo4j.error.running"));
                release.countDown(); var failed = pending.get(5, TimeUnit.SECONDS);
                assertEquals(503, failed.statusCode()); assertTrue(failed.body().contains("neo4j.error.failed"));
                assertFalse(failed.body().contains("synthetic-password")); assertFalse(failed.body().contains("private graph"));
                assertEquals(1, writes.get());
            } finally { release.countDown(); server.stop(); }
        }
    }
    @Test void timeoutHasSpecificSafeErrorAndRemainsRetryable() throws Exception {
        var config = config();
        try (var ontology = ontology()) {
            var sync = new Neo4jSyncService(ontology, () -> config, ignored -> "synthetic-password",
                    (target, password, payload) -> { throw new IllegalStateException("neo4j.timeout"); });
            var server = server(sync);
            try {
                var failed = send("POST", "/sync", confirm(config.fingerprint()), true);
                assertEquals(503, failed.statusCode());
                assertEquals("neo4j.error.timeout", json.readTree(failed.body()).path("errorCode").asText());
                assertFalse(failed.body().contains("synthetic-password"));
                var status = json.readTree(send("GET", "/status", null, true).body());
                assertEquals("error", status.path("state").asText());
                assertEquals("neo4j.timeout", status.path("lastResult").path("code").asText());
            } finally { server.stop(); }
        }
    }

}
