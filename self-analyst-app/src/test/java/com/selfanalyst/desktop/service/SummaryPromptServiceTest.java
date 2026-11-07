package com.selfanalyst.desktop.service;

import com.selfanalyst.i18n.Lang;
import com.selfanalyst.wiki.WikiTitleSampler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SummaryPromptServiceTest {

    @Test
    void adviceUsesOnePolicyAndKeepsLocalEvidenceUnfiltered() {
        var runtime = new com.selfanalyst.config.PrivacyRuntime("Secret.exe", "", "private.test");
        AtomicInteger reads = new AtomicInteger();
        var service = new SummaryPromptService(() -> { reads.incrementAndGet(); return runtime.modelPolicy().get(); });
        var advice = new BehaviorAdviceService.BehaviorAdvice("suggestion", "7 days", "now", "Take a break",
                "Entertainment averaged 60 minutes", List.of("Secret.exe 40 minutes", "Editor 20 minutes", "private.test article"),
                new BehaviorAdviceService.BehaviorAdvice.Basis("7 days", "increasing", "break", "complete"), "medium", null);
        var result = service.enhanceAdvice(advice, (prompt, timeout) -> {
            assertFalse(prompt.contains("Secret.exe"));
            assertFalse(prompt.contains("private.test"));
            assertTrue(prompt.contains("Editor 20 minutes"));
            assertTrue(prompt.contains("60 minutes"));
            runtime.apply("Editor", "", "");
            return "{\"title\":\"Rest\",\"body\":\"Take a short break\",\"confidence\":\"medium\"}";
        }, Lang.english());
        assertEquals(1, reads.get());
        assertEquals(advice.evidenceTags(), result.evidenceTags());
        assertEquals(advice.basis(), result.basis());
        assertEquals(advice.scopeLabel(), result.scopeLabel());
        service.enhanceAdvice(advice, (prompt, timeout) -> {
            assertTrue(prompt.contains("Secret.exe"));
            assertFalse(prompt.contains("Editor 20 minutes"));
            return "{}";
        }, Lang.english());
        assertEquals(2, reads.get());
        assertEquals(List.of("Secret.exe 40 minutes", "Editor 20 minutes", "private.test article"), advice.evidenceTags());
    }

    @Test
    void enhancementUsesOneSixtySecondCallAndPreservesLocalStatistics() {
        SummaryPromptService service = new SummaryPromptService();
        SummaryService.LocalFacts facts = facts("连接超时参数调试", false);
        AtomicInteger calls = new AtomicInteger();
        var summary = service.enhance(facts, (prompt, timeout) -> {
            calls.incrementAndGet();
            assertEquals(Duration.ofSeconds(60), timeout);
            assertTrue(prompt.contains("连接超时参数调试"));
            assertTrue(prompt.contains("evidenceFactIds"));
            assertTrue(prompt.length() <= SummaryPromptService.MAX_PROMPT_CHARS);
            return validResponse();
        });

        assertEquals(1, calls.get());
        assertEquals("查看连接超时参数", summary.headline());
        assertEquals("high", summary.confidence());
        assertEquals(facts.activeTime(), summary.activeTime());
        assertEquals(facts.afkTime(), summary.afkTime());
        assertEquals(facts.switchCount(), summary.switchCount());
        assertEquals(facts.evidence(), summary.evidence());
        assertEquals(List.of("f1"), summary.taskSegments().getFirst().evidenceFactIds());
        assertTrue(summary.taskSegments().getFirst().evidence().getFirst().contains("连接超时参数调试"));
        assertFalse(summary.taskSegments().getFirst().evidence().getFirst().contains("模型编造的证据"));
    }

    @Test
    void invalidOrUnsupportedModelOutputFallsBackWithoutRetry() {
        var facts = facts("连接超时参数调试", false);
        for (String invalid : List.of(
                validResponse().replace("\"f1\"", "\"missing\""),
                validResponse().replace("查看连接超时参数", "已经解决连接问题"),
                validResponse().replace("围绕连接配置查阅相关资料", "窗口切换 12 次"),
                validResponse().replace("\"confidence\":\"high\"", "\"confidence\":3"),
                "{\"headline\":\"模型自由发挥\",\"taskSegments\":{}}")) {
            AtomicInteger calls = new AtomicInteger();
            var summary = new SummaryPromptService().enhance(facts, (prompt, timeout) -> {
                calls.incrementAndGet();
                return invalid;
            });
            assertEquals(1, calls.get());
            assertEquals(facts.headline(), summary.headline());
            assertTrue(summary.taskSegments().isEmpty());
        }
    }

    @Test
    void applicationsAndEvidenceComeFromReferencesEvenWhenModelFieldsAreWrongOrAbsent() {
        var facts = facts("连接超时参数调试", false);
        for (String output : List.of(validResponse().replace("\"Chrome\"", "\"WrongAlias\""),
                validResponse().replace("\"apps\":[\"Chrome\"],", ""),
                validResponse().replace("\"apps\":[\"Chrome\"]", "\"apps\":null"))) {
            var result = new SummaryPromptService().enhance(facts, (prompt, timeout) -> output);
            assertEquals(List.of("Chrome"), result.taskSegments().getFirst().apps());
            assertTrue(result.taskSegments().getFirst().evidence().getFirst().contains("连接超时参数调试"));
        }
    }

    @Test
    void timeoutAndMissingFactsUseLocalFallback() {
        var facts = facts("连接超时参数调试", false);
        AtomicInteger calls = new AtomicInteger();
        var service = new SummaryPromptService();
        var summary = service.enhance(facts, (prompt, timeout) -> {
            calls.incrementAndGet();
            assertEquals(Duration.ofSeconds(60), timeout);
            throw new IllegalStateException("timeout");
        });
        assertEquals(1, calls.get());
        assertEquals(facts.headline(), summary.headline());
        assertEquals(SummaryPromptService.noInsightText(Lang.chinese()), summary.insight());
        var empty = new SummaryService.LocalFacts("本地", List.of(), List.of(), "0秒", "0秒", 0, "");
        assertEquals("本地", service.enhance(empty, (prompt, timeout) -> {
            fail("No title evidence must not trigger a paid call");
            return null;
        }).headline());
    }

    @Test
    void observationOnlyEvidenceCapsOverallConfidence() {
        var summary = new SummaryPromptService().enhance(facts("连接超时参数调试", true),
                (prompt, timeout) -> validResponse());
        assertEquals("low", summary.confidence());
        assertEquals("low", summary.taskSegments().getFirst().confidence());
    }

    @Test
    void oversizedCompleteRequestFallsBackBeforeCallingModel() {
        var normal = facts("连接超时参数调试", false);
        var oversized = new SummaryService.LocalFacts(normal.headline(), normal.evidence(), normal.topApps(),
                normal.activeTime(), normal.afkTime(), normal.switchCount(), normal.goalContext(), 0, "complete",
                new WikiTitleSampler.Selection(normal.titleFacts().facts(), "x".repeat(8000), Map.of()));
        var result = new SummaryPromptService().enhance(oversized, (prompt, timeout) -> {
            fail("Full request limit must be applied before a model call");
            return null;
        });
        assertEquals(oversized.headline(), result.headline());
    }

    @Test
    void exactAppDurationsRemainLocalCacheMetadata() {
        var facts = SummaryFactFingerprintTest.withAppSeconds(Map.of("Chrome", 185.123), List.of("Chrome 3分钟"));
        String prompt = SummaryPromptService.buildPrompt(facts, Lang.english());
        assertFalse(prompt.contains("appSeconds"));
        assertFalse(prompt.contains("185.123"));
        assertTrue(prompt.contains("连接超时参数调试"));
    }

    static SummaryService.LocalFacts facts(String title, boolean observationOnly) {
        var fact = new WikiTitleSampler.Fact("f1", "content", "Chrome", title, "article",
                observationOnly ? null : 3600.0, 1, List.of(), 0);
        var selection = new WikiTitleSampler.Selection(List.of(fact),
                "{\"id\":\"f1\",\"app\":\"Chrome\",\"title\":\"" + title + "\"}\n", Map.of("selectedFacts", 1));
        return new SummaryService.LocalFacts("主要在 Chrome", List.of("主要应用: Chrome 1小时"),
                List.of("Chrome 1小时"), "1小时", "0秒", 12, "目标: 编码", 0, "complete", selection);
    }

    static String validResponse() {
        return """
                {"headline":"查看连接超时参数","insight":"围绕连接配置查阅相关资料","suggestion":null,"confidence":"high",
                 "taskSegments":[{"title":"连接参数","summary":"查看连接配置文档","evidence":["模型编造的证据"],
                  "apps":["Chrome"],"confidence":"high","evidenceFactIds":["f1"],"claimType":"observed"}]}
                """;
    }
}
