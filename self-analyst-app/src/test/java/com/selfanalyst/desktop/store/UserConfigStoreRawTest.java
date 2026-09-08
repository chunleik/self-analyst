package com.selfanalyst.desktop.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verbatim raw read/write + TOML load/save round-trip (SPEC-TOML-TST-006/014/015). */
class UserConfigStoreRawTest {

    @Test
    void titleSettingsAndChineseDirectorySurviveBothWriteForms(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        String text = "# 保留旧名说明 aw.mode\n[events]\ndata-dir='D:\\数据\\标题'\n"
                + "[events.collection.title]\nenabled=false\npollMs=800\n";
        store.saveRaw(text);
        assertEquals(text, store.readRaw());
        Properties expected = store.loadUser();
        assertEquals("false", expected.getProperty("events.collection.title.enabled"));
        assertEquals("800", expected.getProperty("events.collection.title.pollMs"));
        assertEquals("D:\\数据\\标题", expected.getProperty("events.data-dir"));
        store.save(expected);
        assertEquals(expected, store.loadUser());
    }

    @Test
    void readRawReturnsEmptyWhenFileMissing(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        assertEquals("", store.readRaw());
    }

    @Test
    void filePathTargetsConfigToml(@TempDir Path dir) {
        UserConfigStore store = new UserConfigStore(dir);
        assertTrue(store.filePath().toString().endsWith("config.toml"), store.filePath().toString());
    }

    @Test
    void saveRawThenReadRawIsCharForCharFaithful(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-006: raw path is byte-faithful regardless of TOML content.
        UserConfigStore store = new UserConfigStore(dir);
        String text = "# 用户配置\n"
                + "\n"
                + "[llm]\n"
                + "model = \"gpt-4o\"\n"
                + "api-key = \"sk-测试密钥\"\n"
                + "\n"
                + "# 备注：保留注释、空行与键顺序\n";

        store.saveRaw(text);

        assertEquals(text, store.readRaw());
    }

    @Test
    void nonAsciiValuesDecodeViaLoadUser(@TempDir Path dir) throws Exception {
        // saveRaw writes UTF-8; loadUser() parses TOML in UTF-8, so 中文 and
        // backslash literal-string paths are not corrupted at runtime.
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[events]\ndata-dir = 'D:\\数据\\中文目录'\n[llm]\nmodel = \"智谱-glm\"\n");

        assertEquals("D:\\数据\\中文目录", store.loadUser().getProperty("events.data-dir"));
        assertEquals("智谱-glm", store.loadUser().getProperty("llm.model"));
    }

    @Test
    void saveRegeneratesParseableTomlReadBackEqual(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-015 store half: structured save output re-parses to the
        // same flat map and reads back identically.
        UserConfigStore store = new UserConfigStore(dir);
        Properties p = new Properties();
        p.setProperty("llm.model", "gpt-4o-mini");
        p.setProperty("events.port", "5601");
        p.setProperty("events.collection.window", "false");
        p.setProperty("events.data-dir", "D:\\aw\\data");
        store.save(p);

        Properties back = store.loadUser();
        assertEquals("gpt-4o-mini", back.getProperty("llm.model"));
        assertEquals("5601", back.getProperty("events.port"));
        assertEquals("false", back.getProperty("events.collection.window"));
        assertEquals("D:\\aw\\data", back.getProperty("events.data-dir"));
    }

    @Test
    void setPersistsSingleKeyIntoConfigToml(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-014 store half: set() writes the value into config.toml.
        UserConfigStore store = new UserConfigStore(dir);
        store.set("llm.model", "custom-model");

        assertTrue(store.readRaw().contains("custom-model"), store.readRaw());
        assertEquals("custom-model", store.loadUser().getProperty("llm.model"));
    }

    @Test
    void rawEventConfigValuesRoundTripWithDeclaredTomlTypes(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        Properties values = new Properties();
        values.setProperty("events.raw.dir", "D:\\self-analyst\\raw");
        values.setProperty("events.raw.query.maxRangeDays", "14");
        values.setProperty("events.raw.query.maxPageSize", "500");
        values.setProperty("events.raw.lowDisk.warnBytes", "10737418240");
        values.setProperty("events.raw.lowDisk.blockBytes", "1073741824");
        values.setProperty("events.raw.integrity.startupScope", "all");
        values.setProperty("events.raw.projector.batchSize", "256");

        store.save(values);

        Properties loaded = store.loadUser();
        values.forEach((key, value) -> assertEquals(value, loaded.get(key), key.toString()));
        String raw = store.readRaw();
        assertTrue(raw.contains("[events]"), raw);
        assertTrue(raw.contains("raw.query.maxRangeDays = 14"), raw);
        assertTrue(raw.contains("raw.integrity.startupScope = \"all\""), raw);
    }
}
