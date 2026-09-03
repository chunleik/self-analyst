package com.selfanalyst.aw.raw;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;

/** 最近五分钟文件大小样本计算出的每小时增长字节数。 */
public final class RawGrowthRate {
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    public synchronized void record(Instant at, long bytes) {
        samples.addLast(new Sample(at, bytes));
        Instant cutoff = at.minus(WINDOW);
        while (samples.size() > 1 && samples.getFirst().at().isBefore(cutoff)) {
            samples.removeFirst();
        }
    }

    public synchronized Long bytesPerHour() {
        if (samples.size() < 2) return null;
        Sample first = samples.getFirst();
        Sample last = samples.getLast();
        long millis = Duration.between(first.at(), last.at()).toMillis();
        if (millis <= 0) return null;
        long delta = Math.max(0, last.bytes() - first.bytes());
        return Math.round(delta * 3_600_000.0 / millis);
    }

    private record Sample(Instant at, long bytes) {}
}
