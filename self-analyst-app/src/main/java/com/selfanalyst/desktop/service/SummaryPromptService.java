package com.selfanalyst.desktop.service;

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
        if (client == null) {
            return fromLocalOnly(facts);
        }

        try {
            String prompt = buildPrompt(facts);
            String response = client.complete(prompt, Duration.ofSeconds(5));
            if (response == null || response.isBlank()) {
                return fromLocalOnly(facts);
            }
            return parseEnhanced(facts, response);
        } catch (Exception e) {
            // LLM unavailable – fallback to local-only
            return fromLocalOnly(facts);
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
        if (client == null || advice == null || "empty".equals(advice.type())) {
            return advice;
        }

        try {
            String prompt = buildAdvicePrompt(advice);
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

    private static EnhancedSummary fromLocalOnly(SummaryService.LocalFacts facts) {
        return new EnhancedSummary(
                facts.headline(),
                "暂无 AI 洞察（LLM 未配置）",
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

    private static String buildPrompt(SummaryService.LocalFacts facts) {
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
                facts.topApps() != null ? String.join(", ", facts.topApps()) : "无",
                facts.activeTime(),
                facts.afkTime(),
                facts.switchCount(),
                facts.goalContext() != null ? facts.goalContext() : "无"
        );
    }

    private static EnhancedSummary parseEnhanced(SummaryService.LocalFacts facts, String response) {
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
            return fromLocalOnly(facts);
        }
    }

    private static String stringOr(Object val, String fallback) {
        return val != null ? val.toString() : fallback;
    }

    private static String buildAdvicePrompt(BehaviorAdviceService.BehaviorAdvice advice) {
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
