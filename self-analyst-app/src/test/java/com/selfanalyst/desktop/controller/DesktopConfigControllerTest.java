package com.selfanalyst.desktop.controller;

import com.selfanalyst.config.Config;
import com.selfanalyst.config.SupportedKeys;
import com.selfanalyst.config.TomlSupport;
import com.selfanalyst.config.TomlValidationException;
import com.selfanalyst.desktop.store.ConfigHistoryStore;
import com.selfanalyst.desktop.store.UserConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(resp.text().contains("# base-url"), resp.text());
        assertTrue(resp.text().contains("D:\\docs"), resp.text()); // path guidance comment
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
    void formatVersionNameIsTimestamp() {
        // 2026-06-22 00:00:00 UTC → fixed format (SPEC-CFGUI-VER-TST-003)
        String name = DesktopConfigController.formatVersionName(1781136000000L, ZoneId.of("UTC"));
        assertTrue(name.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), name);
    }

    @Test
    void computeDiffSummaryReportsAddedChangedRemoved() {
        Properties oldP = new Properties();
        oldP.setProperty("llm.model", "gpt-4o");
        oldP.setProperty("aw.port", "5700");
        Properties newP = new Properties();
        newP.setProperty("llm.model", "gpt-4o-mini"); // changed
        newP.setProperty("llm.api-key", "sk-x");      // added
        // aw.port removed

        String summary = DesktopConfigController.computeDiffSummary(oldP, newP);
        assertTrue(summary.contains("新增"), summary);
        assertTrue(summary.contains("修改"), summary);
        assertTrue(summary.contains("删除"), summary);
        assertTrue(summary.contains("llm.api-key"), summary);
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

    @Test
    void applyRawSaveRecordsVersionsNewestFirstWithRetention(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        var ctrl = controller(dir, store);

        for (int i = 1; i <= 12; i++) {
            ctrl.applyRawSave("[llm]\nmodel = \"m" + i + "\"\n"); // SPEC-CFGUI-VER-TST-001/002
        }

        // Inspect via a fresh store pointed at the same memory dir.
        ConfigHistoryStore history = new ConfigHistoryStore(dir);
        var list = history.list();
        assertEquals(ConfigHistoryStore.MAX_VERSIONS, list.size());
        assertEquals("[llm]\nmodel = \"m12\"\n", list.get(0).text()); // newest first
        assertEquals(ConfigHistoryStore.FORMAT_TOML, list.get(0).format());
        assertTrue(list.get(0).name().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }
}
