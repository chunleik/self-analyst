package com.selfanalyst.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class WikiSummaryPipelineTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant START = Instant.parse("2026-09-01T04:00:00Z");

    static WikiFactBuilder.WikiFacts facts(int count) {
        var period = new WikiPeriod(WikiLevel.DAY, START, START.plusSeconds(86400), "UTC");
        List<WikiTitleSampler.Fact> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(new WikiTitleSampler.Fact("f" + i, "window", "Editor",
                "项目资料主题" + i + "说明".repeat(30), "window", 60d, 1,
                List.of(new WikiTitleSampler.Interval(START.plusSeconds(i * 120L).toString(),
                        START.plusSeconds(i * 120L + 60).toString(), true, List.of((long) i + 1), 0)), 0));
        return new WikiFactBuilder.WikiFacts(period, 1234, 50, 7,
                List.of(new WikiEntry.AppDuration("Editor", 1234)), List.of(), List.of(), List.of(),
                "facts-test", "events-v2", Map.of("afk", new WikiEntry.SourceCoverage("complete", START, period.end(), null)),
                Map.of("activeSecondsExact", 1234d), WikiTitleSampler.fromFacts(period, rows, Map.of("candidateFacts", count)));
    }

    @SuppressWarnings("unchecked")
    static String groundedResponse(String prompt) {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String line : prompt.lines().toList()) if (line.startsWith("{") && line.endsWith("}")
                    && (line.contains("\"id\":") || line.contains("\"topicId\":"))) {
                Map<String, Object> row = JSON.readValue(line, Map.class);
                if (row.containsKey("id") || row.containsKey("topicId")) rows.add(row);
            }
            List<Map<String, Object>> cards = new ArrayList<>();
            if (!rows.isEmpty()) {
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("title", "项目资料查看"); card.put("summary", "涉及项目资料与相关开发主题。");
                card.put("confidence", "medium"); card.put("claimType", "observed");
                if (rows.getFirst().containsKey("topicId"))
                    card.put("sourceTopicIds", rows.stream().map(row -> row.get("topicId")).toList());
                else {
                    card.put("memberInputIds", rows.stream().map(row -> row.get("id")).toList());
                    card.put("representativeFactIds", List.of(rows.getFirst().get("id")));
                }
                cards.add(card);
            }
            if (!prompt.startsWith("WIKI_STAGE=")) {
                List<Map<String, Object>> tasks = cards.stream().map(card -> Map.<String, Object>of(
                        "title", card.get("title"), "summary", card.get("summary"), "confidence", card.get("confidence"),
                        "claimType", card.get("claimType"), "evidenceFactIds", card.get("representativeFactIds"))).toList();
                return JSON.writeValueAsString(Map.of("summary", "涉及项目资料查看。", "primaryTask", "项目资料查看", "taskSegments", tasks));
            }
            return JSON.writeValueAsString(Map.of("summary", "涉及项目资料查看。", "primaryTask", "项目资料查看", "topicCards", cards));
        } catch (Exception error) { throw new AssertionError(error); }
    }

    @Test void shortInputCallsOnceAndKeepsLocalMetrics() {
        AtomicInteger calls = new AtomicInteger();
        var pipeline = pipeline(24000, 6, calls, new AtomicReference<>("model-a"), prompt -> groundedResponse(prompt));
        var result = pipeline.summarize(facts(2), Duration.ofSeconds(5));
        assertEquals(1, calls.get()); assertEquals(1234, result.metrics().activeSeconds());
        assertEquals(50, result.metrics().afkSeconds());
        assertEquals("direct", generation(result).get("mode"));
        assertFalse(result.taskSegments().getFirst().evidenceFactIds().isEmpty());
    }

    @Test void longInputUsesFullFactsBeforePartitionAndNeverExceedsBudgets() {
        AtomicInteger calls = new AtomicInteger();
        List<String> prompts = new ArrayList<>();
        var pipeline = pipeline(1000, 6, calls, new AtomicReference<>("model-a"), prompt -> {
            prompts.add(prompt); return groundedResponse(prompt);
        });
        var result = pipeline.summarize(facts(16), Duration.ofSeconds(5));
        assertTrue(calls.get() > 1); assertTrue(calls.get() <= 6);
        assertTrue(prompts.stream().allMatch(p -> p.length() + 512 <= 32000));
        assertTrue(prompts.stream().anyMatch(p -> p.contains("\"id\":\"f15\"")), "last facts reach a leaf before compression");
        assertEquals("tree", generation(result).get("mode"));
        assertEquals(0, generation(result).get("omittedFacts"));
        int unreferenced = ((Number) generation(result).get("intermediateUnreferencedFacts")).intValue();
        int finalAvailable = ((Number) generation(result).get("finalAvailableFacts")).intValue();
        int mergeOmitted = ((Number) generation(result).get("mergeOmittedFacts")).intValue();
        assertEquals(0, unreferenced, "complete model membership survives independently of display references");
        assertEquals(16, unreferenced + finalAvailable + mergeOmitted);
        long refs = ((Number) generation(result).get("finalReferencedFacts")).longValue();
        assertTrue(refs <= 3);
        assertEquals(finalAvailable - refs, generation(result).get("finalUnreferencedFacts"));
        assertEquals(16, generation(result).get("assignedFacts"));
        assertEquals(0, generation(result).get("unresolvedFacts"));
        assertEquals(1234, result.metrics().activeSeconds());
    }

    @Test void planningChecksDeadlineBeforeTraversingAllFactsOrCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger visited = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        var base = facts(100);
        List<WikiTitleSampler.Fact> source = base.sampledTitles().facts();
        List<WikiTitleSampler.Fact> tracked = new AbstractList<>() {
            public WikiTitleSampler.Fact get(int index) { visited.incrementAndGet(); return source.get(index); }
            public int size() { return source.size(); }
        };
        var input = base.withInput(new WikiTitleSampler.Selection(tracked, base.sampledTitles().jsonLines(),
                base.sampledTitles().coverage()), List.of());
        var pipeline = new WikiSummaryPipeline(() -> new WikiSummaryPipeline.Session() {
            public String identity() { return "deadline-test"; }
            public String complete(String prompt, Duration timeout) { calls.incrementAndGet(); return groundedResponse(prompt); }
        }, new WikiSummaryPipeline.Limits(1000, 32000, 6), clock::getAndIncrement);
        var error = assertThrows(IllegalStateException.class,
                () -> pipeline.summarize(input, Duration.ofNanos(30)));
        assertEquals("WIKI_SYNTHESIS_TIMEOUT", error.getMessage());
        assertEquals(0, calls.get());
        assertTrue(visited.get() < source.size(), "planning must stop at the deadline, not after a full partition pass");
    }

    @Test void nonMonotonicRepresentativeSizesStillAdmitLaterSmallFactsAndReportLostChildText() throws Exception {
        var base = facts(6);
        List<WikiTitleSampler.Fact> rows = new ArrayList<>(base.sampledTitles().facts());
        var editorFact = rows.get(2);
        var original = new WikiTitleSampler.Fact(editorFact.id(), editorFact.source(), "Browser", editorFact.title(),
                editorFact.kind(), editorFact.activeSeconds(), editorFact.occurrences(), editorFact.intervals(), editorFact.omittedIntervals());
        var emptyTitle = withTitle(original, "");
        int overhead = WikiTitleSampler.fromFacts(base.period(), List.of(emptyTitle), Map.of()).jsonLines().length();
        rows.set(2, withTitle(original, "X".repeat(950 - overhead)));
        int three = WikiTitleSampler.fromFacts(base.period(), List.of(rows.get(0), rows.get(2), rows.get(5)), Map.of()).jsonLines().length();
        int four = WikiTitleSampler.fromFacts(base.period(), List.of(rows.get(0), rows.get(1), rows.get(3), rows.get(5)), Map.of()).jsonLines().length();
        assertTrue(three > 1000 && four <= 1000, "the representative count is not a monotonic budget predicate");
        String child = JSON.writeValueAsString(Map.of("entryId", "child-day", "summary", "跨应用主题相关记录",
                "evidenceFactIds", List.of("f0", "f2")));
        var input = base.withInput(WikiTitleSampler.fromFacts(base.period(), rows, Map.of()), List.of(child));
        AtomicInteger calls = new AtomicInteger();
        List<String> prompts = new ArrayList<>();
        var pipeline = pipeline(1000, 1, calls, new AtomicReference<>("model"), prompt -> {
            prompts.add(prompt); return groundedResponse(prompt);
        });
        var result = pipeline.summarize(input, Duration.ofSeconds(5));
        assertEquals(1, calls.get());
        assertTrue(((Number) generation(result).get("finalAvailableFacts")).intValue() >= 4);
        assertFalse(prompts.getFirst().contains("\"id\":\"f2\""));
        assertTrue(prompts.getFirst().contains("\"id\":\"f3\""));
        assertEquals(1L, generation(result).get("omittedChildSummaries"));
        assertFalse(prompts.getFirst().contains("child-day"));
    }

    @Test void finalMergeAndCacheHitsCannotUpgradeIntermediateInferenceOrConfidence() {
        for (String intermediateType : List.of("observed", "inferred")) {
            AtomicInteger calls = new AtomicInteger();
            var pipeline = pipeline(1000, 6, calls, new AtomicReference<>("model"), prompt -> {
                boolean merge = prompt.startsWith("WIKI_STAGE=FINAL") || prompt.startsWith("WIKI_STAGE=MERGE");
                return merge ? groundedResponse(prompt).replace("\"confidence\":\"medium\"", "\"confidence\":\"high\"")
                        : groundedResponse(prompt).replace("\"confidence\":\"medium\"", "\"confidence\":\"low\"")
                            .replace("\"claimType\":\"observed\"", "\"claimType\":\"" + intermediateType + "\"");
            });
            var first = pipeline.summarize(facts(12), Duration.ofSeconds(5));
            assertEquals("low", first.taskSegments().getFirst().confidence());
            assertEquals(intermediateType, first.taskSegments().getFirst().claimType());
            int before = calls.get();
            var retry = pipeline.summarize(facts(12), Duration.ofSeconds(5));
            assertEquals(0, calls.get() - before);
            assertTrue(((Number) generation(retry).get("cacheHits")).intValue() > 0);
            assertEquals("low", retry.taskSegments().getFirst().confidence());
            assertEquals(intermediateType, retry.taskSegments().getFirst().claimType());
        }
    }

    @Test void algorithmProjectionAndStatisticsVersionsIsolateCachedIntermediates() {
        AtomicInteger calls = new AtomicInteger();
        var pipeline = pipeline(1000, 6, calls, new AtomicReference<>("model"), WikiSummaryPipelineTest::groundedResponse);
        var base = facts(12);
        pipeline.summarize(base, Duration.ofSeconds(5));
        int before = calls.get();
        pipeline.summarize(base, Duration.ofSeconds(5));
        assertEquals(0, calls.get() - before);
        for (WikiFactBuilder.WikiFacts changed : List.of(
                withVersions(base, "facts-next", base.projectorVersion(), Map.of()),
                withVersions(base, base.factBuilderVersion(), "projection-next", Map.of()),
                withVersions(base, base.factBuilderVersion(), base.projectorVersion(), Map.of("statisticsVersion", "stats-next")),
                withVersions(base, base.factBuilderVersion(), base.projectorVersion(), Map.of("calendarVersion", "calendar-next")))) {
            before = calls.get();
            var result = pipeline.summarize(changed, Duration.ofSeconds(5));
            assertTrue(calls.get() - before > 1);
            assertEquals(0, generation(result).get("cacheHits"));
        }
    }

    private static WikiTitleSampler.Fact withTitle(WikiTitleSampler.Fact fact, String title) {
        return new WikiTitleSampler.Fact(fact.id(), fact.source(), fact.app(), title, fact.kind(), fact.activeSeconds(),
                fact.occurrences(), fact.intervals(), fact.omittedIntervals());
    }

    private static WikiFactBuilder.WikiFacts withVersions(WikiFactBuilder.WikiFacts base, String factVersion,
                                                         String projectionVersion, Map<String, Object> versions) {
        Map<String, Object> statistics = new LinkedHashMap<>(base.statistics());
        statistics.putAll(versions);
        return new WikiFactBuilder.WikiFacts(base.period(), base.activeSeconds(), base.afkSeconds(), base.switchCount(),
                base.topApps(), base.titleSamples(), base.contextTitleSamples(), base.childSummaries(), factVersion,
                projectionVersion, base.sourceCoverage(), statistics, base.sampledTitles());
    }

    @Test void mergeFailureReusesLeavesAndModelChangeInvalidatesCache() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> model = new AtomicReference<>("model-a");
        AtomicBoolean failMerge = new AtomicBoolean(true);
        var pipeline = pipeline(1000, 6, calls, model, prompt -> {
            if (prompt.startsWith("WIKI_STAGE=FINAL") && failMerge.getAndSet(false)) throw new IllegalStateException("MODEL_FAILURE");
            return groundedResponse(prompt);
        });
        assertThrows(IllegalStateException.class, () -> pipeline.summarize(facts(12), Duration.ofSeconds(5)));
        int before = calls.get();
        var retry = pipeline.summarize(facts(12), Duration.ofSeconds(5));
        assertEquals(1, calls.get() - before);
        assertTrue(((Number) generation(retry).get("cacheHits")).intValue() > 0);
        model.set("model-b"); before = calls.get();
        pipeline.summarize(facts(12), Duration.ofSeconds(5));
        assertTrue(calls.get() - before > 1);
        before = calls.get();
        pipeline.summarize(facts(13), Duration.ofSeconds(5));
        assertTrue(calls.get() - before > 1, "input change invalidates prior input cache");
    }

    @Test void callCapReportsSamplingInsteadOfClaimingFullCoverage() {
        AtomicInteger calls = new AtomicInteger();
        var result = pipeline(1000, 2, calls, new AtomicReference<>("model"), WikiSummaryPipelineTest::groundedResponse)
                .summarize(facts(80), Duration.ofSeconds(5));
        assertTrue(calls.get() <= 2);
        assertTrue(((Number) generation(result).get("omittedFacts")).intValue() > 0);
    }

    @Test void deadlineCancelsCallAndDoesNotStartAnother() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();
        var pipeline = pipeline(1000, 6, calls, new AtomicReference<>("model"), prompt -> {
            try { Thread.sleep(10_000); } catch (InterruptedException e) { interrupted.set(true); }
            return groundedResponse(prompt);
        });
        // Direct input isolates in-flight cancellation from long-input planning cost.
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(IllegalStateException.class,
                () -> pipeline.summarize(facts(1), Duration.ofSeconds(1))));
        assertEquals(1, calls.get());
        for (int i = 0; i < 100 && !interrupted.get(); i++) Thread.yield();
        assertTrue(interrupted.get());
    }

    @Test void budgetFailurePropagatesWithoutRepairCalls() {
        AtomicInteger calls = new AtomicInteger();
        var pipeline = pipeline(1000, 6, calls, new AtomicReference<>("model"), prompt -> {
            throw new com.selfanalyst.wiki.usage.LlmUnavailableException();
        });
        var error = assertThrows(WikiSummaryPipeline.CallFailure.class,
                () -> pipeline.summarize(facts(12), Duration.ofSeconds(5)));
        assertEquals(WikiSummaryPipeline.FailureKind.CONFIGURATION, error.kind());
        assertEquals(0, pipeline.progress(facts(12).period()).calls());
        assertEquals(1, calls.get());
    }

    static WikiSummaryPipeline pipeline(int factChars, int maxCalls, AtomicInteger calls, AtomicReference<String> identity,
                                       java.util.function.Function<String, String> response) {
        return new WikiSummaryPipeline(() -> new WikiSummaryPipeline.Session() {
            final String id = identity.get();
            public String identity() { return id; }
            public String complete(String prompt, Duration timeout) { calls.incrementAndGet(); return response.apply(prompt); }
        }, new WikiSummaryPipeline.Limits(factChars, 32000, maxCalls), WikiGenerationStore.inMemory(),
                new WikiGenerationStore.BudgetLimits(100, 2_000_000));
    }

    static Map<?, ?> generation(WikiSummarizer.SummaryResult result) { return (Map<?, ?>) result.metrics().extra().get("generation"); }
}
