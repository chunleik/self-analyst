package com.selfanalyst.config;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigResolverTest {

    @Test void everyRemovedKeyAndEnvironmentNameIsRejectedBeforeFallback() {
        for (EventSetting setting : eventSettings()) {
            String oldKey = switch (setting.key()) {
                case "events.collection.title.enabled" -> "aw.collection.content";
                case "events.collection.title.pollMs" -> "aw.collection.content.pollMs";
                case "events.raw.integrity.startupScope" -> "aw.raw.integrity.verifyOnStartup";
                default -> "aw." + setting.key().substring("events.".length());
            };
            Properties user = new Properties();
            user.setProperty(oldKey, "private-value");
            user.setProperty(setting.key(), setting.tomlValue());
            var error = assertThrows(TomlValidationException.class,
                    () -> ConfigResolver.resolve(user, Map.of()), oldKey);
            assertTrue(error.getMessage().contains(oldKey));
            assertTrue(error.getMessage().contains(setting.key()));
            assertFalse(error.getMessage().contains("private-value"));
            if (setting.env() != null) {
                String oldEnv = switch (setting.env()) {
                    case "EVENTS_COLLECTION_TITLE_ENABLED" -> "AW_COLLECTION_CONTENT";
                    case "EVENTS_COLLECTION_TITLE_POLL_MS" -> "AW_CONTENT_POLL_MS";
                    case "EVENTS_RAW_INTEGRITY_STARTUP_SCOPE" -> "AW_RAW_INTEGRITY_VERIFY_ON_STARTUP";
                    default -> "AW_" + setting.env().substring("EVENTS_".length());
                };
                user.remove(oldKey);
                error = assertThrows(TomlValidationException.class,
                        () -> ConfigResolver.resolve(user, Map.of(oldEnv, "", setting.env(), setting.environmentValue())), oldEnv);
                assertTrue(error.getMessage().contains(oldEnv));
                assertTrue(error.getMessage().contains(setting.env()));
            }
        }
    }

    @Test void legacyRemovedFeaturesAndUnrelatedEnvironmentAreNotRenameErrors() {
        Properties user = new Properties();
        user.setProperty("aw.ocr.engine", "unused");
        user.setProperty("aw.audio.enabled", "true");
        user.setProperty("unknown.setting", "value");
        var snapshot = ConfigResolver.resolve(user, Map.of("AW_INSTALLED", "1", "AW_URL", "third-party"));
        assertFalse(snapshot.publicValues().containsKey("aw.ocr.engine"));
        assertFalse(snapshot.publicValues().containsKey("aw.audio.enabled"));
        for (String prefix : List.of("aw.raw.", "events.raw.")) {
            for (String suffix : List.of("enabled", "retentionDays", "maxPartitions", "autoDelete")) {
                Properties invalid = new Properties();
                invalid.setProperty(prefix + suffix, "false");
                assertThrows(IllegalArgumentException.class, () -> ConfigResolver.resolve(invalid, Map.of()));
            }
        }
    }

    private record EventSetting(String key, String env, String environmentValue, String tomlValue,
                                java.util.function.Function<Config, Object> read) {}

    private static java.util.List<EventSetting> eventSettings() {
        return java.util.List.of(
                new EventSetting("events.mode", "EVENTS_MODE", "embedded", "external", c -> c.eventsEmbedded() ? "embedded" : "external"),
                new EventSetting("events.port", null, "5700", "5810", Config::eventsPort),
                new EventSetting("events.base-url", "EVENTS_BASE_URL", "http://localhost:5981/api/0", "http://localhost:5982/api/0", Config::eventsBaseUrl),
                new EventSetting("events.timeout", "EVENTS_TIMEOUT", "16001", "16002", Config::eventsTimeout),
                new EventSetting("events.data-dir", "EVENTS_DATA_DIR", "env-events", "toml-events", Config::eventsDataDir),
                new EventSetting("events.raw.dir", "EVENTS_RAW_DIR", "env-raw", "toml-raw", Config::eventsRawDir),
                new EventSetting("events.raw.query.maxRangeDays", "EVENTS_RAW_QUERY_MAX_RANGE_DAYS", "32", "33", Config::eventsRawQueryMaxRangeDays),
                new EventSetting("events.raw.query.maxPageSize", "EVENTS_RAW_QUERY_MAX_PAGE_SIZE", "101", "102", Config::eventsRawQueryMaxPageSize),
                new EventSetting("events.raw.lowDisk.warnBytes", "EVENTS_RAW_LOW_DISK_WARN_BYTES", "20000000000", "21000000000", Config::eventsRawLowDiskWarnBytes),
                new EventSetting("events.raw.lowDisk.blockBytes", "EVENTS_RAW_LOW_DISK_BLOCK_BYTES", "1001", "1002", Config::eventsRawLowDiskBlockBytes),
                new EventSetting("events.raw.integrity.startupScope", "EVENTS_RAW_INTEGRITY_STARTUP_SCOPE", "all", "latest", c -> c.eventsRawIntegrityStartupScope().configValue()),
                new EventSetting("events.raw.projector.batchSize", "EVENTS_RAW_PROJECTOR_BATCH_SIZE", "111", "112", Config::eventsRawProjectorBatchSize),
                new EventSetting("events.collection.window", "EVENTS_COLLECTION_WINDOW", "false", "true", Config::collectWindow),
                new EventSetting("events.collection.afk", "EVENTS_COLLECTION_AFK", "false", "true", Config::collectAfk),
                new EventSetting("events.collection.title.enabled", "EVENTS_COLLECTION_TITLE_ENABLED", "false", "true", Config::collectTitle),
                new EventSetting("events.collection.title.pollMs", "EVENTS_COLLECTION_TITLE_POLL_MS", "700", "800", Config::titlePollIntervalMs));
    }

    @Test void eventSettingsReachRuntimeFromTomlAndEnvironment() {
        for (EventSetting setting : eventSettings()) {
            Properties user = new Properties();
            // 外部模式才能观察配置的 base-url；其余测试不依赖模式派生。
            if (setting.key().equals("events.base-url")) user.setProperty("events.mode", "external");
            Map<String, String> environment = new HashMap<>();
            environment.put("EVENTS_PORT", "5900");
            environment.put("AW_PORT", "5901");
            if (setting.env() != null) environment.put(setting.env(), setting.environmentValue());
            var fallback = ConfigResolver.resolve(user, environment);
            assertEquals(setting.environmentValue(), setting.read().apply(fallback.config()).toString(), setting.key());
            assertEquals(setting.env() == null ? "default" : "environment",
                    fallback.values().get(setting.key()).source(), setting.key());
            user.setProperty(setting.key(), setting.tomlValue());
            var overridden = ConfigResolver.resolve(user, environment);
            assertEquals(setting.tomlValue(), setting.read().apply(overridden.config()).toString(), setting.key());
            assertEquals("toml", overridden.values().get(setting.key()).source(), setting.key());
            user.remove(setting.key());
            assertEquals(setting.environmentValue(),
                    setting.read().apply(ConfigResolver.resolve(user, environment).config()).toString(), setting.key());
        }
    }

    @Test void newEventMetadataAndPortInheritanceUseOnlyNewNames() {
        Properties user = new Properties();
        user.setProperty("events.port", "5810");
        var snapshot = ConfigResolver.resolve(user, Map.of());
        assertEquals("http://localhost:5810/api/0", snapshot.config().eventsBaseUrl());
        assertEquals("events.port", snapshot.values().get("events.base-url").inheritedFrom());
        assertEquals(16, SupportedKeys.defaults().keySet().stream().filter(k -> k.startsWith("events.")).count());
        assertFalse(snapshot.publicValues().keySet().stream().anyMatch(k -> k.startsWith("aw.")));
        for (EventSetting setting : eventSettings()) {
            assertTrue(ConfigPolicy.requiresRestart(setting.key()));
            assertEquals("events", ConfigPolicy.component(setting.key()));
        }
    }

    @Test void removedNamesRejectEmptyShadowedAndDuplicateInputsWithoutShowingValues() {
        Properties user = new Properties();
        user.setProperty("events.mode", "embedded");
        user.setProperty("aw.mode", "sensitive-old-value");
        var invalid = assertThrows(TomlValidationException.class,
                () -> ConfigResolver.resolve(user, Map.of("AW_MODE", "", "EVENTS_MODE", "external")));
        assertTrue(invalid.getMessage().contains("aw.mode"));
        assertTrue(invalid.getMessage().contains("events.mode"));
        assertTrue(invalid.getMessage().contains("AW_MODE"));
        assertTrue(invalid.getMessage().contains("EVENTS_MODE"));
        assertFalse(invalid.getMessage().contains("sensitive-old-value"));
        user.remove("aw.mode");
        assertThrows(TomlValidationException.class,
                () -> ConfigResolver.resolve(user, Map.of("AW_MODE", "")));
    }
    @Test void sourcesFollowPriorityAndDoNotRevealKeys() {
        Properties user = new Properties();
        user.setProperty("llm.api-key", "secret-toml");
        var snapshot = ConfigResolver.resolve(user, Map.of("OPENAI_API_KEY", "secret-env", "LLM_MODEL", "env-model"));
        assertEquals("secret-toml", snapshot.config().llmApiKey());
        assertEquals("toml", snapshot.values().get("llm.api-key").source());
        assertEquals("environment", snapshot.values().get("llm.model").source());
        assertEquals("inherited", snapshot.values().get("embedding.api-key").source());
        assertEquals("secret-toml", snapshot.values().get("embedding.api-key").value());
        assertFalse(snapshot.publicValues().toString().contains("secret-"));
    }
    @Test void blankAndRemovalAreDistinct() {
        Properties user = new Properties();
        user.setProperty("llm.api-key", "");
        assertEquals("", ConfigResolver.resolve(user, Map.of("OPENAI_API_KEY", "env")).config().llmApiKey());
        user.remove("llm.api-key");
        assertEquals("env", ConfigResolver.resolve(user, Map.of("OPENAI_API_KEY", "env")).config().llmApiKey());
    }
    @Test void runtimeSnapshotsContainNormalizedValues() {
        Properties user = new Properties();
        user.setProperty("embedding.dimensions", "2048");
        user.setProperty("llm.budget.mode", "invalid");
        var snapshot = ConfigResolver.resolve(user, Map.of());
        assertEquals("1024", snapshot.values().get("embedding.dimensions").value());
        assertEquals("warn", snapshot.values().get("llm.budget.mode").value());
    }
    @Test void policiesDistinguishHotAndStartupParameters() {
        ConfigPolicy.LLM.forEach(key -> assertFalse(ConfigPolicy.requiresRestart(key), key));
        for (String key : List.of("llm.agent.maxIters", "agent.compaction.triggerTokens", "llm.budget.mode", "events.port"))
            assertTrue(ConfigPolicy.requiresRestart(key), key);
        assertFalse(ConfigPolicy.requiresRestart("unknown.key"));
    }
}
