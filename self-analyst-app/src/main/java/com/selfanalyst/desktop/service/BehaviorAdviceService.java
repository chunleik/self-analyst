package com.selfanalyst.desktop.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates behavior-based advice using local rules on multi-day activity data.
 *
 * <p>Local rules implement SPEC-ADV-GEN-005:
 * <ul>
 *   <li>Entertainment time decreasing → encouragement</li>
 *   <li>Window switch count increasing significantly → suggestion</li>
 *   <li>High evening entertainment over multiple days → reminder</li>
 * </ul>
 */
public class BehaviorAdviceService {

    /**
     * Output model matching SPEC-ADV-MDL-001.
     */
    public record BehaviorAdvice(
            String type,
            String scopeLabel,
            String generatedAt,
            String title,
            String body,
            List<String> evidenceTags,
            Basis basis,
            String confidence,
            String emptyReason) {

        public record Basis(
                String observationRange,
                String trend,
                String adviceKind,
                String dataCompleteness) {
        }
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    // Rule thresholds
    private static final double ENT_DECREASE_THRESHOLD = -0.15;
    private static final double ENT_INCREASE_THRESHOLD = 0.10;
    private static final double SWITCH_INCREASE_THRESHOLD = 0.25;
    private static final double ENT_STABLE_THRESHOLD = 0.05;
    private static final double MIN_BASELINE_ENT_MIN = 10.0;
    private static final int MIN_BASELINE_SWITCHES = 50;
    private static final double HIGH_EVENING_MIN_THRESHOLD = 90.0;
    private static final double EVENING_TAG_THRESHOLD = 60.0;
    private static final double MAX_ENT_FOR_ENCOURAGEMENT = 120.0;
    private static final int SWITCH_DIFF_TAG_THRESHOLD = 20;
    private static final int MAX_EVIDENCE_TAGS = 5;

    /**
     * Generate advice from local behavior data using deterministic rules.
     * Returns type="empty" when data is insufficient.
     */
    public BehaviorAdvice generate(SummaryService.BehaviorData data) {
        if (data == null || !data.hasEnoughData()) {
            return emptyAdvice("行为数据不足（需要至少 3 天数据），继续使用一段时间后会自动生成建议。");
        }

        Instant now = Instant.now();
        String generatedAt = ISO.format(now);
        String scopeLabel = "最近 " + data.totalDays() + " 天";

        // Rule 1: Entertainment decreasing → encouragement
        double entChange = data.baselineDailyEntertainmentMin() > 0
                ? (data.recentDailyEntertainmentMin() - data.baselineDailyEntertainmentMin())
                  / data.baselineDailyEntertainmentMin()
                : 0;
        if (entChange < ENT_DECREASE_THRESHOLD && data.baselineDailyEntertainmentMin() > MIN_BASELINE_ENT_MIN) {
            return encouragement(data, generatedAt, scopeLabel);
        }

        // Rule 2: Window switches increasing significantly → suggestion
        double switchChange = data.baselineDailySwitches() > 0
                ? (double) (data.recentDailySwitches() - data.baselineDailySwitches())
                  / data.baselineDailySwitches()
                : 0;
        if (switchChange > SWITCH_INCREASE_THRESHOLD && data.baselineDailySwitches() > MIN_BASELINE_SWITCHES) {
            return suggestion(data, generatedAt, scopeLabel);
        }

        // Rule 3: High evening entertainment → reminder
        if (data.recentDailyEveningMin() > HIGH_EVENING_MIN_THRESHOLD) {
            return reminder(data, generatedAt, scopeLabel);
        }

        // Default: if entertainment is stable or slightly decreasing, give encouragement
        if (entChange <= ENT_STABLE_THRESHOLD && data.recentDailyEntertainmentMin() < MAX_ENT_FOR_ENCOURAGEMENT) {
            return encouragement(data, generatedAt, scopeLabel);
        }

        // Fallback: mild suggestion
        return mildSuggestion(data, generatedAt, scopeLabel);
    }

    private BehaviorAdvice encouragement(SummaryService.BehaviorData data, String generatedAt, String scopeLabel) {
        return new BehaviorAdvice(
                "encouragement",
                scopeLabel,
                generatedAt,
                "近几天娱乐类应用时长有所下降，继续保持当前节奏。",
                "最近 " + data.totalDays() + " 天内，娱乐类应用日均时长从 "
                + String.format("%.0f", data.baselineDailyEntertainmentMin()) + " 分钟降至 "
                + String.format("%.0f", data.recentDailyEntertainmentMin()) + " 分钟，呈改善趋势。",
                buildEvidenceTags(data),
                new BehaviorAdvice.Basis(scopeLabel, "改善", "保持策略", completeness(data)),
                confidence(data),
                null
        );
    }

    private BehaviorAdvice suggestion(SummaryService.BehaviorData data, String generatedAt, String scopeLabel) {
        int diff = data.recentDailySwitches() - data.baselineDailySwitches();
        return new BehaviorAdvice(
                "suggestion",
                scopeLabel,
                generatedAt,
                "窗口切换次数明显增加，建议尝试减少多任务切换以提升专注度。",
                "最近平均每天切换窗口 " + data.recentDailySwitches() + " 次，比前期增加约 "
                + diff + " 次，频繁的上下文切换可能降低效率。",
                buildEvidenceTags(data),
                new BehaviorAdvice.Basis(scopeLabel, "上升", "调整策略", completeness(data)),
                confidence(data),
                null
        );
    }

    private BehaviorAdvice reminder(SummaryService.BehaviorData data, String generatedAt, String scopeLabel) {
        return new BehaviorAdvice(
                "reminder",
                scopeLabel,
                generatedAt,
                "最近晚间娱乐类应用使用时间偏高，建议在 22:00 后逐步收尾。",
                "最近平均每天 22:00 后娱乐应用时长约 "
                + String.format("%.0f", data.recentDailyEveningMin()) + " 分钟，可能影响次日状态和作息规律。",
                buildEvidenceTags(data),
                new BehaviorAdvice.Basis(scopeLabel, "偏高", "调整策略", completeness(data)),
                confidence(data),
                null
        );
    }

    private BehaviorAdvice mildSuggestion(SummaryService.BehaviorData data, String generatedAt, String scopeLabel) {
        return new BehaviorAdvice(
                "suggestion",
                scopeLabel,
                generatedAt,
                "活动数据稳定，可考虑设定每日专注时段以进一步提升效率。",
                "最近 " + data.totalDays() + " 天娱乐类应用日均 "
                + String.format("%.0f", data.recentDailyEntertainmentMin()) + " 分钟，整体模式稳定。",
                buildEvidenceTags(data),
                new BehaviorAdvice.Basis(scopeLabel, "稳定", "优化策略", completeness(data)),
                confidence(data),
                null
        );
    }

    private BehaviorAdvice emptyAdvice(String reason) {
        Instant now = Instant.now();
        return new BehaviorAdvice(
                "empty",
                "数据不足",
                ISO.format(now),
                "还没有足够行为数据生成建议。继续使用一段时间后，这里会出现基于过往行为的提醒或鼓励。",
                reason,
                List.of(),
                new BehaviorAdvice.Basis("数据不足", "—", "—", "低"),
                "low",
                reason
        );
    }

    private List<String> buildEvidenceTags(SummaryService.BehaviorData data) {
        List<String> tags = new ArrayList<>();
        double entChange = data.baselineDailyEntertainmentMin() > 0
                ? (data.recentDailyEntertainmentMin() - data.baselineDailyEntertainmentMin())
                  / data.baselineDailyEntertainmentMin()
                : 0;
        if (entChange < ENT_DECREASE_THRESHOLD) {
            tags.add("娱乐时长下降");
        } else if (entChange > ENT_INCREASE_THRESHOLD) {
            tags.add("娱乐时长上升");
        }
        if (data.recentDailyEveningMin() > EVENING_TAG_THRESHOLD) {
            tags.add("晚间娱乐偏高");
        }
        int switchDiff = data.recentDailySwitches() - data.baselineDailySwitches();
        if (Math.abs(switchDiff) > SWITCH_DIFF_TAG_THRESHOLD) {
            tags.add(switchDiff > 0 ? "窗口切换增加" : "窗口切换减少");
        }
        if (!data.topEntertainmentApps().isEmpty()) {
            tags.add("常用娱乐应用");
        }
        if (tags.isEmpty()) {
            tags.add("活动模式稳定");
        }
        if (tags.size() > MAX_EVIDENCE_TAGS) {
            return tags.subList(0, MAX_EVIDENCE_TAGS);
        }
        return tags;
    }

    private String confidence(SummaryService.BehaviorData data) {
        return data.totalDays() >= 5 ? "medium" : "low";
    }

    private String completeness(SummaryService.BehaviorData data) {
        if (data.totalDays() >= 7) return "高";
        if (data.totalDays() >= 5) return "中";
        return "低";
    }
}
