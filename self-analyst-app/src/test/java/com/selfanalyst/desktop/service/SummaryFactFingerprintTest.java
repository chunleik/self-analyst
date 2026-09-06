package com.selfanalyst.desktop.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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

    private static SummaryService.LocalFacts facts(List<String> apps, String active, int switches) {
        return new SummaryService.LocalFacts("headline", List.of(), apps, active, "0秒", switches, "");
    }
}
