package com.selfanalyst.events.projection;

import com.selfanalyst.events.raw.RawEvent;
import com.selfanalyst.events.raw.RawEventStore;
import com.selfanalyst.events.raw.RawPartitionCatalog;
import com.selfanalyst.events.raw.RawPartitionMetadata;
import com.selfanalyst.events.raw.RawTimestamp;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** 启动时从每个分区 checkpoint 后恢复尚未投影的原始事件。 */
public final class ProjectionRecoveryService implements AutoCloseable {

    private final RawPartitionCatalog catalog;
    private final EventProjector projector;

    public ProjectionRecoveryService(Path rawRoot, EventProjector projector) {
        this.catalog = new RawPartitionCatalog(rawRoot);
        this.projector = projector;
    }

    public int recoverPending() {
        int projected = 0;
        for (RawPartitionMetadata partition : catalog.listReadable()) {
            YearMonth month = YearMonth.parse(partition.partitionMonth());
            while (true) {
                ProjectionCheckpointStore.Checkpoint checkpoint =
                        new ProjectionCheckpointStore(projector.database().metaConnection())
                                .find(month).orElse(null);
                List<RawEvent> pending = readBatch(partition, checkpoint, projector.batchSize());
                if (pending.isEmpty()) break;
                projected += projector.projectBatch(pending).stream()
                        .filter(EventProjector.ProjectionResult::newlyProjected).count();
                if (pending.size() < projector.batchSize()) break;
            }
        }
        return projected;
    }

    private List<RawEvent> readBatch(RawPartitionMetadata partition,
                                     ProjectionCheckpointStore.Checkpoint checkpoint,
                                     int limit) {
        Path path = catalog.resolvePartitionPath(partition.relativePath());
        StringBuilder sql = new StringBuilder("SELECT * FROM raw_events");
        if (checkpoint != null) {
            sql.append(" WHERE received_at > ? OR (received_at = ? AND event_id > ?)");
        }
        sql.append(" ORDER BY received_at, event_id LIMIT ?");
        try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + path.toUri() + "?mode=ro");
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            if (checkpoint != null) {
                String receivedAt = RawTimestamp.format(checkpoint.receivedAt());
                statement.setString(index++, receivedAt);
                statement.setString(index++, receivedAt);
                statement.setString(index++, checkpoint.eventId());
            }
            statement.setInt(index, limit);
            List<RawEvent> result = new ArrayList<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(RawEventStore.mapEvent(rows));
            }
            return List.copyOf(result);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "无法读取待投影原始事件: " + partition.partitionMonth(), failure);
        }
    }

    @Override
    public void close() throws Exception {
        catalog.close();
    }
}
