package com.selfanalyst.config;

import com.selfanalyst.file.FileFilterConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals("0", props.getProperty("file.watch.maxFileSizeKb"));
        assertEquals(FileFilterConfig.DEFAULT_EXTENSIONS_CSV,
                props.getProperty("file.watch.extensions"));
        assertEquals("true", props.getProperty("file.watch.respectGitIgnore"));
        assertFalse(props.stringPropertyNames().stream()
                .anyMatch(DeprecatedKeys::contains));
        assertEquals("true", props.getProperty("agent.compaction.enabled"));
        assertEquals("30", props.getProperty("agent.compaction.triggerMessages"));
        assertEquals("60000", props.getProperty("agent.compaction.triggerTokens"));
    }

    @Test
    void testDefaultsDisableCompaction(@TempDir Path dir) {
        Config c = Config.testDefaults(dir);
        assertFalse(c.agentCompactionEnabled(), "unit tests opt in to compaction explicitly");
    }

    @Test
    void explicitEmptyFileExtensionListRemainsFailClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.toml"), """
                [file.watch]
                extensions = []
                respectGitIgnore = false
                """, StandardCharsets.UTF_8);

        Config config = Config.load(dir);

        assertEquals("", config.fileWatchExtensions());
        assertFalse(config.fileWatchRespectGitIgnore());
        assertNull(config.fileWatchConfigurationError());
    }

    @Test
    void fileFilterStartupParsingMatchesRawValidationRangeAndBooleanRules(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("config.toml"), """
                [file.watch]
                maxFileSizeKb = 3000000000
                respectGitIgnore = true
                """, StandardCharsets.UTF_8);
        Config large = Config.load(dir);
        assertEquals(3_000_000_000L, large.fileWatchMaxFileSizeKb());
        assertNull(large.fileWatchConfigurationError());

        Files.writeString(dir.resolve("config.toml"), """
                [file.watch]
                respectGitIgnore = "maybe"
                """, StandardCharsets.UTF_8);
        Config invalid = Config.load(dir);
        assertNotNull(invalid.fileWatchConfigurationError());
        assertTrue(invalid.fileWatchConfigurationError().contains("true 或 false"));
    }

    @Test
    void loadsAndNormalizesCompactionSettings(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.toml"), """
                [agent.compaction]
                enabled = true
                triggerMessages = 12
                triggerTokens = 20000
                keepMessages = 4
                keepTokens = 5000
                """, StandardCharsets.UTF_8);

        withConfigDir(dir, () -> {
            Config c = Config.load(dir);
            assertTrue(c.agentCompactionEnabled());
            assertEquals(12, c.agentCompactionTriggerMessages());
            assertEquals(20000, c.agentCompactionTriggerTokens());
            assertEquals(4, c.agentCompactionKeepMessages());
            assertEquals(5000, c.agentCompactionKeepTokens());
        });
    }

    @Test
    void singleThresholdCompactionKeepsABoundedUsableCutoff(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("config.toml"), """
                [agent.compaction]
                enabled = true
                triggerMessages = 12
                triggerTokens = 0
                keepMessages = 999999
                keepTokens = 999999
                """, StandardCharsets.UTF_8);

        withConfigDir(dir, () -> {
            Config c = Config.load(dir);
            assertEquals(4, c.agentCompactionKeepMessages());
            assertEquals(0, c.agentCompactionKeepTokens(),
                    "message-only mode must use AgentScope's message cutoff");
        });

        Files.writeString(dir.resolve("config.toml"), """
                [agent.compaction]
                enabled = true
                triggerMessages = 0
                triggerTokens = 20000
                keepMessages = 999999
                keepTokens = 0
                """, StandardCharsets.UTF_8);

        withConfigDir(dir, () -> {
            Config c = Config.load(dir);
            assertEquals(10, c.agentCompactionKeepMessages());
            assertEquals(5000, c.agentCompactionKeepTokens(),
                    "token-only mode needs a positive cutoff below its trigger");
        });
    }

    @Test
    void removedFeatureKeysAreAcceptedButHaveNoRuntimeComponents(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("config.toml"), """
                [aw.ocr]
                engine = "paddle"
                [aw.audio]
                enabled = true
                """, StandardCharsets.UTF_8);

        withConfigDir(dir, () -> {
            Config config = Config.load(dir);
            assertNotNull(config);
            var componentNames = java.util.Arrays.stream(Config.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();
            assertFalse(componentNames.stream().anyMatch(name ->
                    name.toLowerCase().contains("ocr") || name.toLowerCase().contains("audio")));
        });
    }

    // ── TOML overlay load priority (SPEC-TOML-LOAD-002a) ──────────────────

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
    void configuredAwPortComesFromToml(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.toml"), "[aw]\nport = 45731\n",
                StandardCharsets.UTF_8);

        withConfigDir(dir, () -> {
            Config config = Config.load(dir);
            assertEquals(45731, config.awPort());
            assertEquals("http://localhost:45731/api/0", config.awBaseUrl());
        });
    }

    @Test
    void externalAwModeKeepsConfiguredBaseUrl(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.toml"), """
                [aw]
                mode = "external"
                port = 45731
                base-url = "http://example.test:5600/api/0"
                """, StandardCharsets.UTF_8);

        withConfigDir(dir, () ->
                assertEquals("http://example.test:5600/api/0", Config.load(dir).awBaseUrl()));
    }

    @Test
    void configTomlCanChooseMemoryDirectory(@TempDir Path dir) throws Exception {
        Path memoryDir = dir.resolve("自定义记忆");
        Files.writeString(dir.resolve("config.toml"),
                "memory.dir = '" + memoryDir + "'\n",
                StandardCharsets.UTF_8);

        withConfigDir(dir, () -> assertEquals(memoryDir, Config.load(dir).memoryDir()));
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

    @Test
    void defaultConfigDirTargetsPortableDataDirectory() {
        assertEquals(Path.of("./data/config"), Config.resolveConfigDir());
    }

    private static void withConfigDir(Path dir, ThrowingRunnable action) throws Exception {
        Files.createDirectories(dir);
        action.run();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
