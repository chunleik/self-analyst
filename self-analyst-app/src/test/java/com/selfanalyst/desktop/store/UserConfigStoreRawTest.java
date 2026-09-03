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
        store.saveRaw("[aw]\ndata-dir = 'D:\\数据\\中文目录'\n[llm]\nmodel = \"智谱-glm\"\n");

        assertEquals("D:\\数据\\中文目录", store.loadUser().getProperty("aw.data-dir"));
        assertEquals("智谱-glm", store.loadUser().getProperty("llm.model"));
    }

    @Test
    void saveRegeneratesParseableTomlReadBackEqual(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-015 store half: structured save output re-parses to the
        // same flat map and reads back identically.
        UserConfigStore store = new UserConfigStore(dir);
        Properties p = new Properties();
        p.setProperty("llm.model", "gpt-4o-mini");
        p.setProperty("aw.port", "5601");
        p.setProperty("aw.collection.window", "false");
        p.setProperty("aw.data-dir", "D:\\aw\\data");
        store.save(p);

        Properties back = store.loadUser();
        assertEquals("gpt-4o-mini", back.getProperty("llm.model"));
        assertEquals("5601", back.getProperty("aw.port"));
        assertEquals("false", back.getProperty("aw.collection.window"));
        assertEquals("D:\\aw\\data", back.getProperty("aw.data-dir"));
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
        values.setProperty("aw.raw.dir", "D:\\self-analyst\\raw");
        values.setProperty("aw.raw.query.maxRangeDays", "14");
        values.setProperty("aw.raw.query.maxPageSize", "500");
        values.setProperty("aw.raw.lowDisk.warnBytes", "10737418240");
        values.setProperty("aw.raw.lowDisk.blockBytes", "1073741824");
        values.setProperty("aw.raw.integrity.verifyOnStartup", "all");
        values.setProperty("aw.raw.projector.batchSize", "256");

        store.save(values);

        Properties loaded = store.loadUser();
        values.forEach((key, value) -> assertEquals(value, loaded.get(key), key.toString()));
        String raw = store.readRaw();
        assertTrue(raw.contains("[aw]"), raw);
        assertTrue(raw.contains("raw.query.maxRangeDays = 14"), raw);
        assertTrue(raw.contains("raw.integrity.verifyOnStartup = \"all\""), raw);
    }
}
