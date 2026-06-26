package com.selfanalyst.desktop.controller;

import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.ConfigHistoryStore;
import com.selfanalyst.desktop.store.UserConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.Method;
import java.time.ZoneId;
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
    void buildRawResponseReturnsTemplateWhenFileMissing(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        var resp = controller(dir, store).buildRawResponse();

        assertFalse(resp.exists()); // SPEC-CFGUI-TST-002
        assertTrue(resp.text().contains("llm.base-url"));
        assertTrue(resp.text().contains("embedding.model"));
        assertTrue(resp.path().endsWith("config.properties"));
    }

    @Test
    void buildRawResponseReturnsFileTextWhenPresent(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("llm.model=x\n");
        var resp = controller(dir, store).buildRawResponse();

        assertTrue(resp.exists()); // SPEC-CFGUI-TST-001
        assertEquals("llm.model=x\n", resp.text());
    }

    @Test
    void parseAndSaveRejectIllegalUnicodeWithoutWriting(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("llm.model=keep\n");
        var ctrl = controller(dir, store);

        String bad = "a=\\uZZZZ"; // SPEC-CFGUI-TST-004
        assertThrows(IllegalArgumentException.class,
                () -> DesktopConfigController.parseProperties(bad));
        assertThrows(IllegalArgumentException.class, () -> ctrl.applyRawSave(bad));

        // File must remain untouched after a failed save.
        assertEquals("llm.model=keep\n", store.readRaw());
    }

    @Test
    void restartRequiredReflectsRestartKeyChanges(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        var ctrl = controller(dir, store);

        var r1 = ctrl.applyRawSave("llm.model=gpt-4o-mini\n"); // SPEC-CFGUI-TST-005
        assertTrue(r1.restartRequired().contains("llm.model"));

        var r2 = ctrl.applyRawSave("llm.model=gpt-4o-mini\nllm.api-key=sk-x\n");
        assertFalse(r2.restartRequired().contains("llm.api-key"));
    }

    @Test
    void unknownKeysWarnButDoNotBlockSave(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        var ctrl = controller(dir, store);

        var r = ctrl.applyRawSave("foo.bar=1\n"); // SPEC-CFGUI-TST-006
        assertTrue(r.unknownKeys().contains("foo.bar"));
        assertEquals("foo.bar=1\n", store.readRaw()); // saved anyway
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
        store.saveRaw("llm.api-key=sk-live-secret\n"
                + "embedding.api-key=emb-live-secret\n"
                + "websearch.api-key=web-live-secret\n");
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
            ctrl.applyRawSave("llm.model=m" + i + "\n"); // SPEC-CFGUI-VER-TST-001/002
        }

        // Inspect via a fresh store pointed at the same memory dir.
        ConfigHistoryStore history = new ConfigHistoryStore(dir);
        var list = history.list();
        assertEquals(ConfigHistoryStore.MAX_VERSIONS, list.size());
        assertEquals("llm.model=m12\n", list.get(0).text()); // newest first
        assertTrue(list.get(0).name().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }
}
