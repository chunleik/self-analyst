package com.selfanalyst.desktop.service;

import com.selfanalyst.i18n.Lang;
import com.selfanalyst.wiki.WikiStore;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Assembles {@code GET /desktop/summary}: snapshot first, Wiki for closed periods,
 * live compute only for the open current windows.
 */
public class DesktopSummaryAssembler {

    public record Request(
            boolean llmAvailable,
            int maxTimelineLlm,
            Lang lang,
            SummaryPromptService.SummaryTextClient client) {
    }

    private final SummaryFactSource facts;
    private final BehaviorAdviceService adviceService;
    private final SummaryPromptService prompts;
    private final SummarySnapshotStore snapshots;
    private final SummaryWindowClassifier windows;
    private final SummaryTimelineAssembler timeline;
    private final Clock clock;

    public DesktopSummaryAssembler(SummaryFactSource facts,
                                   BehaviorAdviceService adviceService,
                                   SummaryPromptService prompts,
                                   SummarySnapshotStore snapshots,
                                   WikiStore wikiStore,
                                   Clock clock) {
        this.facts = facts;
        this.adviceService = adviceService;
        this.prompts = prompts != null ? prompts : new SummaryPromptService();
        this.snapshots = snapshots;
        this.windows = new SummaryWindowClassifier(clock);
        this.timeline = new SummaryTimelineAssembler(wikiStore, facts, this.prompts, clock.getZone());
        this.clock = clock;
    }

    public Map<String, Object> assemble(Request request) {
        Instant now = clock.instant();
        Lang lang = request.lang() != null ? request.lang() : Lang.chinese();
        Optional<SummarySnapshot> snapshot = snapshots != null ? snapshots.load(clock.getZone()) : Optional.empty();

        SummaryService.LocalFacts currentFacts = facts.currentStatus(now);
        SummaryWindowClassifier.Slot todaySlot = windows.slots(now, lang).stream()
                .filter(slot -> "today".equals(slot.key()))
                .findFirst()
                .orElseThrow();
        SummaryService.LocalFacts todayFacts = facts.currentWindowFacts(todaySlot.start(), todaySlot.end(), todaySlot.label());
        Map<String, Object> priorCurrent = snapshot.map(SummarySnapshot::current).orElse(null);
        Map<String, Object> priorToday = snapshotEntry(snapshot.orElse(null), "today");
        String priorAssembledAt = snapshot.map(SummarySnapshot::assembledAt).orElse(null);
        String currentTextAt = textGeneratedAt(priorCurrent, priorAssembledAt);
        String todayTextAt = textGeneratedAt(priorToday, priorAssembledAt);
        String fingerprint = SummaryFactFingerprint.of(currentFacts, todayFacts)
                + "|day=" + todaySlot.start() + "|zone=" + clock.getZone().getId() + "|lang=" + lang.code();
        boolean refreshOpen = SummaryFactFingerprint.needsRefresh(
                snapshot.map(SummarySnapshot::currentWindowFingerprint).orElse(null),
                currentTextAt,
                fingerprint,
                now) || !SummaryFactFingerprint.isFresh(todayTextAt, now);

        AtomicInteger llmUsed = new AtomicInteger();
        int llmCap = Math.max(0, request.maxTimelineLlm());
        boolean canLlm = request.llmAvailable() && request.client() != null && llmCap > 0;

        Map<String, Object> currentMap = openWindowMap(
                "current", com.selfanalyst.i18n.Messages.text(lang, "period.now"), currentFacts, priorCurrent,
                refreshOpen, canLlm, request.client(), lang, llmUsed, llmCap,
                refreshOpen ? now.toString() : currentTextAt);

        List<Map<String, Object>> timelineList = new ArrayList<>();
        for (SummaryWindowClassifier.Slot slot : windows.slots(now, lang)) {
            if ("current".equals(slot.key())) {
                Map<String, Object> currentEntry = new LinkedHashMap<>(currentMap);
                currentEntry.put("key", "current");
                currentEntry.put("label", slot.label());
                timelineList.add(currentEntry);
            } else if (slot.open()) {
                Map<String, Object> prior = snapshotEntry(snapshot.orElse(null), slot.key());
                timelineList.add(openWindowMap(
                        slot.key(), slot.label(), todayFacts, prior,
                        refreshOpen, canLlm, request.client(), lang, llmUsed, llmCap,
                        refreshOpen ? now.toString() : textGeneratedAt(prior, priorAssembledAt)));
            } else {
                timelineList.add(timeline.assembleClosed(slot, lang));
            }
        }

        Map<String, Object> adviceMap = reuseOrGenerateAdvice(
                snapshot.orElse(null), refreshOpen, canLlm, request.client(), lang);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("behaviorAdvice", adviceMap);
        result.put("current", currentMap);
        result.put("timeline", timelineList);
        result.put("statisticsVersion", com.selfanalyst.events.statistics.ActivityStatistics.VERSION);
        result.put("calendarVersion", com.selfanalyst.events.statistics.ActivityCalendar.VERSION);
        result.put("timezone", clock.getZone().getId());
        result.put("assembledAt", now.toString());
        result.put("currentWindowFingerprint", fingerprint);

        if (snapshots != null) {
            snapshots.save(new SummarySnapshot(
                    now.toString(), fingerprint, currentMap, timelineList, adviceMap,
                    com.selfanalyst.events.statistics.ActivityStatistics.VERSION,
                    com.selfanalyst.events.statistics.ActivityCalendar.VERSION, clock.getZone().getId()));
        }
        return result;
    }

    private Map<String, Object> openWindowMap(String key, String label,
                                              SummaryService.LocalFacts live,
                                              Map<String, Object> prior,
                                              boolean refreshOpen,
                                              boolean canLlm,
                                              SummaryPromptService.SummaryTextClient client,
                                              Lang lang,
                                              AtomicInteger llmUsed,
                                              int llmCap,
                                              String textGeneratedAt) {
        SummaryPromptService.EnhancedSummary enhanced;
        if (!refreshOpen && prior != null && prior.get("headline") != null) {
            enhanced = new SummaryPromptService.EnhancedSummary(
                    String.valueOf(prior.get("headline")),
                    stringOr(prior.get("insight"), ""),
                    stringOr(prior.get("suggestion"), ""),
                    stringOr(prior.get("confidence"), "low"),
                    live.evidence(),
                    live.topApps(),
                    live.activeTime(),
                    live.afkTime(),
                    live.switchCount(),
                    live.goalContext());
        } else if (canLlm && llmUsed.get() < llmCap && !live.titleFacts().facts().isEmpty()) {
            llmUsed.incrementAndGet();
            enhanced = prompts.enhance(live, client, lang);
        } else {
            enhanced = prompts.localOnly(live);
        }
        Map<String, Object> map = SummaryTimelineAssembler.fromEnhanced(key, label, enhanced, live);
        if (!refreshOpen && prior != null) {
            // Cached task IDs belong to the cached title projection. Local statistics still refresh.
            for (String field : List.of("taskSegments", "titleFacts", "titleCoverage")) {
                if (prior.containsKey(field)) map.put(field, prior.get(field));
            }
        }
        map.put("source", refreshOpen ? "live" : "snapshot");
        // Includes local fallbacks and failed enhancement attempts: each can retry after the
        // freshness window, while ordinary polling only updates the snapshot's assembledAt.
        map.put("textGeneratedAt", textGeneratedAt);
        if ("current".equals(key)) {
            map.put("suggestion", enhanced.suggestion());
            map.put("goalContext", enhanced.goalContext());
        }
        return map;
    }

    private static String textGeneratedAt(Map<String, Object> prior, String legacyAssembledAt) {
        if (prior == null || prior.get("headline") == null) return null;
        Object timestamp = prior.get("textGeneratedAt");
        return timestamp instanceof String value ? value : legacyAssembledAt;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reuseOrGenerateAdvice(SummarySnapshot snapshot,
                                                      boolean refreshOpen,
                                                      boolean canLlm,
                                                      SummaryPromptService.SummaryTextClient client,
                                                      Lang lang) {
        if (!refreshOpen && snapshot != null && snapshot.behaviorAdvice() != null
                && !snapshot.behaviorAdvice().isEmpty()) {
            return new LinkedHashMap<>(snapshot.behaviorAdvice());
        }
        try {
            BehaviorAdviceService.BehaviorAdvice raw = adviceService.generate(facts.behaviorData());
            BehaviorAdviceService.BehaviorAdvice finalAdvice =
                    !"empty".equals(raw.type()) && canLlm
                            ? prompts.enhanceAdvice(raw, client, lang)
                            : raw;
            return adviceMap(finalAdvice);
        } catch (Exception e) {
            return failedAdvice(lang);
        }
    }

    static Map<String, Object> adviceMap(BehaviorAdviceService.BehaviorAdvice advice) {
        Map<String, Object> adviceMap = new LinkedHashMap<>();
        adviceMap.put("type", advice.type());
        adviceMap.put("scopeLabel", advice.scopeLabel());
        adviceMap.put("generatedAt", advice.generatedAt());
        adviceMap.put("title", advice.title());
        adviceMap.put("body", advice.body());
        adviceMap.put("evidenceTags", advice.evidenceTags());
        adviceMap.put("confidence", advice.confidence());
        adviceMap.put("emptyReason", advice.emptyReason());
        Map<String, Object> basisMap = new LinkedHashMap<>();
        if (advice.basis() != null) {
            basisMap.put("observationRange", advice.basis().observationRange());
            basisMap.put("trend", advice.basis().trend());
            basisMap.put("adviceKind", advice.basis().adviceKind());
            basisMap.put("dataCompleteness", advice.basis().dataCompleteness());
        }
        adviceMap.put("basis", basisMap);
        return adviceMap;
    }

    private static Map<String, Object> failedAdvice(Lang lang) {
        Map<String, Object> adviceMap = new LinkedHashMap<>();
        adviceMap.put("type", "empty");
        adviceMap.put("scopeLabel", com.selfanalyst.i18n.Messages.text(lang, "advice.dataInsufficient"));
        adviceMap.put("generatedAt", Instant.now().toString());
        adviceMap.put("title", com.selfanalyst.i18n.Messages.text(lang, "advice.failed"));
        adviceMap.put("body", "");
        adviceMap.put("evidenceTags", List.of());
        adviceMap.put("confidence", "low");
        adviceMap.put("emptyReason", com.selfanalyst.i18n.Messages.text(lang, "advice.failedReason"));
        adviceMap.put("basis", Map.of(
                "observationRange", "—", "trend", "—", "adviceKind", "—", "dataCompleteness", com.selfanalyst.i18n.Messages.text(lang, "advice.low")));
        return adviceMap;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshotEntry(SummarySnapshot snapshot, String key) {
        if (snapshot == null || snapshot.timeline() == null) {
            return null;
        }
        for (Map<String, Object> entry : snapshot.timeline()) {
            if (key.equals(String.valueOf(entry.get("key")))) {
                return entry;
            }
        }
        return null;
    }

    private static String stringOr(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
