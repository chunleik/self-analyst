package com.selfanalyst.aw.raw;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过 -Draw.benchmark=true 显式运行，避免日常单元测试承担容量压测。 */
class RawEventStoreBenchmarkTest {

    private static final int EVENT_COUNT = 30 * 24 * 60 * 60 / 5;
    private static final int WRITE_BATCH_SIZE = 10_000;

    @Test
    void oneMonthFiveSecondHeartbeatCapacityAndPagination(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("raw.benchmark"),
                "仅在显式启用 raw.benchmark 时运行容量基准");
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        RawEventIdGenerator ids = new RawEventIdGenerator();
        long writeStarted = System.nanoTime();
        try (RawEventStore store = new RawEventStore(dir)) {
            for (int offset = 0; offset < EVENT_COUNT; offset += WRITE_BATCH_SIZE) {
                int count = Math.min(WRITE_BATCH_SIZE, EVENT_COUNT - offset);
                List<RawEvent> batch = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int ordinal = offset + i;
                    Instant time = start.plusSeconds(ordinal * 5L);
                    batch.add(RawEvent.create(ids, "benchmark-" + ordinal, "benchmark-bucket",
                            RawEventSource.WINDOW, 1, RawIngestKind.HEARTBEAT,
                            time, time, 5, Map.of("app", "benchmark", "title", "stable"),
                            null, null));
                }
                store.appendBatch(batch);
            }
            assertEquals(EVENT_COUNT, store.count(YearMonth.of(2026, 9)));
        }
        long writeNanos = System.nanoTime() - writeStarted;

        Path database = dir.resolve("2026/raw-events-2026-09.db");
        long databaseBytes = Files.size(database);
        List<Long> pageLatencies = new ArrayList<>();
        int queried = 0;
        String cursor = null;
        try (RawEventQueryService queries = new RawEventQueryService(dir, 31, 1000)) {
            do {
                long pageStarted = System.nanoTime();
                RawEventQueryService.QueryPage page = queries.query(
                        "benchmark-bucket", start, start.plus(Duration.ofDays(30)),
                        1000, cursor);
                pageLatencies.add(System.nanoTime() - pageStarted);
                queried += page.events().size();
                cursor = page.nextCursor();
            } while (cursor != null);
        }
        pageLatencies.sort(Long::compareTo);
        double eventsPerSecond = EVENT_COUNT / (writeNanos / 1_000_000_000.0);
        double p95PageMs = pageLatencies.get(
                Math.min(pageLatencies.size() - 1,
                        (int) Math.ceil(pageLatencies.size() * 0.95) - 1)) / 1_000_000.0;
        System.out.printf(java.util.Locale.ROOT,
                "RAW_BENCHMARK events=%d throughput=%.1f_events_per_second db_bytes=%d pages=%d p95_page_ms=%.3f%n",
                EVENT_COUNT, eventsPerSecond, databaseBytes, pageLatencies.size(), p95PageMs);

        assertEquals(EVENT_COUNT, queried);
        assertTrue(databaseBytes > 0);
        assertTrue(Files.exists(database));
        try (RawEventStore reopened = new RawEventStore(dir)) {
            assertEquals(EVENT_COUNT, reopened.count(YearMonth.of(2026, 9)),
                    "基准与分页不得触发自动删除");
        }
    }
}
