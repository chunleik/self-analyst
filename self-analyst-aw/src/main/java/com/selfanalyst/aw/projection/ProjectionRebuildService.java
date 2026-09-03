package com.selfanalyst.aw.projection;

import com.selfanalyst.aw.store.Database;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 在服务停止、活动连接关闭后旁路重建并原子替换 aw.db。 */
public final class ProjectionRebuildService {

    private final Path awDataDir;
    private final Path rawRoot;
    private final int pulseTimeSeconds;
    private final int batchSize;
    private final String projectorVersion;
    private final RebuildObserver observer;

    public ProjectionRebuildService(Path awDataDir, Path rawRoot,
                                    int pulseTimeSeconds, int batchSize,
                                    String projectorVersion) {
        this(awDataDir, rawRoot, pulseTimeSeconds, batchSize,
                projectorVersion, RebuildObserver.NOOP);
    }

    ProjectionRebuildService(Path awDataDir, Path rawRoot,
                             int pulseTimeSeconds, int batchSize,
                             String projectorVersion, RebuildObserver observer) {
        this.awDataDir = Objects.requireNonNull(awDataDir, "awDataDir").toAbsolutePath().normalize();
        this.rawRoot = Objects.requireNonNull(rawRoot, "rawRoot");
        this.pulseTimeSeconds = pulseTimeSeconds;
        this.batchSize = batchSize;
        this.projectorVersion = Objects.requireNonNull(projectorVersion, "projectorVersion");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public RebuildResult rebuild() {
        String id = UUID.randomUUID().toString();
        Path current = awDataDir.resolve("aw.db");
        Path rebuilding = awDataDir.resolve("aw.db.rebuilding-" + id);
        Path stagingDir = awDataDir.resolve(".projection-rebuilding-" + id);
        Path backup = awDataDir.resolve("aw.db.backup-" + id);
        try {
            Files.createDirectories(awDataDir);
            Files.createDirectory(stagingDir);
            int projected;
            try (Database target = new Database(stagingDir)) {
                EventProjector projector = new EventProjector(target, pulseTimeSeconds,
                        batchSize, projectorVersion);
                try (ProjectionRecoveryService recovery =
                             new ProjectionRecoveryService(rawRoot, projector)) {
                    projected = recovery.recoverPending();
                }
                verifyTarget(target.metaConnection(), projected);
            }
            moveReplacing(stagingDir.resolve("aw.db"), rebuilding);
            Files.deleteIfExists(stagingDir);
            observer.beforeSwitch(rebuilding, current);

            boolean hadCurrent = Files.exists(current);
            if (hadCurrent) Files.move(current, backup);
            try {
                moveReplacing(rebuilding, current);
            } catch (Exception switchFailure) {
                if (hadCurrent && Files.exists(backup) && !Files.exists(current)) {
                    Files.move(backup, current);
                }
                throw switchFailure;
            }
            return new RebuildResult(projected, current, hadCurrent ? backup : null,
                    Instant.now());
        } catch (Exception failure) {
            throw new IllegalStateException("ActivityWatch 投影旁路重建失败", failure);
        }
    }

    private static void verifyTarget(Connection connection, int expectedSources) throws Exception {
        try (var statement = connection.createStatement();
             var integrity = statement.executeQuery("PRAGMA integrity_check")) {
            if (!integrity.next() || !"ok".equalsIgnoreCase(integrity.getString(1))) {
                throw new IllegalStateException("重建投影 integrity_check 未通过");
            }
        }
        try (var statement = connection.createStatement();
             var count = statement.executeQuery("SELECT COUNT(*) FROM raw_projection_sources")) {
            if (!count.next() || count.getInt(1) != expectedSources) {
                throw new IllegalStateException("重建投影来源覆盖不完整");
            }
        }
        try (var statement = connection.createStatement();
             var invalid = statement.executeQuery("""
                     SELECT 1 FROM raw_projection_sources s
                     LEFT JOIN events e ON e.id = s.projection_event_id
                     WHERE e.id IS NULL LIMIT 1
                     """)) {
            if (invalid.next()) throw new IllegalStateException("重建投影来源映射悬空");
        }
    }

    private static void moveReplacing(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record RebuildResult(int projectedRawEvents, Path activeDatabase,
                                Path previousProjectionBackup, Instant completedAt) {}

    @FunctionalInterface
    interface RebuildObserver {
        RebuildObserver NOOP = (rebuilding, current) -> {};
        void beforeSwitch(Path rebuilding, Path current) throws Exception;
    }
}
