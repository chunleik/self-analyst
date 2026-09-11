package com.selfanalyst.desktop.service;

import com.selfanalyst.i18n.Lang;
import com.selfanalyst.i18n.Messages;

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
        return enhance(facts, client, Lang.chinese());
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
        return enhanceAdvice(advice, client, Lang.chinese());
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
        return Messages.text(lang, "summary.noInsight");
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
        String topApps = facts.topApps() != null ? String.join(", ", facts.topApps()) : Messages.text(lang, "common.none");
        return Messages.text(lang, "summary.prompt").formatted(topApps, facts.activeTime(), facts.afkTime(), facts.switchCount(), facts.goalContext() != null ? facts.goalContext() : Messages.text(lang, "common.none"));
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
        return Messages.text(lang, "advice.prompt").formatted(advice.type(), advice.title(), advice.body(), advice.evidenceTags() != null ? String.join(", ", advice.evidenceTags()) : Messages.text(lang, "common.none"), advice.basis() != null ? advice.basis().trend() : Messages.text(lang, "common.unknown"));
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
