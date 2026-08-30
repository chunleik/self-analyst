package com.selfanalyst.desktop.controller;

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
        assertTrue(defaults.containsKey("aw.base-url"));
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
                + "[aw]\n"
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
        // SPEC-TOML-TST-005: aw.port = "abc" is not an integer → 400, no write.
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[llm]\nmodel = \"keep\"\n");
        var ctrl = controller(dir, store);

        TomlValidationException ex = assertThrows(TomlValidationException.class,
                () -> ctrl.applyRawSave("[aw]\nport = \"abc\"\n"));
        assertTrue(ex.getMessage().contains("aw.port"), ex.getMessage());

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
        assertTrue(r1.restartRequired().contains("llm.model"));

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
        ctrl.applyRawSave("[aw]\nport = 5601\ncollection.window = false\n");

        Properties back = store.loadUser();
        assertEquals("5601", back.getProperty("aw.port"));
        assertEquals("false", back.getProperty("aw.collection.window"));
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
