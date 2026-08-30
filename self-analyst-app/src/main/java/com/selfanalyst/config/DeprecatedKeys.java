package com.selfanalyst.config;

import java.util.Set;

/** Removed feature keys accepted for upgrade compatibility and omitted from active configuration. */
public final class DeprecatedKeys {

    private static final Set<String> KEYS = Set.of(
            "aw.ocr.engine",
            "ocr.sample.enabled",
            "ocr.sample.dir",
            "ocr.excluded.apps",
            "ocr.title-strip-height",
            "ocr.stable-capture-interval-ms",
            "ocr.force-refresh-ms",
            "aw.audio.enabled",
            "aw.audio.whisperPath",
            "aw.audio.vadThreshold",
            "aw.audio.source",
            "aw.audio.engine",
            "aw.audio.model",
            "aw.audio.chunkSeconds",
            "file.watch.maxContentChars",
            "file.watch.minReindexIntervalMinutes",
            "file.watch.semantic.enabled",
            "file.watch.semantic.index-dir");

    private DeprecatedKeys() {}

    public static boolean contains(String key) {
        return key != null && (KEYS.contains(key) || key.startsWith("aw.audio."));
    }

    public static Set<String> all() {
        return KEYS;
    }
}
