package com.selfanalyst;

import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.wiki.WikiEntry;
import com.selfanalyst.wiki.WikiLevel;
import com.selfanalyst.wiki.WikiStatus;
import com.selfanalyst.wiki.WikiStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Syncs WikiStore summaries into AW event buckets so they appear as readable
 * swim-lane rows in the AW timeline, replacing the dense content axis.
 *
 * Three buckets are maintained:
 *   wiki-hourly_<hostname>
 *   wiki-halfday_<hostname>
 *   wiki-daily_<hostname>
 *
 * Each bucket is rebuilt from the last 7 days of DONE wiki entries every
 * SYNC_INTERVAL_MS milliseconds.
 */
public class WikiSummaryWatcher extends Thread {

    private static final Logger log = LoggerFactory.getLogger(WikiSummaryWatcher.class);
    private static final long SYNC_INTERVAL_MS = 2 * 60 * 1000L;
    private static final int LOOKBACK_DAYS = 7;
    static final String CLIENT = "wiki";

    private static final Map<WikiLevel, String> LEVEL_SUFFIX = Map.of(
            WikiLevel.HOUR,     "wiki-hourly",
            WikiLevel.HALF_DAY, "wiki-halfday",
            WikiLevel.DAY,      "wiki-daily"
    );

    private final WikiStore wikiStore;
    private final BucketStore bucketStore;
    private final EventStore eventStore;
    private final String hostname;
    private volatile boolean running = true;

    public WikiSummaryWatcher(WikiStore wikiStore, BucketStore bucketStore,
                               EventStore eventStore) {
        this.wikiStore   = wikiStore;
        this.bucketStore = bucketStore;
        this.eventStore  = eventStore;
        this.hostname    = resolveHostname();
        setDaemon(true);
        setName("wiki-summary-watcher");
    }

    @Override
    public void run() {
        // Initial sync immediately, then every SYNC_INTERVAL_MS
        while (running) {
            try {
                sync();
            } catch (Exception e) {
                log.warn("WikiSummaryWatcher sync failed: {}", e.getMessage());
            }
            try {
                Thread.sleep(SYNC_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    // ── Sync ───────────────────────────────────────────────────────────

    private void sync() {
        Instant start = Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS);
        Instant end   = Instant.now().plus(1, ChronoUnit.DAYS);

        for (WikiLevel level : LEVEL_SUFFIX.keySet()) {
            String bucketId = bucketId(level);
            ensureBucket(bucketId, level);

            List<WikiEntry> entries = wikiStore.query(start, end, level)
                    .stream()
                    .filter(e -> e.status() == WikiStatus.SUMMARIZED && e.summary() != null)
                    .toList();

            if (entries.isEmpty()) continue;

            // Rebuild: clear then re-insert (idempotent, handles summary updates)
            eventStore.deleteByBucket(bucketId);
            int written = 0;
            for (WikiEntry entry : entries) {
                double durationSec = entry.periodEnd().getEpochSecond()
                        - entry.periodStart().getEpochSecond();
                if (durationSec <= 0) continue;

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("title",   truncate(entry.primaryTask() != null ? entry.primaryTask() : "", 80));
                data.put("summary", truncate(entry.summary(), 300));
                if (entry.metrics() != null && entry.metrics().activeSeconds() > 0) {
                    data.put("active_min", entry.metrics().activeSeconds() / 60);
                }

                eventStore.insertEvent(bucketId, new Event(entry.periodStart(), durationSec, data));
                written++;
            }
            if (written > 0) {
                log.debug("WikiSummaryWatcher: wrote {} {} events", written, level);
            }
        }
    }

    private void ensureBucket(String bucketId, WikiLevel level) {
        if (bucketStore.get(bucketId).isEmpty()) {
            Bucket bucket = Bucket.create(bucketId,
                    "Wiki summaries (" + level.name().toLowerCase() + ")",
                    "summary", CLIENT, hostname);
            bucketStore.create(bucket);
            log.info("WikiSummaryWatcher: created bucket {}", bucketId);
        }
    }

    static String timelineBucketId(WikiLevel level, String hostname) {
        return LEVEL_SUFFIX.get(level) + "_" + hostname;
    }

    private String bucketId(WikiLevel level) {
        return timelineBucketId(level, hostname);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String resolveHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
