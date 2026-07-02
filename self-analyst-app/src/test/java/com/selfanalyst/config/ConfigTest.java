package com.selfanalyst.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigTest {

    @Test
    void exposesAudioEnabledAsConfigValue(@TempDir Path dir) throws Exception {
        Config cfg = Config.testDefaults(dir);

        Method audioEnabled = Config.class.getMethod("audioEnabled");

        assertEquals(false, audioEnabled.invoke(cfg));
    }

    // ── TOML overlay load priority (SPEC-TOML-MIG-002a) ──────────────────

    @Test
    void tomlOverridesClasspathDefault(@TempDir Path dir) throws Exception {
        Properties props = new Properties();
        props.setProperty("llm.model", "gpt-4o"); // classpath default
        Files.writeString(dir.resolve("config.toml"), "[llm]\nmodel = \"custom-x\"\n",
                StandardCharsets.UTF_8);

        Config.overlayUserConfig(props, dir);

        assertEquals("custom-x", props.getProperty("llm.model"));
    }

    @Test
    void tomlWinsWhenBothTomlAndPropertiesPresent(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-009 load half: properties residue ignored while toml exists.
        Files.writeString(dir.resolve("config.toml"), "[llm]\nmodel = \"from-toml\"\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("config.properties"), "llm.model=from-props\n",
                StandardCharsets.UTF_8);

        Properties props = new Properties();
        Config.overlayUserConfig(props, dir);

        assertEquals("from-toml", props.getProperty("llm.model"));
    }

    @Test
    void propertiesHonoredWhenTomlAbsent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.properties"), "llm.model=from-props\n",
                StandardCharsets.UTF_8);

        Properties props = new Properties();
        Config.overlayUserConfig(props, dir);

        assertEquals("from-props", props.getProperty("llm.model"));
    }

    @Test
    void chineseTomlValueRoundTripsThroughOverlay(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-011 load half: UTF-8 中文 value survives parse + overlay.
        Files.writeString(dir.resolve("config.toml"),
                "[aw]\ndata-dir = 'D:\\数据\\中文'\n", StandardCharsets.UTF_8);

        Properties props = new Properties();
        Config.overlayUserConfig(props, dir);

        assertEquals("D:\\数据\\中文", props.getProperty("aw.data-dir"));
    }

    @Test
    void brokenTomlIsSkippedWithoutOverlay(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.toml"), "llm.model = \n",
                StandardCharsets.UTF_8); // syntactically invalid

        Properties props = new Properties();
        props.setProperty("llm.model", "default");
        Config.overlayUserConfig(props, dir); // must not throw

        assertEquals("default", props.getProperty("llm.model"));
    }
}
