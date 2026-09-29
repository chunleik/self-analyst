package com.selfanalyst.config;

import java.util.Set;

/** Removed feature keys accepted for upgrade compatibility and omitted from active configuration. */
public final class DeprecatedKeys {

    private static final Set<String> KEYS = Set.of(
            "llm.max-tokens",
            "file.watch.maxContentChars",
            "file.watch.minReindexIntervalMinutes",
            "file.watch.semantic.enabled",
            "file.watch.semantic.index-dir");

    private DeprecatedKeys() {}

    public static boolean contains(String key) {
        return key != null && KEYS.contains(key);
    }

    public static Set<String> all() {
        return KEYS;
    }
}
