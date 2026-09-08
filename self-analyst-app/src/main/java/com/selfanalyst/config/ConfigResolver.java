package com.selfanalyst.config;

import java.util.*;

/** 用户覆盖、环境兜底与运行配置共用的解析器；不负责持久化。 */
public final class ConfigResolver {
    private static final Map<String, String> ENV_KEYS = Map.ofEntries(
            Map.entry("llm.api-key", "OPENAI_API_KEY"),
            Map.entry("llm.base-url", "LLM_BASE_URL"),
            Map.entry("llm.model", "LLM_MODEL"),
            Map.entry("llm.temperature", "LLM_TEMPERATURE"),
            Map.entry("events.base-url", "EVENTS_BASE_URL"),
            Map.entry("events.timeout", "EVENTS_TIMEOUT"),
            Map.entry("events.mode", "EVENTS_MODE"),
            Map.entry("events.data-dir", "EVENTS_DATA_DIR"),
            Map.entry("events.raw.dir", "EVENTS_RAW_DIR"),
            Map.entry("events.raw.query.maxRangeDays", "EVENTS_RAW_QUERY_MAX_RANGE_DAYS"),
            Map.entry("events.raw.query.maxPageSize", "EVENTS_RAW_QUERY_MAX_PAGE_SIZE"),
            Map.entry("events.raw.lowDisk.warnBytes", "EVENTS_RAW_LOW_DISK_WARN_BYTES"),
            Map.entry("events.raw.lowDisk.blockBytes", "EVENTS_RAW_LOW_DISK_BLOCK_BYTES"),
            Map.entry("events.raw.integrity.startupScope", "EVENTS_RAW_INTEGRITY_STARTUP_SCOPE"),
            Map.entry("events.raw.projector.batchSize", "EVENTS_RAW_PROJECTOR_BATCH_SIZE"),
            Map.entry("wiki.enabled", "WIKI_ENABLED"),
            Map.entry("wiki.backfill.enabled", "WIKI_BACKFILL_ENABLED"),
            Map.entry("wiki.worker.intervalSeconds", "WIKI_WORKER_INTERVAL_SECONDS"),
            Map.entry("wiki.prompt.maxContentChars", "WIKI_PROMPT_MAX_CONTENT_CHARS"),
            Map.entry("wiki.topApps.limit", "WIKI_TOP_APPS_LIMIT"),
            Map.entry("wiki.semantic.enabled", "WIKI_SEMANTIC_ENABLED"),
            Map.entry("wiki.semantic.index-dir", "WIKI_SEMANTIC_INDEX_DIR"),
            Map.entry("wiki.semantic.topK", "WIKI_SEMANTIC_TOP_K"),
            Map.entry("embedding.enabled", "EMBEDDING_ENABLED"),
            Map.entry("embedding.base-url", "EMBEDDING_BASE_URL"),
            Map.entry("embedding.api-key", "EMBEDDING_API_KEY"),
            Map.entry("embedding.model", "EMBEDDING_MODEL"),
            Map.entry("embedding.dimensions", "EMBEDDING_DIMENSIONS"),
            Map.entry("embedding.send-encoding-format", "EMBEDDING_SEND_ENCODING_FORMAT"),
            Map.entry("events.collection.title.pollMs", "EVENTS_COLLECTION_TITLE_POLL_MS"),
            Map.entry("events.collection.window", "EVENTS_COLLECTION_WINDOW"),
            Map.entry("events.collection.afk", "EVENTS_COLLECTION_AFK"),
            Map.entry("events.collection.title.enabled", "EVENTS_COLLECTION_TITLE_ENABLED"),
            Map.entry("file.watch.enabled", "FILE_WATCH_ENABLED"),
            Map.entry("file.watch.paths", "FILE_WATCH_PATHS"),
            Map.entry("file.watch.maxFileSizeKb", "FILE_WATCH_MAX_FILE_SIZE_KB"),
            Map.entry("file.watch.worker.intervalSeconds", "FILE_WATCH_WORKER_INTERVAL_SECONDS"),
            Map.entry("file.watch.debounceSeconds", "FILE_WATCH_DEBOUNCE_SECONDS"),
            Map.entry("file.watch.heartbeatThrottleSeconds", "FILE_WATCH_HEARTBEAT_THROTTLE_SECONDS"),
            Map.entry("file.watch.extensions", "FILE_WATCH_EXTENSIONS"),
            Map.entry("file.watch.excludeDirs", "FILE_WATCH_EXCLUDE_DIRS"),
            Map.entry("file.watch.excludeGlobs", "FILE_WATCH_EXCLUDE_GLOBS"),
            Map.entry("file.watch.respectGitIgnore", "FILE_WATCH_RESPECT_GITIGNORE"),
            Map.entry("file.watch.semantic.index-dir", "FILE_WATCH_SEMANTIC_INDEX_DIR"),
            Map.entry("llm.max-tokens", "LLM_MAX_TOKENS"),
            Map.entry("llm.agent.maxIters", "LLM_AGENT_MAX_ITERS"),
            Map.entry("agent.compaction.enabled", "AGENT_COMPACTION_ENABLED"),
            Map.entry("agent.compaction.triggerMessages", "AGENT_COMPACTION_TRIGGER_MESSAGES"),
            Map.entry("agent.compaction.triggerTokens", "AGENT_COMPACTION_TRIGGER_TOKENS"),
            Map.entry("agent.compaction.keepMessages", "AGENT_COMPACTION_KEEP_MESSAGES"),
            Map.entry("agent.compaction.keepTokens", "AGENT_COMPACTION_KEEP_TOKENS"),
            Map.entry("desktop.summary.maxTimelineLlm", "DESKTOP_SUMMARY_MAX_TIMELINE_LLM"),
            Map.entry("llm.budget.mode", "LLM_BUDGET_MODE"),
            Map.entry("llm.budget.dailyTokens", "LLM_BUDGET_DAILY_TOKENS"),
            Map.entry("llm.budget.warnRatio", "LLM_BUDGET_WARN_RATIO"),
            Map.entry("websearch.enabled", "WEBSEARCH_ENABLED"),
            Map.entry("websearch.mcp-url", "WEBSEARCH_MCP_URL"),
            Map.entry("websearch.api-key", "WEBSEARCH_API_KEY"),
            Map.entry("app.language", "APP_LANGUAGE"),
            Map.entry("memory.dir", "MEMORY_DIR"));
    private final Properties user;
    private final Properties defaults = Config.loadClasspathProps();
    private final Map<String, String> environment;
    private final Map<String, Value> values = new LinkedHashMap<>();

    public record Value(String value, String source, String inheritedFrom) {
        public Map<String, Object> publicValue(String key) {
            return Map.of("value", sensitive(key) ? (value.isBlank() ? "" : "****") : value,
                    "source", source, "inheritedFrom", inheritedFrom, "isSet", !value.isBlank());
        }
    }

    public record Snapshot(Config config, Map<String, Value> values) {
        public Properties properties() {
            Properties result = new Properties();
            values.forEach((key, value) -> result.setProperty(key, value.value()));
            return result;
        }
        public Map<String, Object> publicValues() {
            Map<String, Object> result = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (SupportedKeys.contains(key)) result.put(key, value.publicValue(key));
            });
            return result;
        }
    }

    public ConfigResolver(Properties user, Map<String, String> environment) {
        RemovedEventConfig.validate(user, environment);
        this.user = new Properties();
        this.user.putAll(user);
        this.environment = Map.copyOf(environment);
        Set<String> keys = new TreeSet<>(defaults.stringPropertyNames());
        keys.addAll(user.stringPropertyNames());
        for (String key : keys) read(key, defaults.getProperty(key, ""), true, false);
    }

    public static Snapshot resolve(Properties user) { return resolve(user, System.getenv()); }

    public static Snapshot resolve(Properties user, Map<String, String> environment) {
        ConfigResolver resolver = new ConfigResolver(user, environment);
        Config config = Config.parse(resolver);
        String embedding = resolver.user.containsKey("embedding.api-key")
                ? resolver.user.getProperty("embedding.api-key")
                : resolver.environment.getOrDefault("EMBEDDING_API_KEY",
                        resolver.defaults.getProperty("embedding.api-key", ""));
        boolean inheritedKey = embedding.replace("${EMBEDDING_API_KEY:}", "").isBlank();
        resolver.project(config);
        if (inheritedKey) resolver.values.put("embedding.api-key",
                new Value(config.embeddingApiKey(), "inherited", "llm.api-key"));
        if (config.eventsEmbedded()) resolver.values.put("events.base-url",
                new Value(config.eventsBaseUrl(), "inherited", "events.port"));
        return new Snapshot(config, Collections.unmodifiableMap(new LinkedHashMap<>(resolver.values)));
    }

    private void project(Config config) {
        effective("llm.api-key", String.valueOf(config.llmApiKey()));
        effective("llm.base-url", String.valueOf(config.llmBaseUrl()));
        effective("llm.model", String.valueOf(config.llmModel()));
        effective("events.base-url", String.valueOf(config.eventsBaseUrl()));
        effective("events.timeout", String.valueOf(config.eventsTimeout()));
        effective("memory.dir", String.valueOf(config.memoryDir()));
        effective("events.port", String.valueOf(config.eventsPort()));
        effective("events.data-dir", String.valueOf(config.eventsDataDir()));
        effective("events.raw.dir", String.valueOf(config.eventsRawDir()));
        effective("events.raw.query.maxRangeDays", String.valueOf(config.eventsRawQueryMaxRangeDays()));
        effective("events.raw.query.maxPageSize", String.valueOf(config.eventsRawQueryMaxPageSize()));
        effective("events.raw.lowDisk.warnBytes", String.valueOf(config.eventsRawLowDiskWarnBytes()));
        effective("events.raw.lowDisk.blockBytes", String.valueOf(config.eventsRawLowDiskBlockBytes()));
        effective("events.raw.integrity.startupScope", String.valueOf(config.eventsRawIntegrityStartupScope()));
        effective("events.raw.projector.batchSize", String.valueOf(config.eventsRawProjectorBatchSize()));
        effective("wiki.enabled", String.valueOf(config.wikiEnabled()));
        effective("wiki.backfill.enabled", String.valueOf(config.wikiBackfillEnabled()));
        effective("wiki.worker.intervalSeconds", String.valueOf(config.wikiWorkerIntervalSeconds()));
        effective("wiki.prompt.maxContentChars", String.valueOf(config.wikiPromptMaxContentChars()));
        effective("wiki.topApps.limit", String.valueOf(config.wikiTopAppsLimit()));
        effective("wiki.semantic.enabled", String.valueOf(config.wikiSemanticEnabled()));
        effective("wiki.semantic.index-dir", String.valueOf(config.wikiSemanticIndexDir()));
        effective("wiki.semantic.topK", String.valueOf(config.wikiSemanticTopK()));
        effective("embedding.enabled", String.valueOf(config.embeddingEnabled()));
        effective("embedding.base-url", String.valueOf(config.embeddingBaseUrl()));
        effective("embedding.api-key", String.valueOf(config.embeddingApiKey()));
        effective("embedding.model", String.valueOf(config.embeddingModel()));
        effective("embedding.dimensions", String.valueOf(config.embeddingDimensions()));
        effective("embedding.send-encoding-format", String.valueOf(config.embeddingSendEncodingFormat()));
        effective("events.collection.title.pollMs", String.valueOf(config.titlePollIntervalMs()));
        effective("websearch.enabled", String.valueOf(config.webSearchEnabled()));
        effective("websearch.mcp-url", String.valueOf(config.webSearchMcpUrl()));
        effective("websearch.api-key", String.valueOf(config.webSearchApiKey()));
        effective("llm.temperature", String.valueOf(config.llmTemperature()));
        effective("events.collection.window", String.valueOf(config.collectWindow()));
        effective("events.collection.afk", String.valueOf(config.collectAfk()));
        effective("events.collection.title.enabled", String.valueOf(config.collectTitle()));
        effective("file.watch.enabled", String.valueOf(config.fileWatchEnabled()));
        effective("file.watch.paths", String.valueOf(config.fileWatchPaths()));
        effective("file.watch.maxFileSizeKb", String.valueOf(config.fileWatchMaxFileSizeKb()));
        effective("file.watch.worker.intervalSeconds", String.valueOf(config.fileWatchWorkerIntervalSeconds()));
        effective("file.watch.debounceSeconds", String.valueOf(config.fileWatchDebounceSeconds()));
        effective("file.watch.heartbeatThrottleSeconds", String.valueOf(config.fileWatchHeartbeatThrottleSeconds()));
        effective("file.watch.extensions", String.valueOf(config.fileWatchExtensions()));
        effective("file.watch.excludeDirs", String.valueOf(config.fileWatchExcludeDirs()));
        effective("file.watch.excludeGlobs", String.valueOf(config.fileWatchExcludeGlobs()));
        effective("file.watch.respectGitIgnore", String.valueOf(config.fileWatchRespectGitIgnore()));
        effective("file.watch.semantic.index-dir", String.valueOf(config.legacyFileSemanticIndexDir()));
        effective("llm.max-tokens", String.valueOf(config.llmMaxTokens()));
        effective("llm.agent.maxIters", String.valueOf(config.agentMaxIters()));
        effective("agent.compaction.enabled", String.valueOf(config.agentCompactionEnabled()));
        effective("agent.compaction.triggerMessages", String.valueOf(config.agentCompactionTriggerMessages()));
        effective("agent.compaction.triggerTokens", String.valueOf(config.agentCompactionTriggerTokens()));
        effective("agent.compaction.keepMessages", String.valueOf(config.agentCompactionKeepMessages()));
        effective("agent.compaction.keepTokens", String.valueOf(config.agentCompactionKeepTokens()));
        effective("desktop.summary.maxTimelineLlm", String.valueOf(config.desktopSummaryMaxTimelineLlm()));
        effective("llm.budget.mode", String.valueOf(config.budgetMode()));
        effective("llm.budget.dailyTokens", String.valueOf(config.budgetDailyTokens()));
        effective("llm.budget.warnRatio", String.valueOf(config.budgetWarnRatio()));
        effective("app.language", String.valueOf(config.appLanguage()));
        effective("events.mode", config.eventsEmbedded() ? "embedded" : "external");
    }
    public static Properties runtimeProperties(Config config) {
        ConfigResolver resolver = new ConfigResolver(new Properties(), Map.of());
        resolver.values.clear();
        resolver.project(config);
        return resolver.inputProperties();
    }
    public Properties inputProperties() {
        Properties result = new Properties();
        values.forEach((key, value) -> result.setProperty(key, value.value()));
        return result;
    }
    Properties user() { return user; }
    Properties properties() {
        Properties props = new Properties(defaults);
        props.putAll(user);
        return props;
    }
    String get(String key, String fallback) { return read(key, fallback, false, false); }
    String allowBlank(String key, String fallback) { return read(key, fallback, true, false); }
    String explicit(String key, String fallback) { return read(key, fallback, false, true); }

    String memoryDir() {
        String system = System.getProperty("memory.dir");
        if (system != null && !system.isBlank()) {
            values.put("memory.dir", new Value(system, "jvm", ""));
            return system;
        }
        return get("memory.dir", System.getProperty("user.home") + "/.self-analyst");
    }

    private String read(String key, String fallback, boolean allowBlank, boolean explicitOnly) {
        String value, source;
        String envKey = ENV_KEYS.get(key);
        String env = envKey == null ? null : environment.get(envKey);
        if (user.containsKey(key)) {
            value = user.getProperty(key);
            source = "toml";
        } else if (env != null && (allowBlank || !env.isBlank())) {
            value = env;
            source = "environment";
        } else {
            value = explicitOnly ? fallback : defaults.getProperty(key, fallback);
            source = "default";
        }
        if (!allowBlank && value.isBlank()) value = fallback;
        value = value.replace("${user.home}", System.getProperty("user.home"));
        values.put(key, new Value(value, source, ""));
        return value;
    }
    private void effective(String key, String value) {
        Value previous = values.get(key);
        values.put(key, new Value(value, previous == null ? "default" : previous.source(), ""));
    }
    public static boolean sensitive(String key) { return key.contains("api-key"); }
}
