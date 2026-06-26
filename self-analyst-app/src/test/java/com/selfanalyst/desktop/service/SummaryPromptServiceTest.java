package com.selfanalyst.desktop.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SummaryPromptServiceTest {

    @Test
    void enhanceUsesSuppliedSummaryClientWithoutConversationalAgent() {
        SummaryPromptService service = new SummaryPromptService();
        SummaryService.LocalFacts facts = new SummaryService.LocalFacts(
                "主要在 Chrome",
                List.of("主要应用: Chrome 1小时"),
                List.of("Chrome 1小时"),
                "1小时",
                "0秒",
                12,
                "目标: 编码");

        SummaryPromptService.EnhancedSummary summary = service.enhance(
                facts,
                (prompt, timeout) -> {
                    assertEquals(Duration.ofSeconds(5), timeout);
                    return """
                            {"headline":"专注编码","insight":"切换次数可控","suggestion":"保持当前节奏","confidence":"high"}
                            """;
                });

        assertEquals("专注编码", summary.headline());
        assertEquals("切换次数可控", summary.insight());
        assertEquals("保持当前节奏", summary.suggestion());
        assertEquals("high", summary.confidence());
        assertEquals(List.of("主要应用: Chrome 1小时"), summary.evidence());
    }
}
