package com.selfanalyst.aw.store;

import com.selfanalyst.aw.model.Event;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class EventStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Database db;
    private final PulseTimeConfig pulseConfig;
    private final BucketStore bucketStore;

    public EventStore(Database db, PulseTimeConfig pulseConfig) {
        this.db = db;
        this.pulseConfig = pulseConfig;
        this.bucketStore = new BucketStore(db);
    }

    public Event insertEvent(String bucketId, Event event) {
        validateEvent(bucketId, event);
        String sql = """
            INSERT INTO events (bucket_id, timestamp, duration, datastr, app)
            VALUES (?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = db.bucketConnection(bucketId)
                .prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, bucketId);
            ps.setString(2, event.timestamp().toString());
            ps.setDouble(3, event.duration());
            String dataJson = MAPPER.writeValueAsString(event.data());
            ps.setString(4, dataJson);
            var appNode = MAPPER.readTree(dataJson).get("app");
            ps.setString(5, appNode != null && appNode.isTextual() ? appNode.textValue() : "");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : -1;
                return new Event(id, event.timestamp(), event.duration(), event.data());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert event", e);
        }
    }

    public void validateEvent(String bucketId, Event event) {
        String client = bucketStore.get(bucketId).map(com.selfanalyst.aw.model.Bucket::client)
                .orElse(null);
        ContentEventPolicy.validate(bucketId, client, event != null ? event.data() : null);
    }

    public boolean bucketExists(String bucketId) {
        return bucketStore.get(bucketId).isPresent();
    }

    public String currentProjectorVersion() {
        try (Statement statement = db.metaConnection().createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT projector_version FROM raw_projection_sources ORDER BY projected_at DESC LIMIT 1")) {
            return result.next() ? result.getString(1) : null;
        } catch (SQLException failure) {
            return null;
        }
    }

    public java.util.Optional<Event> findById(String bucketId, long eventId) {
        String sql = "SELECT * FROM events WHERE bucket_id = ? AND id = ?";
        try (PreparedStatement ps = db.bucketConnection(bucketId).prepareStatement(sql)) {
            ps.setString(1, bucketId);
            ps.setLong(2, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return java.util.Optional.of(mapEvent(rs));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to find last event", e);
        }
        return java.util.Optional.empty();
    }

    public List<Event> queryEvents(String bucketId, int limit) {
        return queryEvents(bucketId, limit, null, null);
    }

    public List<Event> queryEvents(String bucketId, int limit, String startTime, String endTime) {
        List<Event> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM events WHERE bucket_id = ?");
        List<Object> paramList = new ArrayList<>();
        paramList.add(bucketId);

        if (startTime != null && !startTime.isBlank()) {
            sql.append(" AND timestamp >= ?");
            paramList.add(normalizeTimestamp(startTime));
        }
        if (endTime != null && !endTime.isBlank()) {
            sql.append(" AND timestamp <= ?");
            paramList.add(normalizeTimestamp(endTime));
        }
        sql.append(" ORDER BY timestamp ASC LIMIT ?");
        paramList.add(limit);

        try (PreparedStatement ps = db.bucketConnection(bucketId)
                .prepareStatement(sql.toString())) {
            for (int i = 0; i < paramList.size(); i++) {
                Object p = paramList.get(i);
                if (p instanceof Integer n) ps.setInt(i + 1, n);
                else ps.setString(i + 1, (String) p);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapEvent(rs));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to query events", e);
        }
        return result;
    }

    public List<Event> queryAllEvents(String bucketId) {
        List<Event> result = new ArrayList<>();
        String sql = "SELECT * FROM events WHERE bucket_id = ? ORDER BY timestamp ASC";
        try (PreparedStatement ps = db.bucketConnection(bucketId).prepareStatement(sql)) {
            ps.setString(1, bucketId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapEvent(rs));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to query all events", e);
        }
        return result;
    }

    public int countByBucket(String bucketId) {
        String sql = "SELECT COUNT(*) FROM events WHERE bucket_id = ?";
        try (PreparedStatement ps = db.bucketConnection(bucketId).prepareStatement(sql)) {
            ps.setString(1, bucketId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    public synchronized int deleteByBucket(String bucketId) {
        Connection connection = db.metaConnection();
        try {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement coverage = connection.prepareStatement(
                        "DELETE FROM projection_event_coverage WHERE bucket_id = ?")) {
                    coverage.setString(1, bucketId);
                    coverage.executeUpdate();
                }
                try (PreparedStatement sources = connection.prepareStatement(
                        "DELETE FROM raw_projection_sources WHERE bucket_id = ?")) {
                    sources.setString(1, bucketId);
                    sources.executeUpdate();
                }
                int deleted;
                try (PreparedStatement events = connection.prepareStatement(
                        "DELETE FROM events WHERE bucket_id = ?")) {
                    events.setString(1, bucketId);
                    deleted = events.executeUpdate();
                }
                connection.commit();
                return deleted;
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete projection events for bucket: " + bucketId, e);
        }
    }

    public List<Event> getAllEvents() {
        List<Event> result = new ArrayList<>();
        String sql = "SELECT * FROM events ORDER BY timestamp ASC";
        try (Statement stmt = db.metaConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(mapEvent(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get all events", e);
        }
        return result;
    }

    private String normalizeTimestamp(String ts) {
        try {
            return Instant.parse(ts).toString();
        } catch (Exception e) {
            return ts; // fallback
        }
    }

    private Event mapEvent(ResultSet rs) throws SQLException {
        Map<String, Object> data;
        try {
            data = MAPPER.readValue(rs.getString("datastr"),
                    new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            data = Collections.emptyMap();
        }
        return new Event(
                rs.getLong("id"),
                Instant.parse(rs.getString("timestamp")),
                rs.getDouble("duration"),
                data);
    }
}
