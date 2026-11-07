package com.selfanalyst.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.PrivacyRuntime;
import com.selfanalyst.events.EventServer;
import com.selfanalyst.events.model.Bucket;
import com.selfanalyst.events.model.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real HTTP QueryController, interpreter, serialization, and model boundary. */
class EventQueryPrivacyIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BUCKET = "watcher-window_privacy-test";
    private static final String PERIOD = "2026-11-01T00:00:00Z/2026-11-02T00:00:00Z";
    private static final String SOURCE = "query_bucket(\"" + BUCKET + "\")";
    @TempDir Path root;
    private EventServer server;
    private EventQueryTools tools;
    private PrivacyRuntime privacy;

    @BeforeEach void start() {
        server = new EventServer(root.resolve("events"), 0);
        server.bucketStore().create(Bucket.create(BUCKET, "Privacy test", "watcher-window", "watcher-window", "privacy-test"));
        List<Map<String, Object>> events = List.of(
                Map.of("app", "PRIVATE.EXE", "title", "APP_SECRET".repeat(10_000)),
                Map.of("app", "browser.exe", "title", "TITLE_SECRET private.test"),
                Map.of("app", "browser.exe", "context_title", "CONTEXT_SECRET private.test"),
                Map.of("app", "browser.exe", "url_host", "sub.private.test", "title", "HOST_SECRET"),
                Map.of("app", "browser.exe", "private_browsing", true, "title", "FLAG_SECRET"),
                Map.of("app", "browser.exe", "title", "INCOGNITO_SECRET Incognito"),
                Map.of("app", "visible.exe", "title", "retained"));
        for (int index = 0; index < events.size(); index++) {
            server.eventStore().insertEvent(BUCKET,
                    new Event(Instant.parse("2026-11-01T00:00:00Z").plusSeconds(index * 10L), 10, events.get(index)));
        }
        server.start(0);
        privacy = new PrivacyRuntime("private.exe", "", "private.test");
        tools = new EventQueryTools("http://127.0.0.1:" + server.port() + "/api/0", 5000, privacy.modelPolicy());
    }

    @AfterEach void stop() {
        if (server != null) server.stop();
    }

    @Test void directNestedVariableAndObjectReturnsFilterRealFlattenedRows() throws Exception {
        for (String query : List.of(
                "RETURN = " + SOURCE + ";",
                "RETURN = sort_by_duration(" + SOURCE + ");",
                "events = " + SOURCE + "; sorted = sort_by_timestamp(events); RETURN = sorted;")) {
            JsonNode response = query(query);
            assertOnlyVisible(response.path("rows"));
            assertEquals(70, response.path("total_duration").asDouble());
        }
        JsonNode named = query("events = " + SOURCE + "; RETURN = {\"events\": events, \"again\": events};");
        assertOnlyVisible(named.path("events"));
        assertOnlyVisible(named.path("again"));
    }

    @Test void aggregateAndHotUpdatesKeepTheirExistingContracts() throws Exception {
        JsonNode aggregate = query("RETURN = sum_durations(" + SOURCE + ");");
        assertEquals(1, aggregate.path("rows").size());
        assertEquals(70, aggregate.at("/rows/0/duration").asDouble());
        assertFalse(aggregate.at("/rows/0").has("app"));

        assertOnlyVisible(query("RETURN = " + SOURCE + ";").path("rows"));
        privacy.apply("private.exe", "visible.exe", "");
        JsonNode next = query("RETURN = " + SOURCE + ";");
        // The model-app and website rules changed; capture exclusion and private browsing remain excluded.
        assertEquals(3, next.path("rows").size());
        assertFalse(next.toString().contains("retained"));
        assertFalse(next.toString().contains("FLAG_SECRET"));
        assertFalse(next.toString().contains("INCOGNITO_SECRET"));
        assertTrue(next.toString().contains("TITLE_SECRET"));
    }

    @Test void changedTimestampAndDurationTypesDoNotDisableFlatRowFiltering() throws Exception {
        JsonNode response = query("RETURN = tag(" + SOURCE + ", \"timestamp:changed,duration:changed\");");
        assertOnlyVisible(response.path("rows"));
        assertEquals("changed", response.at("/rows/0/timestamp").asText());
        assertEquals("changed", response.at("/rows/0/duration").asText());
    }


    @Test void protectedTagsAreBlockedButDirectLocalAqlRemainsUnchanged() throws Exception {
        for (String key : List.of("app", "title", "context_title", "url_host", "private_browsing")) {
            String command = "RETURN = tag(" + SOURCE + ", '" + key + ":changed');";
            JsonNode rejected = JSON.readTree(tools.executeAQL(command, PERIOD));
            assertEquals("EVENT_PRIVACY_UNSAFE_AQL", rejected.path("error").asText());
        }
        String command = "RETURN = tag(" + SOURCE + ", 'app:changed');";
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                "http://127.0.0.1:" + server.port() + "/api/0/query/"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("query", List.of(command), "timeperiods", List.of(PERIOD)))))
                .build();
        var local = java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, local.statusCode());
        JsonNode rows = JSON.readTree(local.body()).path("rows");
        assertEquals(7, rows.size());
        for (JsonNode row : rows) assertEquals("changed", row.path("app").asText());
    }

    @Test void safeTagsAndLiteralMentionsKeepWorkingOnRealInterpreter() throws Exception {
        assertOnlyVisible(query("RETURN = tag(" + SOURCE + ", 'app:changed'); RETURN = " + SOURCE + ";").path("rows"));
        for (String tags : List.of("'project:work'", "project:work", "'note:tag(events app:changed)'")) {
            assertOnlyVisible(query("RETURN = tag(" + SOURCE + ", " + tags + ");").path("rows"));
        }
        JsonNode matches = query("RETURN = filter_keyvals(" + SOURCE + ", 'title', ['tag(events, app:changed)']);");
        assertTrue(matches.path("rows").isEmpty());
        String escapedKey = "\\" + "u0061pp";
        JsonNode result = query("RETURN = tag(" + SOURCE + ", '" + escapedKey + ":changed');");
        assertOnlyVisible(result.path("rows"));
        assertEquals("changed", result.at("/rows/0").path(escapedKey).asText());
    }

    private JsonNode query(String query) throws Exception {
        String result = tools.executeAQL(query, PERIOD);
        assertFalse(result.contains("事件查询工具结果已截断"), "Privacy filtering must happen before budgeting");
        JsonNode response = JSON.readTree(result);
        assertFalse(response.has("error"), result);
        return response;
    }

    private static void assertOnlyVisible(JsonNode rows) {
        assertTrue(rows.isArray(), rows.toString());
        assertEquals(1, rows.size(), rows.toString());
        assertEquals("visible.exe", rows.get(0).path("app").asText());
        assertEquals("retained", rows.get(0).path("title").asText());
        assertFalse(rows.toString().contains("SECRET"));
    }
}
