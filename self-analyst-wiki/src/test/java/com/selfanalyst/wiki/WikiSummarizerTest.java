package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WikiSummarizerTest {

    private static final String GROUNDED_JSON = """
            {"summary":"查看数据库同步设计","primaryTask":"数据库同步相关活动",
             "taskSegments":[{"title":"查看设计","summary":"涉及数据库同步",
             "confidence":"high","evidenceFactIds":["f1"],"claimType":"inferred"}]}
            """;

    private final ZoneId tz = ZoneId.systemDefault();
    private final Instant t1 = Instant.parse("2026-06-01T10:00:00Z");
    private final Instant t2 = Instant.parse("2026-06-01T11:00:00Z");

    private static final String SAMPLE_VALID_JSON = """
        {
          "summary": "主要在进行Java后端开发",
          "primaryTask": "SelfAnalyst Wiki功能开发",
          "taskSegments": [
            {"title": "编码", "summary": "写WikiStore代码", "evidence": ["IDE可见"], "apps": ["IntelliJ"], "confidence": "high"}
          ],
          "metrics": {"activeSeconds": 3600, "afkSeconds": 0, "switchCount": 3, "topApps": []}
        }
        """;

    @Test
    void structuredResponseKeepsTraceableReferencesAndOnlyLocalMetrics() {
        var summarizer = new WikiSummarizer(prompt -> GROUNDED_JSON.replace("\"taskSegments\":",
                "\"metrics\":{\"invented\":\"PRIVATE_METRIC\"},\"taskSegments\":"));
        var facts = structuredFacts();
        var result = summarizer.summarize(facts, Duration.ofSeconds(3));
        assertEquals("medium", result.taskSegments().getFirst().confidence());
        assertEquals(List.of("f1"), result.taskSegments().getFirst().evidenceFactIds());
        assertEquals(List.of("观察到标题：「数据库同步设计」"), result.taskSegments().getFirst().evidence());
        assertEquals(facts.sampledTitles().facts(), result.metrics().extra().get("evidenceFacts"));
        assertFalse(result.metrics().extra().containsKey("invented"));
        assertEquals(3600, result.metrics().activeSeconds());
        assertTrue(summarizer.buildPrompt(facts).contains("evidenceFactIds"));
        assertFalse(summarizer.buildPrompt(facts).contains("不证明"));
        assertTrue(summarizer.buildPrompt(facts).contains("不要在文案里说明证据边界"));
    }

    @Test
    void rejectsEmptyMalformedAndUnreferencedStructuredTasks() {
        var summarizer = new WikiSummarizer(prompt -> GROUNDED_JSON);
        for (String response : List.of(
                "{\"summary\":\"查看设计\",\"primaryTask\":\"数据库\",\"taskSegments\":[]}",
                GROUNDED_JSON.replace("\"f1\"", "\"PRIVATE_UNKNOWN\""),
                GROUNDED_JSON.replace("\"evidenceFactIds\":[\"f1\"],", ""),
                GROUNDED_JSON.replace("\"title\":\"查看设计\"", "\"title\":42"))) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> summarizer.parseResponse(response, structuredFacts()));
            assertFalse(error.getMessage().contains("PRIVATE"));
            assertNull(error.getCause());
        }
    }

    @Test
    void structuredTasksIgnoreCompatibilityFieldsButRetainTheWholeResponseLimit() {
        var summarizer = new WikiSummarizer(prompt -> GROUNDED_JSON);
        var expected = summarizer.parseResponse(GROUNDED_JSON, structuredFacts());
        for (String extras : List.of(
                "\"apps\":[\"Other\"],\"evidence\":[\"已完成迁移，AFK覆盖为partial\"],",
                "\"apps\":null,\"evidence\":42,",
                "\"apps\":{\"invented\":true},\"evidence\":\"自由文本\",")) {
            String response = GROUNDED_JSON.replace("\"confidence\":", extras + "\"confidence\":");
            assertEquals(expected, summarizer.parseResponse(response, structuredFacts()));
            assertThrows(IllegalArgumentException.class, () -> summarizer.parseResponse(
                    response.replace("\"f1\"", "\"unknown\""), structuredFacts()));
        }
        String oversized = GROUNDED_JSON.replace("\"confidence\":",
                "\"apps\":\"" + "x".repeat(65536) + "\",\"confidence\":");
        var error = assertThrows(IllegalArgumentException.class,
                () -> summarizer.parseResponse(oversized, structuredFacts()));
        assertEquals("WIKI_RESPONSE_STRUCTURE:response", error.getMessage());
        assertEquals(List.of("IDE"), expected.taskSegments().getFirst().apps());
    }

    @Test
    void promptRequestsOnlyTaskSemanticsAndRepresentativeReferencesAcrossApps() {
        var summarizer = new WikiSummarizer(prompt -> GROUNDED_JSON);
        String prompt = summarizer.buildPrompt(structuredFacts());
        String output = prompt.substring(prompt.indexOf("## 输出要求"));
        assertFalse(output.contains("\"apps\":"));
        assertFalse(output.contains("\"evidence\":"));
        assertFalse(output.contains("apps必须"));
        assertTrue(output.contains("1-3"));
        assertTrue(output.contains("apps和evidence由本地"));
        assertTrue(output.contains("跨应用"));
        assertTrue(output.contains("不要按应用拆分"));
        assertEquals("wiki-v9-focus", summarizer.promptVersion());
    }

    @Test
    void evidenceBoundaryDisclaimersAreRemovedWithoutHidingOutcomes() {
        var summarizer = new WikiSummarizer(prompt -> GROUNDED_JSON);
        String response = GROUNDED_JSON
                .replace("查看数据库同步设计", "查看数据库同步设计。以上仅为查看或窗口标题，不证明项目运行、配置、交付或消息发送。")
                .replace("涉及数据库同步", "涉及企业微信与 WorkBuddy 窗口；不表明发送消息或参会。");
        var result = summarizer.parseResponse(response, structuredFacts());
        assertEquals("查看数据库同步设计。", result.summary());
        assertEquals("涉及企业微信与 WorkBuddy 窗口。", result.taskSegments().getFirst().summary());
        assertEquals(2, result.metrics().extra().get("disclaimerClausesRemoved"));
        String onlyDisclaimer = GROUNDED_JSON.replace("涉及数据库同步", "不表明发送消息或参会。");
        assertEquals("涉及查看设计。", summarizer.parseResponse(onlyDisclaimer, structuredFacts())
                .taskSegments().getFirst().summary());
        assertDoesNotThrow(() -> summarizer.parseResponse(
                GROUNDED_JSON.replace("涉及数据库同步", "排查连接不返回数据的问题。"), structuredFacts()));
        var rejected = assertThrows(IllegalArgumentException.class, () -> summarizer.parseResponse(
                GROUNDED_JSON.replace("查看数据库同步设计", "已完成发布"), structuredFacts()));
        assertTrue(rejected.getMessage().startsWith("WIKI_EVIDENCE_UNSUPPORTED_CLAIM:"));
        assertFalse(WikiTopicProtocol.factPrompt(structuredFacts(), "DIRECT", "{\"id\":\"f1\"}").contains("不证明"));
    }

    @Test
    void negatedOutcomeWordsAreStillDisclaimersButContrastedOutcomesAreRejected() {
        var summarizer = new WikiSummarizer(prompt -> GROUNDED_JSON);
        var negated = summarizer.parseResponse(GROUNDED_JSON.replace("查看数据库同步设计",
                "查看数据库同步设计。标题仅表明相关开发活动被观察，不证明具体任务已完成或交付。"), structuredFacts());
        assertEquals("查看数据库同步设计。", negated.summary());
        var joined = summarizer.parseResponse(GROUNDED_JSON.replace("涉及数据库同步",
                "涉及数据库同步，不证明已交付"), structuredFacts());
        assertEquals("涉及数据库同步。", joined.taskSegments().getFirst().summary());
        for (String contrasted : List.of("不证明实际参加会议，但已解决问题", "不证明参会但已解决问题")) {
            var error = assertThrows(IllegalArgumentException.class, () -> summarizer.parseResponse(
                    GROUNDED_JSON.replace("查看数据库同步设计", contrasted), structuredFacts()), contrasted);
            assertTrue(error.getMessage().startsWith("WIKI_EVIDENCE_UNSUPPORTED_CLAIM:"), contrasted);
        }
    }

    @Test
    void titleWordingPassesAcrossNarrativeFieldsButLaterAssertionsStillFail() {
        var summarizer = new WikiSummarizer(prompt -> GROUNDED_JSON);
        String titleObservation = "涉及协作程序中带添加群聊成员字样的窗口标题，仅反映该窗口观察。";
        for (String original : List.of("查看数据库同步设计", "数据库同步相关活动", "查看设计", "涉及数据库同步")) {
            assertDoesNotThrow(() -> summarizer.parseResponse(
                    GROUNDED_JSON.replace(original, titleObservation), structuredFacts()));
            for (String assertion : List.of("随后添加了群聊成员。", "已完成数据库迁移。")) {
                var error = assertThrows(IllegalArgumentException.class, () -> summarizer.parseResponse(
                        GROUNDED_JSON.replace(original, titleObservation + assertion), structuredFacts()));
                assertTrue(error.getMessage().startsWith("WIKI_EVIDENCE_UNSUPPORTED_CLAIM:"));
            }
        }
    }

    @Test
    void validatesGlobalAssertionsAndJsonWithoutLeakingResponse() {
        var summarizer = new WikiSummarizer(prompt -> GROUNDED_JSON);
        for (String response : List.of(
                GROUNDED_JSON.replace("查看数据库同步设计", "已完成迁移并成功发布"),
                GROUNDED_JSON.replace("数据库同步相关活动", "已解决数据库故障"),
                GROUNDED_JSON.replace("\"summary\":\"涉及数据库同步\"", "\"summary\":\"参与团队会议\""),
                GROUNDED_JSON + " PRIVATE_TRAILING_JSON", "{\"PRIVATE_BROKEN_JSON\":", "[]",
                GROUNDED_JSON.replace("\"summary\":\"查看数据库同步设计\"", "\"summary\":\"x\",\"summary\":\"y\""))) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> summarizer.parseResponse(response, structuredFacts()));
            assertFalse(error.getMessage().contains("PRIVATE"));
            assertNull(error.getCause());
        }
        assertDoesNotThrow(() -> summarizer.parseResponse(
                GROUNDED_JSON.replace("查看数据库同步设计", "查看已完成订单列表"), structuredFacts()));
    }

    @Test
    void onceBoundaryPropagatesRemainingTimeout() {
        var timeout = Duration.ofMillis(1234);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var summarizer = new WikiSummarizer((prompt, actualTimeout) -> {
            assertEquals(timeout, actualTimeout);
            calls.incrementAndGet();
            return GROUNDED_JSON;
        });
        summarizer.summarizeOnce(structuredFacts(), timeout);
        assertEquals(1, calls.get());
    }

    private WikiFactBuilder.WikiFacts structuredFacts() {
        var fact = new WikiTitleSampler.Fact("f1", "window", "IDE", "数据库同步设计", "window", 30.0, 1,
                List.of(new WikiTitleSampler.Interval(t1.toString(), t2.toString(), true, List.of(7L), 0)), 0);
        return new WikiFactBuilder.WikiFacts(new WikiPeriod(WikiLevel.HOUR, t1, t2, tz.getId()), 3600, 0, 0,
                List.of(new WikiEntry.AppDuration("IDE", 3600)), List.of(), List.of(), List.of(),
                WikiFactBuilder.FACT_BUILDER_VERSION, null,
                java.util.Map.of("afk", new WikiEntry.SourceCoverage("complete", t1, t2, 0L)), java.util.Map.of(),
                new WikiTitleSampler.Selection(List.of(fact), "{\"id\":\"f1\",\"title\":\"数据库同步设计\"}", java.util.Map.of()));
    }

    @Test
    void promptSeparatesInternalStatisticsFromAllNarrativeFields() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var summarizer = new WikiSummarizer(prompt -> {
            calls.incrementAndGet();
            assertTrue(prompt.contains("仅用于内部判断，不得复述"));
            assertTrue(prompt.contains("taskSegments.title/summary"));
            assertTrue(prompt.contains("旧子摘要中的统计说明也不得复制"));
            String outputSection = prompt.substring(prompt.indexOf("## 输出要求"));
            assertFalse(outputSection.contains("\"metrics\":"), "the model should only generate narrative fields");
            return SAMPLE_VALID_JSON.replace("\"activeSeconds\": 3600", "\"activeSeconds\": 99999");
        });
        var result = summarizer.summarize(factsWithCoverage("partial", java.util.Map.of()), Duration.ofSeconds(30));
        assertEquals(1, calls.get());
        assertEquals(7200, result.metrics().activeSeconds());
        assertEquals(300, result.metrics().afkSeconds());
    }

    @Test
    void incompleteCoverageCapsConfidenceWithoutAddingStatisticsToText() {
        var summarizer = new WikiSummarizer(prompt -> SAMPLE_VALID_JSON);
        for (String status : List.of("missing", "partial", "failed", "lagging")) {
            var result = summarizer.summarize(factsWithCoverage(status, java.util.Map.of()), Duration.ofSeconds(30));
            assertEquals("medium", result.taskSegments().getFirst().confidence(), status);
            assertEquals("主要在进行Java后端开发", result.summary());
            assertEquals(List.of("IDE可见"), result.taskSegments().getFirst().evidence());
        }
        var certain = summarizer.summarize(factsWithCoverage("complete", java.util.Map.of()), Duration.ofSeconds(30));
        assertEquals("high", certain.taskSegments().getFirst().confidence());
        var conflict = summarizer.summarize(factsWithCoverage("complete", java.util.Map.of("conflictSeconds", 1.5)), Duration.ofSeconds(30));
        assertEquals("medium", conflict.taskSegments().getFirst().confidence());
    }

    @Test
    void rejectsStatisticsInEveryNarrativeFieldWithoutExtraModelCallsOrTextInError() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String field : List.of("summary", "primaryTask", "taskSegments.title", "taskSegments.summary", "taskSegments.evidence")) {
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(SAMPLE_VALID_JSON);
            String forbidden = "AFK覆盖为partial，PRIVATE_RESPONSE_MARKER";
            if (!field.startsWith("taskSegments.")) tree.put(field, forbidden);
            else {
                var segment = (com.fasterxml.jackson.databind.node.ObjectNode) tree.get("taskSegments").get(0);
                String key = field.substring("taskSegments.".length());
                if (key.equals("evidence")) segment.putArray(key).add(forbidden);
                else segment.put(key, forbidden);
            }
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var summarizer = new WikiSummarizer(prompt -> { calls.incrementAndGet(); return tree.toString(); });
            var exception = assertThrows(IllegalArgumentException.class,
                    () -> summarizer.summarize(factsWithCoverage("complete", java.util.Map.of()), Duration.ofSeconds(30)), field);
            assertEquals("WIKI_NARRATIVE_STATISTICS:" + field, exception.getMessage());
            assertFalse(exception.getMessage().contains("PRIVATE_RESPONSE_MARKER"));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void responseWithoutModelMetricsPreservesLocalExactValues() {
        var stats = java.util.Map.<String, Object>of("activeSecondsExact", 7200.75, "uncoveredSeconds", 10.25);
        var summarizer = new WikiSummarizer(prompt -> """
                {"summary":"查看数据库同步与ETL资料", "primaryTask":"数据库相关开发",
                 "taskSegments":[{"title":"排查连接超时", "summary":"排查30秒连接超时，并调试AFK采集器",
                 "evidence":["编辑器出现相关配置标题"], "apps":["IDE"], "confidence":"low"}]}
                """);
        var result = summarizer.summarize(factsWithCoverage("partial", stats), Duration.ofSeconds(30));
        assertEquals(7200, result.metrics().activeSeconds());
        assertEquals(stats, result.metrics().extra());
        assertEquals("low", result.taskSegments().getFirst().confidence());
    }

    private WikiFactBuilder.WikiFacts factsWithCoverage(String status, java.util.Map<String, Object> stats) {
        return new WikiFactBuilder.WikiFacts(new WikiPeriod(WikiLevel.DAY, t1, t2, tz.getId()),
                7200, 300, 25, List.of(new WikiEntry.AppDuration("IDE", 7200)),
                List.of("数据库同步设计"), List.of(), List.of("旧摘要：活跃时长2小时，AFK覆盖partial。"),
                WikiFactBuilder.FACT_BUILDER_VERSION, null,
                java.util.Map.of("afk", new WikiEntry.SourceCoverage(status, t1, t2, null)), stats);
    }

    @Test
    void shouldUseRawFactsForHour() {
        WikiSummarizer summarizer = new WikiSummarizer(prompt -> {
            assertTrue(prompt.contains("应用内标题样本"));
            assertTrue(prompt.contains("项目讨论群"));
            assertFalse(prompt.contains("屏幕内容片段"));
            assertFalse(prompt.contains("SELF_ANALYST_FORBIDDEN_BODY_7F3A"));
            return SAMPLE_VALID_JSON;
        });
        WikiFactBuilder.WikiFacts facts = new WikiFactBuilder.WikiFacts(
                new WikiPeriod(WikiLevel.HOUR, t1, t2, tz.getId()),
                3600, 120, 15,
                List.of(new WikiEntry.AppDuration("IntelliJ", 2400)),
                List.of("IntelliJ - SelfAnalyst"),
                List.of("[Weixin.exe] 项目讨论群"), List.of());

        WikiSummarizer.SummaryResult result = summarizer.summarize(facts, Duration.ofSeconds(30));
        assertEquals("主要在进行Java后端开发", result.summary());
        assertEquals("SelfAnalyst Wiki功能开发", result.primaryTask());
        assertEquals(1, result.taskSegments().size());
        assertEquals(3600, result.metrics().activeSeconds());
        assertEquals(120, result.metrics().afkSeconds());
    }

    @Test
    void shouldUseChildSummariesForWeek() {
        WikiSummarizer summarizer = new WikiSummarizer(prompt -> {
            assertTrue(prompt.contains("已总结"), "Week prompt should reference child summaries");
            return SAMPLE_VALID_JSON.replace("Java后端开发", "本周技术工作");
        });

        WikiFactBuilder.WikiFacts facts = new WikiFactBuilder.WikiFacts(
                new WikiPeriod(WikiLevel.WEEK, t1, Instant.parse("2026-06-08T00:00:00Z"), tz.getId()),
                28800, 1200, 60,
                List.of(new WikiEntry.AppDuration("IntelliJ", 20000)),
                List.of(), List.of(),
                List.of("周一: 编码工作", "周二: 代码审查"));

        WikiSummarizer.SummaryResult result = summarizer.summarize(facts, Duration.ofSeconds(30));
        assertNotNull(result.summary());
    }

    @Test
    void shouldRejectInvalidJson() {
        WikiSummarizer summarizer = new WikiSummarizer(prompt -> "not valid json {{{");
        WikiFactBuilder.WikiFacts facts = new WikiFactBuilder.WikiFacts(
                new WikiPeriod(WikiLevel.HOUR, t1, t2, tz.getId()),
                3600, 0, 5, List.of(), List.of(), List.of());

        assertThrows(RuntimeException.class, () ->
                summarizer.summarize(facts, Duration.ofSeconds(5)));
    }

    @Test
    void shouldRejectMissingSummary() {
        WikiSummarizer summarizer = new WikiSummarizer(prompt -> """
            {"primaryTask": "something", "taskSegments": [], "metrics": {}}
            """);
        WikiFactBuilder.WikiFacts facts = new WikiFactBuilder.WikiFacts(
                new WikiPeriod(WikiLevel.HOUR, t1, t2, tz.getId()),
                3600, 0, 5, List.of(), List.of(), List.of());

        assertThrows(RuntimeException.class, () ->
                summarizer.summarize(facts, Duration.ofSeconds(5)));
    }

    @Test
    void shouldPreserveLocalMetrics() {
        WikiSummarizer summarizer = new WikiSummarizer(prompt -> SAMPLE_VALID_JSON);
        WikiFactBuilder.WikiFacts facts = new WikiFactBuilder.WikiFacts(
                new WikiPeriod(WikiLevel.DAY, t1, t2, tz.getId()),
                7200, 300, 25,
                List.of(new WikiEntry.AppDuration("VS Code", 5000)),
                List.of(), List.of());

        WikiSummarizer.SummaryResult result = summarizer.summarize(facts, Duration.ofSeconds(30));
        assertEquals(7200, result.metrics().activeSeconds());
        assertEquals(300, result.metrics().afkSeconds());
        assertEquals(25, result.metrics().switchCount());
    }
}
