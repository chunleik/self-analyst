package com.selfanalyst.desktop.service;

import com.selfanalyst.i18n.Lang;
import com.selfanalyst.wiki.WikiStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Assembles {@code GET /desktop/summary}: snapshot first, Wiki for closed periods,
 * live compute only for the open current windows.
 */
public class DesktopSummaryAssembler implements AutoCloseable {

    public record Request(
            boolean llmAvailable,
            int maxTimelineLlm,
            Lang lang,
            SummaryPromptService.SummaryTextClient client,
            Supplier<SummaryPromptService.SummaryTextSession> sessions) {
        public Request(boolean llmAvailable, int maxTimelineLlm, Lang lang,
                       SummaryPromptService.SummaryTextClient client) {
            this(llmAvailable, maxTimelineLlm, lang, client, client == null ? null : () -> new SummaryPromptService.SummaryTextSession() {
                public String complete(String prompt, Duration timeout) { return client.complete(prompt, timeout); }
                public void close() { }
            });
        }
        public static Request background(boolean available, int cap, Lang lang,
                                         Supplier<SummaryPromptService.SummaryTextSession> sessions) {
            return new Request(available, cap, lang, null, sessions);
        }
    }

    private final SummaryFactSource facts;
    private final BehaviorAdviceService adviceService;
    private final SummaryPromptService prompts;
    private final SummarySnapshotStore snapshots;
    private final SummaryWindowClassifier windows;
    private final SummaryTimelineAssembler timeline;
    private final Clock clock;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private boolean inFlight;
    private boolean closed;
    private Instant nextEligibleAt = Instant.MIN;
    private int consecutiveFailures;
    private String activeScope;
    private SummarySnapshot latest;
    static final Duration MIN_INTERVAL = Duration.ofMinutes(5);

    public DesktopSummaryAssembler(SummaryFactSource facts,
                                   BehaviorAdviceService adviceService,
                                   SummaryPromptService prompts,
                                   SummarySnapshotStore snapshots,
                                   WikiStore wikiStore,
                                   Clock clock) {
        this(facts, adviceService, prompts, snapshots, wikiStore, clock, null);
    }

    DesktopSummaryAssembler(SummaryFactSource facts, BehaviorAdviceService adviceService,
                            SummaryPromptService prompts, SummarySnapshotStore snapshots,
                            WikiStore wikiStore, Clock clock, Executor executor) {
        this.facts = facts;
        this.adviceService = adviceService;
        this.prompts = prompts != null ? prompts : new SummaryPromptService();
        this.snapshots = snapshots;
        this.windows = new SummaryWindowClassifier(clock);
        this.timeline = new SummaryTimelineAssembler(wikiStore, facts, this.prompts, clock.getZone());
        this.clock = clock;
        this.ownedExecutor = executor == null ? Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "dashboard-summary");
            thread.setDaemon(true);
            return thread;
        }) : null;
        this.executor = executor != null ? executor : ownedExecutor;
        this.latest = snapshots != null ? snapshots.load(clock.getZone()).orElse(null) : null;
        if (latest != null && latest.enhancement() != null) {
            nextEligibleAt = parseTime(latest.enhancement().get("nextEligibleAt"), Instant.MIN);
            Object failures = latest.enhancement().get("consecutiveFailures");
            consecutiveFailures = failures instanceof Number number ? Math.max(0, Math.min(5, number.intValue())) : 0;
        }
    }

    public synchronized Map<String, Object> assemble(Request request) {
        Instant now = clock.instant();
        Lang lang = request.lang() != null ? request.lang() : Lang.chinese();
        Optional<SummarySnapshot> snapshot = Optional.ofNullable(latest);

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
        String scope = "|day=" + todaySlot.start() + "|zone=" + clock.getZone().getId() + "|lang=" + lang.code();
        activeScope = scope;
        String fingerprint = SummaryFactFingerprint.of(currentFacts, todayFacts) + scope;
        boolean compatibleText = snapshot.map(SummarySnapshot::currentWindowFingerprint)
                .map(value -> value.endsWith(scope)).orElse(false);
        boolean refreshOpen = SummaryFactFingerprint.needsRefresh(
                snapshot.map(SummarySnapshot::currentWindowFingerprint).orElse(null),
                currentTextAt,
                fingerprint,
                now) || !SummaryFactFingerprint.isFresh(todayTextAt, now);

        AtomicInteger llmUsed = new AtomicInteger();
        int llmCap = Math.max(0, request.maxTimelineLlm());
        boolean canLlm = !closed && request.llmAvailable() && request.sessions() != null && llmCap > 0;

        Map<String, Object> currentMap = openWindowMap(
                "current", com.selfanalyst.i18n.Messages.text(lang, "period.now"), currentFacts, priorCurrent,
                !compatibleText, false, null, lang, llmUsed, llmCap,
                compatibleText ? currentTextAt : null);

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
                        !compatibleText, false, null, lang, llmUsed, llmCap,
                        compatibleText ? textGeneratedAt(prior, priorAssembledAt) : null));
            } else {
                timelineList.add(timeline.assembleClosed(slot, lang));
            }
        }

        Map<String, Object> adviceMap = reuseOrGenerateAdvice(
                snapshot.orElse(null), !compatibleText, false, null, lang);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("behaviorAdvice", adviceMap);
        result.put("current", currentMap);
        result.put("timeline", timelineList);
        result.put("statisticsVersion", com.selfanalyst.events.statistics.ActivityStatistics.VERSION);
        result.put("calendarVersion", com.selfanalyst.events.statistics.ActivityCalendar.VERSION);
        result.put("timezone", clock.getZone().getId());
        result.put("assembledAt", now.toString());
        String savedFingerprint = compatibleText ? latest.currentWindowFingerprint() : null;
        result.put("currentWindowFingerprint", savedFingerprint);

        save(now.toString(), savedFingerprint, currentMap, timelineList, adviceMap);
        if (refreshOpen && canLlm && !inFlight && !now.isBefore(nextEligibleAt)) {
            inFlight = true;
            nextEligibleAt = now.plus(MIN_INTERVAL);
            // Persist admission before dispatch, including when a process dies during generation.
            boolean admitted = save(now.toString(), savedFingerprint, currentMap, timelineList, adviceMap);
            try {
                if (admitted) executor.execute(() -> enhance(request, currentFacts, todayFacts, todaySlot, fingerprint, scope, now));
                else inFlight = false;
            } catch (RuntimeException rejected) {
                inFlight = false;
            }
            // Deterministic inline executors used by tests may already have published the batch.
            if (!inFlight && latest != null) {
                result.put("current", latest.current());
                result.put("timeline", latest.timeline());
                result.put("behaviorAdvice", latest.behaviorAdvice());
                result.put("currentWindowFingerprint", latest.currentWindowFingerprint());
            }
        }
        result.put("enhancement", progress());
        return result;
    }

    private void enhance(Request request, SummaryService.LocalFacts current, SummaryService.LocalFacts today,
                         SummaryWindowClassifier.Slot todaySlot, String fingerprint, String scope, Instant capturedAt) {
        synchronized (this) {
            if (closed) { inFlight = false; return; }
        }
        AtomicBoolean failed = new AtomicBoolean();
        Map<String, Object> generatedCurrent = null;
        Map<String, Object> generatedToday = null;
        Map<String, Object> generatedAdvice = null;
        try (var session = request.sessions().get()) {
            SummaryPromptService.SummaryTextClient client = (prompt, timeout) -> {
                if (failed.get() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("DASHBOARD_BATCH_STOPPED");
                try { return session.complete(prompt, timeout); }
                catch (RuntimeException failure) { failed.set(true); throw failure; }
            };
            AtomicInteger used = new AtomicInteger();
            Lang lang = request.lang() != null ? request.lang() : Lang.chinese();
            generatedCurrent = openWindowMap("current", com.selfanalyst.i18n.Messages.text(lang, "period.now"),
                    current, null, true, true, client, lang, used, request.maxTimelineLlm(), capturedAt.toString());
            if (!current.titleFacts().facts().isEmpty() && used.get() > 0
                    && ((List<?>) generatedCurrent.get("taskSegments")).isEmpty()) failed.set(true);
            if (!failed.get()) {
                int before = used.get();
                generatedToday = openWindowMap(todaySlot.key(), todaySlot.label(), today, null,
                        true, true, client, lang, used, request.maxTimelineLlm(), capturedAt.toString());
                if (used.get() > before && ((List<?>) generatedToday.get("taskSegments")).isEmpty()) failed.set(true);
            }
            if (!failed.get()) {
                BehaviorAdviceService.BehaviorAdvice localAdvice = adviceService.generate(facts.behaviorData());
                BehaviorAdviceService.BehaviorAdvice enhancedAdvice = "empty".equals(localAdvice.type())
                        ? localAdvice : prompts.enhanceAdvice(localAdvice, client, lang);
                if (!"empty".equals(localAdvice.type()) && enhancedAdvice == localAdvice) failed.set(true);
                generatedAdvice = adviceMap(enhancedAdvice);
            }
        } catch (RuntimeException failure) {
            failed.set(true);
        } finally {
            synchronized (this) {
                inFlight = false;
                Instant finishedAt = clock.instant();
                consecutiveFailures = failed.get() ? Math.min(5, consecutiveFailures + 1) : 0;
                long delayMinutes = failed.get() ? Math.min(60, 5L << (consecutiveFailures - 1)) : 5;
                nextEligibleAt = finishedAt.plus(Duration.ofMinutes(delayMinutes));
                if (latest != null && !closed) {
                    Map<String, Object> currentMap = latest.current();
                    List<Map<String, Object>> entries = latest.timeline();
                    Map<String, Object> advice = latest.behaviorAdvice();
                    String key = latest.currentWindowFingerprint();
                    if (!failed.get() && scope.equals(activeScope)) {
                        currentMap = withGeneratedText(currentMap, generatedCurrent);
                        Map<String, Object> completedCurrent = currentMap;
                        Map<String, Object> completedToday = generatedToday;
                        entries = entries.stream().map(entry -> "current".equals(entry.get("key"))
                                ? withGeneratedText(entry, completedCurrent) : "today".equals(entry.get("key"))
                                ? withGeneratedText(entry, completedToday) : entry).toList();
                        if (generatedAdvice != null) advice = generatedAdvice;
                        key = fingerprint;
                    }
                    save(latest.assembledAt(), key, currentMap, entries, advice);
                }
            }
        }
    }

    private static Map<String, Object> withGeneratedText(Map<String, Object> live, Map<String, Object> generated) {
        if (generated == null) return live;
        Map<String, Object> result = new LinkedHashMap<>(live);
        for (String field : List.of("headline", "insight", "suggestion", "confidence", "taskSegments",
                "titleFacts", "titleCoverage", "textGeneratedAt")) {
            if (generated.containsKey(field)) result.put(field, generated.get(field));
        }
        result.put("source", "snapshot");
        return result;
    }

    private Map<String, Object> progress() {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("nextEligibleAt", nextEligibleAt.equals(Instant.MIN) ? null : nextEligibleAt.toString());
        progress.put("consecutiveFailures", consecutiveFailures);
        progress.put("inFlight", inFlight);
        return progress;
    }

    private boolean save(String assembledAt, String fingerprint, Map<String, Object> current,
                      List<Map<String, Object>> entries, Map<String, Object> advice) {
        latest = new SummarySnapshot(assembledAt, fingerprint, current, entries, advice,
                com.selfanalyst.events.statistics.ActivityStatistics.VERSION,
                com.selfanalyst.events.statistics.ActivityCalendar.VERSION, clock.getZone().getId(), progress());
        return snapshots == null || snapshots.save(latest);
    }

    private static Instant parseTime(Object value, Instant fallback) {
        try { return value instanceof String text ? Instant.parse(text) : fallback; }
        catch (RuntimeException invalid) { return fallback; }
    }

    @Override
    public void close() {
        synchronized (this) { closed = true; }
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
            boolean interrupted = false;
            while (!ownedExecutor.isTerminated()) {
                try { ownedExecutor.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
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
