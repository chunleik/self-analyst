package com.selfanalyst.aw.projection;

import com.selfanalyst.aw.raw.RawTimestamp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;

/** 与投影写入使用同一 aw.db 事务的单调 checkpoint 存储。 */
public final class ProjectionCheckpointStore {

    private final Connection connection;

    public ProjectionCheckpointStore(Connection connection) {
        this.connection = connection;
    }

    public Optional<Checkpoint> find(YearMonth partitionMonth) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT received_at, event_id, projector_version, updated_at
                FROM raw_projection_checkpoints WHERE partition_month = ?
                """)) {
            statement.setString(1, partitionMonth.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new Checkpoint(partitionMonth,
                        Instant.parse(result.getString("received_at")),
                        result.getString("event_id"), result.getString("projector_version"),
                        Instant.parse(result.getString("updated_at"))));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("无法读取投影 checkpoint", failure);
        }
    }

    /** 调用方必须先开启事务；checkpoint 仅随同一投影事务提交。 */
    public void advance(YearMonth partitionMonth, Instant receivedAt,
                        String eventId, String projectorVersion) {
        try {
            if (connection.getAutoCommit()) {
                throw new IllegalStateException("投影 checkpoint 必须在显式事务中推进");
            }
            String sql = """
                    INSERT INTO raw_projection_checkpoints (
                        partition_month, received_at, event_id, projector_version, updated_at
                    ) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(partition_month) DO UPDATE SET
                        received_at = excluded.received_at,
                        event_id = excluded.event_id,
                        projector_version = excluded.projector_version,
                        updated_at = excluded.updated_at
                    WHERE excluded.received_at > raw_projection_checkpoints.received_at
                       OR (excluded.received_at = raw_projection_checkpoints.received_at
                           AND excluded.event_id > raw_projection_checkpoints.event_id)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, partitionMonth.toString());
                statement.setString(2, RawTimestamp.format(receivedAt));
                statement.setString(3, eventId);
                statement.setString(4, projectorVersion);
                statement.setString(5, RawTimestamp.format(Instant.now()));
                statement.executeUpdate();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("无法推进投影 checkpoint", failure);
        }
    }

    public record Checkpoint(YearMonth partitionMonth, Instant receivedAt,
                             String eventId, String projectorVersion, Instant updatedAt) {}
}
