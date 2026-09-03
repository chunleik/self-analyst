package com.selfanalyst.aw.raw;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 不含 payload 的原始存储容量、完整性和投影进度状态。 */
public final class RawStorageStatusService implements AutoCloseable {
    private final Path rawRoot;
    private final Path projectionDatabase;
    private final RawPartitionCatalog catalog;
    private final long warnBytes;
    private final long blockBytes;
    private final RawGrowthRate growth = new RawGrowthRate();
    private final ScheduledExecutorService sampler;
    private volatile long totalBytes;
    private volatile long usableBytes;

    public RawStorageStatusService(Path rawRoot, Path projectionDatabase,
                                   long warnBytes, long blockBytes) {
        this.rawRoot = rawRoot;
        this.projectionDatabase = projectionDatabase;
        this.warnBytes = warnBytes;
        this.blockBytes = blockBytes;
        this.catalog = new RawPartitionCatalog(rawRoot);
        this.sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "raw-status-sampler");
            thread.setDaemon(true);
            return thread;
        });
        sample();
        sampler.scheduleAtFixedRate(this::sample, 60, 60, TimeUnit.SECONDS);
    }

    public Map<String, Object> snapshot() {
        List<RawPartitionMetadata> partitions = catalog.listAll();
        RawPartitionMetadata active = partitions.stream()
                .filter(p -> p.status() == RawPartitionStatus.ACTIVE).reduce((a, b) -> b).orElse(null);
        boolean quarantined = partitions.stream()
                .anyMatch(p -> p.status() == RawPartitionStatus.QUARANTINED);
        String status = usableBytes < blockBytes ? "blocked"
                : quarantined || usableBytes < warnBytes ? "degraded" : "running";
        Instant earliest = partitions.stream().map(RawPartitionMetadata::receivedStart)
                .filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
        Instant latest = partitions.stream().map(RawPartitionMetadata::receivedEnd)
                .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
        long events = partitions.stream().mapToLong(RawPartitionMetadata::eventCount).sum();
        RawPartitionMetadata integrity = partitions.stream()
                .filter(p -> p.verifiedAt() != null).max(java.util.Comparator.comparing(
                        RawPartitionMetadata::verifiedAt)).orElse(null);
        Long lagSeconds = projectionLagSeconds(latest);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("activePartition", active == null ? null : active.partitionMonth());
        result.put("partitionCount", partitions.size());
        result.put("eventCount", events);
        result.put("earliestReceivedAt", earliest == null ? null : earliest.toString());
        result.put("latestReceivedAt", latest == null ? null : latest.toString());
        result.put("totalBytes", totalBytes);
        result.put("growthBytesPerHour", growth.bytesPerHour());
        result.put("usableBytes", usableBytes);
        result.put("diskWarning", usableBytes < warnBytes);
        result.put("projectionLagSeconds", lagSeconds);
        result.put("integrity", integrity == null
                ? Map.of("status", quarantined ? "failed" : "not_verified")
                : Map.of("status", "ok", "partition", integrity.partitionMonth(),
                        "verifiedAt", integrity.verifiedAt().toString()));
        return result;
    }

    void sample() {
        try (var files = Files.walk(rawRoot)) {
            totalBytes = files.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); } catch (Exception ignored) { return 0; }
            }).sum();
            usableBytes = Files.getFileStore(rawRoot).getUsableSpace();
            growth.record(Instant.now(), totalBytes);
        } catch (Exception ignored) {
            usableBytes = 0;
        }
    }

    private Long projectionLagSeconds(Instant latestRaw) {
        if (latestRaw == null || !Files.isRegularFile(projectionDatabase)) return null;
        try (var connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + projectionDatabase.toUri() + "?mode=ro");
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT MAX(received_at) FROM raw_projection_checkpoints")) {
            if (!result.next() || result.getString(1) == null) return null;
            Instant checkpoint = Instant.parse(result.getString(1));
            return Math.max(0, Duration.between(checkpoint, latestRaw).getSeconds());
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override public void close() throws Exception {
        sampler.shutdownNow();
        catalog.close();
    }
}
