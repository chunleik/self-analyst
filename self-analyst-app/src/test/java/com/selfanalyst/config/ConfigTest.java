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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @Test
    void explicitTomlWinsAndDeletionRestoresEnvironment(@TempDir Path dir) throws Exception {
        var environment = java.util.Map.of(
                "OPENAI_API_KEY", "env-key", "LLM_MODEL", "env-model",
                "LLM_BASE_URL", "https://env.invalid/v1", "LLM_TEMPERATURE", "0.9",
                "EMBEDDING_API_KEY", "env-embedding", "APP_LANGUAGE", "en",
                "FILE_WATCH_EXTENSIONS", "md");
        Path file = dir.resolve("config.toml");
        Files.writeString(file, """
                [llm]
                api-key = "toml-key"
                model = "toml-model"
                base-url = "https://toml.invalid/v1"
                temperature = 0.3
                [embedding]
                api-key = "toml-embedding"
                [app]
                language = "zh"
                [file.watch]
                extensions = []
                """);
        Config configured = Config.load(dir, environment);
        assertEquals("toml-key", configured.llmApiKey());
        assertEquals("toml-model", configured.llmModel());
        assertEquals("https://toml.invalid/v1", configured.llmBaseUrl());
        assertEquals(0.3, configured.llmTemperature());
        assertEquals("toml-embedding", configured.embeddingApiKey());
        assertEquals("zh", configured.appLanguage());
        assertEquals("", configured.fileWatchExtensions());

        Files.writeString(file, "");
        Config fallback = Config.load(dir, environment);
        assertEquals("env-key", fallback.llmApiKey());
        assertEquals("env-model", fallback.llmModel());
        assertEquals("https://env.invalid/v1", fallback.llmBaseUrl());
        assertEquals(0.9, fallback.llmTemperature());
        assertEquals("env-embedding", fallback.embeddingApiKey());
        assertEquals("en", fallback.appLanguage());
        assertEquals("md", fallback.fileWatchExtensions());
        assertEquals("gpt-4o", Config.load(dir, java.util.Map.of()).llmModel());
    }

    @Test
    void embeddingFallbackUsesEffectiveLlmKey(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.toml"), "[llm]\napi-key = 'toml-key'\n");
        Config config = Config.load(dir, java.util.Map.of("OPENAI_API_KEY", "env-key"));
        assertEquals("toml-key", config.embeddingApiKey());
    }

    @Test
    void explicitBlankKeyDoesNotUseEnvironment(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.toml"), "[llm]\napi-key = ''\n");
        Config config = Config.load(dir, java.util.Map.of("OPENAI_API_KEY", "env-key"));
        assertEquals("", config.llmApiKey());
    }

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
        assertEquals("./data/events", props.getProperty("aw.data-dir"));
        assertEquals("./data/events/raw", props.getProperty("aw.raw.dir"));
        assertEquals("31", props.getProperty("aw.raw.query.maxRangeDays"));
        assertEquals("1000", props.getProperty("aw.raw.query.maxPageSize"));
        assertEquals("10737418240", props.getProperty("aw.raw.lowDisk.warnBytes"));
        assertEquals("1073741824", props.getProperty("aw.raw.lowDisk.blockBytes"));
        assertEquals("latest", props.getProperty("aw.raw.integrity.verifyOnStartup"));
        assertEquals("1000", props.getProperty("aw.raw.projector.batchSize"));
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
    void rawEventSettingsUseDefaultsAndParseTomlOverrides(@TempDir Path dir) throws Exception {
        Config defaults = Config.testDefaults(dir);
        assertEquals(dir.resolve("events"), defaults.awDataDir());
        assertEquals(dir.resolve("events/raw"), defaults.awRawDir());
        assertEquals(31, defaults.awRawQueryMaxRangeDays());
        assertEquals(1000, defaults.awRawQueryMaxPageSize());
        assertEquals(10_737_418_240L, defaults.awRawLowDiskWarnBytes());
        assertEquals(1_073_741_824L, defaults.awRawLowDiskBlockBytes());
        assertEquals(RawIntegrityPolicy.LATEST, defaults.awRawIntegrityVerifyOnStartup());
        assertEquals(1000, defaults.awRawProjectorBatchSize());

        Path rawDir = dir.resolve("永久原始事件");
        Files.writeString(dir.resolve("config.toml"), """
                [aw.raw]
                dir = '%s'
                [aw.raw.query]
                maxRangeDays = 7
                maxPageSize = 250
                [aw.raw.lowDisk]
                warnBytes = 8589934592
                blockBytes = 536870912
                [aw.raw.integrity]
                verifyOnStartup = "all"
                [aw.raw.projector]
                batchSize = 128
                """.formatted(rawDir),
                StandardCharsets.UTF_8);

        Config configured = Config.load(dir);
        assertEquals(rawDir, configured.awRawDir());
        assertEquals(7, configured.awRawQueryMaxRangeDays());
        assertEquals(250, configured.awRawQueryMaxPageSize());
        assertEquals(8_589_934_592L, configured.awRawLowDiskWarnBytes());
        assertEquals(536_870_912L, configured.awRawLowDiskBlockBytes());
        assertEquals(RawIntegrityPolicy.ALL, configured.awRawIntegrityVerifyOnStartup());
        assertEquals(128, configured.awRawProjectorBatchSize());
    }

    @Test
    void rawIntegrityPolicyAcceptsOnlyLatestAndAll() {
        assertEquals(RawIntegrityPolicy.LATEST, RawIntegrityPolicy.parse("latest"));
        assertEquals(RawIntegrityPolicy.ALL, RawIntegrityPolicy.parse("ALL"));
        assertThrows(IllegalArgumentException.class, () -> RawIntegrityPolicy.parse("none"));
    }

    @Test
    void rawDirectoryDefaultsUnderConfiguredAwDataDirectory(@TempDir Path dir) throws Exception {
        Path awDir = dir.resolve("自定义-aw");
        Files.writeString(dir.resolve("config.toml"),
                "[aw]\ndata-dir = '" + awDir + "'\n", StandardCharsets.UTF_8);

        Config configured = Config.load(dir);

        assertEquals(awDir.resolve("raw"), configured.awRawDir());
    }

    @Test
    void invalidRawRuntimeSettingsAreRejected(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.toml"), """
                [aw.raw.lowDisk]
                warnBytes = 1024
                blockBytes = 1024
                """, StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Config.load(dir));
        assertTrue(error.getMessage().contains("blockBytes"), error.getMessage());
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
