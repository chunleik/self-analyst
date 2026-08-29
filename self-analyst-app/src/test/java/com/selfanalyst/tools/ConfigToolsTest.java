package com.selfanalyst.tools;

import com.selfanalyst.desktop.store.UserConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigToolsTest {

    @Test
    void getConfigOmitsRemovedHeadroomSettings(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[headroom]\nenabled = true\nproxy-url = \"http://127.0.0.1:8787/v1\"\n");

        String config = new ConfigTools(store, () -> "disabled").getConfig();

        assertFalse(config.toLowerCase().contains("headroom"), config);
    }

    @Test
    void setConfigValueRejectsRemovedHeadroomKeys(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);

        String result = new ConfigTools(store).setConfigValue("headroom.enabled", "true");

        assertTrue(result.contains("不支持修改配置键"), result);
    }
}
