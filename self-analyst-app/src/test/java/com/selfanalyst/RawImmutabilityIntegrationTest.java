package com.selfanalyst;

import com.selfanalyst.events.projection.EventProjector;
import com.selfanalyst.events.projection.ProjectionRebuildService;
import com.selfanalyst.events.raw.RawEvent;
import com.selfanalyst.events.raw.RawEventIdGenerator;
import com.selfanalyst.events.raw.RawEventQueryService;
import com.selfanalyst.events.raw.RawEventSource;
import com.selfanalyst.events.raw.RawEventStore;
import com.selfanalyst.events.raw.RawIngestKind;
import com.selfanalyst.events.raw.RawStorageStatusService;
import com.selfanalyst.events.store.Database;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.PulseTimeConfig;
import com.selfanalyst.file.FileTools;
import com.selfanalyst.file.FileWatchStore;
import com.selfanalyst.wiki.WikiStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RawImmutabilityIntegrationTest {

    @Test
    void derivedMaintenanceSequenceNeverChangesRawFacts(@TempDir Path dir) throws Exception {
        Path rawDir = dir.resolve("raw");
        Path awDir = dir.resolve("aw");
        Instant firstAt = Instant.parse("2026-09-03T12:00:00Z");
        RawEventIdGenerator ids = new RawEventIdGenerator();
        List<RawEvent> rawEvents = List.of(
                RawEvent.create(ids, "one", "bucket", RawEventSource.WINDOW, 1,
                        RawIngestKind.HEARTBEAT, firstAt, firstAt, 5,
                        Map.of("app", "editor"), null, null),
                RawEvent.create(ids, "two", "bucket", RawEventSource.WINDOW, 1,
                        RawIngestKind.HEARTBEAT, firstAt.plusSeconds(10),
                        firstAt.plusSeconds(10), 5, Map.of("app", "editor"), null, null));
        Path rawDatabase;
        try (RawEventStore raw = new RawEventStore(rawDir)) {
            raw.appendBatch(rawEvents);
            rawDatabase = raw.partitionPath(YearMonth.of(2026, 9));
        }
        String hashBefore = RawEventStore.fileSha256(rawDatabase);
        List<String> queryBefore = queryIds(rawDir, firstAt);

        try (Database projection = new Database(awDir)) {
            new EventProjector(projection, 30, 1000, "v1").projectBatch(rawEvents);
            EventStore events = new EventStore(projection, PulseTimeConfig.DEFAULT);
            assertEquals(1, events.countByBucket("bucket"));
            events.deleteByBucket("bucket");
        }

        Path fileDb = dir.resolve("file-watch.db");
        try (FileWatchStore files = new FileWatchStore(fileDb)) {
            new FileTools(files).updateWatchRoots(List.of());
        }
        Path wikiDb = dir.resolve("wiki.db");
        try (WikiStore ignored = new WikiStore(wikiDb)) {
            // 派生 Wiki 初始化后执行显式清理。
        }
        Files.delete(wikiDb);

        new ProjectionRebuildService(awDir, rawDir, 30, 1000, "v2").rebuild();
        try (RawStorageStatusService warning = new RawStorageStatusService(
                rawDir, awDir.resolve("events.db"), Long.MAX_VALUE, 1)) {
            warning.snapshot();
        }

        assertEquals(hashBefore, RawEventStore.fileSha256(rawDatabase));
        assertEquals(queryBefore, queryIds(rawDir, firstAt));
        try (RawEventStore raw = new RawEventStore(rawDir)) {
            assertEquals(2, raw.count(YearMonth.of(2026, 9)));
        }
    }

    private static List<String> queryIds(Path rawDir, Instant firstAt) throws Exception {
        try (RawEventQueryService query = new RawEventQueryService(rawDir, 31, 1000)) {
            return query.query("bucket", firstAt.minusSeconds(1),
                            firstAt.plusSeconds(60), 100, null).events().stream()
                    .map(RawEvent::eventId).toList();
        }
    }
}
