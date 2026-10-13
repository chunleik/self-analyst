package com.selfanalyst.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WikiUserEvidenceTest {
    private static final WikiPrivacyPolicy PRIVACY = WikiPrivacyPolicy.none();
    private static final Instant START = Instant.parse("2026-06-01T10:00:00Z");
    private static final String STATEMENT = "user_statement:synthetic-1";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private WikiFactBuilder.WikiFacts facts() {
        WikiPeriod period = new WikiPeriod(WikiLevel.HOUR, START, START.plusSeconds(3600), "UTC");
        var selection = WikiTitleSampler.fromFacts(period, List.of(new WikiTitleSampler.Fact(
                "f1", "window", "IDE", "甲服务部署说明", "window", 90d, 1,
                List.of(new WikiTitleSampler.Interval(START.toString(), START.plusSeconds(90).toString(), true, List.of(1L), 0)), 0)), Map.of());
        return new WikiFactBuilder.WikiFacts(period, 90, 10, 2, List.of(new WikiEntry.AppDuration("IDE", 90)),
                List.of(), List.of(), List.of(), WikiFactBuilder.FACT_BUILDER_VERSION, "synthetic", Map.of(),
                Map.of("unknownActivitySeconds", 7), selection);
    }

    private WikiEntry.TaskSegment confirmed() {
        return new WikiEntry.TaskSegment("甲服务部署", "已完成甲服务部署。", List.of("模型伪造的证据"),
                List.of("不存在的应用"), "high", List.of(STATEMENT), "user_confirmed");
    }

    private WikiEntry.TaskSegment observed() {
        return new WikiEntry.TaskSegment("部署说明", "查看甲服务部署说明。", List.of(), List.of(), "high", List.of("f1"), "observed");
    }

    private WikiEvidencePolicy.UserStatement statement(WikiEntry.TaskSegment task, int index) {
        return new WikiEvidencePolicy.UserStatement(STATEMENT, "hour", "direct:synthetic", WikiEvidencePolicy.confirmedText(
                task.title(), task.summary(), PRIVACY), index, 3, START.plusSeconds(7200));
    }

    private WikiSummarizer.SummaryResult validate(List<WikiEntry.TaskSegment> tasks, List<WikiEvidencePolicy.UserStatement> statements) {
        return WikiEvidencePolicy.validateRepair(WikiEvidencePolicy.composeConfirmedSummary(tasks), tasks.getFirst().title(),
                tasks, facts(), "hour", 3, statements, PRIVACY);
    }

    @Test
    void explicitBoundedConfirmationSupportsOnlyItsExactTaskAndPreservesStatistics() {
        var result = validate(List.of(confirmed(), observed()), List.of(statement(confirmed(), 0)));
        assertEquals("user_confirmed", result.taskSegments().getFirst().claimType());
        assertEquals("medium", result.taskSegments().getFirst().confidence());
        assertTrue(result.taskSegments().getFirst().apps().isEmpty());
        assertTrue(result.taskSegments().getFirst().evidence().getFirst().contains("未经独立核实"));
        assertEquals("observed", result.taskSegments().get(1).claimType());
        assertEquals("high", result.taskSegments().get(1).confidence());
        assertEquals(90, result.metrics().activeSeconds());
        assertEquals(10, result.metrics().afkSeconds());
        assertEquals(2, result.metrics().switchCount());
        assertEquals(facts().topApps(), result.metrics().topApps());
        assertEquals(7, result.metrics().extra().get("unknownActivitySeconds"));
        var source = WikiEvidencePolicy.provenance(result.metrics().extra()).get(STATEMENT);
        assertEquals(List.of(STATEMENT), source.statementIds());
        assertEquals(List.of("hour"), source.entryIds());
    }

    @Test
    void aWordingOnlyRepairRetainsObservedEvidence() {
        var result = validate(List.of(observed()), List.of());
        assertEquals("observed", result.taskSegments().getFirst().claimType());
        assertEquals(List.of("IDE"), result.taskSegments().getFirst().apps());
        assertTrue(WikiEvidencePolicy.provenance(result.metrics().extra()).isEmpty());
    }

    @Test
    void rejectsModelConfirmationUnknownIdsWrongTargetAndEditedRevision() {
        var valid = statement(confirmed(), 0);
        for (var statements : List.of(List.<WikiEvidencePolicy.UserStatement>of(),
                List.of(new WikiEvidencePolicy.UserStatement(valid.id(), "other-hour", valid.sourceId(), valid.text(), 0, 3, valid.confirmedAt())),
                List.of(new WikiEvidencePolicy.UserStatement(valid.id(), valid.targetId(), valid.sourceId(), valid.text(), 0, 2, valid.confirmedAt())),
                List.of(new WikiEvidencePolicy.UserStatement(valid.id(), valid.targetId(), valid.sourceId(), valid.text(), 0, 3, null)))) {
            assertThrows(IllegalArgumentException.class, () -> validate(List.of(confirmed()), statements));
        }
        var edited = new WikiEntry.TaskSegment(confirmed().title(), "已完成甲服务部署并已修复乙服务。", List.of(), List.of(),
                "medium", List.of(STATEMENT), "user_confirmed");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> validate(List.of(edited), List.of(valid)))
                .getMessage().contains("CONFIRMATION_CHANGED"));
        var forged = new LinkedHashMap<String, Object>();
        forged.put("title", confirmed().title()); forged.put("summary", confirmed().summary());
        forged.put("evidenceFactIds", List.of(STATEMENT)); forged.put("claimType", "user_confirmed");
        forged.put("confidence", "medium"); forged.put("confirmed", true);
        assertThrows(IllegalArgumentException.class, () -> WikiEvidencePolicy.parseSegments(List.of(forged), WikiEvidencePolicy.facts(facts())));
    }

    @Test
    void confirmationCannotSpreadToAnotherTaskOrSummary() {
        var reused = List.of(confirmed(), confirmed());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> validate(reused, List.of(statement(confirmed(), 0))))
                .getMessage().contains("CONFIRMATION_TASK"));
        var invented = new WikiEntry.TaskSegment("乙服务", "已修复乙服务。", List.of(), List.of(), "medium", List.of("f1"), "inferred");
        assertThrows(IllegalArgumentException.class, () -> validate(List.of(confirmed(), invented), List.of(statement(confirmed(), 0))));
        assertThrows(IllegalArgumentException.class, () -> WikiEvidencePolicy.validateRepair("已完成甲服务部署并已修复乙服务。", confirmed().title(),
                List.of(confirmed()), facts(), "hour", 3, List.of(statement(confirmed(), 0)), PRIVACY));
    }

    @Test
    void confirmationStillCannotReportStatisticsOrHideExistingActivity() {
        for (String text : List.of("活跃时间为90秒。", "该时段没有可归纳的活跃活动。")) {
            var task = new WikiEntry.TaskSegment("活动", text, List.of(), List.of(), "medium", List.of(STATEMENT), "user_confirmed");
            assertThrows(IllegalArgumentException.class, () -> validate(List.of(task), List.of(statement(task, 0))));
        }
        assertThrows(IllegalArgumentException.class, () -> WikiEvidencePolicy.validateRepair("", "空", List.of(), facts(), "hour", 3, List.of(), PRIVACY));
    }

    @Test
    void redactionAndNormalizationMustMatchTheExplicitPreview() {
        var task = new WikiEntry.TaskSegment("甲服务", "已完成10.2.3.4服务部署。", List.of(), List.of(), "high", List.of(STATEMENT), "user_confirmed");
        var valid = statement(task, 0);
        assertFalse(valid.text().contains("10.2.3.4"));
        assertTrue(validate(List.of(task), List.of(valid)).taskSegments().getFirst().summary().contains("[内网地址]"));
        var oldUnredacted = new WikiEvidencePolicy.UserStatement(STATEMENT, "hour", "direct:synthetic", task.title() + "\n" + task.summary(), 0, 3, valid.confirmedAt());
        assertThrows(IllegalArgumentException.class, () -> validate(List.of(task), List.of(oldUnredacted)));
        assertFalse(WikiEvidencePolicy.normalizeText("１０．２．３．４", PRIVACY).contains("10.2.3.4"));
    }

    @Test
    void mixedSourceKeepsSelfReportLabelAndDerivesOnlyRealApps() {
        var mixed = new WikiEntry.TaskSegment(confirmed().title(), confirmed().summary(), List.of(), List.of("Fake"), "high",
                List.of("f1", STATEMENT), "user_confirmed");
        var result = validate(List.of(mixed), List.of(statement(mixed, 0))).taskSegments().getFirst();
        assertEquals(List.of("IDE"), result.apps());
        assertEquals("user_confirmed", result.claimType());
        assertEquals("medium", result.confidence());
    }

    @Test
    void hourHalfdayAndDayRetainStatementChainWithoutInventingClaims() throws Exception {
        var repaired = validate(List.of(confirmed()), List.of(statement(confirmed(), 0)));
        WikiEntry hour = entry("hour", WikiLevel.HOUR, repaired);
        WikiFactBuilder builder = new WikiFactBuilder(null, 12000);
        WikiPeriod halfPeriod = new WikiPeriod(WikiLevel.HALF_DAY, START, START.plusSeconds(7200), "UTC");
        var halfFacts = builder.buildFactsFromChildren(List.of(hour), halfPeriod);
        assertEquals("user_confirmed", halfFacts.sampledTitles().facts().getFirst().kind());
        assertTrue(halfFacts.childSummaries().getFirst().contains(STATEMENT));
        var half = parseParent(halfFacts, confirmed().title(), confirmed().summary(), "user_confirmed");
        assertEquals(confirmed().summary(), half.summary());
        assertEquals("medium", half.taskSegments().getFirst().confidence());
        assertTrue(half.taskSegments().getFirst().apps().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> parseParent(halfFacts, confirmed().title(), "已完成乙服务部署。", "user_confirmed"));
        assertThrows(IllegalArgumentException.class, () -> parseParent(halfFacts, "部署说明", "查看部署说明。", "observed"));
        WikiEntry savedHalf = entry("half", WikiLevel.HALF_DAY, half);
        // Simulate JSON round trip from SQLite, rather than relying on typed in-memory records.
        @SuppressWarnings("unchecked") Map<String, Object> extra = json.readValue(json.writeValueAsString(half.metrics().extra()), Map.class);
        savedHalf = entry("half", WikiLevel.HALF_DAY, new WikiSummarizer.SummaryResult(half.summary(), half.primaryTask(), half.taskSegments(),
                new WikiEntry.WikiMetrics(90, 10, 2, half.metrics().topApps(), extra)));
        var dayFacts = builder.buildFactsFromChildren(List.of(savedHalf), new WikiPeriod(WikiLevel.DAY, START, START.plusSeconds(86400), "UTC"));
        var day = parseParent(dayFacts, confirmed().title(), confirmed().summary(), "user_confirmed");
        var provenance = WikiEvidencePolicy.provenance(day.metrics().extra()).values().iterator().next();
        assertEquals(List.of(STATEMENT), provenance.statementIds());
        assertEquals(List.of("hour", "half"), provenance.entryIds());
        assertEquals("user_confirmed", day.taskSegments().getFirst().claimType());
        assertEquals(90, day.metrics().activeSeconds());
    }



    @Test
    void confirmedParentPipelineCheckpointRestoresWithoutAnotherModelCall() throws Exception {
        var repaired = validate(List.of(confirmed()), List.of(statement(confirmed(), 0)));
        var parent = new WikiFactBuilder(null, 12000).buildFactsFromChildren(List.of(entry("hour", WikiLevel.HOUR, repaired)),
                new WikiPeriod(WikiLevel.HALF_DAY, START, START.plusSeconds(7200), "UTC"));
        String id = parent.sampledTitles().facts().getFirst().id();
        String response = json.writeValueAsString(Map.of("summary", "模型新增的结果不会进入摘要。", "primaryTask", "主线", "topicCards",
                List.of(Map.of("title", confirmed().title(), "summary", confirmed().summary(), "memberInputIds", List.of(id),
                        "representativeFactIds", List.of(id), "claimType", "user_confirmed", "confidence", "high"))));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var ledger = WikiGenerationStore.inMemory()) {
            var pipeline = new WikiSummaryPipeline(() -> new WikiSummaryPipeline.Session() {
                public String identity() { return "synthetic-confirmed"; }
                public String complete(String prompt, java.time.Duration timeout) { calls.incrementAndGet(); return response; }
            }, new WikiSummaryPipeline.Limits(24000, 32000, 6), ledger, WikiGenerationStore.BudgetLimits.defaults());
            var first = pipeline.summarize(parent, java.time.Duration.ofSeconds(5));
            pipeline.markPublished(first);
            var restored = pipeline.summarize(parent, java.time.Duration.ofSeconds(5));
            assertEquals(1, calls.get());
            assertEquals(first.taskSegments(), restored.taskSegments());
            assertEquals(confirmed().summary(), restored.summary());
            assertEquals("user_confirmed", restored.taskSegments().getFirst().claimType());
            assertEquals("medium", restored.taskSegments().getFirst().confidence());
            assertEquals(List.of(STATEMENT), WikiEvidencePolicy.provenance(restored.metrics().extra()).get(id).statementIds());
        }
    }

    @Test
    void legacySegmentsRemainReadableWithoutFabricatedReferences() throws Exception {
        var segment = json.readValue("{\"title\":\"旧主题\",\"summary\":\"查看旧资料。\",\"confidence\":\"low\"}", WikiEntry.TaskSegment.class);
        assertEquals("legacy", segment.claimType());
        assertTrue(segment.evidenceFactIds().isEmpty());
    }

    @Test
    void persistedConfirmedTypeCannotAuthorizeAChangedChildDescription() {
        var repaired = validate(List.of(confirmed()), List.of(statement(confirmed(), 0)));
        var changed = new WikiEntry.TaskSegment(confirmed().title(), "已完成乙服务部署。", List.of(), List.of(), "medium", List.of(STATEMENT), "user_confirmed");
        var tampered = entry("hour", WikiLevel.HOUR, new WikiSummarizer.SummaryResult(changed.summary(), changed.title(), List.of(changed), repaired.metrics()));
        assertThrows(IllegalArgumentException.class, () -> new WikiFactBuilder(null, 12000).buildFactsFromChildren(List.of(tampered), facts().period()));
    }


    @Test
    void confirmedFocusPacksFourLongDescriptionsWithoutOversizedTask() throws Exception {
        var result = focusConfirmed(4, 150, true);
        assertTrue(result.taskSegments().size() <= 3);
        assertTrue(result.taskSegments().stream().anyMatch(task -> "observed".equals(task.claimType())));
        assertEquals(4, result.taskSegments().stream().filter(task -> "user_confirmed".equals(task.claimType()))
                .flatMap(task -> task.evidenceFactIds().stream()).distinct().count());
        assertTrue(result.taskSegments().stream().allMatch(task -> task.summary().length() <= 500));
        assertEquals(0, result.metrics().extra().get("omittedConfirmedTopicCards"));
    }

    @Test
    void confirmedFocusRecordsWholeOmissionsInsteadOfTruncatingAssertions() throws Exception {
        var result = focusConfirmed(4, 299, false);
        assertEquals(3, result.taskSegments().size());
        assertTrue(result.taskSegments().stream().allMatch(task -> task.summary().length() == 299));
        assertEquals(1, result.metrics().extra().get("omittedConfirmedTopicCards"));
        assertEquals(1, ((List<?>) result.metrics().extra().get("omittedConfirmedTopicIds")).size());
        assertEquals(1, ((List<?>) result.metrics().extra().get("omittedConfirmedInputIds")).size());
        assertEquals(4, WikiEvidencePolicy.provenance(result.metrics().extra()).size());
        assertTrue(result.summary().length() <= 1200);
    }

    @Test
    void confirmedFocusSplitsMoreThanSixteenReferencesWithoutDiscardingThem() throws Exception {
        var result = focusConfirmed(24, 20, false);
        assertTrue(result.taskSegments().size() <= 3);
        assertTrue(result.taskSegments().stream().allMatch(task -> task.evidenceFactIds().size() <= 16));
        assertEquals(24, result.taskSegments().stream().flatMap(task -> task.evidenceFactIds().stream()).distinct().count());
        assertEquals(0, result.metrics().extra().get("omittedConfirmedTopicCards"));
    }


    @Test
    void confirmedParentCanSelectBoundedNarrativeFromLargeValidChildCatalog() throws Exception {
        var result = focusConfirmed(24, 300, false);
        assertTrue(result.taskSegments().size() <= 3);
        assertTrue(result.summary().length() <= 1200);
        assertEquals(21, result.metrics().extra().get("omittedConfirmedTopicCards"));
        assertEquals(21, ((List<?>) result.metrics().extra().get("omittedConfirmedInputIds")).size());
    }

    @Test
    void modelCannotConsumeTwoConfirmedMembersWhileOnlyDescribingOne() throws Exception {
        var period = new WikiPeriod(WikiLevel.HALF_DAY, START, START.plusSeconds(7200), "UTC");
        var a = new WikiTitleSampler.Fact("a", "wiki", "", "甲服务", "user_confirmed", 10d, 1, List.of(), 0);
        var b = new WikiTitleSampler.Fact("b", "wiki", "", "乙服务", "user_confirmed", 10d, 1, List.of(), 0);
        var sources = Map.of("a", new WikiEvidencePolicy.ConfirmedSource("甲服务", "已完成甲服务部署。", List.of("user_statement:a"), List.of("hour")),
                "b", new WikiEvidencePolicy.ConfirmedSource("乙服务", "已完成乙服务部署。", List.of("user_statement:b"), List.of("hour")));
        var facts = new WikiFactBuilder.WikiFacts(period, 20, 0, 0, List.of(), List.of(), List.of(), List.of(),
                WikiFactBuilder.FACT_BUILDER_VERSION, "synthetic", Map.of(), Map.of(WikiEvidencePolicy.PROVENANCE_KEY, sources),
                WikiTitleSampler.fromFacts(period, List.of(a, b), Map.of()));
        String omitted = json.writeValueAsString(Map.of("summary", "主线", "primaryTask", "甲服务", "topicCards", List.of(Map.of(
                "title", "甲服务", "summary", "已完成甲服务部署。", "memberInputIds", List.of("a", "b"), "representativeFactIds", List.of("a"),
                "claimType", "user_confirmed", "confidence", "medium"))));
        assertEquals("WIKI_TOPIC_CONFIRMED_MEMBERS", assertThrows(IllegalArgumentException.class,
                () -> WikiTopicProtocol.parse(omitted, facts, List.of(a, b), List.of(), false)).getMessage());
        // Even both representatives do not authorize omitting a distinct source's assertion.
        assertThrows(IllegalArgumentException.class, () -> WikiTopicProtocol.parse(omitted.replace("\"representativeFactIds\":[\"a\"]",
                "\"representativeFactIds\":[\"a\",\"b\"]"), facts, List.of(a, b), List.of(), false));
        String separate = json.writeValueAsString(Map.of("summary", "主线", "primaryTask", "主题", "topicCards", List.of(
                Map.of("title", "甲服务", "summary", "已完成甲服务部署。", "memberInputIds", List.of("a"), "representativeFactIds", List.of("a"), "claimType", "user_confirmed", "confidence", "medium"),
                Map.of("title", "乙服务", "summary", "已完成乙服务部署。", "memberInputIds", List.of("b"), "representativeFactIds", List.of("b"), "claimType", "user_confirmed", "confidence", "medium"))));
        var parents = WikiTopicProtocol.cards(WikiTopicProtocol.parse(separate, facts, List.of(a, b), List.of(), false));
        var merged = new LinkedHashMap<String, Object>();
        merged.put("title", "甲服务"); merged.put("summary", "已完成甲服务部署。");
        merged.put("sourceTopicIds", parents.stream().map(WikiTopicProtocol.TopicCard::id).toList());
        merged.put("claimType", "user_confirmed"); merged.put("confidence", "medium");
        String missingDescription = json.writeValueAsString(Map.of("summary", "主线", "primaryTask", "主题", "topicCards", List.of(merged)));
        assertThrows(IllegalArgumentException.class, () -> WikiTopicProtocol.parse(missingDescription, facts, List.of(a, b), parents, false));
        merged.put("title", WikiEvidencePolicy.CONFIRMED_GROUP_TITLE);
        merged.put("summary", "已完成甲服务部署。\n已完成乙服务部署。");
        String complete = json.writeValueAsString(Map.of("summary", "主线", "primaryTask", "主题", "topicCards", List.of(merged)));
        var valid = WikiTopicProtocol.parse(complete, facts, List.of(a, b), parents, false);
        assertEquals(List.of("a", "b"), valid.taskSegments().getFirst().evidenceFactIds());
        assertEquals("user_confirmed", valid.taskSegments().getFirst().claimType());
    }

    private WikiSummarizer.SummaryResult focusConfirmed(int count, int descriptionLength, boolean automatic) throws Exception {
        var period = new WikiPeriod(WikiLevel.HALF_DAY, START, START.plusSeconds(7200), "UTC");
        List<WikiTitleSampler.Fact> sourceFacts = new ArrayList<>();
        List<Map<String, Object>> cards = new ArrayList<>();
        Map<String, WikiEvidencePolicy.ConfirmedSource> sources = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String id = "child:synthetic:" + i;
            String title = "任务" + i;
            String prefix = "已完成服务" + i + "部署";
            String description = prefix + "甲".repeat(descriptionLength - prefix.length() - 1) + "。";
            sourceFacts.add(new WikiTitleSampler.Fact(id, "wiki", "", title, "user_confirmed", (double) (count - i), 1, List.of(), 0));
            sources.put(id, new WikiEvidencePolicy.ConfirmedSource(title, description, List.of("user_statement:many-" + i), List.of("hour" + i)));
            cards.add(Map.of("title", title, "summary", description, "memberInputIds", List.of(id), "representativeFactIds", List.of(id),
                    "claimType", "user_confirmed", "confidence", "medium"));
        }
        if (automatic) {
            sourceFacts.add(new WikiTitleSampler.Fact("auto", "wiki", "IDE", "资料", "window", 100d, 1, List.of(), 0));
            cards.add(Map.of("title", "资料", "summary", "查看服务部署资料。", "memberInputIds", List.of("auto"), "representativeFactIds", List.of("auto"),
                    "claimType", "observed", "confidence", "medium"));
        }
        var facts = new WikiFactBuilder.WikiFacts(period, 90, 0, 0, List.of(), List.of(), List.of(), List.of(),
                WikiFactBuilder.FACT_BUILDER_VERSION, "synthetic", Map.of(), Map.of(WikiEvidencePolicy.PROVENANCE_KEY, sources),
                WikiTitleSampler.fromFacts(period, sourceFacts, Map.of()));
        String response = json.writeValueAsString(Map.of("summary", "主线", "primaryTask", "主题", "topicCards", cards));
        var parsed = WikiTopicProtocol.parse(response, facts, sourceFacts, List.of(), false);
        return WikiSummaryFocus.apply(parsed, facts);
    }

    private WikiSummarizer.SummaryResult parseParent(WikiFactBuilder.WikiFacts facts, String title, String summary, String type) throws Exception {
        String id = facts.sampledTitles().facts().getFirst().id();
        String response = json.writeValueAsString(Map.of("summary", "已修复未经确认的另一项故障。", "primaryTask", "主线",
                "topicCards", List.of(Map.of("title", title, "summary", summary, "memberInputIds", List.of(id),
                        "representativeFactIds", List.of(id), "claimType", type, "confidence", "high"))));
        return WikiTopicProtocol.parse(response, facts, facts.sampledTitles().facts(), List.of(), false);
    }

    private WikiEntry entry(String id, WikiLevel level, WikiSummarizer.SummaryResult result) {
        return new WikiEntry(id, level, START, START.plusSeconds(3600), "UTC", WikiStatus.SUMMARIZED, result.summary(), result.primaryTask(),
                result.taskSegments(), result.metrics(), List.of(), "repair", "wiki-v10-user-confirmed", 0, null, null, START, START, START);
    }
}
