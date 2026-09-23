package com.selfanalyst.desktop.service;

import com.selfanalyst.i18n.Lang;
import com.selfanalyst.i18n.Messages;
import com.selfanalyst.wiki.WikiEntry;
import com.selfanalyst.wiki.WikiEvidencePolicy;

import java.time.Duration;
import java.util.*;

/**
 * Enriches {@link SummaryService.LocalFacts} with LLM when available.
 * Falls back to raw local facts when the agent is null or LLM call fails.
 */
public class SummaryPromptService {

    public static final String PROMPT_VERSION = "desktop-derived-evidence-v3";
    static final int MAX_PROMPT_CHARS = 8000;

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
        if (client == null || facts.titleFacts().facts().isEmpty()) {
            return fromLocalOnly(facts, lang);
        }

        try {
            String prompt = buildPrompt(facts, lang);
            if (prompt.length() > MAX_PROMPT_CHARS) return fromLocalOnly(facts, lang);
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
        String goal = facts.goalContext() == null ? "" : facts.goalContext();
        if (goal.length() > 512) goal = goal.substring(0, 512);
        Map<String, Object> samplingCoverage = new LinkedHashMap<>(facts.titleFacts().coverage());
        samplingCoverage.remove("appSeconds"); // Local cache metadata does not consume the title prompt budget.
        return Messages.text(lang, "summary.prompt").formatted(facts.titleFacts().jsonLines(),
                        samplingCoverage, goal)
                + Messages.text(lang, "summary.statisticsContext").formatted(
                        facts.unknownActivitySeconds(), facts.coverage());
    }

    private static EnhancedSummary parseEnhanced(SummaryService.LocalFacts facts, String response, Lang lang) {
        try {
            if (response.length() > 12000) throw new IllegalArgumentException("Summary response too large");
            String json = response.strip();
            if (json.startsWith("```json") && json.endsWith("```")) json = json.substring(7, json.length() - 3).strip();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = mapper.readValue(json, Map.class);
            String headline = narrative(map.get("headline"), "headline", 180, false);
            String insight = narrative(map.get("insight"), "insight", 600, false);
            String suggestion = narrative(map.get("suggestion"), "suggestion", 300, true);
            if (!(map.get("confidence") instanceof String confidence)
                    || !Set.of("high", "medium", "low").contains(confidence)) {
                throw new IllegalArgumentException("Invalid summary confidence");
            }
            List<WikiEvidencePolicy.EvidenceFact> evidenceFacts = WikiEvidencePolicy.facts(facts.titleFacts());
            List<WikiEntry.TaskSegment> segments = WikiEvidencePolicy.parseSegments(map.get("taskSegments"),
                    evidenceFacts, !"complete".equals(facts.coverage()));
            if (segments.isEmpty() || segments.size() > 4) throw new IllegalArgumentException("Invalid current task count");
            Map<String, String> evidenceById = new HashMap<>();
            for (var fact : evidenceFacts) {
                String title = WikiEvidencePolicy.evidenceTitle(fact);
                evidenceById.put(fact.id(), title == null ? Messages.text(lang, "summary.titleEvidenceUnavailable")
                        : Messages.text(lang, "summary.titleEvidence").formatted(title));
            }
            segments = segments.stream().map(segment -> new WikiEntry.TaskSegment(segment.title(), segment.summary(),
                    segment.evidenceFactIds().stream().map(evidenceById::get).toList(), segment.apps(),
                    segment.confidence(), segment.evidenceFactIds(), segment.claimType())).toList();
            for (WikiEntry.TaskSegment segment : segments) {
                if (confidenceRank(segment.confidence()) < confidenceRank(confidence)) confidence = segment.confidence();
            }

            return new EnhancedSummary(headline, insight, suggestion, confidence,
                    facts.evidence(), facts.topApps(),
                    facts.activeTime(), facts.afkTime(),
                    facts.switchCount(), facts.goalContext(), segments);
        } catch (Exception e) {
            return fromLocalOnly(facts, lang);
        }
    }

    private static String narrative(Object raw, String field, int maxLength, boolean optional) {
        if (raw == null && optional) return null;
        if (!(raw instanceof String value) || value.length() > maxLength || (!optional && value.isBlank())) {
            throw new IllegalArgumentException("Invalid summary " + field);
        }
        WikiEvidencePolicy.validateNarrative(field, value);
        return value;
    }

    private static int confidenceRank(String confidence) {
        return "high".equals(confidence) ? 2 : "medium".equals(confidence) ? 1 : 0;
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
            String goalContext,
            List<WikiEntry.TaskSegment> taskSegments) {
        public EnhancedSummary {
            taskSegments = taskSegments == null ? List.of() : List.copyOf(taskSegments);
        }

        public EnhancedSummary(String headline, String insight, String suggestion, String confidence,
                               List<String> evidence, List<String> topApps, String activeTime, String afkTime,
                               int switchCount, String goalContext) {
            this(headline, insight, suggestion, confidence, evidence, topApps, activeTime, afkTime,
                    switchCount, goalContext, List.of());
        }
    }
}
