package com.selfanalyst.events.raw;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 通过 catalog 顺序读取健康分区的有界、稳定分页查询。 */
public final class RawEventQueryService implements AutoCloseable {

    private static final String CURSOR_VERSION = "v1";

    private final RawPartitionCatalog catalog;
    private final int maxRangeDays;
    private final int maxPageSize;

    public RawEventQueryService(Path rawRoot, int maxRangeDays, int maxPageSize) {
        if (maxRangeDays <= 0 || maxPageSize <= 0) {
            throw new IllegalArgumentException("原始查询上限必须为正整数");
        }
        this.catalog = new RawPartitionCatalog(rawRoot);
        this.maxRangeDays = maxRangeDays;
        this.maxPageSize = maxPageSize;
    }

    public QueryPage query(String bucketId, Instant start, Instant end,
                           int limit, String cursor) {
        validateRequest(bucketId, start, end, limit);
        CursorAnchor anchor = cursor == null || cursor.isBlank()
                ? null : decodeCursor(cursor, bucketId, start, end);
        List<RawEvent> collected = new ArrayList<>(limit + 1);
        List<String> scannedPartitions = new ArrayList<>();
        for (RawPartitionMetadata partition : catalog.listReadable(start, end)) {
            if (collected.size() > limit) break;
            scannedPartitions.add(partition.partitionMonth());
            queryPartition(partition, bucketId, start, end, anchor,
                    limit + 1 - collected.size(), collected);
        }
        boolean hasMore = collected.size() > limit;
        List<RawEvent> pageEvents = hasMore
                ? List.copyOf(collected.subList(0, limit)) : List.copyOf(collected);
        String nextCursor = hasMore
                ? encodeCursor(bucketId, start, end, pageEvents.getLast()) : null;
        return new QueryPage(pageEvents, nextCursor, List.copyOf(scannedPartitions));
    }

    private void validateRequest(String bucketId, Instant start, Instant end, int limit) {
        if (bucketId == null || bucketId.isBlank()) {
            throw new IllegalArgumentException("bucketId 必填");
        }
        Objects.requireNonNull(start, "start 必填");
        Objects.requireNonNull(end, "end 必填");
        if (!start.isBefore(end)) throw new IllegalArgumentException("start 必须早于 end");
        if (Duration.between(start, end).compareTo(Duration.ofDays(maxRangeDays)) > 0) {
            throw new IllegalArgumentException("查询时间范围超过上限 " + maxRangeDays + " 天");
        }
        if (limit <= 0 || limit > maxPageSize) {
            throw new IllegalArgumentException("limit 必须在 1 到 " + maxPageSize + " 之间");
        }
    }

    /** 内部文件导出：先固定全部分区的只读快照，再分页消费，不向模型返回记录。 */
    public List<String> exportSnapshot(String bucketId, Instant start, Instant end, int maxRows,
                                      java.util.function.Consumer<RawEvent> consume, Runnable checkpoint) {
        validateRequest(bucketId, start, end, Math.min(maxPageSize, 1000));
        if (maxRows < 1 || maxRows > 100_000) throw new IllegalArgumentException("导出数量上限无效");
        var partitions = catalog.listReadable(start, end);
        var connections = new ArrayList<Connection>();
        try {
            // 第一次 SELECT 固定 SQLite WAL 快照，后续分页不看见新到达事件。
            for (var partition : partitions) {
                checkpoint.run();
                var connection = DriverManager.getConnection("jdbc:sqlite:"
                        + catalog.resolvePartitionPath(partition.relativePath()).toUri() + "?mode=ro");
                connections.add(connection); connection.setAutoCommit(false);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT MAX(rowid) FROM raw_events")) { rows.next(); }
            }
            int count = 0;
            for (var connection : connections) {
                CursorAnchor anchor = null;
                while (true) {
                    checkpoint.run();
                    String sql = "SELECT * FROM raw_events WHERE bucket_id=? AND received_at>=? AND received_at<?"
                            + (anchor == null ? "" : " AND (received_at>? OR (received_at=? AND event_id>?))")
                            + " ORDER BY received_at,event_id LIMIT ?";
                    int pageCount = 0;
                    try (var statement = connection.prepareStatement(sql)) {
                        int p = 1; statement.setString(p++, bucketId); statement.setString(p++, RawTimestamp.format(start)); statement.setString(p++, RawTimestamp.format(end));
                        if (anchor != null) { statement.setString(p++, RawTimestamp.format(anchor.receivedAt)); statement.setString(p++, RawTimestamp.format(anchor.receivedAt)); statement.setString(p++, anchor.eventId); }
                        statement.setInt(p, Math.min(maxPageSize, 1000));
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) {
                                checkpoint.run();
                                if (++count > maxRows) throw new IllegalArgumentException("记录超过导出上限，请缩小范围");
                                var event = RawEventStore.mapEvent(rows); consume.accept(event); pageCount++;
                                anchor = new CursorAnchor(event.receivedAt(), event.eventId());
                            }
                        }
                    }
                    if (pageCount < Math.min(maxPageSize, 1000)) break;
                }
            }
            return partitions.stream().map(RawPartitionMetadata::partitionMonth).toList();
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("原始记录快照导出失败", failure);
        } finally {
            for (var connection : connections) try { connection.close(); } catch (java.sql.SQLException ignored) { }
        }
    }

    private void queryPartition(RawPartitionMetadata partition, String bucketId,
                                Instant start, Instant end, CursorAnchor anchor,
                                int limit, List<RawEvent> output) {
        Path path = catalog.resolvePartitionPath(partition.relativePath());
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM raw_events
                WHERE bucket_id = ? AND received_at >= ? AND received_at < ?
                """);
        if (anchor != null) {
            sql.append(" AND (received_at > ? OR (received_at = ? AND event_id > ?))");
        }
        sql.append(" ORDER BY received_at, event_id LIMIT ?");
        try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + path.toUri() + "?mode=ro");
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, bucketId);
            statement.setString(index++, RawTimestamp.format(start));
            statement.setString(index++, RawTimestamp.format(end));
            if (anchor != null) {
                statement.setString(index++, RawTimestamp.format(anchor.receivedAt()));
                statement.setString(index++, RawTimestamp.format(anchor.receivedAt()));
                statement.setString(index++, anchor.eventId());
            }
            statement.setInt(index, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) output.add(RawEventStore.mapEvent(result));
            }
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "无法只读查询原始分区: " + partition.partitionMonth(), failure);
        }
    }

    private static String encodeCursor(String bucketId, Instant start, Instant end,
                                       RawEvent lastEvent) {
        String payload = String.join("\n", CURSOR_VERSION,
                queryFingerprint(bucketId, start, end),
                lastEvent.receivedAt().toString(), lastEvent.eventId());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static CursorAnchor decodeCursor(String cursor, String bucketId,
                                             Instant start, Instant end) {
        try {
            String payload = new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8);
            String[] fields = payload.split("\\n", -1);
            if (fields.length != 4 || !CURSOR_VERSION.equals(fields[0])) {
                throw new IllegalArgumentException("cursor 格式或版本无效");
            }
            if (!queryFingerprint(bucketId, start, end).equals(fields[1])) {
                throw new IllegalArgumentException("cursor 与查询条件不匹配");
            }
            Instant receivedAt = Instant.parse(fields[2]);
            if (fields[3].isBlank()) throw new IllegalArgumentException("cursor eventId 无效");
            return new CursorAnchor(receivedAt, fields[3]);
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("cursor")) {
                throw failure;
            }
            throw new IllegalArgumentException("cursor 格式无效", failure);
        }
    }

    private static String queryFingerprint(String bucketId, Instant start, Instant end) {
        try {
            String conditions = bucketId.strip() + "\n" + start + "\n" + end;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(conditions.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
    }

    @Override
    public void close() throws Exception {
        catalog.close();
    }

    public record QueryPage(List<RawEvent> events, String nextCursor,
                            List<String> scannedPartitions) {}

    private record CursorAnchor(Instant receivedAt, String eventId) {}
}
