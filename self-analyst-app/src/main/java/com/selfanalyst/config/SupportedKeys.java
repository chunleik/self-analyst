package com.selfanalyst.config;

import com.selfanalyst.config.TomlSupport.KeyType;

import java.util.LinkedHashMap;

/**
 * Single source of truth for user-overridable config keys → (default value,
 * declared type). Consumed by the raw-edit template, unknown-key detection, TOML
 * type validation, and TOML generation so they all read one definition. Keep this
 * aligned with {@link Config#load()} and the desktop-only settings persisted by
 * {@code UserConfigStore}.
 *
 * <p>Network/LLM-sending features remain opt-in by default: wiki, embedding,
 * file watch, and web search are all listed with {@code false} where they cause
 * external processing or broader data capture.
 */
public final class SupportedKeys {

    private SupportedKeys() {}

    /** A supported key's default value and declared TOML type. */
    public record Spec(String defaultValue, KeyType type) {}

    private static final LinkedHashMap<String, Spec> KEYS = new LinkedHashMap<>();

    private static void put(String key, String def, KeyType type) {
        KEYS.put(key, new Spec(def, type));
    }

    static {
        put("log.dir", "./logs", KeyType.STRING);
        put("memory.dir", "./data/memory", KeyType.STRING);

        put("llm.api-key", "", KeyType.STRING);
        put("llm.base-url", "https://api.openai.com/v1", KeyType.STRING);
        put("llm.model", "gpt-4o", KeyType.STRING);
        put("llm.temperature", "0.7", KeyType.FLOAT);
        put("llm.max-tokens", "2048", KeyType.INTEGER);
        put("llm.agent.maxIters", "8", KeyType.INTEGER);
        put("llm.budget.mode", "warn", KeyType.STRING);
        put("llm.budget.dailyTokens", "100000000", KeyType.INTEGER);
        put("llm.budget.warnRatio", "0.8", KeyType.FLOAT);

        put("agent.summaryRefreshMinutes", "5", KeyType.INTEGER);
        put("agent.allowAgentTasks", "false", KeyType.BOOLEAN);
        put("agent.cacheSummaries", "true", KeyType.BOOLEAN);

        put("desktop.hideToTray", "true", KeyType.BOOLEAN);
        put("desktop.autoOpenWindow", "true", KeyType.BOOLEAN);
        put("desktop.autoStartBackend", "true", KeyType.BOOLEAN);
        put("desktop.summary.maxTimelineLlm", "4", KeyType.INTEGER);

        put("aw.mode", "embedded", KeyType.STRING);
        put("aw.port", "5700", KeyType.INTEGER);
        put("aw.base-url", "http://localhost:5700/api/0", KeyType.STRING);
        put("aw.timeout", "15000", KeyType.INTEGER);
        put("aw.data-dir", "./data/aw-data", KeyType.STRING);
        put("aw.collection.window", "true", KeyType.BOOLEAN);
        put("aw.collection.afk", "true", KeyType.BOOLEAN);
        put("aw.collection.content", "true", KeyType.BOOLEAN);
        put("aw.collection.content.pollMs", "500", KeyType.INTEGER);
        put("aw.ocr.engine", "auto", KeyType.STRING);
        put("aw.audio.enabled", "false", KeyType.BOOLEAN);
        put("aw.audio.whisperPath", "tools/whisper", KeyType.STRING);
        put("aw.audio.vadThreshold", "0.0001", KeyType.FLOAT);
        put("aw.audio.source", "mic", KeyType.STRING);
        put("aw.audio.engine", "auto", KeyType.STRING);
        put("aw.audio.model", "gpt-4o-transcribe", KeyType.STRING);
        put("aw.audio.chunkSeconds", "10", KeyType.INTEGER);
        put("ocr.sample.dir", "./data/aw-data/ocr-samples", KeyType.STRING);
        put("ocr.excluded.apps", "", KeyType.STRING);
        put("ocr.title-strip-height", "80", KeyType.INTEGER);

        put("wiki.enabled", "false", KeyType.BOOLEAN);
        put("wiki.backfill.enabled", "false", KeyType.BOOLEAN);
        put("wiki.worker.intervalSeconds", "60", KeyType.INTEGER);
        put("wiki.prompt.maxContentChars", "12000", KeyType.INTEGER);
        put("wiki.topApps.limit", "10", KeyType.INTEGER);
        put("wiki.semantic.enabled", "true", KeyType.BOOLEAN);
        put("wiki.semantic.index-dir", "./data/memory/wiki-semantic-index", KeyType.STRING);
        put("wiki.semantic.topK", "8", KeyType.INTEGER);

        put("embedding.enabled", "false", KeyType.BOOLEAN);
        put("embedding.base-url", "https://api.openai.com/v1", KeyType.STRING);
        put("embedding.api-key", "", KeyType.STRING);
        put("embedding.model", "text-embedding-3-small", KeyType.STRING);
        put("embedding.dimensions", "1024", KeyType.INTEGER);
        put("embedding.send-encoding-format", "true", KeyType.BOOLEAN);

        put("file.watch.enabled", "false", KeyType.BOOLEAN);
        put("file.watch.paths", "", KeyType.STRING);
        put("file.watch.maxFileSizeKb", "512", KeyType.INTEGER);
        put("file.watch.maxContentChars", "8000", KeyType.INTEGER);
        put("file.watch.worker.intervalSeconds", "60", KeyType.INTEGER);
        put("file.watch.debounceSeconds", "5", KeyType.INTEGER);
        put("file.watch.minReindexIntervalMinutes", "5", KeyType.INTEGER);
        put("file.watch.heartbeatThrottleSeconds", "5", KeyType.INTEGER);
        put("file.watch.extensions", "", KeyType.LIST);
        put("file.watch.excludeDirs", "", KeyType.LIST);
        put("file.watch.excludeGlobs", "", KeyType.LIST);
        put("file.watch.semantic.enabled", "true", KeyType.BOOLEAN);
        put("file.watch.semantic.index-dir", "./data/memory/file-semantic-index", KeyType.STRING);

        put("websearch.enabled", "false", KeyType.BOOLEAN);
        put("websearch.mcp-url", "https://search.parallel.ai/mcp", KeyType.STRING);
        put("websearch.api-key", "", KeyType.STRING);

        put("app.language", "auto", KeyType.STRING);
    }

    /** All supported keys → default value, in declaration order. */
    public static LinkedHashMap<String, String> defaults() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        KEYS.forEach((k, v) -> m.put(k, v.defaultValue()));
        return m;
    }

    /** All supported keys → declared type, in declaration order. */
    public static LinkedHashMap<String, KeyType> types() {
        LinkedHashMap<String, KeyType> m = new LinkedHashMap<>();
        KEYS.forEach((k, v) -> m.put(k, v.type()));
        return m;
    }

    /** Whether {@code key} is in the supported whitelist. */
    public static boolean contains(String key) {
        return KEYS.containsKey(key);
    }
}
