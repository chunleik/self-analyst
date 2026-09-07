package com.selfanalyst.desktop.service;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.store.Database;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.PulseTimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void queriesProductWindowAndAfkBuckets() throws Exception {
        try (Database db = new Database(tempDir.resolve("events"))) {
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            SummaryService service = new SummaryService(events, null);
            assertTrue(service.windowBucket().startsWith("watcher-window_"));
            assertTrue(service.afkBucket().startsWith("watcher-afk_"));
            assertFalse(service.windowBucket().startsWith("aw-watcher-"));

            Instant start = Instant.parse("2026-09-06T00:00:00Z");
            Instant end = start.plusSeconds(3600);
            events.insertEvent(service.windowBucket(), new Event(
                    start.plusSeconds(10), 120,
                    Map.of("app", "editor.exe", "title", "窗口")));

            SummaryService.LocalFacts facts = service.factsFor(start, end, "测试");
            assertTrue(facts.topApps().stream().anyMatch(app -> app.contains("editor")),
                    facts.topApps().toString());
        }
    }
}
