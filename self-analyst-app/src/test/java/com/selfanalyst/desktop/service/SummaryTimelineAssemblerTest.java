package com.selfanalyst.desktop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.wiki.WikiEntry;
import com.selfanalyst.wiki.WikiLevel;
import com.selfanalyst.wiki.WikiStatus;
import com.selfanalyst.wiki.WikiStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SummaryTimelineAssemblerTest {
    private static final Instant START = Instant.parse("2026-10-06T20:00:00Z");
    private static final Instant END = START.plusSeconds(86400);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    @TempDir Path tempDir;

    @Test
    void storedTopicsPreserveOrderFullTextAndSerializedFieldsWithoutLocalOrModelWork() throws Exception {
        String fullText = "完整主题说明，不应截断。".repeat(80);
        var tasks = List.of(new WikiEntry.TaskSegment("主题一", fullText, List.of("标题事实"),
                List.of("Editor"), "low", List.of("f1"), "inferred"),
                new WikiEntry.TaskSegment("主题二", "第二项", List.of(), List.of(), "low"));
        try (WikiStore store = new WikiStore(tempDir.resolve("wiki.db"))) {
            store.upsert(entry(tasks, new WikiEntry.WikiMetrics(120, 30, 2,
                    List.of(new WikiEntry.AppDuration("Editor", 120)), Map.of())));
            Map<String, Object> result = assemble(store);
            assertEquals(tasks, result.get("taskSegments"));
            assertEquals("摘要全文", result.get("insight"));
            assertEquals("主要任务", result.get("headline"));
            assertEquals("wiki", result.get("source"));
            assertEquals(false, result.get("incomplete"));
            assertEquals(2, result.get("switchCount"));
            assertFalse(((List<?>) result.get("topApps")).isEmpty());
            assertEquals(List.of(), result.get("evidence"));
            var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(result));
            assertEquals(fullText, json.path("taskSegments").get(0).path("summary").asText());
            assertEquals("主题二", json.path("taskSegments").get(1).path("title").asText());
            assertEquals("f1", json.path("taskSegments").get(0).path("evidenceFactIds").get(0).asText());
            assertEquals("inferred", json.path("taskSegments").get(0).path("claimType").asText());
        }
    }

    @Test
    void storedTopicsRemainAvailableWithoutMetrics() {
        var tasks = List.of(new WikiEntry.TaskSegment("主题", "说明", null, null, "low"));
        try (WikiStore store = new WikiStore(tempDir.resolve("wiki.db"))) {
            store.upsert(entry(tasks, null));
            Map<String, Object> result = assemble(store);
            assertEquals(tasks, result.get("taskSegments"));
            assertEquals(List.of(), result.get("evidence"));
            assertEquals("0秒", result.get("activeTime"));
            assertEquals(0, result.get("switchCount"));
        }
    }

    @Test
    void inMemoryEntryWithoutMetricsPreservesTopics() {
        var tasks = List.of(new WikiEntry.TaskSegment("主题", "说明", null, null, "low"));
        try (WikiStore store = new WikiStore(tempDir.resolve("no-metrics.db")) {
            @Override public List<WikiEntry> query(Instant start, Instant end, WikiLevel level) {
                return List.of(entry(tasks, null));
            }
        }) {
            Map<String, Object> result = assemble(store);
            assertEquals(tasks, result.get("taskSegments"));
            assertEquals("", result.get("activeTime"));
            assertEquals(List.of(), result.get("evidence"));
        }
    }

    @Test
    void legacyNullStoredTopicsSerializeAsEmptyArray() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        try (WikiStore store = new WikiStore(db)) {
            store.upsert(entry(null, null));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE wiki_entries SET task_segments_json = NULL");
            }
            Map<String, Object> result = assemble(store);
            assertEquals(List.of(), result.get("taskSegments"));
            assertEquals("摘要全文", result.get("insight"));
            assertEquals("主要任务", result.get("headline"));
            assertEquals("low", result.get("confidence"));
            assertTrue(new ObjectMapper().writeValueAsString(result).contains("\"taskSegments\":[]"));
        }
    }

    private static Map<String, Object> assemble(WikiStore store) {
        // Successful stored-Wiki mapping must not access local facts or prompt services.
        return new SummaryTimelineAssembler(store, null, null, ZONE).assembleClosed(
                new SummaryWindowClassifier.Slot("yesterday", "昨天", START, END, false, false, WikiLevel.DAY));
    }

    private static WikiEntry entry(List<WikiEntry.TaskSegment> tasks, WikiEntry.WikiMetrics metrics) {
        return new WikiEntry("day", WikiLevel.DAY, START, END, ZONE.getId(), WikiStatus.SUMMARIZED,
                "摘要全文", "主要任务", tasks, metrics, List.of(), "test", "v1", 0, null, null,
                START, END, END);
    }
}
