package com.selfanalyst.aw.raw;

import java.util.Locale;

/** 原始事件进入受管写入链路的接口类型。 */
public enum RawIngestKind {
    HEARTBEAT("heartbeat"),
    EVENTS("events"),
    IMPORT("import");

    private final String storageValue;

    RawIngestKind(String storageValue) {
        this.storageValue = storageValue;
    }

    public String storageValue() {
        return storageValue;
    }

    public static RawIngestKind parse(String value) {
        if (value == null) throw new IllegalArgumentException("原始事件接收类型不能为空");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (RawIngestKind kind : values()) {
            if (kind.storageValue.equals(normalized)) return kind;
        }
        throw new IllegalArgumentException("不支持的原始事件接收类型: " + value);
    }
}
