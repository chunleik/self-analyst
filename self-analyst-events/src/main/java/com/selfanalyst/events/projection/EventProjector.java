package com.selfanalyst.events.projection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.raw.ProjectionStatus;
import com.selfanalyst.events.raw.RawEvent;
import com.selfanalyst.events.raw.RawIngestKind;
import com.selfanalyst.events.store.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 将永久原始事件幂等投影为 ActivityWatch 紧凑时间线。 */
public final class EventProjector implements RawEventProjector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Database database;
    private final int pulseTimeSeconds;
    private final int batchSize;
    private final String projectorVersion;
    private final ProjectionObserver observer;

    public EventProjector(Database database, int pulseTimeSeconds,
                          int batchSize, String projectorVersion) {
        this(database, pulseTimeSeconds, batchSize, projectorVersion, ProjectionObserver.NOOP);
    }

    EventProjector(Database database, int pulseTimeSeconds, int batchSize,
                   String projectorVersion, ProjectionObserver observer) {
        if (pulseTimeSeconds < 0 || batchSize <= 0) {
            throw new IllegalArgumentException("投影 pulsetime 和 batchSize 无效");
        }
        this.database = Objects.requireNonNull(database, "database");
        this.pulseTimeSeconds = pulseTimeSeconds;
        this.batchSize = batchSize;
        this.projectorVersion = requireText(projectorVersion, "projectorVersion");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public synchronized ProjectionResult project(RawEvent rawEvent) {
        Objects.requireNonNull(rawEvent, "rawEvent");
        Connection connection = database.metaConnection();
        try {
            Long existing = findProjectionId(connection, rawEvent.eventId());
            if (existing != null) {
                return new ProjectionResult(rawEvent.eventId(), existing,
                        ProjectionStatus.PROJECTED, false);
            }
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                existing = findProjectionId(connection, rawEvent.eventId());
                if (existing != null) {
                    connection.rollback();
                    return new ProjectionResult(rawEvent.eventId(), existing,
                            ProjectionStatus.PROJECTED, false);
                }
                long projectionId = projectEvent(connection, rawEvent);
                observer.afterProjectionMutation(rawEvent, projectionId);
                recordSource(connection, rawEvent, projectionId);
                updateCoverage(connection, rawEvent, projectionId);
                new ProjectionCheckpointStore(connection).advance(
                        YearMonth.from(rawEvent.receivedAt().atZone(ZoneOffset.UTC)),
                        rawEvent.receivedAt(), rawEvent.eventId(), projectorVersion);
                connection.commit();
                return new ProjectionResult(rawEvent.eventId(), projectionId,
                        ProjectionStatus.PROJECTED, true);
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("原始事件投影失败: " + rawEvent.eventId(), failure);
        }
    }

    public List<ProjectionResult> projectBatch(List<RawEvent> rawEvents) {
        if (rawEvents == null || rawEvents.isEmpty()) return List.of();
        List<RawEvent> ordered = rawEvents.stream()
                .sorted(Comparator.comparing(RawEvent::receivedAt)
                        .thenComparing(RawEvent::eventId))
                .limit(batchSize).toList();
        List<ProjectionResult> results = new ArrayList<>(ordered.size());
        for (RawEvent event : ordered) results.add(project(event));
        return List.copyOf(results);
    }

    Database database() {
        return database;
    }

    int batchSize() {
        return batchSize;
    }

    private long projectEvent(Connection connection, RawEvent rawEvent) throws Exception {
        if (rawEvent.ingestKind() == RawIngestKind.HEARTBEAT) {
            ProjectedEvent last = findLastEvent(connection, rawEvent.bucketId());
            if (last != null && last.dataJson().equals(rawEvent.canonicalDataJson())) {
                Instant lastEnd = last.timestamp().plusMillis((long) (last.duration() * 1000));
                if (!rawEvent.eventTimestamp().isAfter(lastEnd.plusSeconds(pulseTimeSeconds))) {
                    double duration = (rawEvent.eventTimestamp().toEpochMilli()
                            + (long) (rawEvent.duration() * 1000)
                            - last.timestamp().toEpochMilli()) / 1000.0;
                    if (duration < 0) duration = rawEvent.duration();
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE events SET duration = ? WHERE id = ? AND bucket_id = ?")) {
                        update.setDouble(1, duration);
                        update.setLong(2, last.id());
                        update.setString(3, rawEvent.bucketId());
                        update.executeUpdate();
                    }
                    return last.id();
                }
            }
        }
        String app = "";
        Map<String, Object> data = MAPPER.readValue(rawEvent.canonicalDataJson(),
                new TypeReference<>() {});
        Object appValue = data.get("app");
        if (appValue instanceof String value) app = value;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO events(bucket_id, timestamp, duration, datastr, app)
                VALUES (?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, rawEvent.bucketId());
            insert.setString(2, rawEvent.eventTimestamp().toString());
            insert.setDouble(3, rawEvent.duration());
            insert.setString(4, rawEvent.canonicalDataJson());
            insert.setString(5, app);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) throw new IllegalStateException("投影事件未返回 ID");
                return keys.getLong(1);
            }
        }
    }

    private static ProjectedEvent findLastEvent(Connection connection, String bucketId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, timestamp, duration, datastr FROM events
                WHERE bucket_id = ? ORDER BY timestamp DESC, id DESC LIMIT 1
                """)) {
            statement.setString(1, bucketId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new ProjectedEvent(result.getLong("id"),
                        Instant.parse(result.getString("timestamp")),
                        result.getDouble("duration"), result.getString("datastr")) : null;
            }
        }
    }

    private static Long findProjectionId(Connection connection, String rawEventId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT projection_event_id FROM raw_projection_sources WHERE raw_event_id = ?")) {
            statement.setString(1, rawEventId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    private void recordSource(Connection connection, RawEvent event, long projectionId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO raw_projection_sources(
                    raw_event_id, bucket_id, projection_event_id, projector_version, projected_at
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, event.eventId());
            statement.setString(2, event.bucketId());
            statement.setLong(3, projectionId);
            statement.setString(4, projectorVersion);
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private void updateCoverage(Connection connection, RawEvent event, long projectionId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO projection_event_coverage(
                    projection_event_id, bucket_id, first_raw_event_id,
                    last_raw_event_id, raw_event_count, projector_version
                ) VALUES (?, ?, ?, ?, 1, ?)
                ON CONFLICT(projection_event_id) DO UPDATE SET
                    last_raw_event_id = excluded.last_raw_event_id,
                    raw_event_count = projection_event_coverage.raw_event_count + 1,
                    projector_version = excluded.projector_version
                """)) {
            statement.setLong(1, projectionId);
            statement.setString(2, event.bucketId());
            statement.setString(3, event.eventId());
            statement.setString(4, event.eventId());
            statement.setString(5, projectorVersion);
            statement.executeUpdate();
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }

    public record ProjectionResult(String rawEventId, long projectionEventId,
                                   ProjectionStatus status, boolean newlyProjected) {}

    private record ProjectedEvent(long id, Instant timestamp, double duration, String dataJson) {}

    @FunctionalInterface
    interface ProjectionObserver {
        ProjectionObserver NOOP = (event, projectionId) -> {};
        void afterProjectionMutation(RawEvent event, long projectionId) throws Exception;
    }
}
