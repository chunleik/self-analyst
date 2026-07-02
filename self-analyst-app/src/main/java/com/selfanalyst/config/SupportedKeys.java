package com.selfanalyst.config;

import com.selfanalyst.config.TomlSupport.KeyType;

import java.util.LinkedHashMap;

/**
 * Single source of truth for supported user config keys → (default value,
 * declared type). Consumed by the raw-edit template, unknown-key detection, TOML
 * type validation, and TOML generation so they all read one definition. The key
 * set itself is unchanged from the pre-TOML whitelist (SPEC-TOML-NON-002); it
 * mirrors the structured sections and {@code SPEC-CFG-TOOL-002}.
 *
 * <p>Types follow SPEC-TOML: BOOLEAN for {@code *.enabled} / {@code aw.collection.*}
 * / {@code agent.allowAgentTasks} / {@code agent.cacheSummaries} / {@code desktop.*}
 * / {@code embedding.send-encoding-format}; INTEGER for {@code aw.port} /
 * {@code agent.summaryRefreshMinutes} / {@code embedding.dimensions}; FLOAT for
 * {@code llm.temperature}; STRING otherwise.
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
        put("llm.api-key", "", KeyType.STRING);
        put("llm.base-url", "https://api.openai.com/v1", KeyType.STRING);
        put("llm.model", "gpt-4o", KeyType.STRING);
        put("llm.temperature", "0.7", KeyType.FLOAT);
        put("websearch.enabled", "true", KeyType.BOOLEAN);
        put("websearch.mcp-url", "https://search.parallel.ai/mcp", KeyType.STRING);
        put("websearch.api-key", "", KeyType.STRING);
        put("agent.summaryRefreshMinutes", "5", KeyType.INTEGER);
        put("agent.allowAgentTasks", "false", KeyType.BOOLEAN);
        put("agent.cacheSummaries", "true", KeyType.BOOLEAN);
        put("desktop.hideToTray", "true", KeyType.BOOLEAN);
        put("desktop.autoOpenWindow", "true", KeyType.BOOLEAN);
        put("desktop.autoStartBackend", "true", KeyType.BOOLEAN);
        put("aw.mode", "embedded", KeyType.STRING);
        put("aw.port", "5700", KeyType.INTEGER);
        put("aw.collection.window", "true", KeyType.BOOLEAN);
        put("aw.collection.afk", "true", KeyType.BOOLEAN);
        put("aw.collection.content", "true", KeyType.BOOLEAN);
        put("aw.ocr.engine", "auto", KeyType.STRING);
        put("aw.audio.enabled", "false", KeyType.BOOLEAN);
        put("aw.audio.whisperPath", "tools/whisper", KeyType.STRING);
        put("embedding.enabled", "true", KeyType.BOOLEAN);
        put("embedding.base-url", "https://api.openai.com/v1", KeyType.STRING);
        put("embedding.api-key", "", KeyType.STRING);
        put("embedding.model", "text-embedding-3-small", KeyType.STRING);
        put("embedding.dimensions", "1536", KeyType.INTEGER);
        put("embedding.send-encoding-format", "true", KeyType.BOOLEAN);
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
