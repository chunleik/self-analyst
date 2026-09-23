package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.time.Duration;

import static com.selfanalyst.wiki.WikiSummaryPipelineTest.facts;
import static org.junit.jupiter.api.Assertions.*;

class WikiTopicProtocolTest {
    @Test void rootConsumesWholeMembershipWhileDisplayingFewRepresentativesAndKeepsStrengthCeiling() {
        var full = facts(20);
        List<String> members = full.sampledTitles().facts().stream().map(WikiTitleSampler.Fact::id).toList();
        var leaf = WikiTopicProtocol.parse(response(List.of(Map.of("title", "项目资料", "summary", "涉及项目资料查看。",
                        "memberInputIds", members, "representativeFactIds", List.of("f0"),
                        "claimType", "inferred", "confidence", "low"))),
                full, full.sampledTitles().facts(), List.of(), false);
        var source = WikiTopicProtocol.cards(leaf).getFirst();
        var root = WikiTopicProtocol.parse(response(List.of(Map.of("title", "项目资料", "summary", "涉及项目资料查看。",
                        "sourceTopicIds", List.of(source.id()), "claimType", "observed", "confidence", "high"))),
                full, full.sampledTitles().facts(), List.of(source), false);
        var card = WikiTopicProtocol.cards(root).getFirst();
        assertEquals(20, card.memberInputIds().size());
        assertEquals(3, card.representativeFactIds().size());
        assertEquals("inferred", card.claimType()); assertEquals("low", card.confidence());
        assertEquals(0, ((List<?>) root.metrics().extra().get("unresolvedInputIds")).size());
        assertEquals(3, root.taskSegments().getFirst().evidenceFactIds().size());
    }

    @Test void omittedMembershipAndOmittedSourceCardsRemainExplicitlyUnresolved() {
        var full = facts(3);
        var leaf = WikiTopicProtocol.parse(response(List.of(
                        leaf("第一组", List.of("f0")), leaf("第二组", List.of("f1")))),
                full, full.sampledTitles().facts(), List.of(), false);
        assertEquals(List.of("f2"), leaf.metrics().extra().get("unresolvedInputIds"));
        var sources = WikiTopicProtocol.cards(leaf);
        var root = WikiTopicProtocol.parse(response(List.of(Map.of("title", "第一组", "summary", "涉及相关资料。",
                        "sourceTopicIds", List.of(sources.getFirst().id()), "claimType", "inferred", "confidence", "medium"))),
                full, full.sampledTitles().facts(), sources, false);
        assertEquals(List.of("f1", "f2"), root.metrics().extra().get("unresolvedInputIds"));
        assertEquals(List.of(sources.get(1).id()), root.metrics().extra().get("unresolvedTopicIds"));
    }

    @Test void unknownMembersTopicsAndRepresentativesOutsideMembersAreRejectedWithoutEcho() {
        var full = facts(2);
        var unknown = assertThrows(IllegalArgumentException.class, () -> WikiTopicProtocol.parse(
                response(List.of(leaf("相关主题", List.of("private-unknown-id")))),
                full, full.sampledTitles().facts(), List.of(), false));
        assertEquals("WIKI_TOPIC_UNKNOWN_FACT", unknown.getMessage());
        Map<String, Object> wrongRepresentative = new LinkedHashMap<>(leaf("相关主题", List.of("f0")));
        wrongRepresentative.put("representativeFactIds", List.of("f1"));
        assertThrows(IllegalArgumentException.class, () -> WikiTopicProtocol.parse(response(List.of(wrongRepresentative)),
                full, full.sampledTitles().facts(), List.of(), false));
        var leaf = WikiTopicProtocol.parse(response(List.of(leaf("相关主题", List.of("f0")))),
                full, full.sampledTitles().facts(), List.of(), false);
        assertThrows(IllegalArgumentException.class, () -> WikiTopicProtocol.parse(response(List.of(Map.of(
                        "title", "主题", "summary", "涉及相关资料。", "sourceTopicIds", List.of("private-topic"),
                        "claimType", "inferred", "confidence", "medium"))),
                full, full.sampledTitles().facts(), WikiTopicProtocol.cards(leaf), false));
    }

    @Test void compactMergeProjectionRetainsEveryNarrativeCharacterAndQualifier() {
        var full = facts(1);
        String description = "涉及项目资料中的参数讨论。".repeat(35) + "这里只描述标题观察，不能据此声称项目已交付。";
        var card = new WikiTopicProtocol.TopicCard("t-test", "包含完整限定语的主题", description,
                List.of("f0"), List.of("f0"), List.of(), "inferred", "low");
        for (int compactness = 0; compactness < 3; compactness++) {
            String prompt = WikiTopicProtocol.mergePrompt(full, "FINAL", List.of(card), compactness);
            assertTrue(prompt.contains(description));
            assertTrue(prompt.contains(card.title()));
            if (compactness > 0) assertFalse(prompt.contains("\"representatives\""));
        }
    }

    @Test void restoredCardIdentityAndReferencesAreRevalidated() {
        var full = facts(2);
        var result = WikiTopicProtocol.parse(response(List.of(leaf("项目资料", List.of("f0", "f1")))),
                full, full.sampledTitles().facts(), List.of(), false);
        var card = WikiTopicProtocol.cards(result).getFirst();
        var restored = WikiTopicProtocol.parse(response(List.of(card)), full, full.sampledTitles().facts(), List.of(), true);
        assertEquals(result.taskSegments(), restored.taskSegments());
        var corrupt = new WikiTopicProtocol.TopicCard("t-wrong", card.title(), card.summary(), card.memberInputIds(),
                card.representativeFactIds(), card.sourceTopicIds(), card.claimType(), card.confidence());
        assertThrows(IllegalArgumentException.class, () -> WikiTopicProtocol.parse(response(List.of(corrupt)),
                full, full.sampledTitles().facts(), List.of(), true));
    }

    @Test void plannerIsDeterministicUnderInputShuffleAndPreservesWholeTitles() {
        var full = facts(80);
        var limits = new WikiSummaryPipeline.Limits(1000, 16000, 4);
        var first = WikiTopicPlanner.plan(full, limits, 512);
        List<WikiTitleSampler.Fact> shuffled = new ArrayList<>(full.sampledTitles().facts());
        Collections.shuffle(shuffled, new Random(4291));
        var second = WikiTopicPlanner.plan(full.withInput(WikiTitleSampler.fromFacts(full.period(), shuffled,
                full.sampledTitles().coverage()), List.of()), limits, 512);
        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals(first.omittedFactIds(), second.omittedFactIds());
        for (var batch : first.leaves()) for (var fact : batch.facts()) assertTrue(batch.projection().contains(fact.title()));
        Set<String> ids = new HashSet<>();
        first.leaves().forEach(batch -> batch.facts().forEach(f -> assertTrue(ids.add(f.id()))));
        assertEquals(80, ids.size() + first.omittedFactIds().size());
        assertTrue(first.leaves().size() + first.reservedMergeCalls() + 1 <= limits.maxCalls());
    }

    @Test void largeMonthlyChildrenAreBudgetedPerLeafAndMultiAppChildNarrativesRemainTogether() {
        var base = facts(1);
        var period = new WikiPeriod(WikiLevel.MONTH, base.period().start(), base.period().start().plusSeconds(30 * 86400L), "UTC");
        List<WikiTitleSampler.Fact> rows = new ArrayList<>();
        List<String> children = new ArrayList<>();
        for (int task = 0; task < 70; task++) {
            List<String> refs = new ArrayList<>();
            String entry = "day-" + task;
            var start = period.start().plusSeconds((task % 30) * 86400L);
            for (int app = 0; app < 3; app++) {
                String id = "child:" + entry + ":" + app; refs.add(id);
                rows.add(new WikiTitleSampler.Fact(id, "wiki", List.of("Editor", "Browser", "Terminal").get(app),
                        "相关项目资料主题" + task, "inferred", null, 1,
                        List.of(new WikiTitleSampler.Interval(start.toString(), start.plusSeconds(86400).toString(), false, List.of(), 0)), 0));
            }
            children.add(WikiTopicProtocol.json(Map.of("entryId", entry, "start", start.toString(),
                    "end", start.plusSeconds(86400).toString(), "title", "相关项目资料主题" + task,
                    "summary", "查看项目资料中的相关说明。".repeat(25), "evidenceFactIds", refs)));
        }
        var full = new WikiFactBuilder.WikiFacts(period, base.activeSeconds(), base.afkSeconds(), base.switchCount(),
                base.topApps(), List.of(), List.of(), children, base.factBuilderVersion(), base.projectorVersion(),
                base.sourceCoverage(), base.statistics(), WikiTitleSampler.fromFacts(period, rows, Map.of("candidateFacts", rows.size())));
        assertTrue(String.join("\n", children).length() > 12000);
        List<String> prompts = new ArrayList<>();
        try (var store = WikiGenerationStore.inMemory()) {
            var pipeline = new WikiSummaryPipeline(() -> new WikiSummaryPipeline.Session() {
                public String identity() { return "monthly-children"; }
                public String complete(String prompt, Duration timeout) {
                    prompts.add(prompt); return WikiSummaryPipelineTest.groundedResponse(prompt);
                }
            }, new WikiSummaryPipeline.Limits(24000, 12000, 16), store, new WikiGenerationStore.BudgetLimits(20, 256000));
            var result = pipeline.summarize(full, Duration.ofSeconds(10));
            var generation = WikiSummaryPipelineTest.generation(result);
            assertEquals(210, generation.get("processedFacts"));
            assertEquals(0, generation.get("omittedFacts"));
            assertEquals(0L, generation.get("omittedChildSummaries"));
            assertTrue(prompts.stream().allMatch(prompt -> prompt.length() + 512 <= 12000));
            for (String child : children) assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains(child)));
            assertTrue(prompts.stream().filter(prompt -> prompt.startsWith("WIKI_STAGE=FINAL"))
                    .allMatch(prompt -> prompt.contains("sourcePeriods") && prompt.contains("sourceEntries")));
        }
    }

    private static Map<String, Object> leaf(String title, List<String> members) {
        return Map.of("title", title, "summary", "涉及相关资料。", "memberInputIds", members,
                "representativeFactIds", List.of(members.getFirst()), "claimType", "inferred", "confidence", "medium");
    }

    private static String response(List<?> cards) {
        return WikiTopicProtocol.json(Map.of("summary", "涉及相关资料查看。", "primaryTask", "资料查看", "topicCards", cards));
    }
}
