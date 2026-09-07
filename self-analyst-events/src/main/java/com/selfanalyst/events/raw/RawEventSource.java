package com.selfanalyst.events.raw;

import java.util.Locale;

/** 已通过对应来源 schema 与隐私策略校验的原始事件来源。 */
public enum RawEventSource {
    WINDOW("window"),
    AFK("afk"),
    CONTENT("content"),
    FILE("file"),
    IMPORT("import"),
    THIRD_PARTY("third_party");

    private final String storageValue;

    RawEventSource(String storageValue) {
        this.storageValue = storageValue;
    }

    public String storageValue() {
        return storageValue;
    }

    public static RawEventSource fromBucket(String bucketId, String client) {
        String id = bucketId == null ? "" : bucketId.toLowerCase(Locale.ROOT);
        String normalizedClient = client == null ? "" : client.toLowerCase(Locale.ROOT);
        if (matchesKind(id, normalizedClient, "window")) return WINDOW;
        if (matchesKind(id, normalizedClient, "afk")) return AFK;
        if (matchesKind(id, normalizedClient, "content")) return CONTENT;
        if (matchesKind(id, normalizedClient, "file")) return FILE;
        return THIRD_PARTY;
    }

    private static boolean matchesKind(String bucketId, String client, String kind) {
        return bucketId.startsWith("watcher-" + kind + "_")
                || bucketId.startsWith("aw-watcher-" + kind + "_")
                || client.contains(kind);
    }

    public static RawEventSource parse(String value) {
        if (value == null) throw new IllegalArgumentException("原始事件来源不能为空");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (RawEventSource source : values()) {
            if (source.storageValue.equals(normalized)) return source;
        }
        throw new IllegalArgumentException("不支持的原始事件来源: " + value);
    }
}
