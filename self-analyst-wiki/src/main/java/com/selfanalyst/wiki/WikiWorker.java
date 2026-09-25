package com.selfanalyst.wiki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.selfanalyst.wiki.semantic.WikiEmbeddingWorker;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class WikiWorker {

    private static final Logger log = LoggerFactory.getLogger(WikiWorker.class);
    private static final WikiLevel[] PROCESS_ORDER = {
            WikiLevel.HOUR, WikiLevel.HALF_DAY, WikiLevel.DAY,
            WikiLevel.WEEK, WikiLevel.BIWEEK, WikiLevel.MONTH
    };
    private static final Duration BACKFILL_LOOKBACK = Duration.ofDays(7);

    private final WikiStore store;
    private final WikiFactBuilder factBuilder;
    private final WikiSummarizer summarizer;
    private final ZoneId timezone;
    private final Duration timeout;
    private final int intervalSeconds;
    private final boolean backfillEnabled;
    private final WikiEmbeddingWorker embeddingWorker;
    private final Supplier<Instant> nowSupplier;

    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant enqueueCursor;
    private volatile boolean recoverPaused;
    private static final Instant PAUSED_UNTIL = Instant.parse("9999-12-31T00:00:00Z");

    public WikiWorker(WikiStore store, WikiFactBuilder factBuilder, WikiSummarizer summarizer,
                       ZoneId timezone, Duration timeout, int intervalSeconds, boolean backfillEnabled,
                       WikiEmbeddingWorker embeddingWorker) {
        this(store, factBuilder, summarizer, timezone, timeout, intervalSeconds,
                backfillEnabled, embeddingWorker, Instant::now);
    }

    WikiWorker(WikiStore store, WikiFactBuilder factBuilder, WikiSummarizer summarizer,
               ZoneId timezone, Duration timeout, int intervalSeconds, boolean backfillEnabled,
               WikiEmbeddingWorker embeddingWorker, Supplier<Instant> nowSupplier) {
        this.store = store;
        this.factBuilder = factBuilder;
        this.summarizer = summarizer;
        this.timezone = timezone;
        this.timeout = timeout;
        this.intervalSeconds = intervalSeconds;
        this.backfillEnabled = backfillEnabled;
        this.embeddingWorker = embeddingWorker;
        this.nowSupplier = nowSupplier;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wiki-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        recoverPaused = true;

        Instant startedAt = nowSupplier.get();
        if (backfillEnabled) {
            enqueueCursor = startedAt.minus(BACKFILL_LOOKBACK);
            // Discovery runs on the background executor, never on the startup thread.
        } else {
            // Reconcile one recent hour on the first round so an opt-out from historical
            // backfill still produces live Wiki entries immediately.
            enqueueCursor = startedAt.minus(Duration.ofHours(1));
        }

        executor.scheduleWithFixedDelay(
                this::processOneRound,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS);

        log.info("WikiWorker started (interval={}s, backfill={})", intervalSeconds, backfillEnabled);
    }

    public void shutdown() {
        running.set(false);
        executor.shutdownNow();
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try { executor.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        log.info("WikiWorker shut down");
    }

    private boolean enqueueHistoricalPeriods(Instant oldest, Instant now) {
        try {
            enqueueMissingPeriods(oldest, now);
            log.info("Historical period enqueue complete");
            return true;
        } catch (Exception e) {
            log.warn("Historical backfill enqueue failed: {}", e.getMessage());
            return false;
        }
    }

    void processOneRound() {
        if (!running.get()) return;

        reconcileNewPeriods();
        if (backfillEnabled) discoverOlderStatistics();

        try {
            restorePausedGenerations();
            // Process in priority order: lower levels first
            for (WikiLevel level : PROCESS_ORDER) {
                List<WikiEntry> pending = store.findPending(level, Integer.MAX_VALUE, nowSupplier.get());
                for (WikiEntry entry : pending) {
                    if (canProcess(entry)) {
                        processEntry(entry);
                        return;
                    }
                }
            }

            // Retry failed entries
            List<WikiEntry> retryable = store.findRetryable(Integer.MAX_VALUE, nowSupplier.get());
            for (WikiLevel level : PROCESS_ORDER) {
                for (WikiEntry entry : retryable) {
                    if (entry.level() == level && canProcess(entry)) {
                        processEntry(entry);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("WikiWorker round failed: {}", e.getMessage());
        }
    }

    private void discoverOlderStatistics() {
        try {
            Instant before = store.historyBefore();
            if (before == null) before = nowSupplier.get().minus(BACKFILL_LOOKBACK);
            Instant oldest = factBuilder.earliestEvent().orElse(before);
            if (!oldest.isBefore(before)) return;
            Instant from = before.minus(Duration.ofDays(1));
            if (from.isBefore(oldest)) from = oldest;
            enqueueMissingPeriods(from, before);
            store.saveHistoryBefore(from);
        } catch (Exception error) {
            log.warn("Wiki historical statistics discovery failed: {}", error.getClass().getSimpleName());
        }
    }

    private void reconcileNewPeriods() {
        Instant now = nowSupplier.get();
        Instant from = enqueueCursor != null
                ? enqueueCursor : now.minus(Duration.ofHours(1));
        try {
            enqueueMissingPeriods(from, now);
            enqueueCursor = now;
        } catch (Exception e) {
            // Keep the old cursor so the next round retries the same discovery window.
            log.warn("Wiki period reconciliation failed: {}", e.getMessage());
        }
    }

    int enqueueMissingPeriods(Instant rangeStart, Instant rangeEnd) {
        if (rangeStart == null || rangeEnd == null || !rangeStart.isBefore(rangeEnd)) {
            return 0;
        }

        int created = 0;
        Instant timestamp = nowSupplier.get();
        for (WikiLevel level : PROCESS_ORDER) {
            List<WikiPeriod> periods = WikiPeriodFactory.generate(
                    rangeStart, rangeEnd, level, timezone);
            for (WikiPeriod period : periods) {
                boolean exists = store.query(period.start(), period.end(), level).stream()
                        .anyMatch(entry -> entry.periodStart().equals(period.start())
                                && entry.periodEnd().equals(period.end())
                                && entry.timezone().equals(period.timezone()));
                if (exists) continue;

                store.upsert(new WikiEntry(periodId(level, period), level,
                        period.start(), period.end(), period.timezone(), WikiStatus.PENDING,
                        null, null, List.of(),
                        new WikiEntry.WikiMetrics(0, 0, 0, List.of(), java.util.Map.of()),
                        List.of(), null, null, 0, null, null,
                        timestamp, timestamp, null));
                created++;
            }
        }
        if (created > 0) {
            log.debug("Enqueued {} newly completed Wiki periods", created);
        }
        return created;
    }

    private boolean canProcess(WikiEntry entry) {
        WikiLevel childLevel = childLevel(entry.level());
        if (!entry.currentStatistics() || entry.periodEnd().isAfter(nowSupplier.get())) return false;
        if (childLevel == null) return true;

        return completedExpectedChildren(entry, childLevel) != null;
    }

    private List<WikiEntry> completedExpectedChildren(WikiEntry entry, WikiLevel childLevel) {
        List<WikiEntry> children = store.query(entry.periodStart(), entry.periodEnd(), childLevel);
        List<WikiPeriod> expectedChildren = WikiPeriodFactory.generate(
                entry.periodStart(), entry.periodEnd(), childLevel,
                ZoneId.of(entry.timezone()));
        if (expectedChildren.isEmpty()) return null;

        List<WikiEntry> exactChildren = new ArrayList<>(expectedChildren.size());
        for (WikiPeriod expected : expectedChildren) {
            WikiEntry child = children.stream()
                    .filter(candidate -> candidate.periodStart().equals(expected.start())
                            && candidate.periodEnd().equals(expected.end())
                            && candidate.timezone().equals(expected.timezone()))
                    .findFirst()
                    .orElse(null);
            if (child == null || (child.status() != WikiStatus.SUMMARIZED
                    && child.status() != WikiStatus.SKIPPED)) {
                return null;
            }
            exactChildren.add(child);
        }
        return exactChildren;
    }

    private void processEntry(WikiEntry entry) {
        String id = entry.id();
        WikiPeriod period = new WikiPeriod(entry.level(), entry.periodStart(), entry.periodEnd(), entry.timezone());
        WikiFactBuilder.WikiFacts facts = null;
        boolean generated = false;
        boolean published = false;
        String attemptedConfiguration = "unavailable";
        try {
            log.debug("Processing wiki entry {} (level={})", id, entry.level());

            if (needsRawEvents(entry.level())) {
                facts = summarizer instanceof WikiSummaryPipeline
                        ? factBuilder.buildCompleteFacts(period) : factBuilder.buildFacts(period);
            } else {
                WikiLevel childLevel = childLevel(entry.level());
                List<WikiEntry> children = completedExpectedChildren(entry, childLevel);
                if (children == null) {
                    throw new IllegalStateException("Parent dependencies are incomplete for " + id);
                }
                facts = factBuilder.buildFactsFromChildren(children, period);
            }

            if (isEmpty(facts)) {
                store.markSkipped(id, "No events in period");
                return;
            }

            WikiSummarizer.SummaryResult result;
            String model = "llm";
            if (locallyEmpty(facts)) {
                result = localEmptyResult(facts);
                model = "local";
            } else {
                // Capture before acquiring the generation lease: a hot update during an old
                // request must remain visible to the next pause-recovery scan.
                if (summarizer instanceof WikiSummaryPipeline pipeline)
                    attemptedConfiguration = configurationStamp(pipeline);
                result = summarizer.summarize(facts, timeout);
                generated = true;
            }

            store.updateStatus(id, WikiStatus.SUMMARIZED, result.summary(), result.primaryTask(),
                    result.taskSegments(), result.metrics(),
                    sourceEntries(facts), model, summarizer.promptVersion(),
                    facts.factBuilderVersion(), facts.projectorVersion(), facts.sourceCoverage());
            published = true;
            if (generated && summarizer instanceof WikiSummaryPipeline pipeline) pipeline.markPublished(result);

            log.debug("Summarized wiki entry {}: {}", id, result.primaryTask());

            // Enqueue semantic indexing
            if (embeddingWorker != null) {
                var summarized = store.query(entry.periodStart(), entry.periodEnd(), entry.level())
                        .stream().filter(e -> e.id().equals(id)).findFirst();
                summarized.ifPresent(embeddingWorker::enqueueEntry);
            }
        } catch (WikiPeriodBudgetException exhausted) {
            recordFailure(entry, facts, period, "period_budget", "WIKI_PERIOD_BUDGET_EXHAUSTED", PAUSED_UNTIL, false, attemptedConfiguration);
        } catch (WikiSummaryPipeline.CallFailure failure) {
            String state = failure.kind().name().toLowerCase(java.util.Locale.ROOT);
            Instant next = retryTime(entry);
            if (failure.kind() == WikiSummaryPipeline.FailureKind.CONFIGURATION
                    || failure.kind() == WikiSummaryPipeline.FailureKind.INPUT) next = PAUSED_UNTIL;
            else if (failure.kind() == WikiSummaryPipeline.FailureKind.GLOBAL_BUDGET) next = nextBudgetDay();
            else if (failure.kind() == WikiSummaryPipeline.FailureKind.PERIOD_BUDGET) next = PAUSED_UNTIL;
            recordFailure(entry, facts, period, state, failure.code(), next, !failure.beforeSend(), attemptedConfiguration);
        } catch (com.selfanalyst.wiki.usage.BudgetExceededException gate) {
            recordFailure(entry, facts, period, "global_budget", "WIKI_GLOBAL_BUDGET", nextBudgetDay(), false, attemptedConfiguration);
        } catch (com.selfanalyst.wiki.usage.LlmUnavailableException unavailable) {
            recordFailure(entry, facts, period, "configuration", "WIKI_MODEL_UNAVAILABLE", PAUSED_UNTIL, false, attemptedConfiguration);
        } catch (Exception e) {
            String code = safeFailure(e);
            log.warn("Wiki entry {} failed: {}", id, code);
            if (published) return; // A checkpoint-cleanup/index failure cannot undo a committed summary.
            String state = generated ? "publish_failed" : code.startsWith("WIKI_EVIDENCE_")
                    || code.startsWith("WIKI_NARRATIVE_") || code.startsWith("WIKI_RESPONSE_") ? "quality" : "transport";
            recordFailure(entry, facts, period, state, code, retryTime(entry), true, attemptedConfiguration);
        }
    }

    private void restorePausedGenerations() {
        boolean startup = recoverPaused;
        if (!(summarizer instanceof WikiSummaryPipeline pipeline)) return;
        List<WikiEntry> paused = store.findGenerationPaused();
        String configuration = paused.isEmpty() ? "unavailable" : configurationStamp(pipeline);
        for (WikiEntry entry : paused) {
            String state = WikiGenerationProgress.state(entry);
            boolean resume = "period_budget".equals(state) ? startup && pipeline.canResume(period(entry))
                    : startup || !configuration.equals(WikiGenerationProgress.raw(entry).get("configurationStamp"));
            if (resume) store.resumeGeneration(entry);
        }
        recoverPaused = false;
    }

    private void recordFailure(WikiEntry entry, WikiFactBuilder.WikiFacts facts, WikiPeriod period,
                               String state, String code, Instant next, boolean failure, String attemptedConfiguration) {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("state", state); progress.put("reason", code);
        progress.put("nextRetryAt", next.toString());
        if (summarizer instanceof WikiSummaryPipeline pipeline) {
            var budget = pipeline.progress(period);
            progress.put("calls", budget.calls()); progress.put("tokens", budget.tokens());
            progress.put("unsettledCalls", budget.unsettledCalls());
            progress.put("reservedTokens", budget.reservedTokens()); progress.put("estimatedCalls", budget.estimatedCalls());
            progress.put("maxCalls", pipeline.budgetLimits().maxCalls()); progress.put("maxTokens", pipeline.budgetLimits().maxTokens());
            progress.put("configurationStamp", attemptedConfiguration);
        }
        store.markGenerationFailure(entry, facts, progress, next, failure);
    }

    private Instant nextBudgetDay() {
        return nowSupplier.get().atZone(timezone).toLocalDate().plusDays(1).atStartOfDay(timezone).toInstant();
    }

    private static String configurationStamp(WikiSummaryPipeline pipeline) {
        try { return pipeline.configurationStamp(); }
        catch (RuntimeException unavailable) { return "unavailable"; }
    }

    private Instant retryTime(WikiEntry entry) {
        long minutes = (long) Math.min(1440, Math.pow(2, Math.min(20, entry.retryCount() + 1)));
        return nowSupplier.get().plus(Duration.ofMinutes(minutes));
    }

    private static WikiPeriod period(WikiEntry entry) {
        return new WikiPeriod(entry.level(), entry.periodStart(), entry.periodEnd(), entry.timezone());
    }

    private static String safeFailure(Exception error) {
        String text = error.getMessage();
        return text != null && text.matches("WIKI_[A-Z0-9_]+(?::[A-Za-z.]+)?") ? text : "WIKI_GENERATION_FAILED";
    }

    private static boolean needsRawEvents(WikiLevel level) {
        return level == WikiLevel.HOUR || level == WikiLevel.HALF_DAY || level == WikiLevel.DAY;
    }

    private static List<String> sourceEntries(WikiFactBuilder.WikiFacts facts) {
        Object ids = facts.statistics().get("sourceEntryIds");
        return ids instanceof List<?> list ? list.stream().filter(String.class::isInstance)
                .map(String.class::cast).toList() : List.of();
    }

    private static WikiLevel childLevel(WikiLevel parent) {
        return switch (parent) {
            case WEEK -> WikiLevel.DAY;
            case BIWEEK -> WikiLevel.WEEK;
            case MONTH -> WikiLevel.DAY;
            default -> null;
        };
    }

    /** No task evidence remains after noise exclusion. Budget omission is not emptiness. */
    private static boolean locallyEmpty(WikiFactBuilder.WikiFacts facts) {
        if (!facts.childSummaries().isEmpty() || facts.sampledTitles() == null) return false;
        Object candidates = facts.sampledTitles().coverage().get("candidateFacts");
        return candidates instanceof Number number && number.intValue() == 0;
    }

    private static WikiSummarizer.SummaryResult localEmptyResult(WikiFactBuilder.WikiFacts facts) {
        Map<String, Object> extra = new LinkedHashMap<>(facts.statistics());
        extra.put("generation", Map.of("mode", "local_empty", "calls", 0));
        extra.put("localEmpty", true);
        return new WikiSummarizer.SummaryResult("该时段没有可归纳的活跃活动。", "无活跃活动", List.of(),
                new WikiEntry.WikiMetrics(facts.activeSeconds(), facts.afkSeconds(), facts.switchCount(),
                        facts.topApps(), extra));
    }

    private boolean isEmpty(WikiFactBuilder.WikiFacts facts) {
        return facts.activeSeconds() == 0 && facts.afkSeconds() == 0
                && facts.titleSamples().isEmpty() && facts.contextTitleSamples().isEmpty()
                && (facts.sampledTitles() == null
                    || ((Number) facts.sampledTitles().coverage().get("candidateFacts")).intValue() == 0)
                && facts.childSummaries().isEmpty();
    }

    private static String periodId(WikiLevel level, WikiPeriod p) {
        String shortLevel = level.name().substring(0, 1);
        return shortLevel + "-" + p.start().toString().replace(":", "").replace("-", "")
                + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
