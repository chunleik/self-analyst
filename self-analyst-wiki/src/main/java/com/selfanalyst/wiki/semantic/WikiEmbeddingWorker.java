package com.selfanalyst.wiki.semantic;

import com.selfanalyst.wiki.WikiEntry;
import com.selfanalyst.wiki.WikiStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class WikiEmbeddingWorker {

    private static final Logger log = LoggerFactory.getLogger(WikiEmbeddingWorker.class);
    private static final String DOC_TYPE_ENTRY = "ENTRY_SUMMARY";
    private static final String DOC_TYPE_SEGMENT = "TASK_SEGMENT";

    private final WikiStore store;
    private final WikiSemanticIndex index;
    private final EmbeddingClient embeddingClient;
    private final String embeddingModel;
    private final int embeddingDimensions;
    private final int intervalSeconds;

    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WikiEmbeddingWorker(WikiStore store, WikiSemanticIndex index,
                                EmbeddingClient embeddingClient,
                                String embeddingModel, int embeddingDimensions,
                                int intervalSeconds) {
        this.store = store;
        this.index = index;
        this.embeddingClient = embeddingClient;
        this.embeddingModel = embeddingModel;
        this.embeddingDimensions = embeddingDimensions;
        this.intervalSeconds = intervalSeconds;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wiki-embedding-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;

        // Mark stale any documents with different model/dimensions
        try {
            store.markSemanticDocsStale(embeddingModel, embeddingDimensions);
        } catch (Exception e) {
            log.warn("Failed to mark stale semantic docs: {}", e.getMessage());
        }

        executor.scheduleWithFixedDelay(
                this::processOneRound,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS);

        log.info("WikiEmbeddingWorker started (interval={}s, model={}, dims={})",
                intervalSeconds, embeddingModel, embeddingDimensions);
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
        log.info("WikiEmbeddingWorker shut down");
    }

    void processOneRound() {
        if (!running.get()) return;

        try {
            // 1. Enqueue semantic docs for new summarized entries
            enqueueForNewEntries();

            // 2. Process PENDING semantic docs
            List<WikiStore.SemanticDoc> pending = store.findPendingSemanticDocs(1);
            for (WikiStore.SemanticDoc doc : pending) {
                processDoc(doc);
                return;
            }

            // 3. Retry FAILED docs
            List<WikiStore.SemanticDoc> retryable = store.findRetryableSemanticDocs(1);
            for (WikiStore.SemanticDoc doc : retryable) {
                processDoc(doc);
                return;
            }
        } catch (Exception e) {
            log.warn("WikiEmbeddingWorker round failed: {}", e.getMessage());
        }
    }

    private void enqueueForNewEntries() {
        List<WikiEntry> entries = store.findSummarizedWithoutSemanticDocs(20);
        for (WikiEntry entry : entries) {
            try {
                enqueueEntry(entry);
            } catch (Exception e) {
                log.warn("Failed to enqueue semantic docs for entry {}: {}",
                        entry.id(), e.getMessage());
            }
        }
    }

    public void enqueueEntry(WikiEntry entry) {
        ZoneId tz = ZoneId.of(entry.timezone());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        // ENTRY_SUMMARY doc
        String entryText = buildEntryIndexText(entry, tz, fmt);
        String entryHash = sha256(entryText);
        String entryDocId = "sem-" + entry.id() + "-entry";

        store.upsertSemanticDoc(new WikiStore.SemanticDoc(
                entryDocId, entry.id(), DOC_TYPE_ENTRY, entry.level().name(),
                entry.periodStart().toString(), entry.periodEnd().toString(),
                entryHash, embeddingModel, embeddingDimensions,
                WikiStore.SemanticDocStatus.PENDING, 0, null, null,
                Instant.now(), Instant.now(), null));

        // TASK_SEGMENT docs
        if (entry.taskSegments() != null) {
            for (int i = 0; i < entry.taskSegments().size(); i++) {
                WikiEntry.TaskSegment seg = entry.taskSegments().get(i);
                String segText = buildSegmentIndexText(seg);
                String segHash = sha256(segText);
                String segDocId = "sem-" + entry.id() + "-seg-" + i;

                store.upsertSemanticDoc(new WikiStore.SemanticDoc(
                        segDocId, entry.id(), DOC_TYPE_SEGMENT, entry.level().name(),
                        entry.periodStart().toString(), entry.periodEnd().toString(),
                        segHash, embeddingModel, embeddingDimensions,
                        WikiStore.SemanticDocStatus.PENDING, 0, null, null,
                        Instant.now(), Instant.now(), null));
            }
        }
    }

    private void processDoc(WikiStore.SemanticDoc doc) {
        String docId = doc.docId();
        try {
            WikiEntry entry = store.query(
                    doc.periodStart() != null ? Instant.parse(doc.periodStart()) : null,
                    doc.periodEnd() != null ? Instant.parse(doc.periodEnd()) : null,
                    null).stream()
                    .filter(e -> e.id().equals(doc.entryId()))
                    .findFirst().orElse(null);

            if (entry == null) {
                store.markSemanticDocFailed(docId, "Referenced wiki entry not found",
                        Instant.now().plus(Duration.ofMinutes(10)));
                return;
            }

            // Build index text
            String text;
            if (DOC_TYPE_ENTRY.equals(doc.docType())) {
                ZoneId tz = ZoneId.of(entry.timezone());
                text = buildEntryIndexText(entry, tz, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            } else {
                // Find the segment — match by checking all task segments
                text = entry.taskSegments().stream()
                        .map(WikiEmbeddingWorker::buildSegmentIndexText)
                        .filter(t -> sha256(t).equals(doc.textHash()))
                        .findFirst().orElse(null);
                if (text == null) {
                    store.markSemanticDocFailed(docId, "Segment not found in entry",
                            Instant.now().plus(Duration.ofMinutes(10)));
                    return;
                }
            }

            float[] vector = embeddingClient.embedSingle(text);
            index.indexDocument(docId, entry.id(), doc.docType(), entry.level(),
                    entry.periodStart(), entry.periodEnd(),
                    text, entry.summary(), entry.primaryTask(),
                    truncate(text, 500), vector);

            store.markSemanticDocIndexed(docId);
            log.debug("Indexed semantic doc {} (type={})", docId, doc.docType());
        } catch (Exception e) {
            log.warn("Semantic doc {} failed: {}", docId, e.getMessage());
            int retryCount = doc.retryCount() + 1;
            long delayMinutes = (long) Math.min(1440, Math.pow(2, retryCount));
            store.markSemanticDocFailed(docId, truncate(e.getMessage(), 500),
                    Instant.now().plus(Duration.ofMinutes(delayMinutes)));
        }
    }

    static String buildEntryIndexText(WikiEntry entry, ZoneId tz, DateTimeFormatter fmt) {
        StringBuilder sb = new StringBuilder();
        sb.append(entry.summary() != null ? entry.summary() : "").append(" ");
        sb.append(entry.primaryTask() != null ? entry.primaryTask() : "").append(" ");
        sb.append(entry.level().name()).append(" level summary ");
        sb.append(ZonedDateTime.ofInstant(entry.periodStart(), tz).format(fmt))
                .append(" to ").append(ZonedDateTime.ofInstant(entry.periodEnd(), tz).format(fmt));
        if (entry.taskSegments() != null) {
            for (WikiEntry.TaskSegment seg : entry.taskSegments()) {
                sb.append(" ").append(seg.title());
            }
        }
        return sb.toString().trim();
    }

    static String buildSegmentIndexText(WikiEntry.TaskSegment seg) {
        StringBuilder sb = new StringBuilder();
        sb.append(seg.title()).append(" ");
        sb.append(seg.summary() != null ? seg.summary() : "").append(" ");
        sb.append("confidence:").append(seg.confidence()).append(" ");
        if (seg.apps() != null) {
            sb.append("apps:").append(String.join(",", seg.apps()));
        }
        return sb.toString().trim();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
