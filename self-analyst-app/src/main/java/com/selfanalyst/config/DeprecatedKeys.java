package com.selfanalyst.config;

import java.util.Set;

/** Removed feature keys accepted for upgrade compatibility but ignored at runtime. */
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
            "aw.audio.chunkSeconds");

    private DeprecatedKeys() {}

    public static boolean contains(String key) {
        return key != null && (KEYS.contains(key) || key.startsWith("aw.audio."));
    }

    public static Set<String> all() {
        return KEYS;
    }
}
