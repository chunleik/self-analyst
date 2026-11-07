package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.*;
import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.events.model.Bucket;
import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.store.*;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DesktopPrivacyControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final String BUCKET = "watcher-window_testhost";
    @TempDir Path temp;

    @Test void loadingMergesModesAndCandidatesSortDeduplicateAndExcludeListedApps() throws Exception {
        try (var fixture = new Fixture(temp)) {
            fixture.configuration.update(Map.of("wiki.privacy.excludeApps", "same.exe; model.exe",
                    "events.collection.excludeApps", "SAME.EXE,capture.exe", "wiki.privacy.excludeSites", "Private.test;private.TEST"));
            fixture.event("editor.exe", 15); fixture.event("EDITOR.EXE", 20);
            fixture.event("browser.exe", 25); fixture.event("SAME.exe", 900);
            fixture.event("unknown", 900); fixture.event("  ", 900);
            fixture.events.insertEvent("watcher-window_otherhost", new Event(NOW.minusSeconds(100), 100, Map.of("app", "foreign.exe")));
            fixture.events.insertEvent(BUCKET, new Event(NOW.minus(Duration.ofDays(31)), 30, Map.of("app", "old.exe")));
            var payload = JSON.valueToTree(fixture.controller.payload());
            assertEquals(3, payload.path("apps").size());
            assertEquals("capture", payload.at("/apps/0/mode").asText());
            assertEquals("same.exe", payload.at("/apps/0/app").asText());
            assertEquals(1, payload.path("sites").size());
            assertEquals(2, payload.path("candidates").size());
            assertEquals("editor.exe", payload.at("/candidates/0/app").asText());
            assertEquals(35, payload.at("/candidates/0/seconds").asDouble());
            assertEquals("browser.exe", payload.at("/candidates/1/app").asText());
            assertFalse(payload.has("candidatesError"));
        }
    }

    @Test void candidatesAreLimitedToOneHundredAndClipTheThirtyDayBoundary() throws Exception {
        try (var fixture = new Fixture(temp)) {
            for (int index = 1; index <= 105; index++) fixture.event("app-" + index + ".exe", index);
            fixture.events.insertEvent(BUCKET, new Event(NOW.minus(Duration.ofDays(30)).minusSeconds(20), 21,
                    Map.of("app", "boundary.exe")));
            var candidates = JSON.valueToTree(fixture.controller.payload()).path("candidates");
            assertEquals(100, candidates.size());
            assertEquals("app-105.exe", candidates.get(0).path("app").asText());
            assertEquals("app-6.exe", candidates.get(99).path("app").asText());
        }
    }

    @Test void saveAppliesRuntimeBeforeReturningAndConflictPrefersCapture() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var response = fixture.controller.update(JSON.readTree("""
                    {"apps":[{"app":" player.exe ","mode":"model"},
                      {"app":"PLAYER.EXE","mode":"capture"},{"app":"chat.exe","mode":"model"}],
                     "sites":[" private.test ","PRIVATE.TEST"]}
                    """));
            assertEquals(true, response.get("applied"));
            assertEquals(List.of(), response.get("restartRequired"));
            assertTrue(fixture.runtime.captureExcluded().test("PLAYER.EXE"));
            assertFalse(fixture.runtime.captureExcluded().test("chat.exe"));
            assertTrue(fixture.runtime.modelPolicy().get().excludedApp("chat.exe"));
            assertTrue(fixture.runtime.modelPolicy().get().excludedHost("private.test"));
            assertEquals("player.exe", fixture.configuration.saved().config().collectionExcludeApps());
            assertEquals("chat.exe", fixture.configuration.saved().config().wikiExcludeApps());
            fixture.controller.update(JSON.readTree("{\"apps\":[{\"app\":\"player.exe\",\"mode\":\"model\"}],\"sites\":[]}"));
            assertFalse(fixture.runtime.captureExcluded().test("player.exe"));
            assertTrue(fixture.runtime.modelPolicy().get().excludedApp("player.exe"));
            fixture.controller.update(JSON.readTree("{\"apps\":[],\"sites\":[]}"));
            assertFalse(fixture.runtime.modelPolicy().get().excludedApp("player.exe"));
        }
    }

    @Test void invalidInputsCannotChangeSavedOrRunningRules() throws Exception {
        try (var fixture = new Fixture(temp)) {
            fixture.configuration.update(Map.of("events.collection.excludeApps", "old.exe"));
            String before = fixture.store.readRaw();
            for (String app : List.of("", "  ", "one.exe,two.exe", "one.exe;two.exe")) {
                var request = JSON.valueToTree(Map.of("apps", List.of(Map.of("app", app, "mode", "capture")), "sites", List.of()));
                assertThrows(IllegalArgumentException.class, () -> fixture.controller.update(request));
            }
            for (String request : List.of("{}", "{\"apps\":[],\"sites\":\"bad\"}",
                    "{\"apps\":[{\"app\":\"new.exe\",\"mode\":\"invalid\"}],\"sites\":[]}",
                    "{\"apps\":[],\"sites\":[\"one.test;two.test\"]}")) {
                assertThrows(IllegalArgumentException.class, () -> fixture.controller.update(JSON.readTree(request)));
            }
            assertEquals(before, fixture.store.readRaw());
            assertTrue(fixture.runtime.captureExcluded().test("old.exe"));
        }
    }

    @Test void candidateFailureStillAllowsSavingManualApps() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var controller = new DesktopPrivacyController(fixture.configuration, null, "testhost", Clock.fixed(NOW, ZoneOffset.UTC));
            assertEquals("unavailable", controller.payload().get("candidatesError"));
            assertEquals(List.of(), controller.payload().get("candidates"));
            assertEquals(true, controller.update(JSON.readTree("{\"apps\":[{\"app\":\"manual.exe\",\"mode\":\"capture\"}],\"sites\":[]}")).get("applied"));
            assertTrue(fixture.runtime.captureExcluded().test("manual.exe"));
        }
    }

    @Test void httpGetPutAndInvalidPayloadUseStructuredResponses() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var app = Javalin.create();
            app.get("/desktop/privacy", fixture.controller::getPrivacy);
            app.put("/desktop/privacy", fixture.controller::putPrivacy);
            app.start(0);
            try (var client = HttpClient.newHttpClient()) {
                var uri = URI.create("http://127.0.0.1:" + app.port() + "/desktop/privacy");
                var get = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, get.statusCode());
                assertTrue(JSON.readTree(get.body()).path("apps").isArray());
                var put = client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"apps\":[],\"sites\":[]}")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, put.statusCode());
                assertTrue(JSON.readTree(put.body()).path("applied").asBoolean());
                var bad = client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("not-json")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(400, bad.statusCode());
                assertEquals("invalidPayload", JSON.readTree(bad.body()).path("errorCode").asText());
            } finally { app.stop(); }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final UserConfigStore store;
        final ConfigApplicationService configuration;
        final PrivacyRuntime runtime;
        final Database database;
        final EventStore events;
        final DesktopPrivacyController controller;
        Fixture(Path temp) throws Exception {
            store = new UserConfigStore(temp.resolve("config"));
            Config config = ConfigResolver.resolve(new Properties(), Map.of()).config();
            configuration = new ConfigApplicationService(store, config, Map.of());
            runtime = new PrivacyRuntime(config);
            configuration.attachPrivacy(next -> runtime.apply(next.collectionExcludeApps(), next.wikiExcludeApps(), next.wikiExcludeSites()));
            database = new Database(temp.resolve("events"));
            events = new EventStore(database, PulseTimeConfig.DEFAULT);
            new BucketStore(database).create(Bucket.create(BUCKET, "Synthetic", "currentwindow", "test", "testhost"));
            controller = new DesktopPrivacyController(configuration, events, "testhost", Clock.fixed(NOW, ZoneOffset.UTC));
        }
        void event(String app, double seconds) { events.insertEvent(BUCKET, new Event(NOW.minusSeconds(1000), seconds, Map.of("app", app))); }
        public void close() throws Exception { database.close(); configuration.close(); }
    }
}
