package com.selfanalyst.config;

import java.util.Set;

/** Removed feature keys accepted for upgrade compatibility and omitted from active configuration. */
public final class DeprecatedKeys {

    private static final String WEB_SEARCH_ENABLED = "websearch.enabled";
    private static final String WEB_SEARCH_URL = "websearch.mcp-url";
    private static final String WEB_SEARCH_KEY = "websearch.api-key";

    private static final Set<String> KEYS = Set.of(
            "llm.max-tokens",
            "file.watch.maxContentChars",
            "file.watch.minReindexIntervalMinutes",
            "file.watch.semantic.enabled",
            "file.watch.semantic.index-dir",
            WEB_SEARCH_ENABLED, WEB_SEARCH_URL, WEB_SEARCH_KEY);

    private DeprecatedKeys() {}

    public static boolean contains(String key) {
        return key != null && KEYS.contains(key);
    }

    public static Set<String> all() {
        return KEYS;
    }

    /** The old search keys are raw round-trip data only, not runtime or migration inputs. */
    public static boolean legacyWebSearch(String key) {
        return WEB_SEARCH_ENABLED.equals(key) || WEB_SEARCH_URL.equals(key) || WEB_SEARCH_KEY.equals(key);
    }
}
