package com.selfanalyst.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.selfanalyst.i18n.Lang;
import com.selfanalyst.i18n.LangResolver;
import com.selfanalyst.file.FileFilterConfig;

public record Config(
        String llmApiKey,
        String llmBaseUrl,
        String llmModel,
        String eventsBaseUrl,
        int eventsTimeout,
        Path memoryDir,
        boolean eventsEmbedded,
        int eventsPort,
        Path eventsDataDir,
        Path eventsRawDir,
        int eventsRawQueryMaxRangeDays,
        int eventsRawQueryMaxPageSize,
        long eventsRawLowDiskWarnBytes,
        long eventsRawLowDiskBlockBytes,
        RawIntegrityPolicy eventsRawIntegrityStartupScope,
        int eventsRawProjectorBatchSize,
        boolean wikiEnabled,
        boolean wikiBackfillEnabled,
        int wikiWorkerIntervalSeconds,
        int wikiPromptMaxContentChars,
        int wikiTopAppsLimit,
        boolean wikiSemanticEnabled,
        Path wikiSemanticIndexDir,
        int wikiSemanticTopK,
        boolean embeddingEnabled,
        String embeddingBaseUrl,
        String embeddingApiKey,
        String embeddingModel,
        int embeddingDimensions,
        boolean embeddingSendEncodingFormat,
        int titlePollIntervalMs,
        boolean webSearchEnabled,
        String webSearchMcpUrl,
        String webSearchApiKey,
        double llmTemperature,
        boolean collectWindow,
        boolean collectAfk,
        boolean collectTitle,
        boolean fileWatchEnabled,
        String fileWatchPaths,
        long fileWatchMaxFileSizeKb,
        int fileWatchWorkerIntervalSeconds,
        int fileWatchDebounceSeconds,
        int fileWatchHeartbeatThrottleSeconds,
        String fileWatchExtensions,
        String fileWatchExcludeDirs,
        String fileWatchExcludeGlobs,
        boolean fileWatchRespectGitIgnore,
        String fileWatchConfigurationError,
        /** Legacy content-index path retained only so startup can purge old artifacts. */
        Path legacyFileSemanticIndexDir,
        int llmMaxTokens,
        int agentMaxIters,
        boolean agentCompactionEnabled,
        int agentCompactionTriggerMessages,
        int agentCompactionTriggerTokens,
        int agentCompactionKeepMessages,
        int agentCompactionKeepTokens,
        int desktopSummaryMaxTimelineLlm,
        String budgetMode,
        long budgetDailyTokens,
        double budgetWarnRatio,
        String appLanguage) {

    private static final Logger log = LoggerFactory.getLogger(Config.class);

    public static Config load() {
        return load(resolveConfigDir());
    }

    public static Config load(Path configDir) {
        return load(configDir, System.getenv());
    }

    static Config load(Path configDir, Map<String, String> environment) {
        return ConfigResolver.resolve(loadUserConfig(configDir), environment).config();
    }

    static Config parse(ConfigResolver values) {
        Properties userProps = values.user();
        Properties props = values.properties();
        String memDir = values.memoryDir();

        String apiKey = values.get("llm.api-key", "")
                .replace("${OPENAI_API_KEY:CHANGE_ME}", "CHANGE_ME")
                .replace("${OPENAI_API_KEY:}", "");
        String baseUrl = values.get("llm.base-url",
                "https://api.openai.com/v1");
        String model = values.get("llm.model", "gpt-4o");
        double llmTemperature = parseDoubleOr(
                values.get("llm.temperature", "0.7"), 0.7);
        if (llmTemperature < 0 || llmTemperature > 2) {
            llmTemperature = 0.7;
        }
        String configuredEventsUrl = values.get("events.base-url",
                "http://localhost:5600/api/0");
        int eventsTimeout = Integer.parseInt(
                values.get("events.timeout", "15000"));

        boolean eventsEmbedded = "embedded".equalsIgnoreCase(
                values.get("events.mode", "embedded"));
        // config.toml is the single user-controlled source for the embedded server port.
        // The Tauri parent learns the effective value from the Java startup handshake.
        int eventsPort = Integer.parseInt(values.get("events.port", "5700"));
        String eventsUrl = eventsEmbedded
                ? "http://localhost:" + eventsPort + "/api/0"
                : configuredEventsUrl;
        Path eventsDataDir = Path.of(values.get("events.data-dir",
                memDir + "/events"));
        Path eventsRawDir = Path.of(values.explicit("events.raw.dir",
                eventsDataDir.resolve("raw").toString()));
        int eventsRawQueryMaxRangeDays = Integer.parseInt(values.get("events.raw.query.maxRangeDays", "31"));
        int eventsRawQueryMaxPageSize = Integer.parseInt(values.get("events.raw.query.maxPageSize", "1000"));
        long eventsRawLowDiskWarnBytes = Long.parseLong(values.get("events.raw.lowDisk.warnBytes", "10737418240"));
        long eventsRawLowDiskBlockBytes = Long.parseLong(values.get("events.raw.lowDisk.blockBytes", "1073741824"));
        RawIntegrityPolicy eventsRawIntegrityStartupScope = RawIntegrityPolicy.parse(
                values.get("events.raw.integrity.startupScope", "latest"));
        int eventsRawProjectorBatchSize = Integer.parseInt(values.get("events.raw.projector.batchSize", "1000"));
        RawConfigValidator.rejectUnsupportedRetentionKeys(userProps);
        RawConfigValidator.validate(eventsRawDir, eventsRawQueryMaxRangeDays,
                eventsRawQueryMaxPageSize, eventsRawLowDiskWarnBytes,
                eventsRawLowDiskBlockBytes, eventsRawIntegrityStartupScope,
                eventsRawProjectorBatchSize);

        boolean wikiEnabled = Boolean.parseBoolean(
                values.get("wiki.enabled", "false"));
        boolean wikiBackfillEnabled = Boolean.parseBoolean(
                values.get("wiki.backfill.enabled", "false"));
        int wikiWorkerIntervalSeconds = Integer.parseInt(
                values.get("wiki.worker.intervalSeconds", "60"));
        int wikiPromptMaxContentChars = Integer.parseInt(
                values.get("wiki.prompt.maxContentChars", "12000"));
        if (wikiPromptMaxContentChars < 1000) {
            wikiPromptMaxContentChars = 12000;
        }
        int wikiTopAppsLimit = Integer.parseInt(
                values.get("wiki.topApps.limit", "10"));

        boolean wikiSemanticEnabled = Boolean.parseBoolean(
                values.get("wiki.semantic.enabled", "true"));
        Path wikiSemanticIndexDir = Path.of(values.get("wiki.semantic.index-dir", memDir + "/wiki-semantic-index"));
        int wikiSemanticTopK = parseIntOr(props,
                values.get("wiki.semantic.topK", "8"), 8);
        if (wikiSemanticTopK < 1 || wikiSemanticTopK > 50) {
            wikiSemanticTopK = 8;
        }

        boolean embeddingEnabled = Boolean.parseBoolean(
                values.get("embedding.enabled", "false"));
        String embeddingBaseUrl = values.get("embedding.base-url",
                "https://api.openai.com/v1");
        String embeddingApiKey = values.get("embedding.api-key",
                apiKey)
                .replace("${EMBEDDING_API_KEY:}", "");
        if (embeddingApiKey == null || embeddingApiKey.isBlank()) {
            embeddingApiKey = apiKey;
        }
        String embeddingModel = values.get("embedding.model",
                "text-embedding-3-small");
        int embeddingDimensions = parseIntOr(props,
                values.get("embedding.dimensions", "1024"), 1024);
        if (embeddingDimensions <= 0 || embeddingDimensions > 1024) {
            embeddingDimensions = 1024;
        }
        boolean embeddingSendEncodingFormat = Boolean.parseBoolean(
                values.get("embedding.send-encoding-format", "true"));

        int titlePollIntervalMs = parseIntOr(props,
                values.get("events.collection.title.pollMs", "500"), 500);
        if (titlePollIntervalMs < 100 || titlePollIntervalMs > 10000) {
            titlePollIntervalMs = 500;
        }

        boolean collectWindow = Boolean.parseBoolean(
                values.get("events.collection.window", "true"));
        boolean collectAfk = Boolean.parseBoolean(
                values.get("events.collection.afk", "true"));
        boolean collectTitle = Boolean.parseBoolean(
                values.get("events.collection.title.enabled", "true"));
        // ── File Watch (SPEC-FILE-*) ──
        boolean fileWatchEnabled = Boolean.parseBoolean(
                values.get("file.watch.enabled", "false"));
        String fileWatchPaths = values.get("file.watch.paths", "");
        String fileWatchMaxFileSizeRaw = values.allowBlank("file.watch.maxFileSizeKb", "0");
        int fileWatchWorkerIntervalSeconds = parseIntOr(props,
                values.get("file.watch.worker.intervalSeconds", "60"), 60);
        int fileWatchDebounceSeconds = parseIntOr(props,
                values.get("file.watch.debounceSeconds", "5"), 5);
        int fileWatchHeartbeatThrottleSeconds = parseIntOr(props,
                values.get("file.watch.heartbeatThrottleSeconds", "5"), 5);
        String fileWatchExtensions = values.allowBlank("file.watch.extensions", FileFilterConfig.DEFAULT_EXTENSIONS_CSV);
        String fileWatchExcludeDirs = values.get("file.watch.excludeDirs", "");
        String fileWatchExcludeGlobs = values.get("file.watch.excludeGlobs", "");
        String fileWatchRespectGitIgnoreRaw = values.allowBlank("file.watch.respectGitIgnore", "true");
        long fileWatchMaxFileSizeKb = 0;
        boolean fileWatchRespectGitIgnore = true;
        String fileWatchConfigurationError = null;
        try {
            fileWatchMaxFileSizeKb = Long.parseLong(fileWatchMaxFileSizeRaw.trim());
            fileWatchRespectGitIgnore = parseBooleanStrict(
                    fileWatchRespectGitIgnoreRaw, "file.watch.respectGitIgnore");
            FileFilterConfig.parse(fileWatchMaxFileSizeKb,
                    FileFilterConfig.splitCsv(fileWatchExcludeDirs),
                    FileFilterConfig.splitCsv(fileWatchExcludeGlobs),
                    FileFilterConfig.splitCsv(fileWatchExtensions),
                    fileWatchRespectGitIgnore);
        } catch (RuntimeException invalidFileFilter) {
            fileWatchConfigurationError = invalidFileFilter.getMessage();
        }
        Path legacyFileSemanticIndexDir = Path.of(values.get("file.watch.semantic.index-dir", memDir + "/file-semantic-index"));

        // ── Token 用量限制 / 预算 (SPEC-BUDGET-*) ──
        int llmMaxTokens = parseIntOr(props,
                values.get("llm.max-tokens", "2048"), 2048);
        if (llmMaxTokens < 0) llmMaxTokens = 0; // 0 = 不限
        int agentMaxIters = parseIntOr(props,
                values.get("llm.agent.maxIters", "8"), 8);
        if (agentMaxIters < 1) agentMaxIters = 8;
        boolean agentCompactionEnabled = Boolean.parseBoolean(
                values.get("agent.compaction.enabled", "true"));
        int agentCompactionTriggerMessages = parseIntOr(props,
                values.get("agent.compaction.triggerMessages", "30"), 30);
        if (agentCompactionTriggerMessages < 0
                || (agentCompactionTriggerMessages > 0 && agentCompactionTriggerMessages < 3)
                || agentCompactionTriggerMessages > 10000) {
            agentCompactionTriggerMessages = 30;
        }
        int agentCompactionTriggerTokens = parseIntOr(props,
                values.get("agent.compaction.triggerTokens", "60000"), 60000);
        if (agentCompactionTriggerTokens < 0
                || (agentCompactionTriggerTokens > 0 && agentCompactionTriggerTokens < 4000)) {
            agentCompactionTriggerTokens = 60000;
        }
        if (agentCompactionEnabled
                && agentCompactionTriggerMessages == 0
                && agentCompactionTriggerTokens == 0) {
            agentCompactionTriggerMessages = 30;
            agentCompactionTriggerTokens = 60000;
        }
        int agentCompactionKeepMessages = parseIntOr(props,
                values.get("agent.compaction.keepMessages", "10"), 10);
        if (agentCompactionKeepMessages < 2
                || agentCompactionKeepMessages > 100
                || (agentCompactionTriggerMessages > 0
                    && agentCompactionKeepMessages >= agentCompactionTriggerMessages)) {
            agentCompactionKeepMessages = agentCompactionTriggerMessages > 3
                    ? Math.min(100, Math.max(2, agentCompactionTriggerMessages / 3))
                    : agentCompactionTriggerMessages == 3 ? 2 : 10;
        }
        int agentCompactionKeepTokens = parseIntOr(props,
                values.get("agent.compaction.keepTokens", "12000"), 12000);
        if (agentCompactionTriggerTokens == 0) {
            // AgentScope chooses token-based retention whenever keepTokens > 0. In a
            // message-only configuration that can make an oversized token window yield a
            // zero cutoff forever, so use the bounded message window explicitly.
            agentCompactionKeepTokens = 0;
        } else {
            int safeTokenWindow = Math.min(12_000,
                    Math.max(1_000, agentCompactionTriggerTokens / 4));
            if (agentCompactionKeepTokens < 0
                    || agentCompactionKeepTokens > 64_000
                    || agentCompactionKeepTokens >= agentCompactionTriggerTokens
                    || (agentCompactionTriggerMessages == 0
                        && agentCompactionKeepTokens == 0)
                    || (agentCompactionKeepTokens > 0
                        && agentCompactionKeepTokens < 1_000)) {
                agentCompactionKeepTokens = safeTokenWindow;
            }
        }
        int desktopSummaryMaxTimelineLlm = parseIntOr(props,
                values.get("desktop.summary.maxTimelineLlm", "4"), 4);
        if (desktopSummaryMaxTimelineLlm < 0) desktopSummaryMaxTimelineLlm = 0;
        String budgetMode = values.get("llm.budget.mode", "warn")
                .trim().toLowerCase();
        if (!budgetMode.equals("off") && !budgetMode.equals("warn") && !budgetMode.equals("block")) {
            budgetMode = "warn";
        }
        long budgetDailyTokens = parseLongOr(
                values.get("llm.budget.dailyTokens", "100000000"), 100000000L);
        if (budgetDailyTokens < 0) budgetDailyTokens = 0; // 0 = 不限
        double budgetWarnRatio = parseDoubleOr(
                values.get("llm.budget.warnRatio", "0.8"), 0.8);
        if (budgetWarnRatio <= 0 || budgetWarnRatio > 1) budgetWarnRatio = 0.8;

        boolean webSearchEnabled = Boolean.parseBoolean(
                values.get("websearch.enabled", "false"));
        String webSearchMcpUrl = values.get("websearch.mcp-url",
                "https://search.parallel.ai/mcp");
        String webSearchApiKey = values.get("websearch.api-key", "");

        // ── 应用语言 (SPEC-I18N-CFG-001) ──
        String appLanguage = values.get("app.language", "auto");

        return new Config(apiKey, baseUrl, model, eventsUrl, eventsTimeout,
                Path.of(memDir), eventsEmbedded, eventsPort, eventsDataDir,
                eventsRawDir, eventsRawQueryMaxRangeDays, eventsRawQueryMaxPageSize,
                eventsRawLowDiskWarnBytes, eventsRawLowDiskBlockBytes,
                eventsRawIntegrityStartupScope, eventsRawProjectorBatchSize,
                wikiEnabled, wikiBackfillEnabled, wikiWorkerIntervalSeconds,
                wikiPromptMaxContentChars, wikiTopAppsLimit,
                wikiSemanticEnabled, wikiSemanticIndexDir, wikiSemanticTopK,
                embeddingEnabled, embeddingBaseUrl, embeddingApiKey,
                embeddingModel, embeddingDimensions,
                embeddingSendEncodingFormat, titlePollIntervalMs,
                webSearchEnabled, webSearchMcpUrl, webSearchApiKey,
                llmTemperature, collectWindow, collectAfk, collectTitle,
                fileWatchEnabled, fileWatchPaths, fileWatchMaxFileSizeKb,
                fileWatchWorkerIntervalSeconds, fileWatchDebounceSeconds,
                fileWatchHeartbeatThrottleSeconds, fileWatchExtensions,
                fileWatchExcludeDirs, fileWatchExcludeGlobs, fileWatchRespectGitIgnore,
                fileWatchConfigurationError,
                legacyFileSemanticIndexDir,
                llmMaxTokens, agentMaxIters,
                agentCompactionEnabled, agentCompactionTriggerMessages,
                agentCompactionTriggerTokens, agentCompactionKeepMessages,
                agentCompactionKeepTokens, desktopSummaryMaxTimelineLlm,
                budgetMode, budgetDailyTokens, budgetWarnRatio,
                appLanguage);
    }

    /**
     * 有效语言（派生，不入构造）：由启动期 {@code app.language} 与系统 Locale
     * 唯一解析（SPEC-I18N-RES-002）。变更 {@code app.language} 需重启后端才生效
     * （SPEC-I18N-DEC-007）。
     */
    public Lang effectiveLanguage() {
        return LangResolver.resolve(appLanguage, Locale.getDefault());
    }

    /**
     * Build a Config with sane defaults for tests, deriving all directory
     * paths from {@code baseDir} (typically a temp dir). Collectors are
     * disabled and the API key is blank so nothing reaches the network.
     *
     * <p>Centralizing test construction here means new record components only
     * need a default added in one place, rather than fixing every hand-written
     * {@code new Config(...)} call across the test modules.
     */
    public static Config testDefaults(Path baseDir) {
        return new Config(
                "", "https://api.openai.com/v1", "gpt-4o",
                "http://localhost:5600/api/0", 15000, baseDir.resolve("memory"),
                false, 5600, baseDir.resolve("events"),
                baseDir.resolve("events/raw"), 31, 1000,
                10_737_418_240L, 1_073_741_824L,
                RawIntegrityPolicy.LATEST, 1000,
                false, false, 60, 12000, 10,
                false, baseDir.resolve("wiki-semantic-index"), 8,
                false, "", "", "", 1024, true, 500,
                false, "https://search.parallel.ai/mcp", "",
                0.7, false, false, false,
                false, "", 0, 60, 5, 5, FileFilterConfig.DEFAULT_EXTENSIONS_CSV,
                "", "", true, null,
                baseDir.resolve("file-semantic-index"),
                2048, 8, false, 30, 60000, 10, 12000,
                4, "warn", 100000000L, 0.8,
                "auto");
    }

    /** Load the classpath {@code application.properties} defaults (empty if absent). */
    static Properties loadClasspathProps() {
        Properties props = new Properties();
        try (InputStream in = Config.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {}
        return props;
    }

    /** Portable user configuration directory, relative to the executable working directory. */
    public static Path resolveConfigDir() {
        return Path.of("./data/config");
    }

    /**
     * Overlay {@code config.toml} from the portable config directory. A TOML parse failure at runtime is
     * logged and skipped — startup must not crash on a hand-broken file; the raw
     * editor is the strict gate. SPEC-TOML-LOAD-002a, SPEC-TOML-DEC-001. Package
     * visibility for load-priority tests.
     */
    static void overlayUserConfig(Properties props, Path configDir) {
        props.putAll(loadUserConfig(configDir));
    }

    private static Properties loadUserConfig(Path configDir) {
        Properties user = new Properties();
        Path toml = configDir.resolve("config.toml");
        if (Files.exists(toml)) {
            try {
                TomlSupport.parseAndFlatten(Files.readString(toml, StandardCharsets.UTF_8))
                        .forEach(user::setProperty);
            } catch (IOException | RuntimeException e) {
                log.warn("跳过无法解析的 {}: {}", toml, e.getMessage());
            }
        }
        return user;
    }

    private static int parseIntOr(Properties props, String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long parseLongOr(String value, long defaultValue) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double parseDoubleOr(String value, double defaultValue) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean parseBooleanStrict(String value, String key) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(key + " 必须是 true 或 false");
    }

    public void validate() {
        if (llmApiKey == null || llmApiKey.isBlank() || llmApiKey.equals("CHANGE_ME")) {
            throw new IllegalStateException(
                    "LLM API key not configured. Set OPENAI_API_KEY or llm.api-key in ./data/config/config.toml");
        }
    }
}
