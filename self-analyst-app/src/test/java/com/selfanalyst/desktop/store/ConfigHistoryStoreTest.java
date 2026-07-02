package com.selfanalyst.desktop.store;

import com.selfanalyst.desktop.store.ConfigHistoryStore.ConfigVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version snapshot storage, retention, and TOML format/redaction (SPEC-TOML-VER-001). */
class ConfigHistoryStoreTest {

    private static final String TOML = ConfigHistoryStore.FORMAT_TOML;

    @Test
    void addThenListReturnsNewestFirst(@TempDir Path dir) throws Exception {
        ConfigHistoryStore store = new ConfigHistoryStore(dir);
        store.add("v1", "first", "a = 1\n", TOML);
        store.add("v2", "second", "a = 2\n", TOML);

        List<ConfigVersion> list = store.list();
        assertEquals(2, list.size());
        assertEquals("v2", list.get(0).name()); // newest first
        assertEquals("v1", list.get(1).name());
    }

    @Test
    void retentionKeepsOnlyTenNewest(@TempDir Path dir) throws Exception {
        ConfigHistoryStore store = new ConfigHistoryStore(dir);
        for (int i = 1; i <= 12; i++) {
            store.add("v" + i, "s" + i, "a = " + i + "\n", TOML);
        }
        List<ConfigVersion> list = store.list();
        assertEquals(ConfigHistoryStore.MAX_VERSIONS, list.size());
        assertEquals("v12", list.get(0).name());  // newest kept
        assertEquals("v3", list.get(9).name());   // v1, v2 pruned as oldest
    }

    @Test
    void getByIdAndUpdateSummary(@TempDir Path dir) throws Exception {
        ConfigHistoryStore store = new ConfigHistoryStore(dir);
        ConfigVersion v = store.add("v1", "old summary", "a = 1\n", TOML);

        assertTrue(store.get(v.id()).isPresent());
        assertEquals("a = 1\n", store.get(v.id()).get().text());
        assertTrue(store.get("nope").isEmpty());

        store.updateSummary(v.id(), "new summary");
        assertEquals("new summary", store.get(v.id()).get().summary());
        assertEquals(TOML, store.get(v.id()).get().format()); // format preserved
    }

    @Test
    void newAddsCarryTomlFormat(@TempDir Path dir) throws Exception {
        ConfigHistoryStore store = new ConfigHistoryStore(dir);
        ConfigVersion v = store.add("v1", "s", "a = 1\n", TOML);
        assertEquals(TOML, v.format());
        assertEquals(TOML, store.list().get(0).format());
    }

    @Test
    void legacyManifestWithoutFormatReadsAsProperties(@TempDir Path dir) throws Exception {
        // A manifest written before the format field existed. SPEC-TOML-VER-001.
        String json = "{\"versions\":[{"
                + "\"id\":\"x1\",\"name\":\"old\",\"summary\":\"s\","
                + "\"savedAt\":1,\"text\":\"llm.model=gpt-4o\\n\"}]}";
        Files.writeString(dir.resolve("config-history.json"), json, StandardCharsets.UTF_8);

        ConfigHistoryStore store = new ConfigHistoryStore(dir);
        List<ConfigVersion> list = store.list();
        assertEquals(1, list.size());
        assertEquals(ConfigHistoryStore.FORMAT_PROPERTIES, list.get(0).format());
    }

    @Test
    void tomlRedactionEmptiesValueKeepingValidToml(@TempDir Path dir) throws Exception {
        ConfigHistoryStore store = new ConfigHistoryStore(dir);
        store.add("v1", "summary",
                "[llm]\napi-key = \"sk-live-secret\"\nmodel = \"gpt-4o\"\n", TOML);

        String text = store.list().get(0).text();
        assertFalse(text.contains("sk-live-secret"), text);
        assertTrue(text.contains("api-key = \"\""), text); // stays valid TOML
        assertTrue(text.contains("model = \"gpt-4o\""), text);
    }

    @Test
    void tomlRedactionMatchesQuotedDottedKey(@TempDir Path dir) throws Exception {
        ConfigHistoryStore store = new ConfigHistoryStore(dir);
        store.add("v1", "summary",
                "\"llm.api-key\" = \"sk-live-secret\"\n", TOML);

        String text = store.list().get(0).text();
        assertFalse(text.contains("sk-live-secret"), text);
        assertTrue(text.contains("\"llm.api-key\" = \"\""), text);
    }

    @Test
    void propertiesRedactionPreservesLegacyBehavior(@TempDir Path dir) throws Exception {
        ConfigHistoryStore store = new ConfigHistoryStore(dir);
        store.add("v1", "summary",
                "llm.api-key=sk-live-secret\n"
                        + "embedding.api-key: emb-live-secret\n"
                        + "llm.model=gpt-4o\n",
                ConfigHistoryStore.FORMAT_PROPERTIES);

        String text = store.list().get(0).text();
        assertFalse(text.contains("sk-live-secret"), text);
        assertFalse(text.contains("emb-live-secret"), text);
        assertTrue(text.contains("llm.api-key="), text);
        assertTrue(text.contains("embedding.api-key:"), text);
        assertTrue(text.contains("llm.model=gpt-4o"), text);
    }
}
