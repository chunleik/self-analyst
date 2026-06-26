package com.selfanalyst.wiki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.selfanalyst.wiki.semantic.WikiEmbeddingWorker;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WikiWorker {

    private static final Logger log = LoggerFactory.getLogger(WikiWorker.class);
    private static final WikiLevel[] PROCESS_ORDER = {
            WikiLevel.HOUR, WikiLevel.HALF_DAY, WikiLevel.DAY,
            WikiLevel.WEEK, WikiLevel.BIWEEK, WikiLevel.MONTH
    };
    private static final String PROMPT_VERSION = "wiki-v1";

    private final WikiStore store;
    private final WikiFactBuilder factBuilder;
    private final WikiSummarizer summarizer;
    private final ZoneId timezone;
    private final Duration timeout;
    private final int intervalSeconds;
    private final boolean backfillEnabled;
    private final WikiEmbeddingWorker embeddingWorker;

    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WikiWorker(WikiStore store, WikiFactBuilder factBuilder, WikiSummarizer summarizer,
                       ZoneId timezone, Duration timeout, int intervalSeconds, boolean backfillEnabled,
                       WikiEmbeddingWorker embeddingWorker) {
        this.store = store;
        this.factBuilder = factBuilder;
        this.summarizer = summarizer;
        this.timezone = timezone;
        this.timeout = timeout;
        this.intervalSeconds = intervalSeconds;
        this.backfillEnabled = backfillEnabled;
        this.embeddingWorker = embeddingWorker;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wiki-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;

        if (backfillEnabled) {
            enqueueHistoricalPeriods();
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

    private void enqueueHistoricalPeriods() {
        try {
            Instant oldest = findOldestEventTime();
            if (oldest == null) {
                log.info("No AW events found, generating from last 7 days");
                oldest = Instant.now().minus(Duration.ofDays(7));
            }
            Instant now = Instant.now();

            for (WikiLevel level : PROCESS_ORDER) {
                List<WikiPeriod> periods = WikiPeriodFactory.generate(oldest, now, level, timezone);
                for (WikiPeriod p : periods) {
                    String id = periodId(level, p);
                    try {
                        var existing = store.query(p.start(), p.end(), level);
                        if (existing.isEmpty()) {
                            store.upsert(new WikiEntry(id, level, p.start(), p.end(),
                                    p.timezone(), WikiStatus.PENDING, null, null, List.of(),
                                    new WikiEntry.WikiMetrics(0, 0, 0, List.of(), java.util.Map.of()),
                                    List.of(), null, null, 0, null, null,
                                    Instant.now(), Instant.now(), null));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to enqueue period {}: {}", id, e.getMessage());
                    }
                }
            }
            log.info("Historical period enqueue complete");
        } catch (Exception e) {
            log.warn("Historical backfill enqueue failed: {}", e.getMessage());
        }
    }

    private Instant findOldestEventTime() {
        return null; // WikiWorker doesn't have direct AW DB access; use fallback
    }

    void processOneRound() {
        if (!running.get()) return;

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
            List<WikiEntry> retryable = store.findRetryable(1);
            for (WikiEntry entry : retryable) {
                processEntry(entry);
                return;
            }
        } catch (Exception e) {
            log.warn("WikiWorker round failed: {}", e.getMessage());
        }
    }

    private boolean canProcess(WikiEntry entry) {
        WikiLevel childLevel = childLevel(entry.level());
        if (childLevel == null) return true;

        List<WikiEntry> children = store.query(entry.periodStart(), entry.periodEnd(), childLevel);
        if (children.isEmpty()) return false;

        for (WikiEntry child : children) {
            if (child.status() != WikiStatus.SUMMARIZED && child.status() != WikiStatus.SKIPPED) {
                return false;
            }
        }
        return true;
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
                List<WikiEntry> children = store.query(entry.periodStart(), entry.periodEnd(), childLevel);
                facts = factBuilder.buildFactsFromChildren(children, period);
            }

            if (isEmpty(facts)) {
                store.markSkipped(id, "No events in period");
                return;
            }

            WikiSummarizer.SummaryResult result = summarizer.summarize(facts, timeout);

            store.updateStatus(id, WikiStatus.SUMMARIZED, result.summary(), result.primaryTask(),
                    result.taskSegments(), result.metrics(),
                    List.of(), "llm", PROMPT_VERSION);

            log.debug("Summarized wiki entry {}: {}", id, result.primaryTask());

            // Enqueue semantic indexing
            if (embeddingWorker != null) {
                var summarized = store.query(entry.periodStart(), entry.periodEnd(), entry.level())
                        .stream().filter(e -> e.id().equals(id)).findFirst();
                summarized.ifPresent(embeddingWorker::enqueueEntry);
            }
        } catch (com.selfanalyst.wiki.usage.BudgetExceededException be) {
            // 达到每日 token 预算：保持 PENDING，下个周期/次日重试，不计入失败重试次数
            log.debug("Wiki entry {} 因 token 预算暂停，保持 PENDING 稍后重试", id);
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
                && facts.titleSamples().isEmpty() && facts.contentSamples().isEmpty()
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
