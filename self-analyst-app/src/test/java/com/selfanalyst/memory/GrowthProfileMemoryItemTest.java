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
    void contextSummaryExcludesLegacySensitiveActiveMemories() {
        GrowthProfile profile = new GrowthProfile();
        profile.getMemories().add(item("safe", "preference", "用户偏好使用中文交流。", "active"));
        profile.getMemories().add(item("secret", "fact", "client_secret=legacysecret", "active"));

        String summary = profile.buildContextSummary();

        assertTrue(summary.contains("用户偏好使用中文交流。"));
        assertFalse(summary.contains("legacysecret"));
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

    @Test
    void contextSummarySortsActiveMemoriesByConfidenceAndLimitsToThirty() {
        GrowthProfile profile = new GrowthProfile();
        profile.getMemories().add(item("lower-included", "pattern", "较低置信但应保留的长期记忆", "active", 8));
        profile.getMemories().add(item("highest", "preference", "高置信长期记忆", "active", 10));
        for (int i = 1; i <= 28; i++) {
            profile.getMemories().add(item("filler-" + i, "fact", "填充长期记忆 " + i, "active", 7));
        }
        profile.getMemories().add(item("excluded-31", "fact", "第 31 条应被排除的长期记忆", "active", 1));

        String summary = profile.buildContextSummary();

        assertTrue(summary.contains("## 长期记忆"));
        int highestIndex = summary.indexOf("高置信长期记忆");
        int lowerIndex = summary.indexOf("较低置信但应保留的长期记忆");
        assertTrue(highestIndex >= 0);
        assertTrue(lowerIndex >= 0);
        assertTrue(highestIndex < lowerIndex);
        assertFalse(summary.contains("第 31 条应被排除的长期记忆"));
    }

    private static GrowthProfile.MemoryItem item(String id, String type, String content, String status) {
        return item(id, type, content, status, 9);
    }

    private static GrowthProfile.MemoryItem item(String id, String type, String content, String status, int confidence) {
        return new GrowthProfile.MemoryItem(
                id, type, content, "test evidence", confidence, status, false, "auto",
                "chat_auto", "session-1", List.of("msg-1"), Instant.parse("2026-07-04T00:00:00Z"),
                Instant.parse("2026-07-04T00:00:00Z"));
    }
}
