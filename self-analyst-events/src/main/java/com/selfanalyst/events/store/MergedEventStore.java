package com.selfanalyst.events.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.model.Bucket;
import com.selfanalyst.events.raw.CanonicalJson;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 合并事件及短期重试回执的单事务写入边界。 */
public final class MergedEventStore implements AutoCloseable {
    public static final Duration RECEIPT_RETENTION = Duration.ofHours(24);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Connection connection;
    private final int pulseSeconds;
    private final Clock clock;
    private final ScheduledExecutorService maintenance;
    private final String epoch = UUID.randomUUID().toString();
    private final Map<String, Head> heads = new HashMap<>();
    private Runnable writableCheck = () -> {};
    private volatile boolean writeFailed;

    public boolean writeFailed() { return writeFailed; }

    public synchronized void setWritableCheck(Runnable check) { writableCheck = Objects.requireNonNull(check); }

    public MergedEventStore(Database db, int pulseSeconds) {
        this(db, pulseSeconds, Clock.systemUTC());
    }

    public MergedEventStore(Database db, int pulseSeconds, Clock clock) {
        if (pulseSeconds < 0) throw new IllegalArgumentException("Invalid pulsetime");
        this.pulseSeconds = pulseSeconds;
        this.clock = Objects.requireNonNull(clock);
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:"
                    + db.dataDir().resolve(Database.PROJECTION_FILENAME).toAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA synchronous=FULL");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS event_receipts (
                        bucket_id TEXT NOT NULL, identity TEXT NOT NULL,
                        fingerprint BLOB NOT NULL, event_id INTEGER NOT NULL,
                        received_at INTEGER NOT NULL,
                        PRIMARY KEY(bucket_id, identity)
                    ) WITHOUT ROWID
                    """);
                s.execute("CREATE INDEX IF NOT EXISTS idx_event_receipts_time ON event_receipts(received_at)");
            }
            pruneReceipts();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot initialize merged event writer", e);
        }
        maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "event-receipt-maintenance"); t.setDaemon(true); return t;
        });
        maintenance.scheduleWithFixedDelay(() -> {
            try { pruneReceipts(); } catch (RuntimeException ignored) { /* next write retries pruning */ }
        }, 1, 1, TimeUnit.MINUTES);
    }

    public record Submission(Event event, String identity) {}
    private record Head(long id, String session) {}

    public synchronized Event heartbeat(String bucket, Event event, String identity, String session) {
        return write(bucket, List.of(new Submission(event, identity)), true, session).getFirst();
    }

    public synchronized List<Event> insertBatch(String bucket, List<Submission> submissions) {
        return write(bucket, submissions, false, null);
    }

    public synchronized void importBatch(List<Bucket> buckets, Map<String, List<Submission>> events) {
        writableCheck.run();
        Map<String, Head> previousHeads = new HashMap<>(heads);
        try {
            connection.setAutoCommit(false);
            try {
                for (Bucket bucket : buckets) {
                    Database.validateBucketId(bucket.id());
                    try (PreparedStatement s = connection.prepareStatement(
                            "INSERT OR IGNORE INTO buckets VALUES(?,?,?,?,?,?,?)")) {
                        s.setString(1, bucket.id()); s.setString(2, bucket.name()); s.setString(3, bucket.type());
                        s.setString(4, bucket.client()); s.setString(5, bucket.hostname());
                        s.setString(6, bucket.created().toString()); s.setString(7, bucket.lastUpdated().toString());
                        s.executeUpdate();
                    }
                }
                for (var entry : events.entrySet()) write(entry.getKey(), entry.getValue(), false, null);
                connection.commit();
            } catch (Exception failure) {
                connection.rollback(); heads.clear(); heads.putAll(previousHeads); throw failure;
            } finally { connection.setAutoCommit(true); }
        } catch (IllegalArgumentException | IllegalStateException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("Import transaction failed", failure); }
    }

    private List<Event> write(String bucket, List<Submission> submissions, boolean heartbeat, String session) {
        Database.validateBucketId(bucket);
        if (submissions == null || submissions.size() > 100_000) throw new IllegalArgumentException("Invalid batch");
        for (Submission submission : submissions) {
            validate(submission.event());
            if (submission.identity() != null && submission.identity().length() > 512)
                throw new IllegalArgumentException("Submission identity too long");
        }
        if (session != null && session.length() > 256) throw new IllegalArgumentException("Session too long");
        if (submissions.isEmpty()) return List.of();
        writableCheck.run();
        String activeSession = session == null ? epoch : session;
        Head original = heads.get(bucket);
        try (Statement transaction = connection.createStatement()) {
            boolean ownsTransaction = connection.getAutoCommit();
            if (ownsTransaction) transaction.execute("BEGIN IMMEDIATE");
            try {
                String client;
                try (PreparedStatement find = connection.prepareStatement("SELECT client FROM buckets WHERE id=?")) {
                    find.setString(1, bucket);
                    try (ResultSet rows = find.executeQuery()) {
                        if (!rows.next()) throw new IllegalArgumentException("Bucket not found");
                        client = rows.getString(1);
                    }
                }
                pruneWithinTransaction();
                List<Event> results = new ArrayList<>();
                for (Submission submission : submissions) {
                    Event incoming = submission.event();
                    ContentEventPolicy.validate(bucket, client, incoming.data());
                    String json = CanonicalJson.encode(incoming.data()).json();
                    String identity = submission.identity();
                    if (identity != null && identity.isBlank()) identity = null;
                    byte[] fingerprint = fingerprint(incoming, heartbeat, session);
                    Event existing = identity == null ? null : receipt(bucket, identity, fingerprint);
                    if (existing != null) { results.add(existing); continue; }
                    Event saved;
                    Head head = heads.get(bucket);
                    Event previous = heartbeat && head != null ? find(bucket, head.id()) : null;
                    if (previous != null && Objects.equals(head.session(), activeSession)
                            && !incoming.timestamp().isBefore(previous.timestamp())
                            && !incoming.timestamp().isAfter(end(previous).plusSeconds(pulseSeconds))
                            && CanonicalJson.encode(previous.data()).json().equals(json)) {
                        Instant end = end(previous).isAfter(end(incoming)) ? end(previous) : end(incoming);
                        double duration = Duration.between(previous.timestamp(), end).toNanos() / 1_000_000_000.0;
                        try (PreparedStatement update = connection.prepareStatement("UPDATE events SET duration=? WHERE id=? AND bucket_id=?")) {
                            update.setDouble(1, duration); update.setLong(2, previous.id()); update.setString(3, bucket);
                            update.executeUpdate();
                        }
                        saved = new Event(previous.id(), previous.timestamp(), duration, previous.data());
                    } else {
                        saved = insert(bucket, incoming, json);
                        if (heartbeat && (previous == null || !incoming.timestamp().isBefore(previous.timestamp())
                                || !Objects.equals(head.session(), activeSession))) {
                            heads.put(bucket, new Head(saved.id(), activeSession));
                        }
                    }
                    if (!heartbeat) heads.remove(bucket);
                    if (identity != null) recordReceipt(bucket, identity, fingerprint, saved.id());
                    results.add(saved);
                }
                try (PreparedStatement update = connection.prepareStatement("UPDATE buckets SET last_updated=? WHERE id=?")) {
                    update.setString(1, clock.instant().toString()); update.setString(2, bucket); update.executeUpdate();
                }
                if (ownsTransaction) transaction.execute("COMMIT");
                writeFailed = false;
                return List.copyOf(results);
            } catch (Exception failure) {
                if (ownsTransaction) transaction.execute("ROLLBACK");
                if (original == null) heads.remove(bucket); else heads.put(bucket, original);
                throw failure;
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            writeFailed = true;
            throw new IllegalStateException("Event transaction failed", failure);
        }
    }

    private static void validate(Event event) {
        if (event == null || event.timestamp() == null || event.data() == null
                || !Double.isFinite(event.duration()) || event.duration() < 0
                || event.duration() > 315_576_000) throw new IllegalArgumentException("Invalid event time or data");
        try { end(event); } catch (DateTimeException | ArithmeticException failure) {
            throw new IllegalArgumentException("Invalid event end", failure);
        }
    }

    static byte[] fingerprint(Event event, boolean heartbeat, String session) {
        return HexFormat.of().parseHex(CanonicalJson.encode(Map.of(
                "timestamp", event.timestamp().toString(), "duration", event.duration(),
                "data", event.data(), "kind", heartbeat ? "heartbeat" : "event",
                "session", session == null ? "" : session)).sha256());
    }

    private static Instant end(Event event) {
        return event.timestamp().plusNanos((long) (event.duration() * 1_000_000_000));
    }

    private Event insert(String bucket, Event event, String json) throws Exception {
        try (PreparedStatement s = connection.prepareStatement(
                "INSERT INTO events(bucket_id,timestamp,duration,datastr,app) VALUES(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, bucket); s.setString(2, event.timestamp().toString()); s.setDouble(3, event.duration());
            s.setString(4, json); s.setString(5, event.data().get("app") instanceof String app ? app : "");
            s.executeUpdate();
            try (ResultSet keys = s.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing event id");
                return new Event(keys.getLong(1), event.timestamp(), event.duration(), event.data());
            }
        }
    }

    private Event receipt(String bucket, String identity, byte[] fingerprint) throws Exception {
        try (PreparedStatement s = connection.prepareStatement(
                "SELECT fingerprint,event_id FROM event_receipts WHERE bucket_id=? AND identity=?")) {
            s.setString(1, bucket); s.setString(2, identity);
            try (ResultSet rows = s.executeQuery()) {
                if (!rows.next()) return null;
                if (!Arrays.equals(fingerprint, rows.getBytes(1))) throw new IllegalArgumentException("Submission identity conflict");
                Event existing = find(bucket, rows.getLong(2));
                if (existing == null) throw new IllegalStateException("Submission target was deleted");
                return existing;
            }
        }
    }

    private Event find(String bucket, long id) throws Exception {
        try (PreparedStatement s = connection.prepareStatement("SELECT timestamp,duration,datastr FROM events WHERE bucket_id=? AND id=?")) {
            s.setString(1, bucket); s.setLong(2, id);
            try (ResultSet rows = s.executeQuery()) {
                if (!rows.next()) return null;
                return new Event(id, Instant.parse(rows.getString(1)), rows.getDouble(2),
                        MAPPER.readValue(rows.getString(3), new TypeReference<Map<String, Object>>() {}));
            }
        }
    }

    private void recordReceipt(String bucket, String identity, byte[] fingerprint, long id) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("INSERT INTO event_receipts VALUES(?,?,?,?,?)")) {
            s.setString(1, bucket); s.setString(2, identity); s.setBytes(3, fingerprint);
            s.setLong(4, id); s.setLong(5, clock.millis()); s.executeUpdate();
        }
    }

    private void pruneWithinTransaction() throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("DELETE FROM event_receipts WHERE received_at < ?")) {
            s.setLong(1, clock.millis() - RECEIPT_RETENTION.toMillis()); s.executeUpdate();
        }
    }

    public synchronized void pruneReceipts() {
        try { pruneWithinTransaction(); }
        catch (SQLException e) { throw new IllegalStateException("Cannot maintain event receipts", e); }
    }

    @Override public synchronized void close() throws SQLException {
        maintenance.shutdownNow();
        connection.close();
    }
}
