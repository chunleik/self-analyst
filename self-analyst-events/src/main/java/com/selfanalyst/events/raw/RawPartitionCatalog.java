package com.selfanalyst.events.raw;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 可恢复的原始分区索引。所有受 catalog 管理的路径必须是 raw 根目录下的
 * 普通非链接路径；路径不确定时一律拒绝打开。
 */
public final class RawPartitionCatalog implements AutoCloseable {

    public static final int CATALOG_SCHEMA_VERSION = 1;
    private static final int SQLITE_BUSY_TIMEOUT_MS = 1_000;

    private final Path rawRoot;
    private final Path catalogPath;
    private final Connection connection;

    public RawPartitionCatalog(Path configuredRawRoot) {
        Objects.requireNonNull(configuredRawRoot, "configuredRawRoot");
        try {
            Class.forName("org.sqlite.JDBC");
            Path absolute = configuredRawRoot.toAbsolutePath().normalize();
            rejectLinksInExistingChain(absolute);
            Files.createDirectories(absolute);
            rejectLinksInExistingChain(absolute);
            this.rawRoot = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
            this.catalogPath = rawRoot.resolve("catalog.db");
            rejectLink(catalogPath);
            this.connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + catalogPath.toAbsolutePath());
            try {
                prepareConnection(connection);
                initializeSchema(connection);
                rejectLink(catalogPath);
            } catch (Exception initializationFailure) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    initializationFailure.addSuppressed(closeFailure);
                }
                throw initializationFailure;
            }
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "无法安全初始化原始分区 catalog: " + configuredRawRoot, failure);
        }
    }

    public Path rawRoot() {
        return rawRoot;
    }

    public Path catalogPath() {
        return catalogPath;
    }

    static Path validateRawRootForRead(Path configuredRawRoot) throws IOException {
        Path absolute = configuredRawRoot.toAbsolutePath().normalize();
        rejectLinksInExistingChain(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("原始目录不存在或不是目录: " + absolute);
        }
        return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 只读检查既有 catalog 是否记录分区。目录或 catalog 不存在时返回 false；
     * 已存在但无法安全验证或读取时 fail-closed。
     */
    public static boolean hasExistingPartitions(Path configuredRawRoot) {
        Objects.requireNonNull(configuredRawRoot, "configuredRawRoot");
        Path absolute = configuredRawRoot.toAbsolutePath().normalize();
        try {
            if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return false;
            rejectLinksInExistingChain(absolute);
            Path realRoot = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path existingCatalog = realRoot.resolve("catalog.db");
            if (!Files.exists(existingCatalog, LinkOption.NOFOLLOW_LINKS)) return false;
            rejectLink(existingCatalog);
            Class.forName("org.sqlite.JDBC");
            String readOnlyUrl = "jdbc:sqlite:" + existingCatalog.toUri() + "?mode=ro";
            try (Connection readOnly = DriverManager.getConnection(readOnlyUrl);
                 Statement statement = readOnly.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT 1 FROM raw_partitions LIMIT 1")) {
                return result.next();
            }
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "无法安全检查既有原始分区 catalog: " + configuredRawRoot, failure);
        }
    }

    /** 解析 catalog/manifest 中的相对路径，并拒绝越界、绝对路径和链接。 */
    public Path resolvePartitionPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("原始分区相对路径不能为空");
        }
        Path relative;
        try {
            relative = Path.of(relativePath);
        } catch (RuntimeException invalidPath) {
            throw new IllegalArgumentException("原始分区路径无法解析", invalidPath);
        }
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("原始分区路径必须相对于 raw 根目录");
        }
        Path normalized = relative.normalize();
        if (normalized.getNameCount() == 0 || normalized.startsWith("..")) {
            throw new IllegalArgumentException("原始分区路径越过 raw 根目录");
        }
        Path resolved = rawRoot.resolve(normalized).normalize();
        if (!resolved.startsWith(rawRoot)) {
            throw new IllegalArgumentException("原始分区路径越过 raw 根目录");
        }
        try {
            rejectLinksFromRoot(resolved);
        } catch (IOException unsafePath) {
            throw new IllegalArgumentException("原始分区路径包含链接或无法验证", unsafePath);
        }
        return resolved;
    }

    public boolean hasPartitions() {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1 FROM raw_partitions LIMIT 1")) {
            return result.next();
        } catch (SQLException failure) {
            throw new IllegalStateException("无法读取原始分区 catalog", failure);
        }
    }

    public long partitionCount() {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM raw_partitions")) {
            return result.next() ? result.getLong(1) : 0;
        } catch (SQLException failure) {
            throw new IllegalStateException("无法统计原始分区 catalog", failure);
        }
    }

    public void insert(RawPartitionMetadata partition) {
        validatePartition(partition);
        resolvePartitionPath(partition.relativePath());
        String sql = """
                INSERT INTO raw_partitions (
                    partition_month, relative_path, received_start, received_end,
                    status, event_count, first_event_id, last_event_id,
                    size_bytes, file_sha256, verified_at, schema_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, partition.partitionMonth());
            statement.setString(2, Path.of(partition.relativePath()).normalize().toString()
                    .replace('\\', '/'));
            setInstant(statement, 3, partition.receivedStart());
            setInstant(statement, 4, partition.receivedEnd());
            statement.setString(5, partition.status().name());
            statement.setLong(6, partition.eventCount());
            statement.setString(7, partition.firstEventId());
            statement.setString(8, partition.lastEventId());
            statement.setLong(9, partition.sizeBytes());
            statement.setString(10, partition.fileSha256());
            setInstant(statement, 11, partition.verifiedAt());
            statement.setInt(12, partition.schemaVersion());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("无法写入原始分区 catalog", failure);
        }
    }

    public Optional<RawPartitionMetadata> find(String partitionMonth) {
        String sql = "SELECT * FROM raw_partitions WHERE partition_month = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, partitionMonth);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapPartition(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("无法读取原始分区 catalog", failure);
        }
    }

    public List<RawPartitionMetadata> listByStatus(RawPartitionStatus status) {
        String sql = "SELECT * FROM raw_partitions WHERE status = ? ORDER BY partition_month";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            try (ResultSet result = statement.executeQuery()) {
                List<RawPartitionMetadata> partitions = new ArrayList<>();
                while (result.next()) partitions.add(mapPartition(result));
                return List.copyOf(partitions);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("无法列出原始分区 catalog", failure);
        }
    }

    public List<RawPartitionMetadata> listReadable(Instant start, Instant end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        String sql = """
                SELECT * FROM raw_partitions
                WHERE status IN ('ACTIVE', 'SEALED')
                  AND (received_end IS NULL OR received_end >= ?)
                  AND (received_start IS NULL OR received_start < ?)
                ORDER BY partition_month
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, RawTimestamp.format(start));
            statement.setString(2, RawTimestamp.format(end));
            try (ResultSet result = statement.executeQuery()) {
                List<RawPartitionMetadata> partitions = new ArrayList<>();
                while (result.next()) partitions.add(mapPartition(result));
                return List.copyOf(partitions);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("无法选择原始事件查询分区", failure);
        }
    }

    public List<RawPartitionMetadata> listReadable() {
        String sql = """
                SELECT * FROM raw_partitions
                WHERE status IN ('ACTIVE', 'SEALED')
                ORDER BY partition_month
                """;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            List<RawPartitionMetadata> partitions = new ArrayList<>();
            while (result.next()) partitions.add(mapPartition(result));
            return List.copyOf(partitions);
        } catch (SQLException failure) {
            throw new IllegalStateException("无法列出可读原始分区", failure);
        }
    }

    public List<RawPartitionMetadata> listAll() {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM raw_partitions ORDER BY partition_month")) {
            List<RawPartitionMetadata> partitions = new ArrayList<>();
            while (result.next()) partitions.add(mapPartition(result));
            return List.copyOf(partitions);
        } catch (SQLException failure) {
            throw new IllegalStateException("无法列出原始分区", failure);
        }
    }

    public void updateStatus(String partitionMonth, RawPartitionStatus status) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE raw_partitions SET status = ? WHERE partition_month = ?")) {
            statement.setString(1, status.name());
            statement.setString(2, partitionMonth);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("catalog 中不存在分区: " + partitionMonth);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("无法更新原始分区状态", failure);
        }
    }

    public void markSealed(String partitionMonth, long eventCount,
                           String firstEventId, String lastEventId,
                           Instant receivedStart, Instant receivedEnd,
                           long sizeBytes, String fileSha256, Instant verifiedAt) {
        String sql = """
                UPDATE raw_partitions SET
                    status = 'SEALED', event_count = ?, first_event_id = ?,
                    last_event_id = ?, received_start = ?, received_end = ?,
                    size_bytes = ?, file_sha256 = ?, verified_at = ?
                WHERE partition_month = ? AND status = 'SEALING'
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, eventCount);
            statement.setString(2, firstEventId);
            statement.setString(3, lastEventId);
            setInstant(statement, 4, receivedStart);
            setInstant(statement, 5, receivedEnd);
            statement.setLong(6, sizeBytes);
            statement.setString(7, fileSha256);
            setInstant(statement, 8, verifiedAt);
            statement.setString(9, partitionMonth);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("分区未处于 SEALING: " + partitionMonth);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("无法完成原始分区封存", failure);
        }
    }

    public void ensureActivePartition(String partitionMonth, String relativePath,
                                      Instant receivedStart, int schemaVersion) {
        resolvePartitionPath(relativePath);
        String sql = """
                INSERT INTO raw_partitions (
                    partition_month, relative_path, received_start, status, schema_version
                ) VALUES (?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT(partition_month) DO NOTHING
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, partitionMonth);
            statement.setString(2, Path.of(relativePath).normalize().toString().replace('\\', '/'));
            statement.setString(3, RawTimestamp.format(receivedStart));
            statement.setInt(4, schemaVersion);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("无法注册活动原始分区", failure);
        }
        RawPartitionMetadata existing = find(partitionMonth).orElseThrow();
        String normalized = Path.of(relativePath).normalize().toString().replace('\\', '/');
        if (!normalized.equals(existing.relativePath())) {
            throw new IllegalStateException("catalog 分区路径与预期不一致: " + partitionMonth);
        }
    }

    public void updateStatistics(String partitionMonth, long eventCount,
                                 String firstEventId, String lastEventId,
                                 Instant receivedStart, Instant receivedEnd,
                                 long sizeBytes) {
        String sql = """
                UPDATE raw_partitions SET
                    event_count = ?, first_event_id = ?, last_event_id = ?,
                    received_start = ?, received_end = ?, size_bytes = ?
                WHERE partition_month = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, eventCount);
            statement.setString(2, firstEventId);
            statement.setString(3, lastEventId);
            setInstant(statement, 4, receivedStart);
            setInstant(statement, 5, receivedEnd);
            statement.setLong(6, sizeBytes);
            statement.setString(7, partitionMonth);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("catalog 中不存在分区: " + partitionMonth);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("无法更新原始分区统计", failure);
        }
    }

    private static RawPartitionMetadata mapPartition(ResultSet result) throws SQLException {
        return new RawPartitionMetadata(
                result.getString("partition_month"),
                result.getString("relative_path"),
                parseInstant(result.getString("received_start")),
                parseInstant(result.getString("received_end")),
                RawPartitionStatus.valueOf(result.getString("status")),
                result.getLong("event_count"),
                result.getString("first_event_id"),
                result.getString("last_event_id"),
                result.getLong("size_bytes"),
                result.getString("file_sha256"),
                parseInstant(result.getString("verified_at")),
                result.getInt("schema_version"));
    }

    private static Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
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
            statement.execute("BEGIN IMMEDIATE");
            try {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS catalog_metadata (
                            key TEXT PRIMARY KEY,
                            value TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        INSERT INTO catalog_metadata(key, value)
                        VALUES ('schema_version', '1')
                        ON CONFLICT(key) DO NOTHING
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS raw_partitions (
                            partition_month TEXT PRIMARY KEY,
                            relative_path TEXT NOT NULL UNIQUE,
                            received_start TEXT,
                            received_end TEXT,
                            status TEXT NOT NULL CHECK (
                                status IN ('ACTIVE', 'SEALING', 'SEALED', 'QUARANTINED')
                            ),
                            event_count INTEGER NOT NULL DEFAULT 0 CHECK (event_count >= 0),
                            first_event_id TEXT,
                            last_event_id TEXT,
                            size_bytes INTEGER NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
                            file_sha256 TEXT,
                            verified_at TEXT,
                            schema_version INTEGER NOT NULL CHECK (schema_version > 0)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_raw_partitions_received
                        ON raw_partitions(received_start, received_end)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_raw_partitions_status
                        ON raw_partitions(status, partition_month)
                        """);
                statement.execute("COMMIT");
            } catch (SQLException failure) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private static void validatePartition(RawPartitionMetadata partition) {
        Objects.requireNonNull(partition, "partition");
        try {
            YearMonth.parse(partition.partitionMonth());
        } catch (DateTimeParseException invalidMonth) {
            throw new IllegalArgumentException("partitionMonth 必须是 yyyy-MM", invalidMonth);
        }
        Objects.requireNonNull(partition.status(), "status");
        if (partition.eventCount() < 0 || partition.sizeBytes() < 0
                || partition.schemaVersion() <= 0) {
            throw new IllegalArgumentException("分区计数、大小和 schema 版本无效");
        }
    }

    private static void setInstant(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.VARCHAR);
        else statement.setString(index, RawTimestamp.format(value));
    }

    private static void rejectLinksInExistingChain(Path path) throws IOException {
        Path root = path.getRoot();
        Path current = root;
        for (Path part : path) {
            current = current == null ? part : current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
            rejectLink(current);
        }
    }

    private void rejectLinksFromRoot(Path path) throws IOException {
        Path relative = rawRoot.relativize(path);
        Path current = rawRoot;
        rejectLink(current);
        for (Path part : relative) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
            rejectLink(current);
        }
    }

    private static void rejectLink(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(path) || attributes.isSymbolicLink()
                || (!attributes.isDirectory() && !attributes.isRegularFile())) {
            throw new IOException("拒绝链接或特殊文件路径: " + path);
        }
        // Windows directory junctions are reparse points but are not consistently
        // reported by Files.isSymbolicLink/BasicFileAttributes. Following the path
        // resolves to its target while NOFOLLOW_LINKS retains the junction name.
        Path noFollow = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path followed = path.toRealPath();
        if (!noFollow.equals(followed)) {
            throw new IOException("拒绝链接或重解析点路径: " + path);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
