package com.selfanalyst.desktop.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryFactFingerprintTest {

    @Test
    void sameFactsAreStableAndAppOrderDoesNotMatter() {
        SummaryService.LocalFacts a = facts(List.of("Chrome 1小时", "IDEA 20分钟"), "1小时", 12);
        SummaryService.LocalFacts b = facts(List.of("IDEA 20分钟", "Chrome 1小时"), "1小时", 12);
        assertEquals(SummaryFactFingerprint.of(a, a), SummaryFactFingerprint.of(b, b));
    }

    @Test
    void smallDurationAndSwitchChangesStayInSameBucket() {
        SummaryService.LocalFacts shortActive = facts(List.of("Chrome"), "3分钟", 12);
        SummaryService.LocalFacts nearbyActive = facts(List.of("Chrome"), "4分钟", 18);
        assertEquals(
                SummaryFactFingerprint.of(shortActive, shortActive),
                SummaryFactFingerprint.of(nearbyActive, nearbyActive));

        SummaryService.LocalFacts jumped = facts(List.of("Chrome"), "12分钟", 40);
        assertNotEquals(
                SummaryFactFingerprint.of(shortActive, shortActive),
                SummaryFactFingerprint.of(jumped, jumped));
    }

    @Test
    void freshnessUsesFifteenMinutes() {
        Instant assembled = Instant.parse("2026-09-06T04:00:00Z");
        assertTrue(SummaryFactFingerprint.isFresh(assembled.toString(), assembled.plusSeconds(60)));
        assertFalse(SummaryFactFingerprint.isFresh(assembled.toString(), assembled.plusSeconds(16 * 60)));
        assertTrue(SummaryFactFingerprint.needsRefresh("old", assembled.toString(), "new", assembled));
        assertFalse(SummaryFactFingerprint.needsRefresh(
                "same", assembled.toString(), "same", assembled.plusSeconds(60)));
    }

    @Test
    void titleThemeAndObservationStrengthInvalidateOtherwiseIdenticalFacts() {
        var first = SummaryPromptServiceTest.facts("数据库索引分析", false);
        var changed = SummaryPromptServiceTest.facts("发布流程文档", false);
        assertEquals(first.topApps(), changed.topApps());
        assertNotEquals(SummaryFactFingerprint.of(first, first), SummaryFactFingerprint.of(changed, changed));
        var observation = SummaryPromptServiceTest.facts("数据库索引分析", true);
        assertNotEquals(SummaryFactFingerprint.of(first, first), SummaryFactFingerprint.of(observation, observation));
    }

    @Test
    void movingIntervalsAndTemporaryIdsDoNotInvalidateUnchangedTopics() {
        var first = SummaryPromptServiceTest.facts("数据库索引分析", false);
        var fact = first.titleFacts().facts().getFirst();
        var shifted = new com.selfanalyst.wiki.WikiTitleSampler.Fact("f9", fact.source(), fact.app(), fact.title(),
                fact.kind(), 3601.0, 2, List.of(new com.selfanalyst.wiki.WikiTitleSampler.Interval(
                        "2026-09-22T00:00:01Z", "2026-09-22T00:10:01Z", true, List.of(12L), 0)), 0);
        var next = new SummaryService.LocalFacts(first.headline(), first.evidence(), first.topApps(),
                first.activeTime(), first.afkTime(), first.switchCount(), first.goalContext(), 0, "complete",
                new com.selfanalyst.wiki.WikiTitleSampler.Selection(List.of(shifted), "new intervals",
                        first.titleFacts().coverage()));
        assertEquals(SummaryFactFingerprint.of(first, first), SummaryFactFingerprint.of(next, next));
    }

    @Test
    void appDisplayDurationChangesWithinBucketsDoNotInvalidateTopics() {
        var first = withAppSeconds(Map.of("Chrome", 185.0, "Editor", 120.0),
                List.of("Chrome 3分5秒", "Editor 2分钟"));
        var nearby = withAppSeconds(Map.of("Editor", 127.0, "Chrome", 187.0),
                List.of("Chrome 3分7秒", "Editor 2分7秒"));
        assertEquals(SummaryFactFingerprint.of(first, first), SummaryFactFingerprint.of(nearby, nearby));
    }

    @Test
    void appDurationBucketAndRankingChangesInvalidateTopics() {
        var beforeThreshold = withAppSeconds(Map.of("Chrome", 299.0, "Editor", 120.0), List.of("Chrome", "Editor"));
        var afterThreshold = withAppSeconds(Map.of("Chrome", 300.0, "Editor", 120.0), List.of("Chrome", "Editor"));
        assertNotEquals(SummaryFactFingerprint.of(beforeThreshold, beforeThreshold),
                SummaryFactFingerprint.of(afterThreshold, afterThreshold));
        var chromeLeads = withAppSeconds(Map.of("Chrome", 230.0, "Editor", 220.0), List.of("Chrome", "Editor"));
        var editorLeads = withAppSeconds(Map.of("Chrome", 230.0, "Editor", 240.0), List.of("Editor", "Chrome"));
        assertNotEquals(SummaryFactFingerprint.of(chromeLeads, chromeLeads),
                SummaryFactFingerprint.of(editorLeads, editorLeads));
    }

    static SummaryService.LocalFacts withAppSeconds(Map<String, Double> appSeconds, List<String> displayedApps) {
        var original = SummaryPromptServiceTest.facts("连接超时参数调试", false);
        var coverage = new java.util.LinkedHashMap<>(original.titleFacts().coverage());
        coverage.put("appSeconds", appSeconds);
        return new SummaryService.LocalFacts(original.headline(), original.evidence(), displayedApps,
                original.activeTime(), original.afkTime(), original.switchCount(), original.goalContext(),
                original.unknownActivitySeconds(), original.coverage(),
                new com.selfanalyst.wiki.WikiTitleSampler.Selection(original.titleFacts().facts(),
                        original.titleFacts().jsonLines(), coverage));
    }

    private static SummaryService.LocalFacts facts(List<String> apps, String active, int switches) {
        return new SummaryService.LocalFacts("headline", List.of(), apps, active, "0秒", switches, "");
    }
}
