package com.selfanalyst.wiki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.selfanalyst.wiki.semantic.WikiEmbeddingWorker;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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

        Instant startedAt = nowSupplier.get();
        if (backfillEnabled) {
            enqueueCursor = startedAt.minus(BACKFILL_LOOKBACK);
            if (enqueueHistoricalPeriods(enqueueCursor, startedAt)) {
                enqueueCursor = startedAt;
            }
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
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
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

        try {
            // Process in priority order: lower levels first
            for (WikiLevel level : PROCESS_ORDER) {
                List<WikiEntry> pending = store.findPending(level, 1);
                for (WikiEntry entry : pending) {
                    if (canProcess(entry)) {
                        processEntry(entry);
                        return;
                    }
                }
            }

            // Retry failed entries
            List<WikiEntry> retryable = store.findRetryable(Integer.MAX_VALUE);
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
        try {
            log.debug("Processing wiki entry {} (level={})", id, entry.level());

            WikiPeriod period = new WikiPeriod(entry.level(), entry.periodStart(),
                    entry.periodEnd(), entry.timezone());

            WikiFactBuilder.WikiFacts facts;
            if (needsRawEvents(entry.level())) {
                facts = factBuilder.buildFacts(period);
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

            WikiSummarizer.SummaryResult result = summarizer.summarize(facts, timeout);

            store.updateStatus(id, WikiStatus.SUMMARIZED, result.summary(), result.primaryTask(),
                    result.taskSegments(), result.metrics(),
                    List.of(), "llm", summarizer.promptVersion(),
                    facts.factBuilderVersion(), facts.projectorVersion(), facts.sourceCoverage());

            log.debug("Summarized wiki entry {}: {}", id, result.primaryTask());

            // Enqueue semantic indexing
            if (embeddingWorker != null) {
                var summarized = store.query(entry.periodStart(), entry.periodEnd(), entry.level())
                        .stream().filter(e -> e.id().equals(id)).findFirst();
                summarized.ifPresent(embeddingWorker::enqueueEntry);
            }
        } catch (com.selfanalyst.wiki.usage.BudgetExceededException | com.selfanalyst.wiki.usage.LlmUnavailableException be) {
            // 达到每日 token 预算：保持 PENDING，下个周期/次日重试，不计入失败重试次数
            log.debug("Wiki entry {} 因模型未就绪或预算暂停，保持 PENDING 稍后重试", id);
        } catch (Exception e) {
            log.warn("Wiki entry {} failed: {}", id, e.getMessage());
            int retryCount = entry.retryCount() + 1;
            long delayMinutes = (long) Math.min(1440, Math.pow(2, retryCount));
            Instant nextRetry = Instant.now().plus(Duration.ofMinutes(delayMinutes));
            store.markFailed(id, truncate(e.getMessage(), 500), nextRetry);
        }
    }

    private static boolean needsRawEvents(WikiLevel level) {
        return level == WikiLevel.HOUR || level == WikiLevel.HALF_DAY || level == WikiLevel.DAY;
    }

    private static WikiLevel childLevel(WikiLevel parent) {
        return switch (parent) {
            case WEEK -> WikiLevel.DAY;
            case BIWEEK -> WikiLevel.WEEK;
            case MONTH -> WikiLevel.DAY;
            default -> null;
        };
    }

    private boolean isEmpty(WikiFactBuilder.WikiFacts facts) {
        return facts.activeSeconds() == 0 && facts.afkSeconds() == 0
                && facts.titleSamples().isEmpty() && facts.contextTitleSamples().isEmpty()
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
