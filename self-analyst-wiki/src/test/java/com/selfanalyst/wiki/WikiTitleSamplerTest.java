package com.selfanalyst.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.statistics.ActivityStatistics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WikiTitleSamplerTest {
    private final Instant start = Instant.parse("2026-09-21T04:00:00Z");
    private final WikiPeriod period = new WikiPeriod(WikiLevel.DAY, start, start.plusSeconds(86400), "UTC");

    @Test
    void chineseNearDuplicatesLeaveRoomForAnIndependentTopic() {
        var windows = List.of(window(1, 0, 600, "Browser", "订单系统安装说明"),
                window(2, 700, 580, "Browser", "订单系统安装说明新版"),
                window(3, 1400, 400, "Browser", "数据库权限配置指南"));
        var full = sample(windows, List.of(), List.of(), Integer.MAX_VALUE);
        int budget = full.jsonLines().lines().mapToInt(line -> line.length() + 1).sorted().skip(1).sum();
        var limited = sample(windows, List.of(), List.of(), budget);
        assertEquals(Set.of("订单系统安装说明", "数据库权限配置指南"),
                new HashSet<>(limited.facts().stream().map(WikiTitleSampler.Fact::title).toList()));
        assertEquals(3, full.facts().size(), "near duplicate scoring must not merge fact identities");
    }

    @Test
    void cheapestFeasibleTimeCoveragePreventsLargeEarlyTitleFromExcludingTheEvening() {
        List<Event> windows = coverageWindows(true);
        var full = sample(windows, List.of(), List.of(), Integer.MAX_VALUE);
        int budget = full.jsonLines().lines().filter(line -> !line.contains("A".repeat(96)))
                .mapToInt(line -> line.length() + 1).sum();
        assertTrue(budget >= 1000, "the counterexample also applies to a legal configured budget");
        var selected = sample(windows, List.of(), List.of(), budget);
        assertEquals(15, selected.coverage().get("selectedTimeMask"));
        assertEquals(Set.of("B", "C", "D", "E"), new HashSet<>(selected.facts().stream()
                .map(fact -> fact.title().substring(0, 1)).toList()));
        assertEquals(budget, selected.jsonLines().length());
        Collections.shuffle(windows, new Random(37));
        assertEquals(selected, sample(windows, List.of(), List.of(), budget));
    }

    @Test
    void globalCoverageSurvivesSeparateWindowAndContextReservations() {
        List<Event> windows = coverageWindows(false);
        List<Event> contexts = new ArrayList<>();
        for (int layer = 2; layer < 4; layer++) {
            for (int occurrence = 0; occurrence < 4; occurrence++) {
                contexts.add(content(100 + layer * 10 + occurrence, layer * 22000 + occurrence * 3000,
                        1500, "Browser", "Other", String.valueOf((char) ('B' + layer)).repeat(70)));
            }
        }
        var full = sample(windows, List.of(), contexts, Integer.MAX_VALUE);
        int budget = full.jsonLines().lines().filter(line -> !line.contains("A".repeat(96)))
                .mapToInt(line -> line.length() + 1).sum();
        var selected = sample(windows, List.of(), contexts, budget);
        assertEquals(15, selected.coverage().get("selectedTimeMask"));
        assertEquals(2L, selected.coverage().get("windowSelected"));
        assertEquals(2L, selected.coverage().get("contextSelected"));
        assertTrue(selected.jsonLines().length() <= budget);
        Collections.shuffle(windows, new Random(19));
        Collections.shuffle(contexts, new Random(29));
        assertEquals(selected, sample(windows, List.of(), contexts, budget));
    }

    @Test
    void longUnicodeTitlesPreserveBothTheirPrefixAndDistinctFileNames() throws Exception {
        String prefix = "项目😀".repeat(60);
        var windows = List.of(window(1, 0, 60, "Editor", prefix + "/订单安装说明.md"),
                window(2, 100, 60, "Editor", prefix + "/数据库权限配置.md"));
        var selected = sample(windows, List.of(), List.of(), Integer.MAX_VALUE);
        assertEquals(2, selected.facts().size());
        assertTrue(selected.facts().stream().allMatch(f -> f.title().startsWith("项目😀")));
        assertTrue(selected.facts().stream().anyMatch(f -> f.title().endsWith("/订单安装说明.md")));
        assertTrue(selected.facts().stream().anyMatch(f -> f.title().endsWith("/数据库权限配置.md")));
        for (var fact : selected.facts()) {
            assertEquals(160, fact.title().codePointCount(0, fact.title().length()));
            assertFalse(fact.title().contains("\uFFFD"));
        }
        for (String line : selected.jsonLines().lines().toList()) assertNotNull(new ObjectMapper().readTree(line));
        assertEquals(selected.jsonLines(), new String(selected.jsonLines().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void representativesRetainLongestActivityAndAllAvailableTimeLayers() {
        List<Event> windows = new ArrayList<>();
        for (int i = 0; i < 96; i++) windows.add(window(i + 1, i * 60, 10, "Editor", "Recurring"));
        windows.add(window(97, 8000, 500, "Editor", "Recurring"));
        windows.add(window(98, 25000, 20, "Editor", "Recurring"));
        windows.add(window(99, 45000, 300, "Editor", "Recurring"));
        windows.add(window(100, 80000, 10, "Editor", "Recurring"));
        var fact = sample(windows, List.of(), List.of(), Integer.MAX_VALUE).facts().getFirst();
        assertEquals(1790, fact.activeSeconds());
        assertEquals(100, fact.occurrences());
        assertEquals(96, fact.omittedIntervals());
        assertTrue(fact.intervals().stream().anyMatch(i -> i.start().equals(start.plusSeconds(8000).toString())),
                "the longest activity must survive, even when it shares the first layer with the first observation");
        assertEquals(Set.of(0, 1, 2, 3), new HashSet<>(fact.intervals().stream()
                .map(i -> (int) (java.time.Duration.between(start, Instant.parse(i.start())).toSeconds() / 21600)).toList()));
    }

    @Test
    void representativesKeepAnActiveIntervalEvenWhenObservationsAreLonger() {
        List<Event> contexts = new ArrayList<>();
        for (int i = 0; i < 9; i++) contexts.add(content(i + 2, i * 9000, 1000, "Browser", "Window", "Topic"));
        var selected = sample(List.of(window(1, 36000, 1, "Browser", "Window")), List.of(), contexts, Integer.MAX_VALUE);
        var context = selected.facts().stream().filter(f -> f.source().equals("content")).findFirst().orElseThrow();
        assertEquals(1, context.activeSeconds());
        assertTrue(context.intervals().stream().anyMatch(WikiTitleSampler.Interval::activityMatched));
        assertEquals(4, context.intervals().size());
    }

    @Test
    void aLongObservationOnlyReservesItsStartLayerWithoutLosingItsOriginalInterval() {
        var result = sample(List.of(), List.of(),
                List.of(content(1, 100, 80000, "Browser", "Window", "Observed document")), Integer.MAX_VALUE);
        assertEquals(1, result.coverage().get("candidateTimeMask"));
        assertEquals(1, result.coverage().get("selectedTimeMask"));
        var fact = result.facts().getFirst();
        assertNull(fact.activeSeconds());
        assertEquals(start.plusSeconds(80100).toString(), fact.intervals().getFirst().end());
    }

    @Test
    void unboundedSamplingReturnsAllFactsWithoutChangingFullStatistics() {
        List<Event> windows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) windows.add(window(i + 1, i * 80, 20.125, "Editor", "Topic " + i));
        var afk = List.of(new Event(start.plusSeconds(5), 5, Map.of("status", "afk")));
        var before = ActivityStatistics.compute(windows, afk, period.start(), period.end());
        var full = WikiTitleSampler.sample(period, before.activeEvents(), windows, List.of(), Integer.MAX_VALUE);
        var small = WikiTitleSampler.sample(period, before.activeEvents(), windows, List.of(), 1000);
        assertEquals(1000, full.facts().size());
        assertEquals(1000, full.coverage().get("candidateFacts"));
        assertEquals(Integer.MAX_VALUE, full.coverage().get("budgetChars"));
        assertEquals(full.jsonLines().length(), full.coverage().get("usedChars"));
        assertTrue(full.jsonLines().length() > 0);
        assertTrue(small.jsonLines().length() <= 1000);
        assertEquals(before.activeSeconds(), full.facts().stream().mapToDouble(WikiTitleSampler.Fact::activeSeconds).sum(), 1e-8);
        assertEquals(before, ActivityStatistics.compute(windows, afk, period.start(), period.end()));
    }

    @Test
    void factProjectionPreservesIdsOrderReferencesAndCallerCoverage() throws Exception {
        var all = sample(List.of(window(31, 0, 30, "Editor", "A"), window(32, 100, 20, "Editor", "B")),
                List.of(), List.of(), Integer.MAX_VALUE);
        var fact = all.facts().getLast();
        Map<String, Object> coverage = new LinkedHashMap<>(all.coverage());
        coverage.put("batch", "tail");
        var projected = WikiTitleSampler.fromFacts(period, List.of(fact), coverage);
        assertEquals(List.of(fact), projected.facts());
        assertEquals(fact.id(), new ObjectMapper().readTree(projected.jsonLines()).get("id").asText());
        assertEquals(List.of(32L), projected.facts().getFirst().intervals().getFirst().sourceEventIds());
        assertEquals("tail", projected.coverage().get("batch"));
        assertEquals(1, projected.coverage().get("selectedFacts"));
        assertEquals(1, projected.coverage().get("omittedIntervals"));
        assertEquals(1, projected.coverage().get("selectedTimeMask"));
        assertEquals(projected.jsonLines().length(), projected.coverage().get("usedChars"));
        assertEquals(2, coverage.get("selectedFacts"));
        assertThrows(UnsupportedOperationException.class, () -> projected.coverage().put("batch", "changed"));
        assertThrows(UnsupportedOperationException.class, () -> projected.facts().clear());
    }

    private List<Event> coverageWindows(boolean includeLaterWindows) {
        List<Event> windows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            windows.add(window(i + 1, i * 2000, 1500, "Browser", "A".repeat(160)));
            windows.add(window(i + 5, 8000 + i * 2000, 1000, "Browser", "B".repeat(70)));
            windows.add(window(i + 9, 22000 + i * 3000, 1500, "Browser", "C".repeat(70)));
            if (includeLaterWindows) {
                windows.add(window(i + 13, 44000 + i * 3000, 1500, "Browser", "D".repeat(70)));
                windows.add(window(i + 17, 66000 + i * 3000, 1500, "Browser", "E".repeat(70)));
            }
        }
        return windows;
    }

    @Test
    void removesOnlyCoveredFallbackContentAndKeepsUnmatchedEdges() {
        var windows = List.of(window(1, 20, 60, "Browser", "Page"));
        var exact = new Event(2, start.plusSeconds(20), 60, Map.of("app", "Browser", "title", "Page"));
        var full = sample(windows, List.of(), List.of(exact), 12000);
        assertEquals(1, full.facts().size(), "identical fallback must not cost two model facts");
        assertEquals(List.of(1L), full.facts().getFirst().intervals().getFirst().sourceEventIds());
        var partial = new Event(3, start, 100, Map.of("app", "Browser", "title", "Page"));
        var result = sample(windows, List.of(), List.of(partial), 12000);
        var observation = result.facts().stream().filter(f -> f.source().equals("content")).findFirst().orElseThrow();
        assertNull(observation.activeSeconds());
        assertEquals(2, observation.occurrences());
        assertEquals(start.plusSeconds(20).toString(), observation.intervals().getFirst().end());
        assertEquals(start.plusSeconds(80).toString(), observation.intervals().getLast().start());
    }

    @Test
    void explicitChatKindIsNotErasedWhenContextAndWindowTitleMatch() {
        var result = sample(List.of(window(1, 0, 60, "Chat", "Team")), List.of(),
                List.of(content(2, 0, 60, "Chat", "Team", "Team")), 12000);
        assertEquals(2, result.facts().size());
        assertTrue(result.facts().stream().anyMatch(f -> f.source().equals("content") && f.kind().equals("chat")));
    }

    @Test
    void importedContextKindsRemainDistinctAndUnrelatedWindowsDoNotDeduplicate() {
        List<Event> contexts = List.of(
                new Event(2, start, 10, Map.of("app", "Browser", "title", "Window", "context_title", "Topic", "context_kind", "document")),
                new Event(3, start.plusSeconds(20), 10, Map.of("app", "Browser", "title", "Window", "context_title", "Topic", "context_kind", "page")),
                new Event(4, start.plusSeconds(40), 10, Map.of("app", "Browser", "title", "Other")));
        var result = sample(List.of(window(1, 0, 100, "Editor", "Other")), List.of(), contexts, 12000);
        assertTrue(result.facts().stream().anyMatch(f -> f.kind().equals("document")));
        assertTrue(result.facts().stream().anyMatch(f -> f.kind().equals("page")));
        assertTrue(result.facts().stream().anyMatch(f -> f.app().equals("Browser") && f.title().equals("Other") && f.activeSeconds() == null));
    }

    @Test
    void recurringTitleParticipatesInItsLaterTimeLayer() {
        var windows = List.of(window(1, 0, 10, "Browser", "Recurring"),
                window(2, 100, 600, "Browser", "Morning one"),
                window(3, 800, 550, "Browser", "Morning two"),
                window(4, 80000, 500, "Browser", "Recurring"));
        var all = sample(windows, List.of(), List.of(), 12000);
        int budget = all.jsonLines().lines().filter(s -> s.contains("Recurring") || s.contains("Morning one"))
                .mapToInt(s -> s.length()+1).sum();
        var limited = sample(windows, List.of(), List.of(), budget);
        assertEquals(2, limited.facts().size());
        assertTrue(limited.facts().stream().anyMatch(f -> f.title().equals("Recurring")));
        assertTrue(limited.facts().stream().anyMatch(f -> f.title().equals("Morning one")));
    }

    @Test
    void compactOffsetsClipAtDayEndWithoutLosingSubsecondLocalPrecision() throws Exception {
        var event = new Event(1, period.end().minusNanos(123_456_789), 2,
                Map.of("app", "Editor", "title", "Midnight"));
        var result = sample(List.of(event), List.of(), List.of(), 12000);
        var row = new ObjectMapper().readTree(result.jsonLines());
        assertEquals(86399.877, row.get("r").get(0).get(0).asDouble(), 1e-8);
        assertEquals(86400, row.get("r").get(0).get(1).asDouble());
        assertEquals(0.123456789, result.facts().getFirst().activeSeconds(), 1e-9);
        assertEquals(period.end().toString(), result.facts().getFirst().intervals().getFirst().end());
    }

    @Test
    void compactProjectionKeepsTraceLocallyAndUsesLessSpace() throws Exception {
        var result = sample(List.of(window(123456789, 0, 20, "Editor", "Draft")), List.of(), List.of(), 12000);
        assertEquals(List.of(123456789L), result.facts().getFirst().intervals().getFirst().sourceEventIds());
        assertFalse(result.jsonLines().contains("123456789"));
        assertFalse(result.jsonLines().contains("sourceEventIds"));
        assertTrue(result.jsonLines().length() < 200);
        var row = new ObjectMapper().readTree(result.jsonLines());
        assertEquals(result.facts().getFirst().id(), row.get("id").asText());
    }

    @Test
    void majorAppSecondTopicOutranksTinyNewApps() {
        List<Event> windows = new ArrayList<>(List.of(window(1, 0, 600, "Browser", "Build systems"),
                window(2, 700, 500, "Browser", "Database tuning")));
        for (int i = 0; i < 15; i++) windows.add(window(10+i, 1300+i*3, 1, "Tool"+i, "Dialog"));
        var all = sample(windows, List.of(), List.of(), 24000);
        int cost = all.jsonLines().lines().filter(s -> s.contains("Browser")).mapToInt(s -> s.length()+1).sum();
        var bounded = sample(windows, List.of(), List.of(), cost);
        assertTrue(bounded.facts().stream().anyMatch(f -> f.title().equals("Build systems")));
        assertTrue(bounded.facts().stream().anyMatch(f -> f.title().equals("Database tuning")));
    }

    @Test
    void titleNoveltyAndRealActivityBeatRepetitionsAndLongObservations() {
        var windows = List.of(window(1, 0, 600, "Browser", "Project Alpha installation instructions"),
                window(2, 700, 580, "Browser", "Project Alpha installation instructions v2"),
                window(3, 1400, 400, "Browser", "Database access control guide"));
        var observed = content(99, 0, 80000, "Ghost", "Window", "Idle document");
        var all = sample(windows, List.of(), List.of(observed), 24000);
        int cost = all.jsonLines().lines().filter(s -> s.contains("Project Alpha installation instructions\"")
                || s.contains("Database access control guide")).mapToInt(s -> s.length()+1).sum();
        var selected = sample(windows, List.of(), List.of(), cost);
        assertTrue(selected.facts().stream().anyMatch(f -> f.title().equals("Database access control guide")));
        // Observation duration must not rank as activity when competing with matched context.
        var contexts = List.of(observed, content(100, 0, 600, "Browser",
                "Project Alpha installation instructions", "Active topic"));
        var limited = sample(windows, List.of(), contexts, 400);
        assertTrue(limited.facts().stream().anyMatch(f -> f.title().equals("Active topic")));
    }

    @Test
    void deduplicatesTitlesWithoutBridgingGapsAndKeepsSourceIds() {
        List<Event> windows = List.of(window(1, 0, 60, "IDE", "A"), window(2, 60, 30, "IDE", "B"),
                window(3, 90, 60, "IDE", "A"));
        var result = sample(windows, List.of(), List.of(), 12000);
        var a = result.facts().stream().filter(f -> f.title().equals("A")).findFirst().orElseThrow();
        assertEquals(2, result.facts().size());
        assertEquals(120, a.activeSeconds());
        assertEquals(2, a.occurrences());
        assertEquals(start.plusSeconds(60).toString(), a.intervals().getFirst().end());
        assertEquals(start.plusSeconds(90).toString(), a.intervals().getLast().start());
        assertEquals(List.of(1L), a.intervals().getFirst().sourceEventIds());
        assertEquals(List.of(3L), a.intervals().getLast().sourceEventIds());
    }

    @Test
    void clipsBoundaryAndAfkAndDoesNotDoubleCountOverlappingContexts() {
        List<Event> windows = List.of(window(1, -30, 180, "Weixin", "微信"));
        List<Event> afk = List.of(new Event(start.plusSeconds(30), 60, Map.of("status", "afk")));
        var selected = sample(windows, afk,
                List.of(content(2, -30, 180, "Weixin", "微信", "讨论组"),
                        content(3, 0, 150, "Weixin", "微信", "讨论组")), 12000);
        assertEquals(2, selected.facts().size());
        for (var fact : selected.facts()) {
            assertEquals(90, fact.activeSeconds());
            assertEquals(fact.source().equals("window") ? 2 : 3, fact.occurrences());
            assertEquals(start.toString(), fact.intervals().getFirst().start());
            assertEquals(start.plusSeconds(90).toString(), fact.intervals().getLast().start());
        }
    }

    @Test
    void unmatchedContextIsObservationNotClaimedActivityAndBodyNeverEscapes() {
        var event = new Event(4, start, 20, Map.of("app", "Weixin", "title", "微信",
                "context_title", "讨论组", "context_kind", "chat", "text_content", "FORBIDDEN_SECRET"));
        var selected = sample(List.of(window(1, 0, 20, "Other", "微信")), List.of(), List.of(event), 12000);
        var fact = selected.facts().stream().filter(f -> f.source().equals("content")).findFirst().orElseThrow();
        assertNull(fact.activeSeconds());
        assertEquals(List.of(4L), fact.intervals().getFirst().sourceEventIds());
        assertFalse(selected.jsonLines().contains("FORBIDDEN_SECRET"));
    }

    @Test
    void mixedObservedAndActiveContextKeepsOneTitleWithExplicitIntervalSemantics() {
        var result = sample(List.of(window(1, 0, 20, "Weixin", "微信")), List.of(),
                List.of(content(2, 0, 20, "Weixin", "微信", "讨论组"),
                        content(3, 100, 20, "Weixin", "微信", "讨论组")), 12000);
        var contexts = result.facts().stream().filter(f -> f.source().equals("content")).toList();
        assertEquals(1, contexts.size());
        var fact = contexts.getFirst();
        assertEquals(20, fact.activeSeconds());
        assertTrue(fact.intervals().getFirst().activityMatched());
        assertFalse(fact.intervals().getLast().activityMatched());
        assertEquals(2, fact.occurrences());
    }

    @Test
    void independentReservationsCoverDayDeterministically() {
        List<Event> windows = new ArrayList<>();
        for (int quarter = 0; quarter < 4; quarter++) {
            for (int i = 0; i < 30; i++) {
                windows.add(window(1 + quarter * 30 + i, quarter * 21600 + i * 120,
                        60 + i, "App" + i, "Title" + quarter + "-" + i));
            }
        }
        List<Event> contexts = List.of(content(900, 83000, 100, "Weixin", "微信", "晚间讨论"));
        var first = sample(windows, List.of(), contexts, 4000);
        Collections.shuffle(windows, new Random(42));
        var second = sample(windows, List.of(), contexts, 4000);
        assertEquals(first, second);
        assertTrue(first.facts().stream().anyMatch(f -> f.title().equals("晚间讨论")));
        for (int quarter = 0; quarter < 4; quarter++) {
            String prefix = "Title" + quarter + "-";
            assertTrue(first.facts().stream().anyMatch(f -> f.title().startsWith(prefix)), prefix);
        }
        assertTrue(first.jsonLines().length() <= 4000);
        assertEquals(first.jsonLines().length(), first.coverage().get("usedChars"));
        assertTrue(((Number) first.coverage().get("omittedIntervals")).intValue() > 0);
    }

    @Test
    void boundedRepresentativesPreserveLateOccurrencesAndExactTotal() {
        List<Event> windows = new ArrayList<>();
        for (int i = 0; i < 100; i++) windows.add(window(i + 1, i * 100, 0.6, "IDE", "Repeated"));
        var result = sample(windows, List.of(), List.of(), 12000);
        var fact = result.facts().getFirst();
        assertEquals(1, result.facts().size());
        assertEquals(100, fact.occurrences());
        assertEquals(96, fact.omittedIntervals());
        assertEquals(60, fact.activeSeconds(), 1e-9);
        assertEquals(start.plusSeconds(9900).toString(), fact.intervals().getLast().start());
    }

    @Test
    void tinyBudgetAndUnicodeRemainValidAndDifferentFullTitlesDoNotMerge() throws Exception {
        String title = "😀".repeat(170);
        var windows = List.of(window(1, 0, 20, "IDE", title + "A"), window(2, 30, 20, "IDE", title + "B"));
        var tiny = sample(windows, List.of(), List.of(), 20);
        assertTrue(tiny.facts().isEmpty());
        assertEquals("", tiny.jsonLines());
        assertEquals(2, tiny.coverage().get("candidateFacts"));
        var result = sample(windows, List.of(), List.of(), 12000);
        assertEquals(2, result.facts().size());
        for (var fact : result.facts()) assertEquals(160, fact.title().codePointCount(0, fact.title().length()));
        var mapper = new ObjectMapper();
        for (String line : result.jsonLines().lines().toList()) assertNotNull(mapper.readTree(line));
        assertEquals(result.jsonLines(), new String(result.jsonLines().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void skipsOversizedCandidateInsteadOfStoppingAndPrefersDifferentApps() {
        List<Event> windows = List.of(window(1, 0, 600, "IDE", "A".repeat(160)),
                window(2, 700, 400, "IDE", "short"), window(3, 1200, 300, "Browser", "docs"));
        var all = sample(windows, List.of(), List.of(), 12000);
        int smallCost = all.jsonLines().lines().filter(s -> s.contains("\"short\"")).findFirst().orElseThrow().length() + 1;
        var small = sample(windows, List.of(), List.of(), smallCost);
        assertFalse(small.facts().isEmpty());
        assertTrue(small.jsonLines().length() <= smallCost);
        int twoCost = all.jsonLines().lines().filter(s -> !s.contains("\"short\""))
                .mapToInt(s -> s.length() + 1).sum();
        var two = sample(windows, List.of(), List.of(), twoCost);
        assertTrue(two.facts().stream().anyMatch(f -> f.app().equals("Browser")));
    }

    private WikiTitleSampler.Selection sample(List<Event> windows, List<Event> afk, List<Event> content, int budget) {
        var stats = ActivityStatistics.compute(windows, afk, period.start(), period.end());
        return WikiTitleSampler.sample(period, stats.activeEvents(), windows, content, budget);
    }

    private Event window(long id, long offset, double duration, String app, String title) {
        return new Event(id, start.plusSeconds(offset), duration, Map.of("app", app, "title", title));
    }

    private Event content(long id, long offset, double duration, String app, String title, String context) {
        return new Event(id, start.plusSeconds(offset), duration,
                Map.of("app", app, "title", title, "context_title", context, "context_kind", "chat"));
    }
}
