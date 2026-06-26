package com.selfanalyst.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigTest {

    @Test
    void exposesAudioEnabledAsConfigValue(@TempDir Path dir) throws Exception {
        Config cfg = Config.testDefaults(dir);

        Method audioEnabled = Config.class.getMethod("audioEnabled");

        assertEquals(false, audioEnabled.invoke(cfg));
    }
}
