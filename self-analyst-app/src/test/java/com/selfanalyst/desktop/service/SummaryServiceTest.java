package com.selfanalyst.desktop.service;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.store.Database;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.PulseTimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SummaryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void wikiAndDesktopUseTheSameActivityFacts() throws Exception {
        try (Database db = new Database(tempDir.resolve("consistent"))) {
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            SummaryService service = new SummaryService(events, null);
            Instant start = Instant.parse("2026-09-14T20:00:00Z");
            events.insertEvent(service.windowBucket(), new Event(start.minusSeconds(3600), 7200, Map.of("app", "A")));
            events.insertEvent(service.afkBucket(), new Event(start, 1200, Map.of("status", "afk")));
            events.insertEvent(service.afkBucket(), new Event(start.plusSeconds(1200), 2400, Map.of("status", "not-afk")));
            var local = service.factsFor(start, start.plusSeconds(3600), "测试");
            var wiki = new com.selfanalyst.wiki.WikiFactBuilder(events, 12000).buildFacts(
                    new com.selfanalyst.wiki.WikiPeriod(com.selfanalyst.wiki.WikiLevel.HOUR, start, start.plusSeconds(3600), "UTC"));
            assertEquals(SummaryService.formatDuration(wiki.activeSeconds()), local.activeTime());
            assertEquals(SummaryService.formatDuration(wiki.afkSeconds()), local.afkTime());
            assertEquals(wiki.topApps().getFirst().app() + " " + SummaryService.formatDuration(wiki.topApps().getFirst().seconds()), local.topApps().getFirst());
        }
    }

    @Test
    void earlyMorningEntertainmentUsesPriorDayAndExcludesAfk() throws Exception {
        try (Database db = new Database(tempDir.resolve("behavior"))) {
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            SummaryService service = new SummaryService(events, null);
            var zone = java.time.ZoneId.systemDefault();
            for (int day = 10; day <= 12; day++) {
                Instant start = java.time.LocalDate.of(2026, 9, day).atTime(1, 0).atZone(zone).toInstant();
                events.insertEvent(service.windowBucket(), new Event(start, 3600, Map.of("app", "steam")));
                events.insertEvent(service.afkBucket(), new Event(start, 1800, Map.of("status", "afk")));
                events.insertEvent(service.afkBucket(), new Event(start.plusSeconds(1800), 1800, Map.of("status", "not-afk")));
            }
            var facts = service.getBehaviorData(java.time.LocalDate.of(2026, 9, 13).atTime(4, 0).atZone(zone).toInstant());
            assertEquals(3, facts.totalDays());
            assertEquals(30, facts.recentDailyEveningMin());
            assertEquals(30, facts.baselineDailyEveningMin());
        }
    }

    @Test
    void excludesOvernightAfkFromAppRankingAndKeepsUnknownActivity() throws Exception {
        try (Database db = new Database(tempDir.resolve("statistics"))) {
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            SummaryService service = new SummaryService(events, null);
            Instant four = Instant.parse("2026-09-14T20:00:00Z");
            events.insertEvent(service.windowBucket(), new Event(four.minusSeconds(21600), 36000,
                    Map.of("app", "unknown", "title", "unknown")));
            events.insertEvent(service.afkBucket(), new Event(four.minusSeconds(21600), 36000,
                    Map.of("status", "afk")));
            events.insertEvent(service.windowBucket(), new Event(four.plusSeconds(36000), 120,
                    Map.of("app", "editor", "title", "unknown")));
            events.insertEvent(service.windowBucket(), new Event(four.plusSeconds(36120), 30,
                    Map.of("app", "unknown", "title", "unknown")));
            events.insertEvent(service.afkBucket(), new Event(four.plusSeconds(14400), 72000,
                    Map.of("status", "not-afk")));
            var facts = service.factsFor(four, four.plusSeconds(86400), "测试");
            assertEquals(30, facts.unknownActivitySeconds());
            assertEquals("complete", facts.coverage());
            assertTrue(facts.headline().contains("editor"));
            assertFalse(facts.headline().contains("unknown"));
            assertTrue(facts.afkTime().contains("4小时"));
            assertTrue(facts.topApps().stream().noneMatch(app -> app.contains("unknown")));
        }
    }

    @Test
    void missingAfkIsEstimatedAndFailedWindowIsDistinct() throws Exception {
        try (Database db = new Database(tempDir.resolve("coverage"))) {
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            SummaryService service = new SummaryService(events, null);
            Instant start = Instant.parse("2026-09-14T20:00:00Z");
            events.insertEvent(service.windowBucket(), new Event(start, 60, Map.of("app", "A")));
            assertEquals("estimated", service.factsFor(start, start.plusSeconds(60), "测试").coverage());
            EventStore failing = new EventStore(db, PulseTimeConfig.DEFAULT) {
                @Override public Map<String, EventRange> queryIntersecting(java.util.List<String> ids, Instant a, Instant b) {
                    throw new IllegalStateException("unavailable");
                }
            };
            var failed = new SummaryService(failing, null).factsFor(start, start.plusSeconds(60), "测试");
            assertEquals("failed", failed.coverage());
            assertEquals("活动数据查询失败", failed.headline());
        }
    }

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
