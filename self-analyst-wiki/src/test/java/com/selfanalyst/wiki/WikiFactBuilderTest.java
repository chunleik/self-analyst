package com.selfanalyst.wiki;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.store.Database;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.PulseTimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiFactBuilderTest {

    @Test
    void parentKeepsDatedTasksAndNonemptyCoverageWithoutDroppingEvidence() {
        Instant start = Instant.parse("2026-09-01T04:00:00Z");
        Instant end = start.plusSeconds(86400);
        var metrics = new WikiEntry.WikiMetrics(60, 0, 1,
                java.util.List.of(new WikiEntry.AppDuration("Editor", 60)), Map.of());
        var task = new WikiEntry.TaskSegment("订单模块", "查看订单模块资料", java.util.List.of("标题观察"),
                java.util.List.of("Editor"), "medium", java.util.List.of("f7"), "inferred");
        var child = new WikiEntry("day-a", WikiLevel.DAY, start, end, "UTC", WikiStatus.SUMMARIZED,
                "查看项目资料", "订单模块", java.util.List.of(task), metrics, java.util.List.of(), "model", "v7",
                0, null, null, start, end, end, "facts-v5", "events-v2",
                Map.of("afk", new WikiEntry.SourceCoverage("partial", start, end, 0L)));
        var parent = new WikiFactBuilder(null, 24000).buildFactsFromChildren(java.util.List.of(child),
                new WikiPeriod(WikiLevel.WEEK, start, start.plusSeconds(604800), "UTC"));
        assertTrue(parent.childSummaries().getFirst().contains("2026-09-01T04:00:00Z"));
        assertTrue(parent.childSummaries().getFirst().contains("partial"));
        assertTrue(parent.childSummaries().getFirst().contains("child:day-a:0:0"));
        assertEquals("订单模块", parent.sampledTitles().facts().getFirst().title());
        assertEquals(java.util.List.of("day-a"), parent.statistics().get("sourceEntryIds"));
        assertEquals(60, parent.activeSeconds());
    }

    @TempDir
    Path tempDir;

    @Test
    void structuredPromptKeepsAllMetricsRegardlessOfSamplingAndCallsModelOnce() throws Exception {
        try (Database db = new Database(tempDir.resolve("structured"))) {
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            String host = java.net.InetAddress.getLocalHost().getHostName();
            Instant start = Instant.parse("2026-09-21T04:00:00Z");
            String window = "watcher-window_" + host;
            events.insertEvent(window, new Event(start.minusSeconds(60), 180.6,
                    Map.of("app", "IDE", "title", "订单模块")));
            events.insertEvent("watcher-afk_" + host, new Event(start.plusSeconds(30), 30,
                    Map.of("status", "afk")));
            var period = new WikiPeriod(WikiLevel.DAY, start, start.plusSeconds(86400), "UTC");
            var complete = new WikiFactBuilder(events, 12000).buildFacts(period);
            var tiny = new WikiFactBuilder(events, 20).buildFacts(period);
            assertEquals(complete.activeSeconds(), tiny.activeSeconds());
            assertEquals(complete.afkSeconds(), tiny.afkSeconds());
            assertEquals(complete.switchCount(), tiny.switchCount());
            assertEquals(complete.topApps(), tiny.topApps());
            for (String key : java.util.List.of("activeSecondsExact", "afkSecondsExact", "appSecondsExact",
                    "unknownActivitySeconds", "uncoveredSeconds", "conflictSeconds")) {
                assertEquals(complete.statistics().get(key), tiny.statistics().get(key));
            }
            assertEquals(90.6, ((Number) complete.statistics().get("activeSecondsExact")).doubleValue(), 1e-9);
            assertTrue(tiny.sampledTitles().facts().isEmpty());
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            var summarizer = new WikiSummarizer(prompt -> {
                calls.incrementAndGet();
                assertTrue(prompt.contains("结构化标题事实"));
                assertTrue(prompt.contains("\"id\""));
                assertFalse(prompt.contains("sourceEventIds"));
                assertTrue(prompt.contains("订单模块"));
                assertFalse(prompt.contains("## 窗口标题样本"));
                assertTrue(prompt.contains("不得相加"));
                return WikiSummaryPipelineTest.groundedResponse(prompt).replaceFirst("\\{", "{\"metrics\":{\"activeSeconds\":99999},");
            });
            var summary = summarizer.summarize(complete, java.time.Duration.ofSeconds(30));
            assertEquals(1, calls.get());
            assertEquals(complete.activeSeconds(), summary.metrics().activeSeconds());
            assertEquals(complete.statistics().get("titleSampling"), summary.metrics().extra().get("titleSampling"));
        }
    }

    @Test
    void aggregatesAllEventsAndPreservesFractionalMetricsAcrossChildren() throws Exception {
        try (Database db = new Database(tempDir.resolve("many"))) {
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            String host = java.net.InetAddress.getLocalHost().getHostName();
            String window = "watcher-window_" + host, afk = "watcher-afk_" + host;
            Instant start = Instant.parse("2026-09-01T04:00:00Z");
            db.metaConnection().setAutoCommit(false);
            try (var ps = db.metaConnection().prepareStatement("INSERT INTO events(bucket_id,timestamp,duration,datastr,app) VALUES(?,?,0.6,?,?)")) {
                for (int i = 0; i < 2501; i++) {
                    String app = i == 2500 ? "last-app" : "editor";
                    ps.setString(1, window); ps.setString(2, start.plusSeconds(i).toString());
                    ps.setString(3, "{\"app\":\"" + app + "\",\"title\":\"unknown\"}");
                    ps.setString(4, app); ps.addBatch();
                }
                ps.executeBatch();
            }
            db.metaConnection().commit(); db.metaConnection().setAutoCommit(true);
            events.insertEvent(afk, new Event(start, 1000, Map.of("status", "afk")));
            events.insertEvent(afk, new Event(start.plusSeconds(1000), 2600, Map.of("status", "not-afk")));
            WikiFactBuilder builder = new WikiFactBuilder(events, 12000);
            WikiPeriod period = new WikiPeriod(WikiLevel.HOUR, start, start.plusSeconds(3600), "UTC");
            var facts = builder.buildFacts(period);
            assertEquals(900.6, ((Number) facts.statistics().get("activeSecondsExact")).doubleValue(), 1e-9);
            assertTrue(facts.topApps().stream().anyMatch(a -> a.app().equals("last-app")));
            assertTrue(facts.titleSamples().isEmpty());
            assertEquals("complete", facts.sourceCoverage().get("afk").status());
            var metrics = new WikiEntry.WikiMetrics(facts.activeSeconds(), facts.afkSeconds(), facts.switchCount(), facts.topApps(), facts.statistics());
            var child = new WikiEntry("child", WikiLevel.DAY, start, period.end(), "UTC", WikiStatus.SUMMARIZED,
                    "summary", "task", java.util.List.of(), metrics, java.util.List.of(), "test", "v2", 0, null, null, start, start, start);
            var parent = builder.buildFactsFromChildren(java.util.List.of(child, child), period);
            assertEquals(1801.2, ((Number) parent.statistics().get("activeSecondsExact")).doubleValue(), 1e-9);
            assertEquals(1801, parent.activeSeconds());
        }
    }

    @Test
    void samplesContextTitlesAndNeverLegacyBodyText() throws Exception {
        try (Database db = new Database(tempDir.resolve("events"))) {
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            String host = java.net.InetAddress.getLocalHost().getHostName();
            String bucketId = "watcher-content_" + host;
            Instant start = Instant.parse("2026-08-30T01:00:00Z");

            Map<String, Object> titleEvent = new LinkedHashMap<>();
            titleEvent.put("schema_version", 2);
            titleEvent.put("app", "Weixin.exe");
            titleEvent.put("title", "微信");
            titleEvent.put("context_title", "项目讨论群");
            titleEvent.put("context_kind", "chat");
            titleEvent.put("title_source", "uia_context");
            titleEvent.put("title_confidence", "high");
            events.insertEvent(bucketId, new Event(start.plusSeconds(5), 2, titleEvent));

            // Seed a legacy row directly to prove the consumer ignores persisted v1 body text.
            try (PreparedStatement ps = db.bucketConnection(bucketId).prepareStatement("""
                    INSERT INTO events (bucket_id, timestamp, duration, datastr, app)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, bucketId);
                ps.setString(2, start.plusSeconds(10).toString());
                ps.setDouble(3, 2.0);
                ps.setString(4, "{\"app\":\"notes.exe\",\"title\":\"设计文档\","
                        + "\"text_content\":\"SELF_ANALYST_FORBIDDEN_BODY_7F3A\"}");
                ps.setString(5, "notes.exe");
                ps.executeUpdate();
            }

            WikiFactBuilder.WikiFacts facts = new WikiFactBuilder(events, 12_000).buildFacts(
                    new WikiPeriod(WikiLevel.HOUR, start, start.plusSeconds(3600), "UTC"));

            assertEquals(2, facts.contextTitleSamples().size());
            assertTrue(facts.contextTitleSamples().contains("[Weixin.exe] 项目讨论群"));
            assertTrue(facts.contextTitleSamples().contains("[notes.exe] 设计文档"));
            assertFalse(facts.contextTitleSamples().toString()
                    .contains("SELF_ANALYST_FORBIDDEN_BODY_7F3A"));
            assertEquals(WikiFactBuilder.FACT_BUILDER_VERSION, facts.factBuilderVersion());
            assertEquals("complete", facts.sourceCoverage().get("content").status());

            WikiFactBuilder.WikiFacts lagging = new WikiFactBuilder(
                    events, 12_000, () -> 42L).buildFacts(
                    new WikiPeriod(WikiLevel.HOUR, start, start.plusSeconds(3600), "UTC"));
            assertEquals("lagging", lagging.sourceCoverage().get("content").status());
            assertEquals(42L,
                    lagging.sourceCoverage().get("content").projectionLagSeconds());

            String windowBucket = "watcher-window_" + host;
            events.insertEvent(windowBucket, new Event(
                    start.plusSeconds(20), 2,
                    Map.of("app", "editor.exe", "title", "窗口标题样本")));
            WikiFactBuilder.WikiFacts bounded = new WikiFactBuilder(events, 20).buildFacts(
                    new WikiPeriod(WikiLevel.HOUR, start, start.plusSeconds(3600), "UTC"));
            int sampleChars = bounded.titleSamples().stream().mapToInt(String::length).sum()
                    + bounded.contextTitleSamples().stream().mapToInt(String::length).sum();
            assertTrue(sampleChars <= 20,
                    "window and context title samples must share one character budget");
        }
    }

    @Test
    void importedAwWatcherWindowBucketIsStillRecognized() throws Exception {
        try (Database db = new Database(tempDir.resolve("imported"))) {
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            String host = java.net.InetAddress.getLocalHost().getHostName();
            Instant start = Instant.parse("2026-08-30T01:00:00Z");
            events.insertEvent("aw-watcher-window_" + host, new Event(
                    start.plusSeconds(20), 2,
                    Map.of("app", "editor.exe", "title", "导入窗口")));

            WikiFactBuilder.WikiFacts facts = new WikiFactBuilder(events, 12_000).buildFacts(
                    new WikiPeriod(WikiLevel.HOUR, start, start.plusSeconds(3600), "UTC"));
            assertEquals("complete", facts.sourceCoverage().get("window").status());
            assertTrue(facts.titleSamples().contains("导入窗口"));
        }
    }
}
