package com.selfanalyst.tools;

import com.selfanalyst.desktop.store.UserConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigToolsTest {

    @Test
    void getConfigIncludesHeadroomSectionAndRuntimeStatus(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);
        store.saveRaw("[headroom]\nenabled = true\nproxy-url = \"http://127.0.0.1:8787/v1\"\n");

        String config = new ConfigTools(store, () -> "disabled", () -> "available").getConfig();

        assertTrue(config.contains("[Headroom]"), config);
        assertTrue(config.contains("headroom.enabled = true"), config);
        assertTrue(config.contains("headroom.proxy-url = http://127.0.0.1:8787/v1"), config);
        assertTrue(config.contains("headroom.stats.enabled = true"), config);
        assertTrue(config.contains("headroom.output-shaper = false"), config);
        assertTrue(config.contains("headroom.runtimeStatus = available"), config);
    }

    @Test
    void setConfigValueAllowsHeadroomKeys(@TempDir Path dir) throws Exception {
        UserConfigStore store = new UserConfigStore(dir);

        String result = new ConfigTools(store).setConfigValue("headroom.enabled", "true");

        assertTrue(result.contains("配置已保存"), result);
        assertTrue(result.contains("需重启"), result);
    }
}
