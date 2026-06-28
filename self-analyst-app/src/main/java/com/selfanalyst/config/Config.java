package com.selfanalyst.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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
        Path ocrSampleDir,
        String ocrExcludedApps,
        int ocrTitleStripHeight,
        boolean webSearchEnabled,
        String webSearchMcpUrl,
        String webSearchApiKey,
        double llmTemperature,
        boolean collectWindow,
        boolean collectAfk,
        boolean collectContent,
        boolean audioEnabled,
        boolean fileWatchEnabled,
        String fileWatchPaths,
        int fileWatchMaxFileSizeKb,
        int fileWatchMaxContentChars,
        int fileWatchWorkerIntervalSeconds,
        int fileWatchDebounceSeconds,
        int fileWatchMinReindexIntervalMinutes,
        int fileWatchHeartbeatThrottleSeconds,
        String fileWatchExtensions,
        String fileWatchExcludeDirs,
        String fileWatchExcludeGlobs,
        boolean fileWatchSemanticEnabled,
        Path fileSemanticIndexDir,
        int llmMaxTokens,
        int agentMaxIters,
        int desktopSummaryMaxTimelineLlm,
        String budgetMode,
        long budgetDailyTokens,
        double budgetWarnRatio) {

    public static Config load() {
        Properties props = new Properties();
        try (InputStream in = Config.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {}

        // Compute memory.dir early — needed to find user config saved by desktop UI
        String memDir = envOrProp(props, "memory.dir", "MEMORY_DIR",
                System.getProperty("user.home") + "/.self-analyst");

        // Overlay user config from ~/.self-analyst/config.properties (legacy)
        Path legacyConfig = Path.of(System.getProperty("user.home"), ".self-analyst", "config.properties");
        if (Files.exists(legacyConfig)) {
            Properties userProps = new Properties();
            try (Reader r = Files.newBufferedReader(legacyConfig, StandardCharsets.UTF_8)) {
                userProps.load(r);
                props.putAll(userProps);
            } catch (IOException ignored) {}
        }

        // Overlay user config from {memoryDir}/config.properties (desktop UI save target)
        Path desktopConfig = Path.of(memDir).resolve("config.properties");
        if (!desktopConfig.equals(legacyConfig) && Files.exists(desktopConfig)) {
            Properties desktopProps = new Properties();
            try (Reader r = Files.newBufferedReader(desktopConfig, StandardCharsets.UTF_8)) {
                desktopProps.load(r);
                props.putAll(desktopProps);
            } catch (IOException ignored) {}
        }

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
        String awUrl = envOrProp(props, "aw.base-url", "AW_BASE_URL",
                "http://localhost:5600/api/0");
        int awTimeout = Integer.parseInt(
                envOrProp(props, "aw.timeout", "AW_TIMEOUT", "15000"));

        boolean awEmbedded = "embedded".equalsIgnoreCase(
                envOrProp(props, "aw.mode", "AW_MODE", "embedded"));
        int awPort = Integer.parseInt(
                envOrProp(props, "aw.port", "AW_PORT", "5600"));
        Path awDataDir = Path.of(envOrProp(props, "aw.data-dir", "AW_DATA_DIR",
                memDir + "/aw-data"));

        boolean wikiEnabled = Boolean.parseBoolean(
                envOrProp(props, "wiki.enabled", "WIKI_ENABLED", "true"));
        boolean wikiBackfillEnabled = Boolean.parseBoolean(
                envOrProp(props, "wiki.backfill.enabled", "WIKI_BACKFILL_ENABLED", "true"));
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
                envOrProp(props, "embedding.enabled", "EMBEDDING_ENABLED", "true"));
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

        String ocrSampleDirStr = envOrProp(props, "ocr.sample.dir", "OCR_SAMPLE_DIR", "");
        Path ocrSampleDir = ocrSampleDirStr.isBlank()
                ? awDataDir.resolve("ocr-samples")
                : Path.of(ocrSampleDirStr);

        String ocrExcludedApps = envOrProp(props, "ocr.excluded.apps", "OCR_EXCLUDED_APPS", "");
        int ocrTitleStripHeight = parseIntOr(props,
                envOrProp(props, "ocr.title-strip-height", "OCR_TITLE_STRIP_HEIGHT", "80"), 80);
        if (ocrTitleStripHeight < 0) ocrTitleStripHeight = 80;

        boolean collectWindow = Boolean.parseBoolean(
                envOrProp(props, "aw.collection.window", "AW_COLLECTION_WINDOW", "true"));
        boolean collectAfk = Boolean.parseBoolean(
                envOrProp(props, "aw.collection.afk", "AW_COLLECTION_AFK", "true"));
        boolean collectContent = Boolean.parseBoolean(
                envOrProp(props, "aw.collection.content", "AW_COLLECTION_CONTENT", "true"));
        boolean audioEnabled = Boolean.parseBoolean(
                envOrProp(props, "aw.audio.enabled", "AW_AUDIO_ENABLED", "false"));

        // ── File Watch (SPEC-FILE-*) ──
        boolean fileWatchEnabled = Boolean.parseBoolean(
                envOrProp(props, "file.watch.enabled", "FILE_WATCH_ENABLED", "false"));
        String fileWatchPaths = envOrProp(props, "file.watch.paths", "FILE_WATCH_PATHS", "");
        int fileWatchMaxFileSizeKb = parseIntOr(props,
                envOrProp(props, "file.watch.maxFileSizeKb", "FILE_WATCH_MAX_FILE_SIZE_KB", "512"), 512);
        int fileWatchMaxContentChars = parseIntOr(props,
                envOrProp(props, "file.watch.maxContentChars", "FILE_WATCH_MAX_CONTENT_CHARS", "8000"), 8000);
        if (fileWatchMaxContentChars < 500) fileWatchMaxContentChars = 8000;
        int fileWatchWorkerIntervalSeconds = parseIntOr(props,
                envOrProp(props, "file.watch.worker.intervalSeconds", "FILE_WATCH_WORKER_INTERVAL_SECONDS", "60"), 60);
        int fileWatchDebounceSeconds = parseIntOr(props,
                envOrProp(props, "file.watch.debounceSeconds", "FILE_WATCH_DEBOUNCE_SECONDS", "5"), 5);
        int fileWatchMinReindexIntervalMinutes = parseIntOr(props,
                envOrProp(props, "file.watch.minReindexIntervalMinutes", "FILE_WATCH_MIN_REINDEX_INTERVAL_MINUTES", "5"), 5);
        int fileWatchHeartbeatThrottleSeconds = parseIntOr(props,
                envOrProp(props, "file.watch.heartbeatThrottleSeconds", "FILE_WATCH_HEARTBEAT_THROTTLE_SECONDS", "5"), 5);
        String fileWatchExtensions = envOrProp(props, "file.watch.extensions", "FILE_WATCH_EXTENSIONS", "");
        String fileWatchExcludeDirs = envOrProp(props, "file.watch.excludeDirs", "FILE_WATCH_EXCLUDE_DIRS", "");
        String fileWatchExcludeGlobs = envOrProp(props, "file.watch.excludeGlobs", "FILE_WATCH_EXCLUDE_GLOBS", "");
        boolean fileWatchSemanticEnabled = Boolean.parseBoolean(
                envOrProp(props, "file.watch.semantic.enabled", "FILE_WATCH_SEMANTIC_ENABLED", "true"));
        Path fileSemanticIndexDir = Path.of(envOrProp(props, "file.watch.semantic.index-dir",
                "FILE_WATCH_SEMANTIC_INDEX_DIR", memDir + "/file-semantic-index"));

        // ── Token 用量限制 / 预算 (SPEC-BUDGET-*) ──
        int llmMaxTokens = parseIntOr(props,
                envOrProp(props, "llm.max-tokens", "LLM_MAX_TOKENS", "2048"), 2048);
        if (llmMaxTokens < 0) llmMaxTokens = 0; // 0 = 不限
        int agentMaxIters = parseIntOr(props,
                envOrProp(props, "llm.agent.maxIters", "LLM_AGENT_MAX_ITERS", "8"), 8);
        if (agentMaxIters < 1) agentMaxIters = 8;
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

        return new Config(apiKey, baseUrl, model, awUrl, awTimeout,
                Path.of(memDir), awEmbedded, awPort, awDataDir,
                wikiEnabled, wikiBackfillEnabled, wikiWorkerIntervalSeconds,
                wikiPromptMaxContentChars, wikiTopAppsLimit,
                wikiSemanticEnabled, wikiSemanticIndexDir, wikiSemanticTopK,
                embeddingEnabled, embeddingBaseUrl, embeddingApiKey,
                embeddingModel, embeddingDimensions,
                embeddingSendEncodingFormat, contentPollIntervalMs, ocrSampleDir,
                ocrExcludedApps, ocrTitleStripHeight,
                webSearchEnabled, webSearchMcpUrl, webSearchApiKey,
                llmTemperature, collectWindow, collectAfk, collectContent, audioEnabled,
                fileWatchEnabled, fileWatchPaths, fileWatchMaxFileSizeKb,
                fileWatchMaxContentChars, fileWatchWorkerIntervalSeconds,
                fileWatchDebounceSeconds, fileWatchMinReindexIntervalMinutes,
                fileWatchHeartbeatThrottleSeconds, fileWatchExtensions,
                fileWatchExcludeDirs, fileWatchExcludeGlobs,
                fileWatchSemanticEnabled, fileSemanticIndexDir,
                llmMaxTokens, agentMaxIters, desktopSummaryMaxTimelineLlm,
                budgetMode, budgetDailyTokens, budgetWarnRatio);
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
                baseDir.resolve("ocr-samples"), "", 80,
                false, "https://search.parallel.ai/mcp", "",
                0.7, false, false, false, false,
                false, "", 512, 8000, 60, 5, 5, 5, "", "", "", true,
                baseDir.resolve("file-semantic-index"),
                2048, 8, 4, "warn", 100000000L, 0.8);
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

    public void validate() {
        if (llmApiKey == null || llmApiKey.isBlank() || llmApiKey.equals("CHANGE_ME")) {
            throw new IllegalStateException(
                    "LLM API key not configured. Set OPENAI_API_KEY env var or llm.api-key in application.properties");
        }
    }
}
