package com.selfanalyst.config;

import java.util.Locale;

/** 启动时对永久原始事件分区执行的完整性校验范围。 */
public enum RawIntegrityPolicy {
    LATEST("latest"),
    ALL("all");

    private final String configValue;

    RawIntegrityPolicy(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static RawIntegrityPolicy parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "events.raw.integrity.startupScope 必须是 latest 或 all");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "latest" -> LATEST;
            case "all" -> ALL;
            default -> throw new IllegalArgumentException(
                    "events.raw.integrity.startupScope 必须是 latest 或 all");
        };
    }
}
