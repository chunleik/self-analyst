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
