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

    public EventStore(Database db, PulseTimeConfig pulseConfig) {
        this.db = db;
        this.pulseConfig = pulseConfig;
    }

    public Event insertEvent(String bucketId, Event event) {
        String sql = """
            INSERT INTO events (timestamp, duration, datastr)
            VALUES (?, ?, ?)
            """;
        try (PreparedStatement ps = db.bucketConnection(bucketId)
                .prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, event.timestamp().toString());
            ps.setDouble(2, event.duration());
            ps.setString(3, MAPPER.writeValueAsString(event.data()));
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            long id = keys.next() ? keys.getLong(1) : -1;
            return new Event(id, event.timestamp(), event.duration(), event.data());
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert event", e);
        }
    }

    public Event insertHeartbeat(String bucketId, Event event) {
        return insertHeartbeat(bucketId, event, pulseConfig.pulsetime());
    }

    public Event insertHeartbeat(String bucketId, Event event, int pulsetimeSeconds) {
        var last = findLastEvent(bucketId);
        if (last != null) {
            String dataJson;
            try {
                dataJson = MAPPER.writeValueAsString(event.data());
                String lastJson = MAPPER.writeValueAsString(last.data());
                if (dataJson.equals(lastJson)) {
                    Instant lastEnd = last.timestamp().plusMillis((long) (last.duration() * 1000));
                    Instant mergeThreshold = lastEnd.plusSeconds(pulsetimeSeconds);
                    if (!event.timestamp().isAfter(mergeThreshold)) {
                        double newDuration = (double) (event.timestamp().toEpochMilli()
                                + (long) (event.duration() * 1000)
                                - last.timestamp().toEpochMilli()) / 1000.0;
                        if (newDuration < 0) newDuration = event.duration();
                        updateDuration(bucketId, last.id(), newDuration);
                        return new Event(last.id(), last.timestamp(), newDuration, last.data());
                    }
                }
            } catch (JsonProcessingException ignored) {}
        }
        return insertEvent(bucketId, event);
    }

    private Event findLastEvent(String bucketId) {
        String sql = "SELECT * FROM events ORDER BY timestamp DESC LIMIT 1";
        try (PreparedStatement ps = db.bucketConnection(bucketId).prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return mapEvent(rs);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to find last event", e);
        }
        return null;
    }

    private void updateDuration(String bucketId, long eventId, double newDuration) {
        String sql = "UPDATE events SET duration = ? WHERE id = ?";
        try (PreparedStatement ps = db.bucketConnection(bucketId).prepareStatement(sql)) {
            ps.setDouble(1, newDuration);
            ps.setLong(2, eventId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update event duration", e);
        }
    }

    public List<Event> queryEvents(String bucketId, int limit) {
        return queryEvents(bucketId, limit, null, null);
    }

    public List<Event> queryEvents(String bucketId, int limit, String startTime, String endTime) {
        List<Event> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM events WHERE 1=1");
        List<Object> paramList = new ArrayList<>();

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
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(mapEvent(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to query events", e);
        }
        return result;
    }

    public List<Event> queryAllEvents(String bucketId) {
        List<Event> result = new ArrayList<>();
        String sql = "SELECT * FROM events ORDER BY timestamp ASC";
        try (PreparedStatement ps = db.bucketConnection(bucketId).prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapEvent(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to query all events", e);
        }
        return result;
    }

    public int countByBucket(String bucketId) {
        String sql = "SELECT COUNT(*) FROM events";
        try (PreparedStatement ps = db.bucketConnection(bucketId).prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    public int deleteByBucket(String bucketId) {
        String sql = "DELETE FROM events";
        try (PreparedStatement ps = db.bucketConnection(bucketId).prepareStatement(sql)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete events for bucket: " + bucketId, e);
        }
    }

    public List<Event> getAllEvents() {
        List<Event> result = new ArrayList<>();
        // Scan all .db files in the data directory (each is a bucket)
        try {
            var files = java.nio.file.Files.list(db.dataDir())
                    .filter(f -> f.getFileName().toString().endsWith(".db")
                            && !f.getFileName().toString().equals("buckets.db"))
                    .toList();
            for (var file : files) {
                String sql = "SELECT * FROM events ORDER BY timestamp ASC";
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                     Statement stmt = c.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        result.add(mapEvent(rs));
                    }
                }
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
