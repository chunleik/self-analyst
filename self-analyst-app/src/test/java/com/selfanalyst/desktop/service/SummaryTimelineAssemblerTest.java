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

    @Test
    void failedHourExposesPartialHistoryAndItsOwnBudgetWithoutDuplicateMorning() {
        try (WikiStore store = new WikiStore(tempDir.resolve("blocked.db"))) {
            store.upsert(period("day", WikiLevel.DAY, START, END, WikiStatus.PENDING, null));
            Instant noon = START.plusSeconds(8 * 3600);
            store.upsert(period("morning", WikiLevel.HALF_DAY, START, noon, WikiStatus.SUMMARIZED, "上午主题原文"));
            store.upsert(period("afternoon", WikiLevel.HALF_DAY, noon, END, WikiStatus.PENDING, null));
            store.upsert(period("duplicate", WikiLevel.HOUR, START, START.plusSeconds(3600), WikiStatus.SUMMARIZED, "不应重复"));
            for (int hour = 8; hour < 24; hour++) {
                Instant start = START.plusSeconds(hour * 3600L);
                store.upsert(period("hour-" + hour, WikiLevel.HOUR, start, start.plusSeconds(3600),
                        hour == 15 ? WikiStatus.FAILED : WikiStatus.SUMMARIZED, hour == 15 ? null : "小时原文" + hour));
            }
            Map<String, Object> result = assembleLocal(store);
            assertEquals(true, result.get("incomplete"));
            assertEquals("wiki-partial", result.get("source"));
            var parts = (List<?>) result.get("partialSummaries");
            assertEquals(16, parts.size());
            assertEquals("上午主题原文", ((Map<?, ?>) parts.getFirst()).get("summary"));
            assertFalse(parts.toString().contains("不应重复"));
            var status = (Map<?, ?>) result.get("summaryStatus");
            assertEquals("waiting_dependencies", status.get("state"));
            var blocked = (List<?>) status.get("dependencies");
            assertEquals(1, blocked.size());
            var hour = (Map<?, ?>) blocked.getFirst();
            assertEquals(START.plusSeconds(15 * 3600).toString(), hour.get("periodStart"));
            assertEquals("failed", hour.get("state"));
            var progress = (Map<?, ?>) hour.get("generationProgress");
            assertEquals(7, progress.get("calls"));
            assertEquals("quality", progress.get("state"));
            assertFalse(status.toString().contains("PRIVATE"));
            assertFalse(result.containsKey("generationProgress")); // child budget is not the day's budget
            assertEquals("7小时", result.get("activeTime"));
            assertEquals(WikiStatus.PENDING, store.query(START, END, WikiLevel.DAY).getFirst().status());
            store.upsert(entry(List.of(), null));
            Map<String, Object> completed = assembleLocal(store);
            assertEquals("wiki", completed.get("source"));
            assertEquals(false, completed.get("incomplete"));
            assertFalse(completed.containsKey("partialSummaries"));
            assertFalse(completed.containsKey("summaryStatus"));
        }
    }

    @Test
    void unavailableWikiAndMissingDayAreExplicitlyIncomplete() {
        assertEquals(true, assembleLocal(null).get("incomplete"));
        assertEquals("unavailable", ((Map<?, ?>) assembleLocal(null).get("summaryStatus")).get("state"));
        try (WikiStore store = new WikiStore(tempDir.resolve("missing.db"))) {
            Map<String, Object> result = assembleLocal(store);
            assertEquals(true, result.get("incomplete"));
            assertEquals("missing", ((Map<?, ?>) result.get("summaryStatus")).get("state"));
            assertEquals(List.of(), result.get("partialSummaries"));
        }
    }

    private static Map<String, Object> assembleLocal(WikiStore store) {
        SummaryFactSource facts = new SummaryFactSource() {
            public SummaryService.LocalFacts currentStatus(Instant now) { throw new AssertionError("No current facts"); }
            public SummaryService.BehaviorData behaviorData() { throw new AssertionError("No advice"); }
            public SummaryService.LocalFacts factsFor(Instant start, Instant end, String label) {
                assertEquals(START, start);
                assertEquals(END, end);
                return new SummaryService.LocalFacts("应用统计", List.of("统计证据"), List.of("Editor 2小时"), "7小时", "3小时", 88, "");
            }
        };
        SummaryPromptService prompts = new SummaryPromptService() {
            @Override public EnhancedSummary enhance(SummaryService.LocalFacts facts, SummaryTextClient client) {
                throw new AssertionError("History must not call a model");
            }
        };
        return new SummaryTimelineAssembler(store, facts, prompts, ZONE).assembleClosed(
                new SummaryWindowClassifier.Slot("yesterday", "昨天", START, END, false, false, WikiLevel.DAY));
    }

    @Test
    void readyChildrenWaitForParentAndSkippedChildrenDoNotBecomeFailures() {
        try (WikiStore store = new WikiStore(tempDir.resolve("ready.db"))) {
            store.upsert(period("day", WikiLevel.DAY, START, END, WikiStatus.PENDING, null));
            store.upsert(period("morning", WikiLevel.HALF_DAY, START, START.plusSeconds(8 * 3600), WikiStatus.SKIPPED, null));
            store.upsert(period("afternoon", WikiLevel.HALF_DAY, START.plusSeconds(8 * 3600), END, WikiStatus.SUMMARIZED, "下午主题"));
            var result = assembleLocal(store);
            var status = (Map<?, ?>) result.get("summaryStatus");
            assertEquals("pending", status.get("state"));
            assertEquals(List.of(), status.get("dependencies"));
            assertEquals(1, ((List<?>) result.get("partialSummaries")).size());
            store.upsert(period("day", WikiLevel.DAY, START, END, WikiStatus.SKIPPED, null));
            var skipped = assembleLocal(store);
            assertEquals("skipped", ((Map<?, ?>) skipped.get("summaryStatus")).get("state"));
            assertEquals(List.of(), skipped.get("partialSummaries"));
        }
    }

    @Test
    void queryFailureIsNotReportedAsMissingHistory() {
        try (WikiStore store = new WikiStore(tempDir.resolve("unavailable.db")) {
            @Override public List<WikiEntry> query(Instant start, Instant end, WikiLevel level) {
                throw new IllegalStateException("PRIVATE database error");
            }
        }) {
            var result = assembleLocal(store);
            assertEquals("local", result.get("source"));
            assertEquals("unavailable", ((Map<?, ?>) result.get("summaryStatus")).get("state"));
            assertFalse(result.toString().contains("PRIVATE"));
        }
    }

    @Test
    void childLookupIgnoresWrongTimezoneVersionAndNonExactRanges() {
        Instant noon = START.plusSeconds(8 * 3600);
        WikiEntry old = period("old", WikiLevel.HALF_DAY, START, noon, WikiStatus.SUMMARIZED, "旧版本");
        WikiEntry legacy = new WikiEntry(old.id(), old.level(), old.periodStart(), old.periodEnd(), old.timezone(), old.status(),
                old.summary(), old.primaryTask(), old.taskSegments(), old.metrics(), old.sourceEntryIds(), old.model(), old.promptVersion(),
                0, null, null, START, END, null, null, null, Map.of(), "legacy", "legacy");
        WikiEntry otherZone = new WikiEntry("other", WikiLevel.HALF_DAY, START, noon, "UTC", WikiStatus.SUMMARIZED,
                "其他时区", "其他时区", List.of(), null, List.of(), null, null, 0, null, null, START, END, null);
        try (WikiStore store = new WikiStore(tempDir.resolve("boundaries.db")) {
            @Override public List<WikiEntry> query(Instant start, Instant end, WikiLevel level) {
                if (level == WikiLevel.HALF_DAY) return List.of(legacy, otherZone,
                        period("overlap", level, START.minusSeconds(3600), noon, WikiStatus.SUMMARIZED, "越界"));
                return List.of();
            }
        }) {
            var result = assembleLocal(store);
            assertEquals(List.of(), result.get("partialSummaries"));
            assertFalse(result.toString().contains("越界"));
            assertEquals(24, ((List<?>) ((Map<?, ?>) result.get("summaryStatus")).get("dependencies")).size());
        }
    }

    @Test
    void halfDayUsesExactHoursAndRetainsItsOwnFailureMetadata() {
        Instant noon = START.plusSeconds(8 * 3600);
        try (WikiStore store = new WikiStore(tempDir.resolve("half.db"))) {
            store.upsert(period("half", WikiLevel.HALF_DAY, START, noon, WikiStatus.FAILED, null));
            store.upsert(period("hour", WikiLevel.HOUR, START, START.plusSeconds(3600), WikiStatus.SUMMARIZED, "小时主题"));
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            ClosedSummaryHistory.attach(store, new SummaryWindowClassifier.Slot("morning", "上午", START, noon,
                    false, false, WikiLevel.HALF_DAY), ZONE, result);
            assertEquals("wiki-partial", result.get("source"));
            assertEquals("failed", ((Map<?, ?>) result.get("summaryStatus")).get("state"));
            assertEquals(7, ((Map<?, ?>) result.get("generationProgress")).get("calls"));
            assertEquals(7, ((List<?>) ((Map<?, ?>) result.get("summaryStatus")).get("dependencies")).size());
        }
    }

    @Test
    void legacyFailureKeepsSafeReasonAndRetryWithoutInventingBudget() {
        try (WikiStore store = new WikiStore(tempDir.resolve("legacy-failure.db"))) {
            for (String error : List.of("WIKI_EVIDENCE_UNSUPPORTED_CLAIM:taskSegments.summary", "PRIVATE provider response")) {
                store.upsert(new WikiEntry("day", WikiLevel.DAY, START, END, ZONE.getId(), WikiStatus.FAILED,
                        null, null, List.of(), null, List.of(), null, null, 2, END.plusSeconds(3600), error, START, END, null));
                var result = assembleLocal(store);
                var progress = (Map<?, ?>) result.get("generationProgress");
                assertEquals(END.plusSeconds(3600).toString(), progress.get("nextRetryAt"));
                assertFalse(progress.containsKey("calls"));
                assertFalse(result.toString().contains("PRIVATE"));
                if (error.startsWith("WIKI_")) {
                    assertEquals(error, progress.get("reason"));
                    assertEquals("quality", progress.get("state"));
                } else assertFalse(progress.containsKey("reason"));
            }
        }
    }

    private static WikiEntry period(String id, WikiLevel level, Instant start, Instant end, WikiStatus status, String summary) {
        var progress = Map.<String, Object>of("state", "quality", "reason", "WIKI_EVIDENCE_UNSUPPORTED_CLAIM:taskSegments.summary",
                "calls", 7, "maxCalls", 12, "tokens", 31505, "maxTokens", 256000, "configurationStamp", "PRIVATE");
        var metrics = new WikiEntry.WikiMetrics(60, 0, 1, List.of(),
                status == WikiStatus.FAILED ? Map.of("generationProgress", progress) : Map.of());
        return new WikiEntry(id, level, start, end, ZONE.getId(), status, summary, summary,
                List.of(), metrics, List.of(), null, null, status == WikiStatus.FAILED ? 7 : 0,
                status == WikiStatus.FAILED ? END.plusSeconds(3600) : null, null, START, END, null);
    }

    private static WikiEntry entry(List<WikiEntry.TaskSegment> tasks, WikiEntry.WikiMetrics metrics) {
        return new WikiEntry("day", WikiLevel.DAY, START, END, ZONE.getId(), WikiStatus.SUMMARIZED,
                "摘要全文", "主要任务", tasks, metrics, List.of(), "test", "v1", 0, null, null,
                START, END, END);
    }
}
