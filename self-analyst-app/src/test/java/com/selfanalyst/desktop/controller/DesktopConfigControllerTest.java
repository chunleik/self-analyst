package com.selfanalyst.desktop.controller;

import com.selfanalyst.events.raw.RawPartitionCatalog;
import com.selfanalyst.events.raw.RawPartitionMetadata;
import com.selfanalyst.events.raw.RawPartitionStatus;
import com.selfanalyst.config.Config;
import com.selfanalyst.config.DeprecatedKeys;
import com.selfanalyst.config.SupportedKeys;
import com.selfanalyst.config.TomlSupport;
import com.selfanalyst.config.TomlValidationException;
import com.selfanalyst.desktop.store.UserConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure/endpoint-level helper tests for the raw config path. No Javalin Context
 * is mocked — helpers are exercised directly (SPEC-CFGUI-TST-001..006).
 */
class DesktopConfigControllerTest {

    @Test
    void renamedAndRemovedConfigurationEntrypointsAreConsistent(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        var ctrl = controller(dir, store);
        Map<String, Object> payload = ctrl.configPayload();
        assertTrue(payload.containsKey("events"));
        assertFalse(payload.containsKey("aw"));
        Map<?, ?> collection = (Map<?, ?>) payload.get("collection");
        assertFalse(collection.containsKey("content"));
        Map<?, ?> title = (Map<?, ?>) collection.get("title");
        assertTrue(title.containsKey("enabled"));
        assertTrue(title.containsKey("pollMs"));

        var saved = ctrl.applyStructuredSave(Map.of("collection",
                Map.of("title", Map.of("enabled", false, "pollMs", 800))));
        assertEquals("false", store.loadUser().getProperty("events.collection.title.enabled"));
        assertEquals("800", store.loadUser().getProperty("events.collection.title.pollMs"));
        assertTrue(saved.restartRequired().contains("events.collection.title.pollMs"));
        String before = store.readRaw();
        for (Map<String, Object> legacy : List.<Map<String, Object>>of(
                Map.of("aw", Map.of("mode", "embedded")),
                Map.of("aw", Map.of("dataDir", "must-not-leak")),
                Map.of("collection", Map.of("content", false)),
                Map.of("collection", Map.of("content", Map.of("pollMs", 900))),
                Map.of("events", Map.of("raw", Map.of("enabled", false))))) {
            var error = assertThrows(TomlValidationException.class, () -> ctrl.applyStructuredSave(legacy));
            assertFalse(error.getMessage().contains("must-not-leak"));
            assertEquals(before, store.readRaw());
        }
        var invalid = assertThrows(TomlValidationException.class,
                () -> ctrl.applyRawSave("aw.mode='must-not-leak'\nevents.mode='embedded'\n"));
        assertTrue(invalid.getMessage().contains("events.mode"));
        assertFalse(invalid.getMessage().contains("must-not-leak"));
        assertEquals(before, store.readRaw());
        var comment = ctrl.applyRawSave("# aw.mode is now events.mode\n[events.collection.title]\nenabled=false\n");
        assertTrue(comment.unknownKeys().isEmpty());
    }

    @Test
    void removedConfigurationReturns400BeforeConnectionOrWrite(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[llm]\nmodel='unchanged'\n");
        var ctrl = controller(dir, store);
        java.util.concurrent.atomic.AtomicInteger remoteCalls = new java.util.concurrent.atomic.AtomicInteger();
        var remote = io.javalin.Javalin.create().get("/models", ctx -> {
            remoteCalls.incrementAndGet();
            ctx.json(Map.of("data", List.of()));
        }).post("/embeddings", ctx -> {
            remoteCalls.incrementAndGet();
            ctx.json(Map.of("data", List.of()));
        }).start("127.0.0.1", 0);
        var api = io.javalin.Javalin.create()
                .put("/config", ctrl::putConfig).put("/raw", ctrl::putRawConfig)
                .post("/llm", ctrl::testLlm).post("/embedding", ctrl::testEmbedding)
                .start("127.0.0.1", 0);
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            String text = "aw.mode='removed-secret-value'\n[llm]\napi-key='fake'\nbase-url='http://127.0.0.1:"
                    + remote.port() + "'\n[embedding]\napi-key='fake'\nbase-url='http://127.0.0.1:" + remote.port() + "'\n";
            String before = store.readRaw();
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (String route : List.of("config", "raw", "llm", "embedding")) {
                Object body = route.equals("config") ? Map.of("aw", Map.of("mode", "removed-secret-value"))
                        : Map.of("text", text);
                var request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + api.port() + "/" + route))
                        .header("Content-Type", "application/json")
                        .method(route.equals("config") || route.equals("raw") ? "PUT" : "POST",
                                java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(400, response.statusCode(), route + ": " + response.body());
                assertTrue(response.body().contains("events.mode"), response.body());
                assertFalse(response.body().contains("removed-secret-value"));
                assertEquals(before, store.readRaw());
            }
            assertEquals(0, remoteCalls.get());
        } finally {
            api.stop();
            remote.stop();
        }
    }

    @Test
    void connectionTestsUseNewlySavedParameters(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        DesktopConfigController controller = controller(dir, store);
        var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
        var embeddingBody = new java.util.concurrent.atomic.AtomicReference<String>();
        var app = io.javalin.Javalin.create();
        app.post("/test-llm", controller::testLlm);
        app.post("/test-embedding", controller::testEmbedding);
        app.get("/v1/models", ctx -> {
            authorization.set(ctx.header("Authorization"));
            ctx.result("{}");
        });
        app.post("/v1/embeddings", ctx -> {
            authorization.set(ctx.header("Authorization"));
            embeddingBody.set(ctx.body());
            ctx.result("{}");
        });
        app.start("127.0.0.1", 0);
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + app.port();
            store.saveRaw("""
                    [llm]
                    api-key = "saved-llm-key"
                    base-url = "%s/v1"
                    model = "saved-chat"
                    [embedding]
                    api-key = "saved-embedding-key"
                    base-url = "%s/v1"
                    model = "saved-embedding"
                    send-encoding-format = false
                    """.formatted(base, base));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (String kind : List.of("llm", "embedding")) {
                var request = java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(base + "/test-" + kind))
                        .timeout(java.time.Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
                var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(mapper.readTree(response.body()).path("ok").asBoolean(), response.body());
                assertEquals("Bearer saved-" + kind + "-key", authorization.get());
            }
            var payload = mapper.readTree(embeddingBody.get());
            assertEquals("saved-embedding", payload.path("model").asText());
            assertFalse(payload.has("encoding_format"));
            String savedText = store.readRaw();
            String draft = "[llm]\napi-key='draft-key'\nbase-url='" + base + "/v1'\nmodel='draft'";
            var draftRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/test-llm"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("text", draft))))
                    .build();
            var tested = mapper.readTree(client.send(draftRequest, java.net.http.HttpResponse.BodyHandlers.ofString()).body());
            assertTrue(tested.path("ok").asBoolean());
            assertEquals("draft", tested.path("model").asText());
            assertEquals("Bearer draft-key", authorization.get());
            assertEquals(savedText, store.readRaw());
            var emptyKey = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/test-llm"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            Map.of("text", draft.replace("draft-key", "")))))
                    .build();
            var unavailable = mapper.readTree(client.send(emptyKey, java.net.http.HttpResponse.BodyHandlers.ofString()).body());
            assertFalse(unavailable.path("ok").asBoolean());
            assertEquals("Bearer draft-key", authorization.get(), "explicit blank must not send saved credentials");
            assertEquals(0L, store.application().effectivePayload().get("revision"));

        } finally {
            app.stop();
        }
    }

    private DesktopConfigController controller(Path dir, UserConfigStore store) {
        return new DesktopConfigController(Config.testDefaults(dir), store);
    }

    @Test
    void buildRawResponseReturnsTomlTemplateWhenFileMissing(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-012: template has section table headers + path guidance,
        // path points at config.toml.
        UserConfigStore store = new UserConfigStore(dir);
        var resp = controller(dir, store).buildRawResponse();

        assertFalse(resp.exists());
        assertTrue(resp.text().contains("[llm]"), resp.text());
        assertTrue(resp.text().contains("[embedding]"), resp.text());
        assertTrue(resp.text().contains("[file]"), resp.text());
        assertTrue(resp.text().lines().anyMatch(line ->
                line.strip().startsWith("# watch.enabled = false")), resp.text());
        assertTrue(resp.text().contains("# base-url"), resp.text());
        assertTrue(resp.text().contains("D:\\docs"), resp.text()); // path guidance comment
        assertTrue(resp.text().contains("# 中文："), resp.text());
        assertTrue(resp.text().contains("# English:"), resp.text());
        assertFalse(resp.text().contains("ActivityWatch"), resp.text());
        assertEquals(SupportedKeys.defaults().size(), resp.text().lines()
                .filter(line -> line.matches("# .+ = .*  # (string|boolean|integer|float|list)"))
                .count());
        assertTrue(resp.path().endsWith("config.toml"));
        // Template parses as valid TOML (all keys commented → empty tables).
        assertTrue(TomlSupport.parseAndFlatten(resp.text()).isEmpty());
    }

    @Test
    void buildRawResponseReturnsFileTextWhenPresent(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[llm]\nmodel = \"x\"\n");
        var resp = controller(dir, store).buildRawResponse();

        assertTrue(resp.exists());
        assertEquals("[llm]\nmodel = \"x\"\n", resp.text());
    }

    @Test
    void buildRawResponseTreatsWhitespaceOnlyFileAsEmpty(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("  \n\t");
        var resp = controller(dir, store).buildRawResponse();

        assertFalse(resp.exists());
        assertEquals(SupportedKeys.defaults().size(), resp.text().lines()
                .filter(line -> line.matches("# .+ = .*  # (string|boolean|integer|float|list)"))
                .count());
        assertTrue(resp.text().contains("# 中文："));
        assertTrue(resp.text().contains("# English:"));
    }

    @Test
    void rawResponseSupportedKeysCoverAllKeysAndAssignmentsRoundTrip(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-017 / SPEC-TOML-API-001e: supportedKeys covers every key in
        // SupportedKeys, in order; each assignment re-parses to that key's default.
        UserConfigStore store = new UserConfigStore(dir);
        var resp = controller(dir, store).buildRawResponse();

        List<DesktopConfigController.SupportedKeyInfo> keys = resp.supportedKeys();
        var defaults = SupportedKeys.defaults();
        assertEquals(defaults.size(), keys.size());

        int i = 0;
        for (var e : defaults.entrySet()) {
            var info = keys.get(i++);
            assertEquals(e.getKey(), info.key());              // declaration order preserved
            var flat = TomlSupport.parseAndFlatten(info.assignment());
            assertEquals(e.getValue(), flat.get(e.getKey()));  // assignment → default value
        }
    }

    @Test
    void supportedKeysIncludeRuntimeConfigKeysAndPrivacyDefaults() throws Exception {
        var defaults = SupportedKeys.defaults();

        assertEquals("false", defaults.get("wiki.enabled"));
        assertEquals("false", defaults.get("embedding.enabled"));
        assertEquals("false", defaults.get("websearch.enabled"));
        assertFalse(defaults.keySet().stream().anyMatch(DeprecatedKeys::contains));
        assertTrue(defaults.containsKey("memory.dir"));
        assertTrue(defaults.containsKey("events.base-url"));
        assertTrue(defaults.containsKey("wiki.prompt.maxContentChars"));
        assertTrue(defaults.containsKey("file.watch.paths"));
        assertEquals("0", defaults.get("file.watch.maxFileSizeKb"));
        assertTrue(defaults.get("file.watch.extensions").contains("docx"));
        assertEquals("true", defaults.get("file.watch.respectGitIgnore"));
        assertFalse(defaults.containsKey("file.watch.maxContentChars"));
        assertFalse(defaults.containsKey("file.watch.minReindexIntervalMinutes"));
        assertFalse(defaults.containsKey("file.watch.semantic.enabled"));
        assertFalse(defaults.containsKey("file.watch.semantic.index-dir"));
        assertTrue(DeprecatedKeys.contains("file.watch.semantic.enabled"));
        assertTrue(DeprecatedKeys.contains("file.watch.semantic.index-dir"));
        assertTrue(defaults.containsKey("llm.budget.dailyTokens"));
        assertTrue(defaults.containsKey("desktop.summary.maxTimelineLlm"));
        assertFalse(defaults.keySet().stream().anyMatch(key -> key.startsWith("headroom.")));
    }

    @Test
    void configPayloadOmitsRemovedHeadroomSection(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[headroom]\nenabled = true\n");

        Map<String, Object> payload = controller(dir, store).configPayload();

        assertFalse(payload.containsKey("headroom"));
    }

    @Test
    void configPayloadOmitsRemovedFeatureSectionsButAcceptsLegacyKeys(@TempDir Path dir)
            throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("""
                [aw.ocr]
                engine = "paddle"
                [aw.audio]
                enabled = true
                legacyOption = "ignored"
                """);

        Map<String, Object> payload = controller(dir, store).configPayload();
        assertFalse(payload.containsKey("audio"));
        Map<?, ?> collection = (Map<?, ?>) payload.get("collection");
        assertFalse(collection.containsKey("ocrEngine"));

        var raw = controller(dir, store).applyRawSave(store.readRaw());
        assertTrue(raw.unknownKeys().isEmpty());
    }

    @Test
    void structuredSavePreservesIgnoredLegacyHeadroomKeys(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[headroom]\nenabled = true\nproxy-url = \"http://127.0.0.1:8787/v1\"\n");
        var ctrl = controller(dir, store);

        ctrl.applyStructuredSave(Map.of("desktop", Map.of("hideToTray", false)));

        Properties saved = store.loadUser();
        assertEquals("true", saved.getProperty("headroom.enabled"));
        assertEquals("http://127.0.0.1:8787/v1", saved.getProperty("headroom.proxy-url"));
        assertEquals("false", saved.getProperty("desktop.hideToTray"));
    }

    @Test
    void runtimeConfigKeysAreNotReportedUnknown(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        var ctrl = controller(dir, store);

        String text = ""
                + "memory.dir = 'D:\\data\\memory'\n"
                + "[events]\n"
                + "base-url = \"http://localhost:5700/api/0\"\n"
                + "timeout = 15000\n"
                + "[wiki]\n"
                + "enabled = false\n"
                + "prompt.maxContentChars = 12000\n"
                + "[file.watch]\n"
                + "paths = 'D:\\docs'\n"
                + "maxContentChars = 8000\n"
                + "[llm.budget]\n"
                + "dailyTokens = 100000000\n"
                + "[desktop.summary]\n"
                + "maxTimelineLlm = 4\n";

        var result = ctrl.applyRawSave(text);

        assertTrue(result.unknownKeys().isEmpty(), result.unknownKeys().toString());
    }

    @Test
    void invalidTomlSyntaxRejectedWithoutWriting(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-004: syntax error → TomlValidationException (line/col), no write.
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[llm]\nmodel = \"keep\"\n");
        var ctrl = controller(dir, store);

        TomlValidationException ex = assertThrows(TomlValidationException.class,
                () -> ctrl.applyRawSave("model = \n")); // dangling value
        assertTrue(ex.getMessage().contains("行"), ex.getMessage());

        assertEquals("[llm]\nmodel = \"keep\"\n", store.readRaw()); // untouched
    }

    @Test
    void knownKeyTypeViolationRejectedWithoutWriting(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-005: events.port = "abc" is not an integer → 400, no write.
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[llm]\nmodel = \"keep\"\n");
        var ctrl = controller(dir, store);

        TomlValidationException ex = assertThrows(TomlValidationException.class,
                () -> ctrl.applyRawSave("[events]\nport = \"abc\"\n"));
        assertTrue(ex.getMessage().contains("events.port"), ex.getMessage());

        assertEquals("[llm]\nmodel = \"keep\"\n", store.readRaw()); // untouched
    }

    @Test
    void invalidFileFilterSettingsAreRejectedWithoutWriting(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[file.watch]\nextensions = [\"md\"]\n");
        var ctrl = controller(dir, store);

        assertThrows(TomlValidationException.class, () -> ctrl.applyRawSave("""
                [file.watch]
                extensions = ["md", "*"]
                excludeGlobs = ["[broken"]
                """));

        assertEquals("[file.watch]\nextensions = [\"md\"]\n", store.readRaw());
    }

    @Test
    void invalidRawSettingsAreRejectedWithoutReplacingDiskFile(@TempDir Path dir)
            throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        String original = "[events.raw.integrity]\nstartupScope = \"latest\"\n";
        store.saveRaw(original);
        var ctrl = controller(dir, store);

        List<String> invalidTexts = List.of(
                "[events.raw.query]\nmaxRangeDays = 0\n",
                "[events.raw.query]\nmaxPageSize = 10001\n",
                "[events.raw.lowDisk]\nwarnBytes = 1024\nblockBytes = 1024\n",
                "[events.raw.projector]\nbatchSize = -1\n",
                "[events.raw.integrity]\nstartupScope = \"none\"\n",
                "[events.raw]\nenabled = false\n");

        for (String invalidText : invalidTexts) {
            assertThrows(TomlValidationException.class,
                    () -> ctrl.applyRawSave(invalidText), invalidText);
            assertEquals(original, store.readRaw(), invalidText);
        }
    }

    @Test
    void rawDirectoryCannotChangeAfterCatalogContainsPartition(@TempDir Path dir)
            throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        String original = "[events.raw]\ndir = '" + dir.resolve("events/raw") + "'\n";
        store.saveRaw(original);
        Config config = Config.testDefaults(dir);
        try (RawPartitionCatalog catalog = new RawPartitionCatalog(config.eventsRawDir())) {
            catalog.insert(new RawPartitionMetadata(
                    "2026-09", "2026/raw-events-2026-09.db",
                    Instant.parse("2026-09-01T00:00:00Z"), null,
                    RawPartitionStatus.ACTIVE, 0, null, null, 0,
                    null, null, RawPartitionCatalog.CATALOG_SCHEMA_VERSION));
        }

        TomlValidationException error = assertThrows(TomlValidationException.class,
                () -> new DesktopConfigController(config, store).applyRawSave(
                        "[events.raw]\ndir = '" + dir.resolve("new-raw") + "'\n"));

        assertTrue(error.getMessage().contains("events.raw.dir"), error.getMessage());
        assertEquals(original, store.readRaw());
        try (RawPartitionCatalog catalog = new RawPartitionCatalog(config.eventsRawDir())) {
            assertEquals(1, catalog.partitionCount());
        }
    }

    @Test
    void rawDirectoryCanChangeBeforeAnyPartitionExists(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        Config config = Config.testDefaults(dir);
        try (RawPartitionCatalog ignored = new RawPartitionCatalog(config.eventsRawDir())) {
            // catalog exists, but the first raw event has not created a partition yet
        }
        String replacement = "[events.raw]\ndir = '" + dir.resolve("new-raw") + "'\n";

        new DesktopConfigController(config, store).applyRawSave(replacement);

        assertEquals(replacement, store.readRaw());
    }

    @Test
    void validTomlSaveIsVerbatimRoundTrip(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-006: valid text saved char-for-char.
        UserConfigStore store = new UserConfigStore(dir);
        var ctrl = controller(dir, store);
        String text = "[llm]\nmodel = \"gpt-4o-mini\"\napi-key = \"sk-x\"\n";
        ctrl.applyRawSave(text);
        assertEquals(text, store.readRaw());
    }

    @Test
    void restartRequiredReflectsRestartKeyChanges(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-007: restart semantics preserved on dotted keys.
        UserConfigStore store = new UserConfigStore(dir);
        var ctrl = controller(dir, store);

        var r1 = ctrl.applyRawSave("[llm]\nmodel = \"gpt-4o-mini\"\n");
        assertFalse(r1.restartRequired().contains("llm.model"));

        var r2 = ctrl.applyRawSave("[llm]\nmodel = \"gpt-4o-mini\"\napi-key = \"sk-x\"\n");
        assertFalse(r2.restartRequired().contains("llm.api-key"));

        var r3 = ctrl.applyRawSave("""
                [llm]
                model = "gpt-4o-mini"
                api-key = "sk-x"
                [agent.compaction]
                triggerMessages = 24
                """);
        assertTrue(r3.restartRequired().contains("agent.compaction.triggerMessages"));

        var r4 = ctrl.applyRawSave("""
                [aw.ocr]
                engine = "paddle"
                """);
        assertFalse(r4.restartRequired().contains("aw.ocr.engine"));
        assertTrue(r4.unknownKeys().isEmpty());

        var r5 = ctrl.applyRawSave("""
                [file.watch]
                enabled = true
                paths = "D:/Documents"
                """);
        assertTrue(r5.restartRequired().contains("file.watch.enabled"));
        assertTrue(r5.restartRequired().contains("file.watch.paths"));

        var r6 = ctrl.applyRawSave("""
                [file.watch]
                extensions = ["md", "docx"]
                respectGitIgnore = false
                """);
        assertTrue(r6.restartRequired().contains("file.watch.extensions"));
        assertTrue(r6.restartRequired().contains("file.watch.respectGitIgnore"));
    }

    @Test
    void unknownKeysWarnButDoNotBlockSave(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-007: unknown key warned, still saved.
        UserConfigStore store = new UserConfigStore(dir);
        var ctrl = controller(dir, store);

        var r = ctrl.applyRawSave("foo.bar = 1\n");
        assertTrue(r.unknownKeys().contains("foo.bar"));
        assertEquals("foo.bar = 1\n", store.readRaw()); // saved anyway
    }

    @Test
    void savedRawTomlIsReadableByStore(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-015 spirit: a saved raw TOML re-parses consistently.
        UserConfigStore store = new UserConfigStore(dir);
        var ctrl = controller(dir, store);
        ctrl.applyRawSave("[events]\nport = 5601\ncollection.window = false\n");

        Properties back = store.loadUser();
        assertEquals("5601", back.getProperty("events.port"));
        assertEquals("false", back.getProperty("events.collection.window"));
    }

    @Test
    void rawSavePreservesExistingHistoryFile(@TempDir Path dir) throws Exception {
        Path history = dir.resolve("config-history.json");
        java.nio.file.Files.writeString(history, "legacy-history-sentinel");

        controller(dir, new UserConfigStore(dir)).applyRawSave("[llm]\nmodel = \"gpt-4o\"\n");

        assertEquals("legacy-history-sentinel", java.nio.file.Files.readString(history));
    }

    @Test
    void structuredPutMapsNestedAgentCompactionKeys(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        var ctrl = controller(dir, store);

        var result = ctrl.applyStructuredSave(Map.of(
                "agent", Map.of("compaction", Map.of(
                        "enabled", false,
                        "triggerMessages", 24,
                        "keepMessages", 8))));

        Properties user = store.loadUser();
        assertEquals("false", user.getProperty("agent.compaction.enabled"));
        assertEquals("24", user.getProperty("agent.compaction.triggerMessages"));
        assertEquals("8", user.getProperty("agent.compaction.keepMessages"));
        assertTrue(result.restartRequired().contains("agent.compaction.triggerMessages"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void structuredCompactionFieldsUseTheirFullConfigKeyForMetadata(@TempDir Path dir)
            throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[agent.compaction]\ntriggerMessages = 24\n");
        var ctrl = controller(dir, store);
        Method method = DesktopConfigController.class.getDeclaredMethod(
                "buildAgentSection", Properties.class);
        method.setAccessible(true);

        Map<String, Object> agent = (Map<String, Object>) method.invoke(ctrl, store.load());
        Map<String, Map<String, Object>> compaction =
                (Map<String, Map<String, Object>>) agent.get("compaction");
        Map<String, Object> triggerMessages = compaction.get("triggerMessages");

        assertEquals("24", triggerMessages.get("savedValue"));
        assertEquals("user_config", triggerMessages.get("source"));
        assertEquals(true, triggerMessages.get("restartRequiredOnChange"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void structuredConfigSectionsDoNotExposeSecretValues(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[llm]\napi-key = \"sk-live-secret\"\n"
                + "[embedding]\napi-key = \"emb-live-secret\"\n"
                + "[websearch]\napi-key = \"web-live-secret\"\n");
        var ctrl = controller(dir, store);
        Properties effective = store.load();

        Map<String, Map<String, Object>> llm = invokeSection(ctrl, "buildLlmSection", effective);
        Map<String, Map<String, Object>> embedding = invokeSection(ctrl, "buildEmbeddingSection", effective);
        Map<String, Map<String, Object>> websearch = invokeSection(ctrl, "buildWebSearchSection", effective);

        assertFieldDoesNotExpose(llm.get("apiKey"), "sk-live-secret");
        assertFieldDoesNotExpose(embedding.get("embeddingApiKey"), "emb-live-secret");
        assertFieldDoesNotExpose(websearch.get("webSearchApiKey"), "web-live-secret");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> invokeSection(
            DesktopConfigController ctrl, String methodName, Properties effective) throws Exception {
        Method m = DesktopConfigController.class.getDeclaredMethod(methodName, Properties.class);
        m.setAccessible(true);
        return (Map<String, Map<String, Object>>) m.invoke(ctrl, effective);
    }

    private static void assertFieldDoesNotExpose(Map<String, Object> field, String secret) {
        String rendered = String.valueOf(field);
        assertFalse(rendered.contains(secret), rendered);
        assertTrue(String.valueOf(field.get("effectiveValue")).contains("****")
                || String.valueOf(field.get("effectiveValue")).isBlank());
        Object saved = field.get("savedValue");
        if (saved != null) {
            assertFalse(String.valueOf(saved).contains(secret), rendered);
        }
    }

}
