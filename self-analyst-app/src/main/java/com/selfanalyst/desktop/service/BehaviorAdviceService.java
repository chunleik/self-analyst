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
    private final com.selfanalyst.i18n.Lang lang;
    public BehaviorAdviceService() { this(com.selfanalyst.i18n.Lang.chinese()); }
    public BehaviorAdviceService(com.selfanalyst.i18n.Lang lang) { this.lang = lang; }
    private String message(String key, Object... args) { return String.format(lang.locale(), com.selfanalyst.i18n.Messages.text(lang, key), args); }


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
            return emptyAdvice(message("advice.insufficient"));
        }

        Instant now = Instant.now();
        String generatedAt = ISO.format(now);
        String scopeLabel = message("advice.scope", data.totalDays());

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
                message("advice.encouragement.title"),
                message("advice.encouragement.body", data.totalDays(), data.baselineDailyEntertainmentMin(), data.recentDailyEntertainmentMin()),
                buildEvidenceTags(data),
                new BehaviorAdvice.Basis(scopeLabel, message("advice.improving"), message("advice.maintain"), completeness(data)),
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
                message("advice.suggestion.title"),
                message("advice.suggestion.body", data.recentDailySwitches(), diff),
                buildEvidenceTags(data),
                new BehaviorAdvice.Basis(scopeLabel, message("advice.rising"), message("advice.adjust"), completeness(data)),
                confidence(data),
                null
        );
    }

    private BehaviorAdvice reminder(SummaryService.BehaviorData data, String generatedAt, String scopeLabel) {
        return new BehaviorAdvice(
                "reminder",
                scopeLabel,
                generatedAt,
                message("advice.reminder.title"),
                message("advice.reminder.body", data.recentDailyEveningMin()),
                buildEvidenceTags(data),
                new BehaviorAdvice.Basis(scopeLabel, message("advice.elevated"), message("advice.adjust"), completeness(data)),
                confidence(data),
                null
        );
    }

    private BehaviorAdvice mildSuggestion(SummaryService.BehaviorData data, String generatedAt, String scopeLabel) {
        return new BehaviorAdvice(
                "suggestion",
                scopeLabel,
                generatedAt,
                message("advice.mildSuggestion.title"),
                message("advice.mildSuggestion.body", data.totalDays(), data.recentDailyEntertainmentMin()),
                buildEvidenceTags(data),
                new BehaviorAdvice.Basis(scopeLabel, message("advice.stable"), message("advice.optimize"), completeness(data)),
                confidence(data),
                null
        );
    }

    private BehaviorAdvice emptyAdvice(String reason) {
        Instant now = Instant.now();
        return new BehaviorAdvice(
                "empty",
                message("advice.dataInsufficient"),
                ISO.format(now),
                message("advice.empty"),
                reason,
                List.of(),
                new BehaviorAdvice.Basis(message("advice.dataInsufficient"), "—", "—", message("advice.low")),
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
            tags.add(message("advice.entDown"));
        } else if (entChange > ENT_INCREASE_THRESHOLD) {
            tags.add(message("advice.entUp"));
        }
        if (data.recentDailyEveningMin() > EVENING_TAG_THRESHOLD) {
            tags.add(message("advice.eveningHigh"));
        }
        int switchDiff = data.recentDailySwitches() - data.baselineDailySwitches();
        if (Math.abs(switchDiff) > SWITCH_DIFF_TAG_THRESHOLD) {
            tags.add(switchDiff > 0 ? message("advice.switchUp") : message("advice.switchDown"));
        }
        if (!data.topEntertainmentApps().isEmpty()) {
            tags.add(message("advice.entApps"));
        }
        if (tags.isEmpty()) {
            tags.add(message("advice.patternStable"));
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
        if (data.totalDays() >= 7) return message("advice.high");
        if (data.totalDays() >= 5) return message("advice.medium");
        return message("advice.low");
    }
}
