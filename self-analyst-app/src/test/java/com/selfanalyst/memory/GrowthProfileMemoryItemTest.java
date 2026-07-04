package com.selfanalyst.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GrowthProfileMemoryItemTest {

    @Test
    void missingMemoriesDefaultsToEmptyList() {
        GrowthProfile profile = new GrowthProfile();
        assertNotNull(profile.getMemories());
        assertTrue(profile.getMemories().isEmpty());
    }

    @Test
    void contextSummaryIncludesOnlyActiveMemories() {
        GrowthProfile profile = new GrowthProfile();
        profile.getMemories().add(item("active-1", "preference", "用户偏好使用中文交流。", "active"));
        profile.getMemories().add(item("pending-1", "pattern", "用户可能晚上容易分心。", "pending"));
        profile.getMemories().add(item("disabled-1", "fact", "已停用的事实。", "disabled"));

        String summary = profile.buildContextSummary();

        assertTrue(summary.contains("用户偏好使用中文交流。"));
        assertFalse(summary.contains("用户可能晚上容易分心。"));
        assertFalse(summary.contains("已停用的事实。"));
    }

    @Test
    void contextSummaryKeepsLegacyGoalsAndPatterns() {
        GrowthProfile profile = new GrowthProfile();
        profile.getGoals().add(GrowthProfile.Goal.create("每天深度工作 3 小时", "deep_work_minutes", 60, 180));
        profile.getPatterns().add(new GrowthProfile.KnownPattern("上午专注度高", "连续 5 天上午编码", java.time.LocalDate.of(2026, 7, 4), 8));

        String summary = profile.buildContextSummary();

        assertTrue(summary.contains("## 活跃目标"));
        assertTrue(summary.contains("每天深度工作 3 小时"));
        assertTrue(summary.contains("## 已确认的行为模式"));
        assertTrue(summary.contains("上午专注度高"));
    }

    private static GrowthProfile.MemoryItem item(String id, String type, String content, String status) {
        return new GrowthProfile.MemoryItem(
                id, type, content, "test evidence", 9, status, false, "auto",
                "chat_auto", "session-1", List.of("msg-1"), Instant.parse("2026-07-04T00:00:00Z"),
                Instant.parse("2026-07-04T00:00:00Z"));
    }
}
