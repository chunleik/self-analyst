package com.selfanalyst.wiki;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.store.Database;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.PulseTimeConfig;
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

    @TempDir
    Path tempDir;

    @Test
    void samplesContextTitlesAndNeverLegacyBodyText() throws Exception {
        try (Database db = new Database(tempDir.resolve("aw-data"))) {
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            String host = java.net.InetAddress.getLocalHost().getHostName();
            String bucketId = "aw-watcher-content_" + host;
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

            String windowBucket = "aw-watcher-window_" + host;
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
}
