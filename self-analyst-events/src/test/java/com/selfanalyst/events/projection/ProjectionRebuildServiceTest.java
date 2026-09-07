package com.selfanalyst.events.projection;

import com.selfanalyst.events.raw.RawEvent;
import com.selfanalyst.events.raw.RawEventIdGenerator;
import com.selfanalyst.events.raw.RawEventSource;
import com.selfanalyst.events.raw.RawEventStore;
import com.selfanalyst.events.raw.RawIngestKind;
import com.selfanalyst.events.store.Database;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.PulseTimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionRebuildServiceTest {

    @Test
    void interruptedRebuildKeepsCurrentProjectionAndRawPartitions(@TempDir Path dir)
            throws Exception {
        Fixture fixture = fixture(dir);
        String currentHash = com.selfanalyst.events.raw.RawEventStore.fileSha256(
                fixture.awDir().resolve("events.db"));
        String rawHash = com.selfanalyst.events.raw.RawEventStore.fileSha256(fixture.rawDatabase());
        ProjectionRebuildService service = new ProjectionRebuildService(
                fixture.awDir(), fixture.rawDir(), 30, 1000, "v2",
                (rebuilding, current) -> { throw new Exception("injected interruption"); });

        assertThrows(IllegalStateException.class, service::rebuild);

        assertEquals(currentHash, RawEventStore.fileSha256(fixture.awDir().resolve("events.db")));
        assertEquals(rawHash, RawEventStore.fileSha256(fixture.rawDatabase()));
        assertTrue(Files.list(fixture.awDir()).anyMatch(path ->
                path.getFileName().toString().startsWith("events.db.rebuilding-")));
    }

    @Test
    void successfulRebuildAtomicallySwitchesToEquivalentProjection(@TempDir Path dir)
            throws Exception {
        Fixture fixture = fixture(dir);
        String rawHash = RawEventStore.fileSha256(fixture.rawDatabase());
        List<com.selfanalyst.events.model.Event> expected;
        try (Database current = new Database(fixture.awDir())) {
            expected = new EventStore(current, PulseTimeConfig.DEFAULT)
                    .queryAllEvents("bucket");
        }

        ProjectionRebuildService.RebuildResult result = new ProjectionRebuildService(
                fixture.awDir(), fixture.rawDir(), 30, 1000, "v2").rebuild();

        assertEquals(2, result.projectedRawEvents());
        assertNotNull(result.previousProjectionBackup());
        assertTrue(Files.isRegularFile(result.previousProjectionBackup()));
        assertEquals(rawHash, RawEventStore.fileSha256(fixture.rawDatabase()));
        try (Database rebuilt = new Database(fixture.awDir())) {
            assertEquals(expected, new EventStore(rebuilt, PulseTimeConfig.DEFAULT)
                    .queryAllEvents("bucket"));
        }
    }

    private static Fixture fixture(Path dir) throws Exception {
        Path rawDir = dir.resolve("raw");
        Path awDir = dir.resolve("aw");
        RawEventIdGenerator ids = new RawEventIdGenerator();
        Instant firstAt = Instant.parse("2026-09-03T12:00:00Z");
        Instant secondAt = firstAt.plusSeconds(10);
        RawEvent first = RawEvent.create(ids, "first", "bucket", RawEventSource.WINDOW,
                1, RawIngestKind.HEARTBEAT, firstAt, firstAt, 5,
                Map.of("app", "editor"), null, null);
        RawEvent second = RawEvent.create(ids, "second", "bucket", RawEventSource.WINDOW,
                1, RawIngestKind.HEARTBEAT, secondAt, secondAt, 5,
                Map.of("app", "editor"), null, null);
        Path rawDatabase;
        try (RawEventStore raw = new RawEventStore(rawDir)) {
            raw.appendBatch(List.of(first, second));
            rawDatabase = raw.partitionPath(java.time.YearMonth.of(2026, 9));
        }
        try (Database current = new Database(awDir)) {
            EventProjector projector = new EventProjector(current, 30, 1000, "v1");
            projector.projectBatch(List.of(first, second));
        }
        return new Fixture(rawDir, awDir, rawDatabase);
    }

    private record Fixture(Path rawDir, Path awDir, Path rawDatabase) {}
}
