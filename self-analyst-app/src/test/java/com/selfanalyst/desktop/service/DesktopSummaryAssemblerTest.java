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
    void crossingFourRefreshesTextEvenWhenMetricsMatch() {
        CountingFacts facts = new CountingFacts();
        AtomicInteger calls = new AtomicInteger();
        SummarySnapshotStore snapshots = new SummarySnapshotStore(tempDir);
        Instant before = Instant.parse("2026-09-05T19:59:59Z");
        for (Instant at : List.of(before, before.plusSeconds(1))) {
            var assembler = new DesktopSummaryAssembler(facts, new BehaviorAdviceService(),
                    new SummaryPromptService(), snapshots, null, Clock.fixed(at, ZONE));
            assembler.assemble(new DesktopSummaryAssembler.Request(true, 4, Lang.chinese(), countingClient(calls)));
        }
        assertEquals(2, facts.behaviorCalls.get(), "a new statistical day invalidates old text and advice");
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
    void historicalWikiDoesNotUpgradeTaskConfidenceToHigh() {
        wikiStore = new WikiStore(tempDir.resolve("wiki.db"));
        WikiEntry pending = pendingDay(); wikiStore.upsert(pending);
        wikiStore.updateStatus(pending.id(), WikiStatus.SUMMARIZED, "查看项目资料", "项目资料查看",
                List.of(new WikiEntry.TaskSegment("项目资料", "查看相关标题", List.of("标题证据"),
                        List.of("Editor"), "low", List.of("f1"), "inferred")),
                pending.metrics(), List.of(), "model", "v8");
        Map<String, Object> result = assemble(new CountingFacts(), countingClient(new AtomicInteger()), false, 0);
        assertEquals("low", entry(result, "yesterday").get("confidence"));
    }

    @Test
    void periodBudgetPauseShowsProgressAlongsideLocalHistoricalFacts() {
        wikiStore = new WikiStore(tempDir.resolve("wiki.db"));
        WikiEntry pending = pendingDay();
        wikiStore.upsert(pending);
        wikiStore.markGenerationFailure(pending, null, Map.of("state", "period_budget", "reason", "WIKI_PERIOD_BUDGET_EXHAUSTED",
                "calls", 12, "maxCalls", 12, "configurationStamp", "PRIVATE_CONFIG"),
                Instant.parse("9999-12-31T00:00:00Z"), false);
        Map<String, Object> result = assemble(new CountingFacts(), countingClient(new AtomicInteger()), false, 0);
        Map<String, Object> yesterday = entry(result, "yesterday");
        assertEquals("昨天本地", yesterday.get("headline"));
        assertEquals(true, yesterday.get("incomplete"));
        Map<?, ?> progress = (Map<?, ?>) yesterday.get("generationProgress");
        assertEquals("period_budget", progress.get("state"));
        assertEquals(12, progress.get("calls")); assertFalse(progress.containsKey("configurationStamp"));
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

    @Test
    void newTitleThemeRefreshesCurrentWindowsAndPreservesHistoricalWiki() {
        wikiStore = new WikiStore(tempDir.resolve("wiki.db"));
        upsertDay("昨天写方案", yesterdayStart(), yesterdayEnd());
        CountingFacts facts = titledFacts();
        AtomicInteger calls = new AtomicInteger();
        SummaryPromptService.SummaryTextClient client = (prompt, timeout) -> {
            calls.incrementAndGet();
            assertEquals(java.time.Duration.ofSeconds(5), timeout);
            return SummaryPromptServiceTest.validResponse();
        };
        var first = assemble(facts, client, true, 4);
        assertEquals(2, calls.get(), "Only current and today need model enhancement");
        assertEquals("昨天写方案", entry(first, "yesterday").get("headline"));
        assertFalse(((List<?>) entry(first, "current").get("taskSegments")).isEmpty());

        var original = facts.current;
        var originalFact = original.titleFacts().facts().getFirst();
        var renumbered = new com.selfanalyst.wiki.WikiTitleSampler.Fact("f9", originalFact.source(),
                originalFact.app(), originalFact.title(), originalFact.kind(), originalFact.activeSeconds(),
                originalFact.occurrences(), originalFact.intervals(), originalFact.omittedIntervals());
        facts.current = new SummaryService.LocalFacts(original.headline(), original.evidence(), original.topApps(),
                original.activeTime(), original.afkTime(), original.switchCount(), original.goalContext(),
                original.unknownActivitySeconds(), original.coverage(),
                new com.selfanalyst.wiki.WikiTitleSampler.Selection(List.of(renumbered), "renumbered projection",
                        original.titleFacts().coverage()));
        facts.today = facts.current;
        var cached = assemble(facts, client, true, 4);
        assertEquals(2, calls.get());
        assertEquals("snapshot", entry(cached, "current").get("source"));
        // Disk snapshots deserialize records as maps; compare their JSON forms.
        assertEquals(asJson(entry(first, "current").get("taskSegments")),
                asJson(entry(cached, "current").get("taskSegments")));
        assertEquals(asJson(entry(first, "current").get("titleFacts")),
                asJson(entry(cached, "current").get("titleFacts")));
        assertTrue(asJson(entry(cached, "current").get("taskSegments")).contains("\"f1\""));
        assertFalse(asJson(entry(cached, "current").get("titleFacts")).contains("\"f9\""),
                "Cached task IDs must remain paired with their cached source facts");

        facts.current = SummaryPromptServiceTest.facts("数据库索引文档", false);
        facts.today = facts.current;
        var refreshed = assemble(facts, client, true, 4);
        assertEquals(4, calls.get(), "A new topic refreshes the two open windows only");
        assertEquals("wiki", entry(refreshed, "yesterday").get("source"));
        assertEquals("昨天写方案", entry(refreshed, "yesterday").get("headline"));
        assertTrue(asJson(entry(refreshed, "current").get("titleFacts")).contains("数据库索引文档"));
        assertFalse(asJson(entry(refreshed, "current").get("titleFacts")).contains("连接超时参数调试"));
    }

    @Test
    void currentTimeoutKeepsLocalFactsAndClosedWikiSnapshot() {
        wikiStore = new WikiStore(tempDir.resolve("wiki.db"));
        upsertDay("昨天写方案", yesterdayStart(), yesterdayEnd());
        CountingFacts facts = titledFacts();
        assemble(facts, (prompt, timeout) -> SummaryPromptServiceTest.validResponse(), true, 2);
        facts.current = SummaryPromptServiceTest.facts("数据库索引文档", false);
        facts.today = facts.current;
        AtomicInteger calls = new AtomicInteger();
        var result = assemble(facts, (prompt, timeout) -> {
            calls.incrementAndGet();
            assertEquals(java.time.Duration.ofSeconds(5), timeout);
            throw new IllegalStateException("timeout");
        }, true, 2);
        assertEquals(2, calls.get());
        assertEquals(facts.current.headline(), entry(result, "current").get("headline"));
        assertEquals(facts.current.activeTime(), entry(result, "current").get("activeTime"));
        assertEquals("昨天写方案", entry(result, "yesterday").get("headline"));
    }

    @Test
    void currentEnhancementWorksWithWikiDisabledAndRespectsCallCap() {
        AtomicInteger calls = new AtomicInteger();
        var result = assemble(titledFacts(), (prompt, timeout) -> {
            calls.incrementAndGet();
            return SummaryPromptServiceTest.validResponse();
        }, true, 1);
        assertEquals(1, calls.get());
        assertEquals("查看连接超时参数", entry(result, "current").get("headline"));
        assertEquals("主要在 Chrome", entry(result, "today").get("headline"));
        assertEquals("local", entry(result, "yesterday").get("source"));
    }

    @Test
    void emptyCurrentFactsDoNotConsumeTodaysModelAllowance() {
        var facts = titledFacts();
        facts.current = new SummaryService.LocalFacts("当前无数据", List.of(), List.of(), "0秒", "0秒", 0, "");
        AtomicInteger calls = new AtomicInteger();
        var result = assemble(facts, (prompt, timeout) -> {
            calls.incrementAndGet();
            return SummaryPromptServiceTest.validResponse();
        }, true, 1);
        assertEquals(1, calls.get());
        assertEquals("查看连接超时参数", entry(result, "today").get("headline"));
    }

    @Test
    void smallAppDurationChangesReuseModelTextWhileLiveStatisticsRefresh() {
        var facts = titledFacts();
        facts.current = SummaryFactFingerprintTest.withAppSeconds(Map.of("Chrome", 185.0), List.of("Chrome 3分5秒"));
        facts.today = facts.current;
        AtomicInteger calls = new AtomicInteger();
        SummaryPromptService.SummaryTextClient client = (prompt, timeout) -> {
            calls.incrementAndGet();
            return SummaryPromptServiceTest.validResponse();
        };
        assemble(facts, client, true, 2);
        assertEquals(2, calls.get());
        facts.current = SummaryFactFingerprintTest.withAppSeconds(Map.of("Chrome", 187.0), List.of("Chrome 3分7秒"));
        facts.today = facts.current;
        var cached = assemble(facts, client, true, 2);
        assertEquals(2, calls.get(), "Tiny displayed duration changes should not call the model again");
        assertEquals(List.of("Chrome 3分7秒"), entry(cached, "current").get("topApps"));
        assertEquals("snapshot", entry(cached, "current").get("source"));
        facts.current = SummaryFactFingerprintTest.withAppSeconds(Map.of("Chrome", 300.0), List.of("Chrome 5分钟"));
        facts.today = facts.current;
        assemble(facts, client, true, 2);
        assertEquals(4, calls.get(), "A five-minute bucket change should refresh the model text");
    }

    @Test
    void languageChangeRefreshesCurrentTextAndLocalizedEvidence() {
        var facts = titledFacts();
        AtomicInteger calls = new AtomicInteger();
        SummaryPromptService.SummaryTextClient client = (prompt, timeout) -> {
            calls.incrementAndGet();
            return prompt.contains("one English sentence")
                    ? SummaryPromptServiceTest.validResponse().replace("查看连接超时参数", "Inspecting connection settings")
                    : SummaryPromptServiceTest.validResponse();
        };
        var chinese = assembleAt(facts, client, true, 2, AFTERNOON, Lang.chinese());
        var english = assembleAt(facts, client, true, 2, AFTERNOON.plusSeconds(60), Lang.english());
        assertEquals(4, calls.get());
        assertFalse(chinese.get("currentWindowFingerprint").equals(english.get("currentWindowFingerprint")));
        assertEquals("Inspecting connection settings", entry(english, "current").get("headline"));
        assertTrue(asJson(entry(english, "current").get("taskSegments")).contains("Observed title:"));
        assertFalse(asJson(entry(english, "current").get("taskSegments")).contains("观察到标题"));
    }

    @Test
    void minutePollingDoesNotRenewFailedTextAndRetriesAfterFifteenMinutes() {
        var facts = titledFacts();
        AtomicInteger calls = new AtomicInteger();
        SummaryPromptService.SummaryTextClient client = (prompt, timeout) -> {
            int number = calls.incrementAndGet();
            if (number <= 2) throw new IllegalStateException("timeout");
            return SummaryPromptServiceTest.validResponse();
        };
        for (int minute = 0; minute <= 16; minute++) {
            Instant at = AFTERNOON.plusSeconds(minute * 60L);
            var result = assembleAt(facts, client, true, 2, at, Lang.chinese());
            assertEquals(at.toString(), result.get("assembledAt"));
            String expectedTextAt = (minute <= 15 ? AFTERNOON : at).toString();
            assertEquals(expectedTextAt, entry(result, "current").get("textGeneratedAt"));
            assertEquals(expectedTextAt, entry(result, "today").get("textGeneratedAt"));
            if (minute <= 15) {
                assertEquals(2, calls.get());
                assertEquals(facts.current.headline(), entry(result, "current").get("headline"));
            } else {
                assertEquals(4, calls.get(), "Polling must not keep a failed attempt fresh forever");
                assertEquals("查看连接超时参数", entry(result, "current").get("headline"));
            }
        }
    }

    @Test
    void oldSnapshotUsesOriginalAssemblyTimeWhenTextTimeIsMissing() {
        var facts = titledFacts();
        AtomicInteger calls = new AtomicInteger();
        SummaryPromptService.SummaryTextClient client = (prompt, timeout) -> {
            calls.incrementAndGet();
            return SummaryPromptServiceTest.validResponse();
        };
        assembleAt(facts, client, true, 2, AFTERNOON, Lang.chinese());
        var store = new SummarySnapshotStore(tempDir);
        var saved = store.load(ZONE).orElseThrow();
        var current = new java.util.LinkedHashMap<>(saved.current());
        current.remove("textGeneratedAt");
        var timeline = saved.timeline().stream().map(item -> {
            Map<String, Object> old = new java.util.LinkedHashMap<>(item);
            old.remove("textGeneratedAt");
            return old;
        }).toList();
        store.save(new SummarySnapshot(saved.assembledAt(), saved.currentWindowFingerprint(), current, timeline,
                saved.behaviorAdvice(), saved.statisticsVersion(), saved.calendarVersion(), saved.timezone()));
        var cached = assembleAt(facts, client, true, 2, AFTERNOON.plusSeconds(60), Lang.chinese());
        assertEquals(2, calls.get());
        assertEquals(AFTERNOON.toString(), entry(cached, "current").get("textGeneratedAt"));
        assertEquals(AFTERNOON.toString(), entry(cached, "today").get("textGeneratedAt"));
        assembleAt(facts, client, true, 2, AFTERNOON.plusSeconds(16 * 60), Lang.chinese());
        assertEquals(4, calls.get());
    }

    @Test
    void staleTodayTextRefreshesBothWindowsEvenWhenCurrentTextIsFresh() {
        var facts = titledFacts();
        AtomicInteger calls = new AtomicInteger();
        SummaryPromptService.SummaryTextClient client = (prompt, timeout) -> {
            calls.incrementAndGet();
            return SummaryPromptServiceTest.validResponse();
        };
        assembleAt(facts, client, true, 2, AFTERNOON, Lang.chinese());
        var store = new SummarySnapshotStore(tempDir);
        var saved = store.load(ZONE).orElseThrow();
        var current = new java.util.LinkedHashMap<>(saved.current());
        current.put("textGeneratedAt", AFTERNOON.plusSeconds(10 * 60).toString());
        store.save(new SummarySnapshot(AFTERNOON.plusSeconds(10 * 60).toString(), saved.currentWindowFingerprint(),
                current, saved.timeline(), saved.behaviorAdvice(), saved.statisticsVersion(), saved.calendarVersion(), saved.timezone()));
        Instant at = AFTERNOON.plusSeconds(16 * 60);
        var refreshed = assembleAt(facts, client, true, 2, at, Lang.chinese());
        assertEquals(4, calls.get());
        assertEquals(at.toString(), entry(refreshed, "current").get("textGeneratedAt"));
        assertEquals(at.toString(), entry(refreshed, "today").get("textGeneratedAt"));
    }

    private static CountingFacts titledFacts() {
        var facts = new CountingFacts() {
            @Override public SummaryService.BehaviorData behaviorData() {
                return new SummaryService.BehaviorData(0, 0, 0, 0, 0, 0, 0, List.of());
            }
            @Override public SummaryService.LocalFacts currentWindowFacts(Instant start, Instant end, String label) {
                return today;
            }
        };
        facts.current = SummaryPromptServiceTest.facts("连接超时参数调试", false);
        facts.today = facts.current;
        return facts;
    }

    private static String asJson(Object value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private Map<String, Object> assemble(SummaryFactSource facts,
                                         SummaryPromptService.SummaryTextClient client,
                                         boolean llmAvailable,
                                         int cap) {
        return assembleAt(facts, client, llmAvailable, cap, AFTERNOON, Lang.chinese());
    }

    private Map<String, Object> assembleAt(SummaryFactSource facts,
                                         SummaryPromptService.SummaryTextClient client,
                                         boolean llmAvailable, int cap, Instant at, Lang lang) {
        Clock clock = Clock.fixed(at, ZONE);
        DesktopSummaryAssembler assembler = new DesktopSummaryAssembler(
                facts, new BehaviorAdviceService(), new SummaryPromptService(),
                new SummarySnapshotStore(tempDir), wikiStore, clock);
        return assembler.assemble(new DesktopSummaryAssembler.Request(
                llmAvailable, cap, lang, client));
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
        return day.atTime(4, 0).atZone(ZONE).toInstant();
    }

    private static Instant dayEnd(LocalDate day) {
        return day.plusDays(1).atTime(4, 0).atZone(ZONE).toInstant();
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
