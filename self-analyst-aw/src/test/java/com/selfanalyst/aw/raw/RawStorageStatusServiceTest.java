package com.selfanalyst.aw.raw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RawStorageStatusServiceTest {

    @Test
    void rollingGrowthNeedsTwoSamplesAndUsesFiveMinuteWindow() {
        RawGrowthRate rate = new RawGrowthRate();
        Instant start = Instant.parse("2026-09-03T12:00:00Z");
        rate.record(start, 1_000);
        assertNull(rate.bytesPerHour());
        rate.record(start.plusSeconds(60), 2_000);
        assertEquals(60_000, rate.bytesPerHour());
        rate.record(start.plusSeconds(360), 3_000);
        assertEquals(12_000, rate.bytesPerHour());
    }

    @Test
    void statusContainsDiagnosticsButNoPayloadPathTitleOrToken(@TempDir Path dir)
            throws Exception {
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        try (RawEventStore store = new RawEventStore(dir.resolve("raw"))) {
            store.append(RawEvent.create(new RawEventIdGenerator(), "source", "bucket",
                    RawEventSource.CONTENT, 2, RawIngestKind.HEARTBEAT,
                    now, now, 2, Map.of("title", "PRIVATE_TITLE"), null, null));
        }
        try (RawStorageStatusService status = new RawStorageStatusService(
                dir.resolve("raw"), dir.resolve("events.db"), 1, 1)) {
            String text = status.snapshot().toString();
            assertFalse(text.contains("PRIVATE_TITLE"));
            assertFalse(text.contains(dir.toString()));
            assertFalse(text.toLowerCase().contains("token"));
            assertEquals(1L, status.snapshot().get("eventCount"));
        }
    }
}
