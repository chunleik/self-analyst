package com.selfanalyst.tools;

import com.selfanalyst.desktop.store.UserConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigToolsTest {

    @Test
    void titleSwitchUsesNewNameWithoutExpandingWritableKeys(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        ConfigTools tools = new ConfigTools(store);
        assertTrue(tools.setConfigValue("events.collection.title.enabled", "false").contains("配置已保存"));
        org.junit.jupiter.api.Assertions.assertEquals("false", store.loadUser().getProperty("events.collection.title.enabled"));
        String before = store.readRaw();
        String error = tools.setConfigValue("aw.collection.content", "secret-old-value");
        assertTrue(error.contains("已移除"));
        assertTrue(error.contains("events.collection.title.enabled"));
        assertFalse(error.contains("secret-old-value"));
        assertTrue(tools.setConfigValue("events.raw.dir", "elsewhere").contains("不支持修改"));
        assertTrue(tools.setConfigValue("events.collection.title.pollMs", "800").contains("不支持修改"));
        org.junit.jupiter.api.Assertions.assertEquals(before, store.readRaw());
        assertTrue(tools.getConfig().contains("events.collection.title.enabled"));
        assertFalse(tools.getConfig().contains("aw.collection.content"));
    }

    @Test
    void getConfigOmitsRemovedHeadroomSettings(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[headroom]\nenabled = true\nproxy-url = \"http://127.0.0.1:8787/v1\"\n");

        String config = new ConfigTools(store).getConfig();

        assertFalse(config.toLowerCase().contains("headroom"), config);
        assertFalse(config.contains("aw.audio"), config);
    }

    @Test
    void setConfigValueRejectsRemovedHeadroomKeys(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);

        String result = new ConfigTools(store).setConfigValue("headroom.enabled", "true");

        assertTrue(result.contains("不支持修改配置键"), result);
    }
}
