package com.selfanalyst.aw.store;

import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.model.BucketMetadata;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BucketStore {

    private final Database db;

    public BucketStore(Database db) {
        this.db = db;
    }

    public Bucket create(Bucket bucket) {
        if (bucket == null) throw new IllegalArgumentException("Bucket is required");
        validateId(bucket.id());
        String sql = """
            INSERT OR IGNORE INTO buckets (id, name, type, client, hostname, created, last_updated)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = db.metaConnection().prepareStatement(sql)) {
            ps.setString(1, bucket.id());
            ps.setString(2, bucket.name());
            ps.setString(3, bucket.type());
            ps.setString(4, bucket.client());
            ps.setString(5, bucket.hostname());
            ps.setString(6, bucket.created().toString());
            ps.setString(7, bucket.lastUpdated().toString());
            ps.executeUpdate();
            return bucket;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create bucket: " + bucket.id(), e);
        }
    }

    public static void validateId(String id) {
        Database.validateBucketId(id);
    }

    public Optional<Bucket> get(String id) {
        String sql = "SELECT * FROM buckets WHERE id = ?";
        try (PreparedStatement ps = db.metaConnection().prepareStatement(sql)) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapBucket(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get bucket: " + id, e);
        }
        return Optional.empty();
    }

    public List<Bucket> listAll() {
        List<Bucket> result = new ArrayList<>();
        String sql = "SELECT * FROM buckets ORDER BY last_updated DESC";
        try (Statement stmt = db.metaConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(mapBucket(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list buckets", e);
        }
        return result;
    }

    public List<BucketMetadata> listAllWithCounts(EventStore events) {
        List<BucketMetadata> result = new ArrayList<>();
        for (Bucket b : listAll()) {
            result.add(BucketMetadata.fromBucket(b, events.countByBucket(b.id())));
        }
        return result;
    }

    public boolean delete(String id) {
        String sql = "DELETE FROM buckets WHERE id = ?";
        try (PreparedStatement ps = db.metaConnection().prepareStatement(sql)) {
            ps.setString(1, id);
            if (ps.executeUpdate() > 0) {
                db.closeBucket(id);
                return true;
            }
            return false;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete bucket: " + id, e);
        }
    }

    public void updateLastUpdated(String id) {
        String sql = "UPDATE buckets SET last_updated = ? WHERE id = ?";
        try (PreparedStatement ps = db.metaConnection().prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update bucket timestamp", e);
        }
    }

    private Bucket mapBucket(ResultSet rs) throws SQLException {
        return new Bucket(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("type"),
                rs.getString("client"),
                rs.getString("hostname"),
                Instant.parse(rs.getString("created")),
                Instant.parse(rs.getString("last_updated")));
    }
}
