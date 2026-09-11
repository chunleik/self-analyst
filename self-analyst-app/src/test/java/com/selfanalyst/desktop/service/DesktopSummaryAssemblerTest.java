package com.selfanalyst.desktop.service;

import com.selfanalyst.i18n.Lang;
import com.selfanalyst.wiki.WikiEntry;
import com.selfanalyst.wiki.WikiLevel;
import com.selfanalyst.wiki.WikiStatus;
import com.selfanalyst.wiki.WikiStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSummaryAssemblerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant AFTERNOON = Instant.parse("2026-09-06T06:00:00Z");

    @TempDir
    Path tempDir;

    private WikiStore wikiStore;

    @AfterEach
    void tearDown() {
        if (wikiStore != null) {
            wikiStore.close();
        }
    }

    @Test
    void responseAlwaysHasRequiredFieldsAndReusesWikiForYesterday() {
        wikiStore = new WikiStore(tempDir.resolve("wiki.db"));
        upsertDay("昨天写方案", yesterdayStart(), yesterdayEnd());

        AtomicInteger llmCalls = new AtomicInteger();
        CountingFacts facts = new CountingFacts();
        Map<String, Object> result = assemble(facts, countingClient(llmCalls), true, 4);

        assertNotNull(result.get("current"));
        assertNotNull(result.get("timeline"));
        assertNotNull(result.get("behaviorAdvice"));
        Map<String, Object> yesterday = entry(result, "yesterday");
        assertEquals("昨天写方案", yesterday.get("headline"));
        assertEquals("wiki", yesterday.get("source"));
        assertTrue(llmCalls.get() <= 3, "LLM is only for open windows plus at most one advice");
    }

    @Test
    void pendingWikiFallsBackToLocalFactsWithoutLlm() {
        wikiStore = new WikiStore(tempDir.resolve("wiki.db"));
        wikiStore.upsert(pendingDay());

        AtomicInteger llmCalls = new AtomicInteger();
        Map<String, Object> result = assemble(new CountingFacts(), countingClient(llmCalls), true, 4);
        Map<String, Object> yesterday = entry(result, "yesterday");
        assertEquals("local", yesterday.get("source"));
        assertEquals("昨天本地", yesterday.get("headline"));
    }

    @Test
    void weekPendingComposesCompletedDays() {
        wikiStore = new WikiStore(tempDir.resolve("wiki.db"));
        upsertDay("周五完成评审", dayStart(LocalDate.of(2026, 9, 4)), dayEnd(LocalDate.of(2026, 9, 4)));
        wikiStore.upsert(new WikiEntry("week-pending", WikiLevel.WEEK,
                dayStart(LocalDate.of(2026, 8, 31)), dayEnd(LocalDate.of(2026, 9, 6)),
                ZONE.getId(), WikiStatus.PENDING, null, null, List.of(),
                new WikiEntry.WikiMetrics(0, 0, 0, List.of(), Map.of()),
                List.of(), null, null, 0, null, null, Instant.now(), Instant.now(), null));

        Map<String, Object> result = assemble(new CountingFacts(), countingClient(new AtomicInteger()), true, 4);
        Map<String, Object> week = entry(result, "thisWeek");
        assertEquals("wiki-partial", week.get("source"));
        assertEquals(true, week.get("incomplete"));
        assertTrue(String.valueOf(week.get("insight")).contains("周五完成评审"));
    }

    @Test
    void secondRequestReusesAdviceAndSkipsOpenWindowLlm() {
        wikiStore = new WikiStore(tempDir.resolve("wiki.db"));
        CountingFacts facts = new CountingFacts();
        AtomicInteger llmCalls = new AtomicInteger();
        assemble(facts, countingClient(llmCalls), true, 4);
        int firstBehavior = facts.behaviorCalls.get();
        int firstLlm = llmCalls.get();
        assertTrue(firstBehavior > 0);
        assertTrue(firstLlm > 0);

        assemble(facts, countingClient(llmCalls), true, 4);
        assertEquals(firstBehavior, facts.behaviorCalls.get());
        assertEquals(firstLlm, llmCalls.get());
    }

    @Test
    void fingerprintChangeRegeneratesOneAdvice() {
        wikiStore = new WikiStore(tempDir.resolve("wiki.db"));
        CountingFacts facts = new CountingFacts();
        assemble(facts, countingClient(new AtomicInteger()), true, 4);
        int first = facts.behaviorCalls.get();
        facts.today = new SummaryService.LocalFacts("今天大变", List.of(), List.of("Other 2小时"),
                "2小时", "0秒", 80, "");
        assemble(facts, countingClient(new AtomicInteger()), true, 4);
        assertEquals(first + 1, facts.behaviorCalls.get());
    }

    @Test
    void budgetBlockAndZeroCapSkipLlmButKeepWiki() {
        wikiStore = new WikiStore(tempDir.resolve("wiki.db"));
        upsertDay("昨天复盘", yesterdayStart(), yesterdayEnd());
        AtomicInteger llmCalls = new AtomicInteger();
        Map<String, Object> blocked = assemble(new CountingFacts(), countingClient(llmCalls), false, 4);
        assertEquals(0, llmCalls.get());
        assertEquals("wiki", entry(blocked, "yesterday").get("source"));

        Map<String, Object> capped = assemble(new CountingFacts(), countingClient(llmCalls), true, 0);
        assertEquals(0, llmCalls.get());
        assertEquals("wiki", entry(capped, "yesterday").get("source"));
        assertNotNull(entry(capped, "current").get("headline"));
    }

    @Test
    void adviceFailureKeepsCurrentAndTimeline() {
        wikiStore = new WikiStore(tempDir.resolve("wiki.db"));
        CountingFacts facts = new CountingFacts() {
            @Override
            public SummaryService.BehaviorData behaviorData() {
                throw new IllegalStateException("boom");
            }
        };
        Map<String, Object> result = assemble(facts, countingClient(new AtomicInteger()), true, 4);
        assertNotNull(result.get("current"));
        assertFalse(((List<?>) result.get("timeline")).isEmpty());
        assertEquals("empty", ((Map<?, ?>) result.get("behaviorAdvice")).get("type"));
    }

    @Test
    void snapshotDoesNotContainSecrets() throws Exception {
        wikiStore = new WikiStore(tempDir.resolve("wiki.db"));
        assemble(new CountingFacts(), (prompt, timeout) -> {
            assertFalse(prompt.contains("sk-secret"));
            return "{\"headline\":\"ok\",\"insight\":\"i\",\"suggestion\":\"s\",\"confidence\":\"low\"}";
        }, true, 4);
        String json = Files.readString(tempDir.resolve(SummarySnapshotStore.FILE_NAME));
        assertFalse(json.contains("sk-secret"));
        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains("text_content"));
    }

    private Map<String, Object> assemble(SummaryFactSource facts,
                                         SummaryPromptService.SummaryTextClient client,
                                         boolean llmAvailable,
                                         int cap) {
        Clock clock = Clock.fixed(AFTERNOON, ZONE);
        DesktopSummaryAssembler assembler = new DesktopSummaryAssembler(
                facts, new BehaviorAdviceService(), new SummaryPromptService(),
                new SummarySnapshotStore(tempDir), wikiStore, clock);
        return assembler.assemble(new DesktopSummaryAssembler.Request(
                llmAvailable, cap, Lang.chinese(), client));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(Map<String, Object> result, String key) {
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) result.get("timeline");
        return timeline.stream()
                .filter(item -> key.equals(item.get("key")))
                .findFirst()
                .orElseThrow();
    }

    private static SummaryPromptService.SummaryTextClient countingClient(AtomicInteger calls) {
        return (prompt, timeout) -> {
            calls.incrementAndGet();
            return "{\"headline\":\"llm\",\"insight\":\"insight\",\"suggestion\":\"go\",\"confidence\":\"low\",\"title\":\"t\",\"body\":\"b\"}";
        };
    }

    private void upsertDay(String summary, Instant start, Instant end) {
        WikiEntry entry = new WikiEntry("day-" + start, WikiLevel.DAY, start, end,
                ZONE.getId(), WikiStatus.PENDING, null, null, List.of(),
                new WikiEntry.WikiMetrics(3600, 0, 4,
                        List.of(new WikiEntry.AppDuration("IDEA", 3600)), Map.of()),
                List.of(), null, null, 0, null, null, Instant.now(), Instant.now(), null);
        wikiStore.upsert(entry);
        wikiStore.updateStatus(entry.id(), WikiStatus.SUMMARIZED, summary, summary,
                List.of(), entry.metrics(), List.of(), "m", "v1");
    }

    private WikiEntry pendingDay() {
        return new WikiEntry("pending-yesterday", WikiLevel.DAY, yesterdayStart(), yesterdayEnd(),
                ZONE.getId(), WikiStatus.PENDING, null, null, List.of(),
                new WikiEntry.WikiMetrics(0, 0, 0, List.of(), Map.of()),
                List.of(), null, null, 0, null, null, Instant.now(), Instant.now(), null);
    }

    private static Instant yesterdayStart() {
        return dayStart(LocalDate.of(2026, 9, 5));
    }

    private static Instant yesterdayEnd() {
        return dayEnd(LocalDate.of(2026, 9, 5));
    }

    private static Instant dayStart(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    private static Instant dayEnd(LocalDate day) {
        return day.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    private static class CountingFacts implements SummaryFactSource {
        final AtomicInteger behaviorCalls = new AtomicInteger();
        SummaryService.LocalFacts current = new SummaryService.LocalFacts(
                "当前本地", List.of("主要应用: IDE"), List.of("IDE 20分钟"), "20分钟", "0秒", 8, "");
        SummaryService.LocalFacts today = new SummaryService.LocalFacts(
                "今天本地", List.of(), List.of("IDE 1小时"), "1小时", "0秒", 20, "");
        SummaryService.LocalFacts closed = new SummaryService.LocalFacts(
                "昨天本地", List.of(), List.of("邮件 10分钟"), "10分钟", "0秒", 3, "");

        @Override
        public SummaryService.LocalFacts currentStatus(Instant now) {
            return current;
        }

        @Override
        public SummaryService.LocalFacts factsFor(Instant start, Instant end, String label) {
            if ("今天".equals(label)) {
                return today;
            }
            if ("昨天".equals(label)) {
                return closed;
            }
            return new SummaryService.LocalFacts(label + "本地", List.of(), List.of(), "0秒", "0秒", 0, "");
        }

        @Override
        public SummaryService.BehaviorData behaviorData() {
            behaviorCalls.incrementAndGet();
            return new SummaryService.BehaviorData(7, 10, 100, 0, 0, 10, 10, List.of());
        }
    }
}
