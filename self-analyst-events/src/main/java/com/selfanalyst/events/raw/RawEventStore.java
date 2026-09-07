package com.selfanalyst.events.raw;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/** 月度 SQLite 分区上的永久、只追加原始事件存储。 */
public final class RawEventStore implements RawEventAppender, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RawEventStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final int RAW_SCHEMA_VERSION = 1;
    private static final int SQLITE_BUSY_TIMEOUT_MS = 1_000;

    private final RawPartitionCatalog catalog;
    private final SealObserver sealObserver;
    private final RawDiskSpaceMonitor diskSpaceMonitor;
    private final Map<YearMonth, Connection> connections = new HashMap<>();

    public RawEventStore(Path rawRoot) {
        this(new RawPartitionCatalog(rawRoot), SealObserver.NOOP, null);
    }

    RawEventStore(RawPartitionCatalog catalog) {
        this(catalog, SealObserver.NOOP, null);
    }

    RawEventStore(Path rawRoot, SealObserver sealObserver) {
        this(new RawPartitionCatalog(rawRoot), sealObserver, null);
    }

    RawEventStore(Path rawRoot, RawDiskSpaceMonitor diskSpaceMonitor) {
        this(new RawPartitionCatalog(rawRoot), SealObserver.NOOP, diskSpaceMonitor);
    }

    private RawEventStore(RawPartitionCatalog catalog, SealObserver sealObserver,
                          RawDiskSpaceMonitor diskSpaceMonitor) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.sealObserver = Objects.requireNonNull(sealObserver, "sealObserver");
        this.diskSpaceMonitor = diskSpaceMonitor;
        recoverInterruptedSeals();
    }

    @Override
    public synchronized RawEvent append(RawEvent event) {
        return appendBatch(List.of(event)).getFirst();
    }

    @Override
    public synchronized List<RawEvent> appendBatch(List<RawEvent> events) {
        if (events == null || events.isEmpty()) return List.of();
        if (diskSpaceMonitor != null) diskSpaceMonitor.requireWritable();
        List<RawEvent> input = List.copyOf(events);
        YearMonth month = monthOf(input.getFirst().receivedAt());
        if (input.stream().anyMatch(event -> !month.equals(monthOf(event.receivedAt())))) {
            throw new IllegalArgumentException(
                    "同一原始事件批次必须使用同一 receivedAt UTC 月份");
        }

        Connection connection = connectionFor(month, input.getFirst().receivedAt());
        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<RawEvent> stored = new ArrayList<>(input.size());
                for (RawEvent event : input) stored.add(insertOrExisting(connection, event));
                connection.commit();
                refreshCatalog(month, connection);
                sealOlderActivePartitions(month);
                return List.copyOf(stored);
            } catch (Exception failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (RuntimeException failure) {
            if (isStorageFull(failure)) {
                if (diskSpaceMonitor != null) diskSpaceMonitor.markStorageFull(failure);
                throw new RawStorageFullException(
                        "原始事件事务因空间不足失败",
                        diskSpaceMonitor == null ? -1 : diskSpaceMonitor.usableBytes(), failure);
            }
            throw failure;
        } catch (Exception failure) {
            if (isStorageFull(failure)) {
                if (diskSpaceMonitor != null) diskSpaceMonitor.markStorageFull(failure);
                throw new RawStorageFullException(
                        "原始事件事务因空间不足失败",
                        diskSpaceMonitor == null ? -1 : diskSpaceMonitor.usableBytes(), failure);
            }
            throw new IllegalStateException("原始事件批量事务失败", failure);
        }
    }

    public synchronized long count(YearMonth month) {
        RawPartitionMetadata metadata = catalog.find(month.toString()).orElse(null);
        if (metadata == null) return 0;
        try (ConnectionLease lease = readConnection(month, metadata);
             Statement statement = lease.connection().createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM raw_events")) {
            return result.next() ? result.getLong(1) : 0;
        } catch (SQLException failure) {
            throw new IllegalStateException("无法统计原始事件", failure);
        }
    }

    public synchronized List<RawEvent> readPartition(YearMonth month) {
        RawPartitionMetadata metadata = catalog.find(month.toString()).orElse(null);
        if (metadata == null) return List.of();
        String sql = "SELECT * FROM raw_events ORDER BY received_at, event_id";
        try (ConnectionLease lease = readConnection(month, metadata);
             Statement statement = lease.connection().createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            List<RawEvent> events = new ArrayList<>();
            while (result.next()) events.add(mapEvent(result));
            return List.copyOf(events);
        } catch (SQLException failure) {
            throw new IllegalStateException("无法读取原始事件分区", failure);
        }
    }

    public Path partitionPath(YearMonth month) {
        return catalog.resolvePartitionPath(relativePath(month));
    }

    public RawPartitionMetadata partitionMetadata(YearMonth month) {
        return catalog.find(month.toString()).orElse(null);
    }

    private Connection connectionFor(YearMonth month, Instant receivedStart) {
        return connections.computeIfAbsent(month, ignored -> openPartition(month, receivedStart));
    }

    private Connection openPartition(YearMonth month, Instant receivedStart) {
        String relativePath = relativePath(month);
        Path path = catalog.resolvePartitionPath(relativePath);
        try {
            RawPartitionMetadata existing = catalog.find(month.toString()).orElse(null);
            if (existing != null && existing.status() != RawPartitionStatus.ACTIVE) {
                throw new IllegalStateException("原始分区不是活动状态: " + month);
            }
            Files.createDirectories(path.getParent());
            path = catalog.resolvePartitionPath(relativePath);
            if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("原始分区不是普通文件: " + path);
            }
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            try {
                prepareConnection(connection);
                initializeSchema(connection);
                catalog.ensureActivePartition(month.toString(), relativePath,
                        receivedStart != null ? receivedStart : month.atDay(1)
                                .atStartOfDay(ZoneOffset.UTC).toInstant(), RAW_SCHEMA_VERSION);
                return connection;
            } catch (Exception failure) {
                connection.close();
                throw failure;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("无法打开原始事件分区: " + month, failure);
        }
    }

    private ConnectionLease readConnection(YearMonth month, RawPartitionMetadata metadata)
            throws SQLException {
        Connection active = connections.get(month);
        if (active != null) return new ConnectionLease(active, false);
        if (metadata.status() == RawPartitionStatus.ACTIVE) {
            return new ConnectionLease(connectionFor(month, metadata.receivedStart()), false);
        }
        Path path = catalog.resolvePartitionPath(metadata.relativePath());
        Connection readOnly = DriverManager.getConnection(
                "jdbc:sqlite:" + path.toUri() + "?mode=ro");
        try (Statement statement = readOnly.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA busy_timeout=" + SQLITE_BUSY_TIMEOUT_MS);
        }
        return new ConnectionLease(readOnly, true);
    }

    private static void prepareConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + SQLITE_BUSY_TIMEOUT_MS);
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
        }
    }

    private static void initializeSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS raw_events (
                        event_id TEXT PRIMARY KEY,
                        source_event_id TEXT,
                        bucket_id TEXT NOT NULL,
                        source TEXT NOT NULL,
                        schema_version INTEGER NOT NULL CHECK (schema_version > 0),
                        ingest_kind TEXT NOT NULL,
                        event_timestamp TEXT NOT NULL,
                        received_at TEXT NOT NULL,
                        duration REAL NOT NULL CHECK (duration >= 0),
                        canonical_data_json TEXT NOT NULL,
                        data_sha256 TEXT NOT NULL,
                        import_session_id TEXT,
                        import_ordinal INTEGER
                    )
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS ux_raw_source_event
                    ON raw_events(bucket_id, source_event_id)
                    WHERE source_event_id IS NOT NULL
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS ux_raw_import_event
                    ON raw_events(import_session_id, bucket_id, import_ordinal)
                    WHERE import_session_id IS NOT NULL
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_raw_bucket_event_time
                    ON raw_events(bucket_id, event_timestamp, event_id)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_raw_received
                    ON raw_events(received_at, event_id)
                    """);
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS raw_events_reject_update
                    BEFORE UPDATE ON raw_events
                    BEGIN
                        SELECT RAISE(ABORT, 'raw_events is append-only');
                    END
                    """);
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS raw_events_reject_delete
                    BEFORE DELETE ON raw_events
                    BEGIN
                        SELECT RAISE(ABORT, 'raw_events is append-only');
                    END
                    """);
        }
    }

    private static RawEvent insertOrExisting(Connection connection, RawEvent event)
            throws SQLException {
        RawEvent existing = findByStableIdentity(connection, event);
        if (existing != null) return existing;
        String sql = """
                INSERT INTO raw_events (
                    event_id, source_event_id, bucket_id, source, schema_version,
                    ingest_kind, event_timestamp, received_at, duration,
                    canonical_data_json, data_sha256, import_session_id, import_ordinal
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.eventId());
            setNullableString(statement, 2, event.sourceEventId());
            statement.setString(3, event.bucketId());
            statement.setString(4, event.source().storageValue());
            statement.setInt(5, event.schemaVersion());
            statement.setString(6, event.ingestKind().storageValue());
            statement.setString(7, RawTimestamp.format(event.eventTimestamp()));
            statement.setString(8, RawTimestamp.format(event.receivedAt()));
            statement.setDouble(9, event.duration());
            statement.setString(10, event.canonicalDataJson());
            statement.setString(11, event.dataSha256());
            setNullableString(statement, 12, event.importSessionId());
            if (event.importOrdinal() == null) statement.setNull(13, Types.INTEGER);
            else statement.setInt(13, event.importOrdinal());
            statement.executeUpdate();
            return event;
        } catch (SQLException uniqueOrFailure) {
            RawEvent raced = findByStableIdentity(connection, event);
            if (raced != null) return raced;
            throw uniqueOrFailure;
        }
    }

    private static RawEvent findByStableIdentity(Connection connection, RawEvent event)
            throws SQLException {
        if (event.sourceEventId() != null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM raw_events WHERE bucket_id = ? AND source_event_id = ?")) {
                statement.setString(1, event.bucketId());
                statement.setString(2, event.sourceEventId());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) return mapEvent(result);
                }
            }
        }
        if (event.importSessionId() != null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM raw_events
                    WHERE import_session_id = ? AND bucket_id = ? AND import_ordinal = ?
                    """)) {
                statement.setString(1, event.importSessionId());
                statement.setString(2, event.bucketId());
                statement.setInt(3, event.importOrdinal());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) return mapEvent(result);
                }
            }
        }
        return null;
    }

    private void refreshCatalog(YearMonth month, Connection connection) throws SQLException {
        String sql = """
                SELECT COUNT(*) AS event_count,
                       MIN(event_id) AS first_event_id,
                       MAX(event_id) AS last_event_id,
                       MIN(received_at) AS received_start,
                       MAX(received_at) AS received_end
                FROM raw_events
                """;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            Path path = partitionPath(month);
            catalog.updateStatistics(month.toString(), result.getLong("event_count"),
                    result.getString("first_event_id"), result.getString("last_event_id"),
                    parseInstant(result.getString("received_start")),
                    parseInstant(result.getString("received_end")),
                    Files.size(path));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("无法读取原始分区大小", failure);
        }
    }

    private void sealOlderActivePartitions(YearMonth currentMonth) {
        for (RawPartitionMetadata partition : catalog.listByStatus(RawPartitionStatus.ACTIVE)) {
            YearMonth month = YearMonth.parse(partition.partitionMonth());
            if (month.equals(currentMonth)) continue;
            try {
                sealPartition(partition);
            } catch (Exception failure) {
                if (!(failure instanceof SealInterruption)) {
                    catalog.updateStatus(partition.partitionMonth(), RawPartitionStatus.QUARANTINED);
                }
                log.error("原始分区封存失败，已隔离 month={} type={}",
                        partition.partitionMonth(), failure.getClass().getSimpleName());
            }
        }
    }

    private void sealPartition(RawPartitionMetadata partition) throws Exception {
        YearMonth month = YearMonth.parse(partition.partitionMonth());
        catalog.updateStatus(partition.partitionMonth(), RawPartitionStatus.SEALING);
        Connection active = connections.remove(month);
        if (active != null) active.close();
        Path path = catalog.resolvePartitionPath(partition.relativePath());

        try (Connection checkpoint = DriverManager.getConnection(
                "jdbc:sqlite:" + path.toAbsolutePath());
             Statement statement = checkpoint.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + SQLITE_BUSY_TIMEOUT_MS);
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            statement.execute("PRAGMA journal_mode=DELETE");
        }
        sealObserver.after(SealStep.CHECKPOINTED, path);

        PartitionStats stats;
        try (Connection readOnly = DriverManager.getConnection(
                "jdbc:sqlite:" + path.toUri() + "?mode=ro");
             Statement statement = readOnly.createStatement()) {
            try (ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
                if (!integrity.next() || !"ok".equalsIgnoreCase(integrity.getString(1))) {
                    throw new IllegalStateException("SQLite integrity_check 未通过");
                }
            }
            stats = readStats(readOnly);
        }
        sealObserver.after(SealStep.INTEGRITY_VERIFIED, path);
        if (partition.eventCount() != stats.eventCount()
                || !Objects.equals(partition.firstEventId(), stats.firstEventId())
                || !Objects.equals(partition.lastEventId(), stats.lastEventId())) {
            throw new IllegalStateException("catalog 与原始分区计数或边界不一致");
        }

        long size = Files.size(path);
        String sha256 = fileSha256(path);
        Instant verifiedAt = Instant.now();
        writeManifest(path, partition.partitionMonth(), stats, size, sha256, verifiedAt);
        sealObserver.after(SealStep.MANIFEST_WRITTEN, path);
        catalog.markSealed(partition.partitionMonth(), stats.eventCount(),
                stats.firstEventId(), stats.lastEventId(), stats.receivedStart(),
                stats.receivedEnd(), size, sha256, verifiedAt);
    }

    private void recoverInterruptedSeals() {
        for (RawPartitionMetadata partition : catalog.listByStatus(RawPartitionStatus.SEALING)) {
            try {
                sealPartition(partition);
            } catch (Exception failure) {
                catalog.updateStatus(partition.partitionMonth(), RawPartitionStatus.QUARANTINED);
                log.error("恢复原始分区封存失败，已隔离 month={} type={}",
                        partition.partitionMonth(), failure.getClass().getSimpleName());
            }
        }
    }

    private static PartitionStats readStats(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*) AS event_count,
                            MIN(event_id) AS first_event_id,
                            MAX(event_id) AS last_event_id,
                            MIN(received_at) AS received_start,
                            MAX(received_at) AS received_end
                     FROM raw_events
                     """)) {
            return new PartitionStats(result.getLong("event_count"),
                    result.getString("first_event_id"), result.getString("last_event_id"),
                    parseInstant(result.getString("received_start")),
                    parseInstant(result.getString("received_end")));
        }
    }

    private static void writeManifest(Path databasePath, String month, PartitionStats stats,
                                      long size, String sha256, Instant verifiedAt)
            throws Exception {
        LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("manifestVersion", 1);
        manifest.put("partitionMonth", month);
        manifest.put("databaseFile", databasePath.getFileName().toString());
        manifest.put("schemaVersion", RAW_SCHEMA_VERSION);
        manifest.put("eventCount", stats.eventCount());
        manifest.put("firstEventId", stats.firstEventId());
        manifest.put("lastEventId", stats.lastEventId());
        manifest.put("receivedStart", stats.receivedStart() == null
                ? null : stats.receivedStart().toString());
        manifest.put("receivedEnd", stats.receivedEnd() == null
                ? null : stats.receivedEnd().toString());
        manifest.put("fileSizeBytes", size);
        manifest.put("fileSha256", sha256);
        manifest.put("verifiedAt", verifiedAt.toString());
        Path manifestPath = manifestPath(databasePath);
        Path temporary = manifestPath.resolveSibling(manifestPath.getFileName() + ".tmp");
        Files.writeString(temporary, MAPPER.writeValueAsString(manifest),
                StandardCharsets.UTF_8);
        try {
            Files.move(temporary, manifestPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, manifestPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Path manifestPath(Path databasePath) {
        String name = databasePath.getFileName().toString();
        String manifestName = name.endsWith(".db")
                ? name.substring(0, name.length() - 3) + ".manifest.json"
                : name + ".manifest.json";
        return databasePath.resolveSibling(manifestName);
    }

    public static String fileSha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static RawEvent mapEvent(ResultSet result) throws SQLException {
        int ordinal = result.getInt("import_ordinal");
        Integer importOrdinal = result.wasNull() ? null : ordinal;
        return new RawEvent(
                result.getString("event_id"), result.getString("source_event_id"),
                result.getString("bucket_id"), RawEventSource.parse(result.getString("source")),
                result.getInt("schema_version"),
                RawIngestKind.parse(result.getString("ingest_kind")),
                Instant.parse(result.getString("event_timestamp")),
                Instant.parse(result.getString("received_at")), result.getDouble("duration"),
                result.getString("canonical_data_json"), result.getString("data_sha256"),
                result.getString("import_session_id"), importOrdinal);
    }

    private static void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static YearMonth monthOf(Instant receivedAt) {
        return YearMonth.from(receivedAt.atZone(ZoneOffset.UTC));
    }

    static boolean isStorageFull(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && sql.getErrorCode() == 13) return true;
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("sqlite_full")
                        || normalized.contains("database or disk is full")
                        || normalized.contains("no space left on device")
                        || normalized.contains("not enough space on the disk")) return true;
            }
        }
        return false;
    }

    private static String relativePath(YearMonth month) {
        return "%04d/raw-events-%s.db".formatted(month.getYear(), month);
    }

    @Override
    public synchronized void close() throws Exception {
        Exception failure = null;
        for (Connection connection : connections.values()) {
            try {
                connection.close();
            } catch (Exception closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        connections.clear();
        try {
            catalog.close();
        } catch (Exception closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }

    private record PartitionStats(long eventCount, String firstEventId, String lastEventId,
                                  Instant receivedStart, Instant receivedEnd) {}

    private record ConnectionLease(Connection connection, boolean closeOnExit)
            implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            if (closeOnExit) connection.close();
        }
    }

    enum SealStep { CHECKPOINTED, INTEGRITY_VERIFIED, MANIFEST_WRITTEN }

    @FunctionalInterface
    interface SealObserver {
        SealObserver NOOP = (step, path) -> {};

        void after(SealStep step, Path partitionPath) throws Exception;
    }

    static final class SealInterruption extends RuntimeException {
        SealInterruption(String message) {
            super(message);
        }
    }
}
