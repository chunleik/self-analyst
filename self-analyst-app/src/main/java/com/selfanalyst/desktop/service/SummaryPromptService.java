package com.selfanalyst.desktop.service;

import com.selfanalyst.i18n.Lang;

import java.time.Duration;
import java.util.*;

/**
 * Enriches {@link SummaryService.LocalFacts} with LLM when available.
 * Falls back to raw local facts when the agent is null or LLM call fails.
 */
public class SummaryPromptService {

    @FunctionalInterface
    public interface SummaryTextClient {
        String complete(String prompt, Duration timeout);
    }

    /**
     * Enhance local facts using the LLM agent.
     * <p>
     * If {@code client} is null, returns the local facts as-is with
     * {@code confidence = "low"} and no fabricated evidence.
     *
     * @param facts  raw aggregations from {@link SummaryService}
     * @param client nullable – stateless text completion client for summary enhancement
     * @return enriched summary
     */
    public EnhancedSummary enhance(SummaryService.LocalFacts facts, SummaryTextClient client) {
        return enhance(facts, client, Lang.ZH);
    }

    /** Language-aware overload (SPEC-I18N-PROMPT-001): prompt + fallback follow {@code lang}. */
    public EnhancedSummary enhance(SummaryService.LocalFacts facts, SummaryTextClient client, Lang lang) {
        if (client == null) {
            return fromLocalOnly(facts, lang);
        }

        try {
            String prompt = buildPrompt(facts, lang);
            String response = client.complete(prompt, Duration.ofSeconds(5));
            if (response == null || response.isBlank()) {
                return fromLocalOnly(facts, lang);
            }
            return parseEnhanced(facts, response, lang);
        } catch (Exception e) {
            // LLM unavailable – fallback to local-only
            return fromLocalOnly(facts, lang);
        }
    }

    public EnhancedSummary localOnly(SummaryService.LocalFacts facts) {
        return new EnhancedSummary(
                facts.headline(), "", "", "low",
                facts.evidence() != null ? facts.evidence() : List.of(),
                facts.topApps() != null ? facts.topApps() : List.of(),
                facts.activeTime(), facts.afkTime(), facts.switchCount(),
                facts.goalContext());
    }

    /**
     * Enrich behavior advice wording with LLM while preserving local facts.
     * Falls back to the original advice when LLM is unavailable.
     *
     * @param advice local rule-generated advice (never null, never type="empty")
     * @param client nullable – text completion client
     * @return enriched advice (or original if LLM unavailable)
     */
    public BehaviorAdviceService.BehaviorAdvice enhanceAdvice(
            BehaviorAdviceService.BehaviorAdvice advice,
            SummaryTextClient client) {
        return enhanceAdvice(advice, client, Lang.ZH);
    }

    /** Language-aware overload (SPEC-I18N-PROMPT-001): advice prompt follows {@code lang}. */
    public BehaviorAdviceService.BehaviorAdvice enhanceAdvice(
            BehaviorAdviceService.BehaviorAdvice advice,
            SummaryTextClient client, Lang lang) {
        if (client == null || advice == null || "empty".equals(advice.type())) {
            return advice;
        }

        try {
            String prompt = buildAdvicePrompt(advice, lang);
            String response = client.complete(prompt, Duration.ofSeconds(5));
            if (response == null || response.isBlank()) {
                return advice;
            }
            return parseAdviceResponse(advice, response);
        } catch (Exception e) {
            return advice;
        }
    }

    // ── Internal ─────────────────────────────────────────────────

    /** Fallback insight shown when LLM is unavailable, localized (SPEC-I18N-PROMPT-005). */
    static final String NO_INSIGHT_ZH = "暂无 AI 洞察（LLM 未配置）";
    static final String NO_INSIGHT_EN = "No AI insight (LLM not configured)";

    /** Localized "no insight" fallback text; the controller uses this to detect LLM failure. */
    public static String noInsightText(Lang lang) {
        return lang == Lang.EN ? NO_INSIGHT_EN : NO_INSIGHT_ZH;
    }

    private static EnhancedSummary fromLocalOnly(SummaryService.LocalFacts facts, Lang lang) {
        return new EnhancedSummary(
                facts.headline(),
                noInsightText(lang),
                null,
                "low",
                facts.evidence(),
                facts.topApps(),
                facts.activeTime(),
                facts.afkTime(),
                facts.switchCount(),
                facts.goalContext()
        );
    }

    static String buildPrompt(SummaryService.LocalFacts facts, Lang lang) {
        String topApps = facts.topApps() != null ? String.join(", ", facts.topApps()) : null;
        if (lang == Lang.EN) {
            return """
                    You are SelfAnalyst. Based on the following activity data, generate a short summary (1-2 sentences).
                    Output JSON only, nothing else:
                    {
                      "headline": "summarize the current activity in one English sentence",
                      "insight": "one-sentence insight or pattern finding",
                      "suggestion": "a specific actionable suggestion (optional, null if none)",
                      "confidence": "high or medium or low"
                    }

                    Activity data:
                    - Top apps: %s
                    - Active time: %s
                    - Idle time: %s
                    - Window switches: %d
                    - User goal: %s
                    """.formatted(
                    topApps != null ? topApps : "none",
                    facts.activeTime(),
                    facts.afkTime(),
                    facts.switchCount(),
                    facts.goalContext() != null ? facts.goalContext() : "none"
            );
        }
        return """
                你是 SelfAnalyst，请基于以下活动数据生成一条简短摘要（1-2 句）。
                输出格式为 JSON，不要输出其他内容：
                {
                  "headline": "用中文概括当前活动，1 句话",
                  "insight": "一句话洞见或模式发现",
                  "suggestion": "具体可执行的建议（可选，无建议则为 null）",
                  "confidence": "high 或 medium 或 low"
                }

                活动数据：
                - 主要应用: %s
                - 活跃时间: %s
                - 非活跃时间: %s
                - 窗口切换: %d 次
                - 用户目标: %s
                """.formatted(
                topApps != null ? topApps : "无",
                facts.activeTime(),
                facts.afkTime(),
                facts.switchCount(),
                facts.goalContext() != null ? facts.goalContext() : "无"
        );
    }

    private static EnhancedSummary parseEnhanced(SummaryService.LocalFacts facts, String response, Lang lang) {
        try {
            // Best-effort JSON extraction from LLM output
            String json = response;
            int braceStart = json.indexOf('{');
            int braceEnd = json.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = json.substring(braceStart, braceEnd + 1);
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = mapper.readValue(json, Map.class);

            String headline = stringOr(map.get("headline"), facts.headline());
            String insight = stringOr(map.get("insight"), null);
            String suggestion = stringOr(map.get("suggestion"), null);
            String confidence = stringOr(map.get("confidence"), "low");

            return new EnhancedSummary(headline, insight, suggestion, confidence,
                    facts.evidence(), facts.topApps(),
                    facts.activeTime(), facts.afkTime(),
                    facts.switchCount(), facts.goalContext());
        } catch (Exception e) {
            return fromLocalOnly(facts, lang);
        }
    }

    private static String stringOr(Object val, String fallback) {
        return val != null ? val.toString() : fallback;
    }

    static String buildAdvicePrompt(BehaviorAdviceService.BehaviorAdvice advice, Lang lang) {
        if (lang == Lang.EN) {
            return """
                    You are SelfAnalyst. Based on the following behavior analysis, refine the advice wording to be more natural and empathetic.
                    Output JSON only, nothing else:
                    {
                      "title": "refined main conclusion (one sentence, no more than 80 characters)",
                      "body": "refined explanation (no more than 240 characters)",
                      "confidence": "high or medium or low"
                    }

                    Current advice:
                    - Type: %s
                    - Main conclusion: %s
                    - Explanation: %s
                    - Evidence: %s
                    - Trend: %s
                    """.formatted(
                    advice.type(),
                    advice.title(),
                    advice.body(),
                    advice.evidenceTags() != null ? String.join(", ", advice.evidenceTags()) : "none",
                    advice.basis() != null ? advice.basis().trend() : "unknown"
            );
        }
        return """
                你是 SelfAnalyst，请基于以下行为分析结果优化建议措辞，使其更自然、共情。
                输出格式为 JSON，不要输出其他内容：
                {
                  "title": "优化后的主结论（1句话，不超过80字）",
                  "body": "优化后的解释正文（不超过240字）",
                  "confidence": "high 或 medium 或 low"
                }

                当前建议：
                - 类型: %s
                - 主结论: %s
                - 解释: %s
                - 证据: %s
                - 趋势: %s
                """.formatted(
                advice.type(),
                advice.title(),
                advice.body(),
                advice.evidenceTags() != null ? String.join(", ", advice.evidenceTags()) : "无",
                advice.basis() != null ? advice.basis().trend() : "未知"
        );
    }

    private static BehaviorAdviceService.BehaviorAdvice parseAdviceResponse(
            BehaviorAdviceService.BehaviorAdvice original, String response) {
        try {
            String json = response;
            int braceStart = json.indexOf('{');
            int braceEnd = json.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = json.substring(braceStart, braceEnd + 1);
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = mapper.readValue(json, Map.class);

            String title = stringOr(map.get("title"), original.title());
            String body = stringOr(map.get("body"), original.body());
            String confidence = stringOr(map.get("confidence"), original.confidence());

            return new BehaviorAdviceService.BehaviorAdvice(
                    original.type(),
                    original.scopeLabel(),
                    original.generatedAt(),
                    title,
                    body,
                    original.evidenceTags(),
                    original.basis(),
                    confidence,
                    original.emptyReason()
            );
        } catch (Exception e) {
            return original;
        }
    }

    // ── Output model ─────────────────────────────────────────────

    public record EnhancedSummary(
            String headline,
            String insight,
            String suggestion,
            String confidence,
            List<String> evidence,
            List<String> topApps,
            String activeTime,
            String afkTime,
            int switchCount,
            String goalContext) {
    }
}
