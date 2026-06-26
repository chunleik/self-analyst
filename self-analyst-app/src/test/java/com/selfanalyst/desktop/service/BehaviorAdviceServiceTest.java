package com.selfanalyst.desktop.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BehaviorAdviceServiceTest {

    private final BehaviorAdviceService service = new BehaviorAdviceService();

    @Test
    void returnsEmptyWhenInsufficientData() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                2, 30, 40, 15, 20, 100, 80, List.of());

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertEquals("empty", advice.type());
        assertNotNull(advice.emptyReason());
        assertTrue(advice.emptyReason().contains("不足"));
    }

    @Test
    void returnsEmptyWhenNullData() {
        BehaviorAdviceService.BehaviorAdvice advice = service.generate(null);

        assertEquals("empty", advice.type());
    }

    @Test
    void returnsEncouragementWhenEntertainmentDecreasing() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                7, 40, 60, 20, 30, 100, 90, List.of("bilibili 2小时"));

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertEquals("encouragement", advice.type());
        assertNotNull(advice.title());
        assertFalse(advice.title().isBlank());
        assertNotNull(advice.body());
        assertNotNull(advice.evidenceTags());
        assertFalse(advice.evidenceTags().isEmpty());
    }

    @Test
    void returnsSuggestionWhenSwitchesIncreasing() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                7, 30, 30, 10, 10, 150, 100, List.of());

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertEquals("suggestion", advice.type());
        assertNotNull(advice.title());
        assertNotNull(advice.body());
        assertNotNull(advice.basis());
    }

    @Test
    void returnsReminderWhenEveningEntertainmentHigh() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                7, 50, 30, 100, 40, 80, 70, List.of("netflix 3小时"));

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertEquals("reminder", advice.type());
        assertTrue(advice.title().contains("晚间") || advice.body().contains("22:00"));
    }

    @Test
    void returnsValidBasisOnAllNonEmptyAdvice() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                7, 40, 60, 20, 30, 100, 90, List.of("bilibili 2小时"));

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertNotNull(advice.basis());
        assertNotNull(advice.basis().observationRange());
        assertNotNull(advice.basis().trend());
        assertNotNull(advice.basis().adviceKind());
        assertNotNull(advice.basis().dataCompleteness());
    }

    @Test
    void evidenceTagsNeverExceedFive() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                7, 10, 50, 5, 30, 200, 80,
                List.of("bilibili 2小时", "douyin 1小时", "netflix 1小时"));

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertNotNull(advice.evidenceTags());
        assertTrue(advice.evidenceTags().size() <= 5);
    }

    @Test
    void emptyAdviceHasEmptyReason() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                0, 0, 0, 0, 0, 0, 0, List.of());

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertEquals("empty", advice.type());
        assertNotNull(advice.emptyReason());
    }

    @Test
    void returnsAdviceEvenWithoutEntertainmentApps() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                7, 0, 0, 0, 0, 60, 55, List.of());

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertNotNull(advice.type());
        assertFalse(advice.title().isBlank());
    }

    @Test
    void completenessReturnsHighForSevenDays() {
        SummaryService.BehaviorData data = new SummaryService.BehaviorData(
                7, 30, 40, 10, 20, 100, 80, List.of());

        BehaviorAdviceService.BehaviorAdvice advice = service.generate(data);

        assertEquals("高", advice.basis().dataCompleteness());
    }
}
