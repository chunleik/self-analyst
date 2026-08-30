package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WikiSummarizerTest {

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
