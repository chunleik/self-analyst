package com.selfanalyst.events;

import com.fasterxml.jackson.databind.*;
import com.selfanalyst.desktop.controller.DesktopOntologyController;
import com.selfanalyst.ontology.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static com.selfanalyst.ontology.Ontology.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopOntologyHttpTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private String base;
    private HttpResponse<String> request(String method, String path, String body, boolean auth) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (auth) builder.header("X-SelfAnalyst-Token", "ontology-test-token");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void productionDesktopSupportsOntologyWithoutModelAndIsolatesCorruptStore() throws Exception {
        for (boolean corrupt : new boolean[]{false, true}) {
            Path dir = root.resolve(corrupt ? "broken" : "healthy");
            var config = com.selfanalyst.config.Config.testDefaults(dir);
            if (corrupt) {
                java.nio.file.Files.createDirectories(config.memoryDir());
                java.nio.file.Files.writeString(config.memoryDir().resolve("ontology.db"), "broken synthetic database");
            }
            EventServer server = new EventServer(dir.resolve("events"), 0, "ontology-test-token");
            var userConfig = new com.selfanalyst.desktop.store.UserConfigStore(dir.resolve("config"));
            // Optional malformed Neo4j settings must not block normal desktop/ontology startup.
            if (!corrupt) userConfig.saveRaw("[neo4j]\nenabled=true\nuri='invalid'\ntimeout-seconds='broken'\n");
            var desktop = new com.selfanalyst.desktop.DesktopServer(server.app(), config, null, server.eventStore(),
                    null, null, null, true, null, null, null, null,
                    userConfig, null);
            try {
                desktop.start(); server.start(); base = "http://localhost:" + server.port() + "/desktop/ontology";
                assertEquals(corrupt ? 503 : 200, request("GET", "/status", null, true).statusCode());
                var neo4j = request("GET", "/neo4j/status", null, true);
                assertEquals(200, neo4j.statusCode());
                assertEquals(corrupt ? "disabled" : "invalidConfig", json.readTree(neo4j.body()).path("state").asText());
                var status = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/desktop/status"))
                        .header("X-SelfAnalyst-Token", "ontology-test-token").GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, status.statusCode());
                if (!corrupt) assertEquals(200, request("POST", "/entities", "{\"type\":\"project\",\"name\":\"Offline project\"}", true).statusCode());
                else assertEquals("broken synthetic database", java.nio.file.Files.readString(config.memoryDir().resolve("ontology.db")));
            } finally { desktop.shutdown(); server.stop(); }
        }
    }
    @Test void realAuthenticationCrudCorrectionsPaginationAndErrors() throws Exception {
        EventServer server = new EventServer(root.resolve("events"), 0, "ontology-test-token");
        Entity a = new Entity("a", "activity", "Project A", "", List.of(), "wiki", "entry", Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), List.of(), Map.of());
        try (var ontology = new OntologyService(new OntologyStore(root.resolve("ontology.db")), () -> new Snapshot(List.of(a), List.of(), Map.of()))) {
            new DesktopOntologyController(ontology).register(server.app()); server.start(); base = "http://localhost:" + server.port() + "/desktop/ontology";
            assertEquals(401, request("GET", "/entities", null, false).statusCode());
            assertEquals(401, request("POST", "/entities", "{}", false).statusCode());
            var created = request("POST", "/entities", "{\"type\":\"project\",\"name\":\"Project A\",\"aliases\":[]}", true);
            assertEquals(200, created.statusCode()); String id = json.readTree(created.body()).path("id").asText();
            var detail = request("GET", "/entities/" + id, null, true); assertEquals(200, detail.statusCode());
            assertEquals(1, json.readTree(detail.body()).path("relations").path("total").asInt());
            assertEquals(200, request("POST", "/relations", json.writeValueAsString(Map.of("subject", "a", "predicate", "relatedTo", "object", id, "action", "reject")), true).statusCode());
            assertEquals(200, request("POST", "/rebuild", "{}", true).statusCode());
            assertEquals(0, json.readTree(request("GET", "/entities/" + id, null, true).body()).path("relations").path("total").asInt());
            assertEquals(400, request("POST", "/entities", "{invalid", true).statusCode());
            assertEquals(400, request("GET", "/entities?start=bad", null, true).statusCode());
            assertEquals(400, request("GET", "/entities?limit=101", null, true).statusCode());
            assertEquals(400, request("POST", "/merge", json.writeValueAsString(Map.of("from", id, "to", id)), true).statusCode());
            String id2 = json.readTree(request("POST", "/entities", "{\"type\":\"project\",\"name\":\"Target\"}", true).body()).path("id").asText();
            assertEquals(200, request("POST", "/merge", json.writeValueAsString(Map.of("from", id, "to", id2)), true).statusCode());
            assertEquals(id2, json.readTree(request("GET", "/entities/" + id, null, true).body()).path("entity").path("id").asText());
            assertEquals(200, request("DELETE", "/entities/" + id2, null, true).statusCode());
            ontology.close();
            var unavailable = request("GET", "/entities", null, true);
            assertEquals(503, unavailable.statusCode()); assertFalse(unavailable.body().contains(root.toString()));
        } finally { server.stop(); }
    }
}
