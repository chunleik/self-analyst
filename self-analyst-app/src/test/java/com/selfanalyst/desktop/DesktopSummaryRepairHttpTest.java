package com.selfanalyst.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.events.store.Database;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.PulseTimeConfig;
import com.selfanalyst.wiki.*;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP + standard DesktopServer wiring, fixture databases, no model configured. */
class DesktopSummaryRepairHttpTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private String base;

    @Test
    void localOnlyRepairPersistsAcrossRealRoutesAndSessionDeletion() throws Exception {
        Config config = Config.testDefaults(temp);
        try (Database database = new Database(temp.resolve("events"));
             WikiStore wiki = new WikiStore(temp.resolve("wiki.db"))) {
            Instant start = Instant.parse("2026-01-01T10:00:00Z");
            wiki.upsert(new WikiEntry("hour", WikiLevel.HOUR, start, start.plusSeconds(3600), "UTC", WikiStatus.FAILED,
                    null, null, List.of(), new WikiEntry.WikiMetrics(120, 20, 1, List.of(), Map.of()),
                    List.of(), "fake", "v1", 2, null, "WIKI_EVIDENCE_UNSUPPORTED_CLAIM:summary", start, start, null));
            Javalin app = Javalin.create();
            DesktopServer desktop = new DesktopServer(app, config, null,
                    new EventStore(database, PulseTimeConfig.DEFAULT), null, null, null,
                    true, null, null, null, null, new UserConfigStore(temp.resolve("config")), wiki);
            try {
                desktop.start(); app.start(0); base = "http://127.0.0.1:" + app.port();
                JsonNode session = request("POST", "/desktop/chat/sessions", Map.of("source", "summary_repair"), 201);
                String sessionId = session.path("id").asText();
                JsonNode opened = request("POST", "/desktop/summary-repairs", Map.of("sessionId", sessionId, "targetId", "hour", "expectedTargetRevision", 0), 200);
                assertEquals("HOUR", opened.path("target").path("level").asText());
                assertEquals(start.toString(), opened.path("target").path("periodStart").asText());
                assertFalse(opened.path("failureTextAvailable").asBoolean());
                String repairId = opened.path("draft").path("id").asText();
                var task = Map.of("title", "部署服务", "summary", "已经完成测试服务部署", "claimType", "user_confirmed", "confidence", "medium", "evidenceFactIds", List.of());
                var candidate = Map.of("summary", "已经完成测试服务部署", "primaryTask", "部署服务", "taskSegments", List.of(task));
                JsonNode edited = request("PUT", "/desktop/summary-repairs/" + repairId + "/draft", Map.of("sessionId", sessionId, "draftRevision", 0, "candidate", candidate), 200);
                long revision = edited.path("draft").path("draftRevision").asLong();
                JsonNode confirmed = request("POST", "/desktop/summary-repairs/" + repairId + "/confirm", Map.of("sessionId", sessionId, "draftRevision", revision, "taskIndexes", List.of(0)), 200);
                revision = confirmed.path("draft").path("draftRevision").asLong();
                JsonNode valid = request("POST", "/desktop/summary-repairs/" + repairId + "/validate", Map.of("sessionId", sessionId, "draftRevision", revision), 200);
                assertEquals("valid", valid.path("draft").path("state").asText(), valid.toString());
                revision = valid.path("draft").path("draftRevision").asLong();
                JsonNode saved = request("POST", "/desktop/summary-repairs/" + repairId + "/commit", Map.of("sessionId", sessionId, "draftRevision", revision, "idempotencyKey", "http-save-1"), 200);
                assertEquals("committed", saved.path("draft").path("state").asText());
                assertEquals(120, saved.path("currentEntry").path("metrics").path("activeSeconds").asLong());
                JsonNode recovered = request("GET", "/desktop/chat/sessions/" + sessionId, null, 200);
                assertEquals("summary_repair", recovered.path("contextSnapshot").path("type").asText());
                assertEquals("committed", recovered.path("repair").path("draft").path("state").asText());
                request("DELETE", "/desktop/chat/sessions/" + sessionId, null, 200);
                assertEquals("user_confirmed", wiki.versionedEntry("hour").entry().taskSegments().getFirst().claimType());
                request("GET", "/desktop/summary-repairs/" + repairId + "?sessionId=" + sessionId, null, 404);
            } finally { app.stop(); desktop.shutdown(); }
        }
    }

    private JsonNode request(String method, String path, Object body, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), path + " " + response.body());
        return json.readTree(response.body());
    }
}
