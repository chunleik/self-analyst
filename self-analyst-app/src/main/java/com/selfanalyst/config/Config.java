package com.selfanalyst.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.selfanalyst.i18n.Lang;
import com.selfanalyst.i18n.LangResolver;
import com.selfanalyst.file.FileFilterConfig;

public record Config(
        String llmApiKey,
        String llmBaseUrl,
        String llmModel,
        String awBaseUrl,
        int awTimeout,
        Path memoryDir,
        boolean awEmbedded,
        int awPort,
        Path awDataDir,
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
        int contentPollIntervalMs,
        boolean webSearchEnabled,
        String webSearchMcpUrl,
        String webSearchApiKey,
        double llmTemperature,
        boolean collectWindow,
        boolean collectAfk,
        boolean collectContent,
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

    static Config load(Path configDir) {
        Properties props = loadClasspathProps();
        String defaultAwPort = props.getProperty("aw.port", "5700");

        // The portable config location is independent from memory.dir, so config.toml
        // can choose where memory and indexes are stored without a bootstrap cycle.
        props.setProperty("aw.port", defaultAwPort);
        overlayUserConfig(props, configDir);
        String memDir = memoryDirOf(props);

        String apiKey = envOrProp(props, "llm.api-key", "OPENAI_API_KEY", "")
                .replace("${OPENAI_API_KEY:CHANGE_ME}", "CHANGE_ME")
                .replace("${OPENAI_API_KEY:}", "");
        String baseUrl = envOrProp(props, "llm.base-url", "LLM_BASE_URL",
                "https://api.openai.com/v1");
        String model = envOrProp(props, "llm.model", "LLM_MODEL", "gpt-4o");
        double llmTemperature = parseDoubleOr(
                envOrProp(props, "llm.temperature", "LLM_TEMPERATURE", "0.7"), 0.7);
        if (llmTemperature < 0 || llmTemperature > 2) {
            llmTemperature = 0.7;
        }
        String configuredAwUrl = envOrProp(props, "aw.base-url", "AW_BASE_URL",
                "http://localhost:5600/api/0");
        int awTimeout = Integer.parseInt(
                envOrProp(props, "aw.timeout", "AW_TIMEOUT", "15000"));

        boolean awEmbedded = "embedded".equalsIgnoreCase(
                envOrProp(props, "aw.mode", "AW_MODE", "embedded"));
        // config.toml is the single user-controlled source for the embedded server port.
        // The Tauri parent learns the effective value from the Java startup handshake.
        int awPort = Integer.parseInt(props.getProperty("aw.port", "5700"));
        String awUrl = awEmbedded
                ? "http://localhost:" + awPort + "/api/0"
                : configuredAwUrl;
        Path awDataDir = Path.of(envOrProp(props, "aw.data-dir", "AW_DATA_DIR",
                memDir + "/aw-data"));

        boolean wikiEnabled = Boolean.parseBoolean(
                envOrProp(props, "wiki.enabled", "WIKI_ENABLED", "false"));
        boolean wikiBackfillEnabled = Boolean.parseBoolean(
                envOrProp(props, "wiki.backfill.enabled", "WIKI_BACKFILL_ENABLED", "false"));
        int wikiWorkerIntervalSeconds = Integer.parseInt(
                envOrProp(props, "wiki.worker.intervalSeconds", "WIKI_WORKER_INTERVAL_SECONDS", "60"));
        int wikiPromptMaxContentChars = Integer.parseInt(
                envOrProp(props, "wiki.prompt.maxContentChars", "WIKI_PROMPT_MAX_CONTENT_CHARS", "12000"));
        if (wikiPromptMaxContentChars < 1000) {
            wikiPromptMaxContentChars = 12000;
        }
        int wikiTopAppsLimit = Integer.parseInt(
                envOrProp(props, "wiki.topApps.limit", "WIKI_TOP_APPS_LIMIT", "10"));

        boolean wikiSemanticEnabled = Boolean.parseBoolean(
                envOrProp(props, "wiki.semantic.enabled", "WIKI_SEMANTIC_ENABLED", "true"));
        Path wikiSemanticIndexDir = Path.of(envOrProp(props, "wiki.semantic.index-dir",
                "WIKI_SEMANTIC_INDEX_DIR", memDir + "/wiki-semantic-index"));
        int wikiSemanticTopK = parseIntOr(props,
                envOrProp(props, "wiki.semantic.topK", "WIKI_SEMANTIC_TOP_K", "8"), 8);
        if (wikiSemanticTopK < 1 || wikiSemanticTopK > 50) {
            wikiSemanticTopK = 8;
        }

        boolean embeddingEnabled = Boolean.parseBoolean(
                envOrProp(props, "embedding.enabled", "EMBEDDING_ENABLED", "false"));
        String embeddingBaseUrl = envOrProp(props, "embedding.base-url", "EMBEDDING_BASE_URL",
                "https://api.openai.com/v1");
        String embeddingApiKey = envOrProp(props, "embedding.api-key", "EMBEDDING_API_KEY",
                System.getenv().getOrDefault("OPENAI_API_KEY", ""))
                .replace("${EMBEDDING_API_KEY:}", "");
        if (embeddingApiKey == null || embeddingApiKey.isBlank()) {
            embeddingApiKey = apiKey;
        }
        String embeddingModel = envOrProp(props, "embedding.model", "EMBEDDING_MODEL",
                "text-embedding-3-small");
        int embeddingDimensions = parseIntOr(props,
                envOrProp(props, "embedding.dimensions", "EMBEDDING_DIMENSIONS", "1024"), 1024);
        if (embeddingDimensions <= 0 || embeddingDimensions > 1024) {
            embeddingDimensions = 1024;
        }
        boolean embeddingSendEncodingFormat = Boolean.parseBoolean(
                envOrProp(props, "embedding.send-encoding-format", "EMBEDDING_SEND_ENCODING_FORMAT", "true"));

        int contentPollIntervalMs = parseIntOr(props,
                envOrProp(props, "aw.collection.content.pollMs", "AW_CONTENT_POLL_MS", "500"), 500);
        if (contentPollIntervalMs < 100 || contentPollIntervalMs > 10000) {
            contentPollIntervalMs = 500;
        }

        boolean collectWindow = Boolean.parseBoolean(
                envOrProp(props, "aw.collection.window", "AW_COLLECTION_WINDOW", "true"));
        boolean collectAfk = Boolean.parseBoolean(
                envOrProp(props, "aw.collection.afk", "AW_COLLECTION_AFK", "true"));
        boolean collectContent = Boolean.parseBoolean(
                envOrProp(props, "aw.collection.content", "AW_COLLECTION_CONTENT", "true"));
        // ── File Watch (SPEC-FILE-*) ──
        boolean fileWatchEnabled = Boolean.parseBoolean(
                envOrProp(props, "file.watch.enabled", "FILE_WATCH_ENABLED", "false"));
        String fileWatchPaths = envOrProp(props, "file.watch.paths", "FILE_WATCH_PATHS", "");
        String fileWatchMaxFileSizeRaw = envOrPropAllowBlank(props,
                "file.watch.maxFileSizeKb", "FILE_WATCH_MAX_FILE_SIZE_KB", "0");
        int fileWatchWorkerIntervalSeconds = parseIntOr(props,
                envOrProp(props, "file.watch.worker.intervalSeconds", "FILE_WATCH_WORKER_INTERVAL_SECONDS", "60"), 60);
        int fileWatchDebounceSeconds = parseIntOr(props,
                envOrProp(props, "file.watch.debounceSeconds", "FILE_WATCH_DEBOUNCE_SECONDS", "5"), 5);
        int fileWatchHeartbeatThrottleSeconds = parseIntOr(props,
                envOrProp(props, "file.watch.heartbeatThrottleSeconds", "FILE_WATCH_HEARTBEAT_THROTTLE_SECONDS", "5"), 5);
        String fileWatchExtensions = envOrPropAllowBlank(props, "file.watch.extensions",
                "FILE_WATCH_EXTENSIONS", FileFilterConfig.DEFAULT_EXTENSIONS_CSV);
        String fileWatchExcludeDirs = envOrProp(props, "file.watch.excludeDirs", "FILE_WATCH_EXCLUDE_DIRS", "");
        String fileWatchExcludeGlobs = envOrProp(props, "file.watch.excludeGlobs", "FILE_WATCH_EXCLUDE_GLOBS", "");
        String fileWatchRespectGitIgnoreRaw = envOrPropAllowBlank(props,
                "file.watch.respectGitIgnore", "FILE_WATCH_RESPECT_GITIGNORE", "true");
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
        Path legacyFileSemanticIndexDir = Path.of(envOrProp(props, "file.watch.semantic.index-dir",
                "FILE_WATCH_SEMANTIC_INDEX_DIR", memDir + "/file-semantic-index"));

        // ── Token 用量限制 / 预算 (SPEC-BUDGET-*) ──
        int llmMaxTokens = parseIntOr(props,
                envOrProp(props, "llm.max-tokens", "LLM_MAX_TOKENS", "2048"), 2048);
        if (llmMaxTokens < 0) llmMaxTokens = 0; // 0 = 不限
        int agentMaxIters = parseIntOr(props,
                envOrProp(props, "llm.agent.maxIters", "LLM_AGENT_MAX_ITERS", "8"), 8);
        if (agentMaxIters < 1) agentMaxIters = 8;
        boolean agentCompactionEnabled = Boolean.parseBoolean(
                envOrProp(props, "agent.compaction.enabled", "AGENT_COMPACTION_ENABLED", "true"));
        int agentCompactionTriggerMessages = parseIntOr(props,
                envOrProp(props, "agent.compaction.triggerMessages",
                        "AGENT_COMPACTION_TRIGGER_MESSAGES", "30"), 30);
        if (agentCompactionTriggerMessages < 0
                || (agentCompactionTriggerMessages > 0 && agentCompactionTriggerMessages < 3)
                || agentCompactionTriggerMessages > 10000) {
            agentCompactionTriggerMessages = 30;
        }
        int agentCompactionTriggerTokens = parseIntOr(props,
                envOrProp(props, "agent.compaction.triggerTokens",
                        "AGENT_COMPACTION_TRIGGER_TOKENS", "60000"), 60000);
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
                envOrProp(props, "agent.compaction.keepMessages",
                        "AGENT_COMPACTION_KEEP_MESSAGES", "10"), 10);
        if (agentCompactionKeepMessages < 2
                || agentCompactionKeepMessages > 100
                || (agentCompactionTriggerMessages > 0
                    && agentCompactionKeepMessages >= agentCompactionTriggerMessages)) {
            agentCompactionKeepMessages = agentCompactionTriggerMessages > 3
                    ? Math.min(100, Math.max(2, agentCompactionTriggerMessages / 3))
                    : agentCompactionTriggerMessages == 3 ? 2 : 10;
        }
        int agentCompactionKeepTokens = parseIntOr(props,
                envOrProp(props, "agent.compaction.keepTokens",
                        "AGENT_COMPACTION_KEEP_TOKENS", "12000"), 12000);
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
                envOrProp(props, "desktop.summary.maxTimelineLlm", "DESKTOP_SUMMARY_MAX_TIMELINE_LLM", "4"), 4);
        if (desktopSummaryMaxTimelineLlm < 0) desktopSummaryMaxTimelineLlm = 0;
        String budgetMode = envOrProp(props, "llm.budget.mode", "LLM_BUDGET_MODE", "warn")
                .trim().toLowerCase();
        if (!budgetMode.equals("off") && !budgetMode.equals("warn") && !budgetMode.equals("block")) {
            budgetMode = "warn";
        }
        long budgetDailyTokens = parseLongOr(
                envOrProp(props, "llm.budget.dailyTokens", "LLM_BUDGET_DAILY_TOKENS", "100000000"), 100000000L);
        if (budgetDailyTokens < 0) budgetDailyTokens = 0; // 0 = 不限
        double budgetWarnRatio = parseDoubleOr(
                envOrProp(props, "llm.budget.warnRatio", "LLM_BUDGET_WARN_RATIO", "0.8"), 0.8);
        if (budgetWarnRatio <= 0 || budgetWarnRatio > 1) budgetWarnRatio = 0.8;

        boolean webSearchEnabled = Boolean.parseBoolean(
                envOrProp(props, "websearch.enabled", "WEBSEARCH_ENABLED", "false"));
        String webSearchMcpUrl = envOrProp(props, "websearch.mcp-url", "WEBSEARCH_MCP_URL",
                "https://search.parallel.ai/mcp");
        String webSearchApiKey = envOrProp(props, "websearch.api-key", "WEBSEARCH_API_KEY", "");

        // ── 应用语言 (SPEC-I18N-CFG-001) ──
        String appLanguage = envOrProp(props, "app.language", "APP_LANGUAGE", "auto");

        return new Config(apiKey, baseUrl, model, awUrl, awTimeout,
                Path.of(memDir), awEmbedded, awPort, awDataDir,
                wikiEnabled, wikiBackfillEnabled, wikiWorkerIntervalSeconds,
                wikiPromptMaxContentChars, wikiTopAppsLimit,
                wikiSemanticEnabled, wikiSemanticIndexDir, wikiSemanticTopK,
                embeddingEnabled, embeddingBaseUrl, embeddingApiKey,
                embeddingModel, embeddingDimensions,
                embeddingSendEncodingFormat, contentPollIntervalMs,
                webSearchEnabled, webSearchMcpUrl, webSearchApiKey,
                llmTemperature, collectWindow, collectAfk, collectContent,
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
                false, 5600, baseDir.resolve("aw-data"),
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
    private static Properties loadClasspathProps() {
        Properties props = new Properties();
        try (InputStream in = Config.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {}
        return props;
    }

    /** Resolve memory.dir: system property > env {@code MEMORY_DIR} > user TOML > classpath default. */
    private static String memoryDirOf(Properties props) {
        String systemProp = System.getProperty("memory.dir");
        if (systemProp != null && !systemProp.isBlank()) {
            return systemProp;
        }
        return envOrProp(props, "memory.dir", "MEMORY_DIR",
                System.getProperty("user.home") + "/.self-analyst");
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
        Path toml = configDir.resolve("config.toml");
        if (Files.exists(toml)) {
            try {
                props.putAll(TomlSupport.parseAndFlatten(
                        Files.readString(toml, StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                log.warn("跳过无法解析的 {}: {}", toml, e.getMessage());
            }
        }
    }

    private static String envOrProp(Properties props, String propKey,
                                     String envKey, String defaultValue) {
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) return env;
        String prop = props.getProperty(propKey, defaultValue);
        if (prop != null && !prop.isBlank()) {
            return prop.replace("${user.home}", System.getProperty("user.home"));
        }
        return defaultValue;
    }

    /** Variant used by fail-closed settings where an explicit blank is meaningful. */
    private static String envOrPropAllowBlank(Properties props, String propKey,
                                               String envKey, String defaultValue) {
        String env = System.getenv(envKey);
        if (env != null) return env;
        if (props.containsKey(propKey)) return props.getProperty(propKey, "");
        return defaultValue;
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
