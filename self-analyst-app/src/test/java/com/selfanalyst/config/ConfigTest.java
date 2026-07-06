package com.selfanalyst.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @Test
    void classpathDefaultsKeepNetworkFeaturesOptIn() throws Exception {
        Properties props = new Properties();
        try (var in = Config.class.getClassLoader().getResourceAsStream("application.properties")) {
            assertNotNull(in, "application.properties must be available on the test classpath");
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }

        assertEquals("false", props.getProperty("wiki.enabled"));
        assertEquals("false", props.getProperty("embedding.enabled"));
        assertEquals("false", props.getProperty("websearch.enabled"));
    }

    @Test
    void defaultsKeepHeadroomDisabledAndPointAtLocalProxy() throws Exception {
        Path dir = Files.createTempDirectory("config-headroom-defaults");
        withMemoryDir(dir, () -> {
            Config c = Config.load();
            assertFalse(c.headroomEnabled());
            assertEquals("http://127.0.0.1:8787/v1", c.headroomProxyUrl());
            assertTrue(c.headroomStatsEnabled());
            assertFalse(c.headroomOutputShaper());
        });
    }

    @Test
    void testDefaultsIncludeHeadroomDefaults() throws Exception {
        Path dir = Files.createTempDirectory("config-headroom-test-defaults");
        Config c = Config.testDefaults(dir);
        assertFalse(c.headroomEnabled());
        assertEquals("http://127.0.0.1:8787/v1", c.headroomProxyUrl());
        assertTrue(c.headroomStatsEnabled());
        assertFalse(c.headroomOutputShaper());
    }

    @Test
    void loadReadsHeadroomOverridesFromIsolatedMemoryDir() throws Exception {
        Path dir = Files.createTempDirectory("config-headroom-overrides");
        Files.writeString(dir.resolve("config.toml"), """
                [headroom]
                enabled = true
                proxy-url = "http://127.0.0.1:9999/v1"
                output-shaper = true
                [headroom.stats]
                enabled = false
                """, StandardCharsets.UTF_8);

        withMemoryDir(dir, () -> {
            Config c = Config.load();
            assertTrue(c.headroomEnabled());
            assertEquals("http://127.0.0.1:9999/v1", c.headroomProxyUrl());
            assertFalse(c.headroomStatsEnabled());
            assertTrue(c.headroomOutputShaper());
        });
    }

    @Test
    void exposesAudioEnabledAsConfigValue(@TempDir Path dir) throws Exception {
        Config cfg = Config.testDefaults(dir);

        Method audioEnabled = Config.class.getMethod("audioEnabled");

        assertEquals(false, audioEnabled.invoke(cfg));
    }

    @Test
    void exposesAudioWhisperPathAsConfigValue(@TempDir Path dir) {
        Config cfg = Config.testDefaults(dir);

        assertEquals(Path.of("tools/whisper"), cfg.audioWhisperPath());
    }

    @Test
    void exposesAudioVadThresholdAsConfigValue(@TempDir Path dir) {
        Config cfg = Config.testDefaults(dir);

        assertEquals(0.0001, cfg.audioVadThreshold(), 0.00001);
    }

    @Test
    void exposesAudioAsrOptionsAsConfigValues(@TempDir Path dir) {
        Config cfg = Config.testDefaults(dir);

        assertEquals("mic", cfg.audioSource());
        assertEquals("auto", cfg.audioEngine());
        assertEquals("gpt-4o-transcribe", cfg.audioModel());
        assertEquals(10, cfg.audioChunkSeconds());
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

    private static void withMemoryDir(Path dir, ThrowingRunnable action) throws Exception {
        Files.createDirectories(dir);
        String previous = System.getProperty("memory.dir");
        System.setProperty("memory.dir", dir.toString());
        try {
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty("memory.dir");
            } else {
                System.setProperty("memory.dir", previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
