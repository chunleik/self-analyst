package com.selfanalyst.events.raw;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/** 固定 9 位小数的 UTC 文本，保证 SQLite TEXT 排序与 Instant 顺序一致。 */
public final class RawTimestamp {
    private static final DateTimeFormatter FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    private RawTimestamp() {}

    public static String format(Instant value) {
        return FORMATTER.format(value);
    }
}
