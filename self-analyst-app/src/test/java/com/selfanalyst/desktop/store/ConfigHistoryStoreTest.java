package com.selfanalyst.desktop.store;

import com.selfanalyst.desktop.store.ConfigHistoryStore.ConfigVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version snapshot storage and retention (SPEC-CFGUI-VER-TST-001/002/004). */
class ConfigHistoryStoreTest {

    @Test
    void addThenListReturnsNewestFirst(@TempDir Path dir) throws Exception {
        ConfigHistoryStore store = new ConfigHistoryStore(dir);
        store.add("v1", "first", "a=1\n");
        store.add("v2", "second", "a=2\n");

        List<ConfigVersion> list = store.list();
        assertEquals(2, list.size());
        assertEquals("v2", list.get(0).name()); // newest first
        assertEquals("v1", list.get(1).name());
    }

    @Test
    void retentionKeepsOnlyTenNewest(@TempDir Path dir) throws Exception {
        ConfigHistoryStore store = new ConfigHistoryStore(dir);
        for (int i = 1; i <= 12; i++) {
            store.add("v" + i, "s" + i, "a=" + i + "\n");
        }
        List<ConfigVersion> list = store.list();
        assertEquals(ConfigHistoryStore.MAX_VERSIONS, list.size());
        assertEquals("v12", list.get(0).name());  // newest kept
        assertEquals("v3", list.get(9).name());   // v1, v2 pruned as oldest
    }

    @Test
    void getByIdAndUpdateSummary(@TempDir Path dir) throws Exception {
        ConfigHistoryStore store = new ConfigHistoryStore(dir);
        ConfigVersion v = store.add("v1", "old summary", "a=1\n");

        assertTrue(store.get(v.id()).isPresent());
        assertEquals("a=1\n", store.get(v.id()).get().text());
        assertTrue(store.get("nope").isEmpty());

        store.updateSummary(v.id(), "new summary");
        assertEquals("new summary", store.get(v.id()).get().summary());
    }

    @Test
    void addRedactsSensitiveValuesFromStoredSnapshot(@TempDir Path dir) throws Exception {
        ConfigHistoryStore store = new ConfigHistoryStore(dir);

        store.add("v1", "summary",
                "llm.api-key=sk-live-secret\n"
                        + "embedding.api-key: emb-live-secret\n"
                        + "websearch.api-key = web-live-secret\n"
                        + "llm.model=gpt-4o\n");

        String text = store.list().get(0).text();
        assertFalse(text.contains("sk-live-secret"), text);
        assertFalse(text.contains("emb-live-secret"), text);
        assertFalse(text.contains("web-live-secret"), text);
        assertTrue(text.contains("llm.api-key="), text);
        assertTrue(text.contains("embedding.api-key:"), text);
        assertTrue(text.contains("websearch.api-key ="), text);
        assertTrue(text.contains("llm.model=gpt-4o"), text);
    }
}
