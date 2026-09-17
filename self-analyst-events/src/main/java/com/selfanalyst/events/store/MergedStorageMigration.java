package com.selfanalyst.events.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.events.projection.EventProjector;
import com.selfanalyst.events.raw.RawEventStore;
import com.selfanalyst.events.raw.RawPartitionCatalog;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 独占锁覆盖迁移和服务生命周期；源库仅在目标验证后切换。 */
public final class MergedStorageMigration implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root;
    private final Path raw;
    private final Path journal;
    private final FileChannel channel;
    private final FileLock lock;
    private final java.util.function.Consumer<String> observer;
    private final SpaceProbe spaceProbe;

    @FunctionalInterface interface SpaceProbe { long usable(Path path) throws Exception; }

    public MergedStorageMigration(Path directory, Path rawDirectory) {
        this(directory, rawDirectory, step -> {});
    }

    public MergedStorageMigration(Path directory, Path rawDirectory, Runnable beforeSwitch) {
        this(directory, rawDirectory, step -> { if (step.equals("before-switch")) beforeSwitch.run(); });
    }

    MergedStorageMigration(Path directory, Path rawDirectory, java.util.function.Consumer<String> observer) {
        this(directory, rawDirectory, observer, path -> Files.getFileStore(path).getUsableSpace());
    }

    MergedStorageMigration(Path directory, Path rawDirectory, java.util.function.Consumer<String> observer, SpaceProbe spaceProbe) {
        this.observer = observer;
        this.spaceProbe = spaceProbe;
        root = directory.toAbsolutePath().normalize();
        raw = rawDirectory.toAbsolutePath().normalize();
        journal = root.resolve("merged-migration.json");
        FileChannel opened = null;
        FileLock acquired = null;
        try {
            safe(root); safe(raw);
            if (root.startsWith(raw)) throw new IllegalArgumentException("Raw and event directories overlap");
            Files.createDirectories(root);
            opened = FileChannel.open(root.resolve("events.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            acquired = opened.tryLock();
            if (acquired == null) throw new IllegalStateException("Event data is in use");
            channel = opened; lock = acquired;
            migrate();
        } catch (Exception error) {
            try { if (acquired != null) acquired.release(); if (opened != null) opened.close(); }
            catch (Exception ignored) { }
            String detail = error instanceof com.selfanalyst.events.raw.RawStartupIntegrityException
                    ? error.getMessage() : error.getClass().getSimpleName();
            throw new IllegalStateException("合并存储迁移失败；源数据已保留，请检查格式、空间及完整性（" + detail + "）");
        }
    }

    private void migrate() throws Exception {
        Path current = root.resolve(Database.PROJECTION_FILENAME);
        if (Files.exists(journal)) {
            var state = readState();
            if ("prepared".equals(state.get("phase"))) { finish(state); return; }
            if ("complete".equals(state.get("phase"))) {
                if (!Files.exists(current) || !isMerged(current)) throw new IllegalStateException("Current event database missing or invalid");
                integrity(current); return;
            }
            if (!"copying".equals(state.get("phase"))) throw new IllegalStateException("Unknown migration phase");
            // 复制阶段中断未改源库，保留失败副本并重新构建独立目标。
        }
        if (Files.exists(current) && isMerged(current)) { integrity(current); return; }
        if (Files.exists(current)) { integrity(current); validateLegacy(current); }
        long required = (Files.exists(current) ? Files.size(current) * 3 : 0) + treeSize(raw) * 2 + 64L * 1024 * 1024;
        if (spaceProbe.usable(root) < required) throw new IllegalStateException("Insufficient migration space");
        String id = UUID.randomUUID().toString();
        Path home = root.resolve("merged-migration-" + id);
        Files.createDirectory(home);
        Path backup = home.resolve("backup"); Files.createDirectory(backup);
        Path work = home.resolve("work"); Files.createDirectory(work);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("version", 1); state.put("phase", "copying"); state.put("id", id);
        state.put("rawRoot", raw.toString()); state.put("createdAt", Instant.now().toString());
        writeState(state);
        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.exists(current)) {
            snapshot(current, backup.resolve("events.db"));
            Files.copy(backup.resolve("events.db"), work.resolve("events.db"));
            registerTree(backup, files);
        }
        if (Files.exists(raw)) {
            copyTree(raw, backup.resolve("raw"));
            // 仅工作副本允许 raw catalog 完整性记录发生变化。
            copyTree(backup.resolve("raw"), home.resolve("raw-work"));
            registerTree(raw, files);
            registerTree(backup.resolve("raw"), files);
        }
        observer.accept("copied");
        try (Database target = new Database(work)) {
            try (var receipts = new MergedEventStore(target, PulseTimeConfig.DEFAULT.pulsetime())) { }
            if (Files.exists(home.resolve("raw-work/catalog.db"))) {
                try (RawEventStore legacy = new RawEventStore(home.resolve("raw-work"))) {
                    legacy.verifyOnStartup(true);
                }
                reconcile(target, home.resolve("raw-work"));
            } else {
                try (var s = target.metaConnection().createStatement();
                     var rows = s.executeQuery("SELECT count(*) FROM raw_projection_sources")) {
                    if (rows.next() && rows.getLong(1) > 0) throw new IllegalStateException("Raw partitions missing");
                }
            }
            String expected = digestEvents(target.metaConnection());
            validatePersistedPolicy(target.metaConnection());
            try (Statement s = target.metaConnection().createStatement()) {
                s.execute("DROP TABLE raw_projection_sources");
                s.execute("DROP TABLE projection_event_coverage");
                s.execute("DROP TABLE raw_projection_checkpoints");
                s.execute("CREATE TABLE merged_storage(version INTEGER NOT NULL CHECK(version=2))");
                s.execute("INSERT INTO merged_storage VALUES(2)");
                s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                s.execute("VACUUM");
            }
            if (!expected.equals(digestEvents(target.metaConnection()))) throw new IllegalStateException("Event verification failed");
            state.put("eventDigest", expected);
        }
        integrity(work.resolve("events.db"));
        observer.accept("validated");
        state.put("targetSha256", RawEventStore.fileSha256(work.resolve("events.db")));
        registerTree(home.resolve("raw-work"), files, true);
        state.put("files", files); state.put("phase", "prepared"); writeState(state);
        observer.accept("prepared");
        finish(state);
    }

    private void reconcile(Database target, Path rawWork) throws Exception {
        EventProjector projector = new EventProjector(target, PulseTimeConfig.DEFAULT.pulsetime(), 1000, "migration-v2");
        try (RawPartitionCatalog catalog = new RawPartitionCatalog(rawWork)) {
            for (var partition : catalog.listAll()) {
                String checkpointTime = null, checkpointId = null;
                try (var s = target.metaConnection().prepareStatement("SELECT received_at,event_id FROM raw_projection_checkpoints WHERE partition_month=?")) {
                    s.setString(1, partition.partitionMonth());
                    try (var r = s.executeQuery()) { if (r.next()) { checkpointTime = r.getString(1); checkpointId = r.getString(2); } }
                }
                try (Connection source = readOnly(catalog.resolvePartitionPath(partition.relativePath()));
                     Statement s = source.createStatement();
                     ResultSet rows = s.executeQuery("SELECT * FROM raw_events ORDER BY received_at,event_id")) {
                    while (rows.next()) {
                        var event = RawEventStore.mapEvent(rows);
                        boolean known; long projectionId = 0;
                        try (var lookup = target.metaConnection().prepareStatement("SELECT projection_event_id FROM raw_projection_sources WHERE raw_event_id=?")) {
                            lookup.setString(1, event.eventId());
                            try (var r = lookup.executeQuery()) { known = r.next(); if (known) projectionId = r.getLong(1); }
                        }
                        if (known) { migrateReceipt(target.metaConnection(), event, projectionId); continue; }
                        boolean pastCheckpoint = checkpointTime == null || event.receivedAt().isAfter(Instant.parse(checkpointTime))
                                || (event.receivedAt().equals(Instant.parse(checkpointTime)) && event.eventId().compareTo(checkpointId) > 0);
                        if (!pastCheckpoint || new BucketStore(target).get(event.bucketId()).isEmpty())
                            throw new IllegalStateException("Cannot distinguish deleted history from pending submissions");
                        ContentEventPolicy.validate(event.bucketId(), new BucketStore(target).get(event.bucketId()).orElseThrow().client(),
                                JSON.readValue(event.canonicalDataJson(), new TypeReference<Map<String, Object>>() {}));
                        var projected = projector.project(event);
                        migrateReceipt(target.metaConnection(), event, projected.projectionEventId());
                    }
                }
            }
        }
    }

    private static void migrateReceipt(Connection c, com.selfanalyst.events.raw.RawEvent raw, long eventId) throws Exception {
        if (raw.receivedAt().isBefore(Instant.now().minus(MergedEventStore.RECEIPT_RETENTION))) return;
        String identity = raw.importSessionId() != null ? "import:" + raw.importSessionId() + ":" + raw.importOrdinal() : raw.sourceEventId();
        if (identity == null) return;
        var event = new com.selfanalyst.events.model.Event(raw.eventTimestamp(), raw.duration(),
                JSON.readValue(raw.canonicalDataJson(), new TypeReference<Map<String, Object>>() {}));
        try (var s = c.prepareStatement("INSERT OR IGNORE INTO event_receipts VALUES(?,?,?,?,?)")) {
            s.setString(1, raw.bucketId()); s.setString(2, identity);
            s.setBytes(3, MergedEventStore.fingerprint(event, raw.ingestKind() == com.selfanalyst.events.raw.RawIngestKind.HEARTBEAT, null));
            s.setLong(4, eventId); s.setLong(5, raw.receivedAt().toEpochMilli()); s.executeUpdate();
        }
    }

    private void finish(Map<String, Object> state) throws Exception {
        Path home = migrationHome(state);
        Path target = home.resolve("work/events.db");
        Path current = root.resolve("events.db");
        if (Files.exists(target)) {
            if (!Objects.equals(state.get("targetSha256"), RawEventStore.fileSha256(target)))
                throw new IllegalStateException("Prepared database changed");
            observer.accept("before-switch");
            // 旧 WAL 已包含在一致备份中；独占锁期间没有在线连接。
            Files.deleteIfExists(root.resolve("events.db-wal"));
            Files.deleteIfExists(root.resolve("events.db-shm"));
            Files.move(target, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else if (!Files.exists(current) || !Objects.equals(state.get("targetSha256"), RawEventStore.fileSha256(current))) {
            throw new IllegalStateException("Interrupted switch cannot be verified");
        }
        integrity(current);
        observer.accept("switched");
        state.put("phase", "complete"); writeState(state);
    }

    public synchronized Map<String, Object> backupStatus() {
        try {
            if (!Files.exists(journal)) return Map.of("migration", "not-required", "backupBytes", 0);
            var state = readState(); long bytes = 0, staging = 0;
            for (var entry : entries(state)) {
                Path p = registeredPath(state, entry);
                if (Files.exists(p)) {
                    if (Boolean.TRUE.equals(entry.get("staging"))) staging += Files.size(p);
                    else bytes += Files.size(p);
                }
            }
            try (var homes = Files.list(root)) {
                for (Path p : homes.filter(Files::isDirectory).toList()) {
                    if (p.getFileName().toString().matches("merged-migration-[a-f0-9-]{36}") && !p.equals(migrationHome(state))) staging += treeSize(p);
                }
            }
            return Map.of("migration", state.get("phase"), "migrationId", state.get("id"), "backupBytes", bytes, "stagingBytes", staging);
        } catch (Exception e) { return Map.of("migration", "failed", "backupBytes", 0); }
    }

    public synchronized Map<String, Object> cleanBackups(String confirmation) {
        try {
            var state = readState();
            if (!"complete".equals(state.get("phase")) || !Objects.equals(confirmation, state.get("id")))
                throw new IllegalArgumentException("Migration confirmation required");
            integrity(root.resolve("events.db"));
            for (var entry : entries(state)) {
                Path p = registeredPath(state, entry);
                if (Files.exists(p) && !Objects.equals(entry.get("sha256"), RawEventStore.fileSha256(p)))
                    throw new IllegalStateException("Backup changed; cleanup refused");
            }
            for (var entry : entries(state)) Files.deleteIfExists(registeredPath(state, entry));
            return backupStatus();
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Backup cleanup incomplete; active data preserved", e); }
    }

    private Path registeredPath(Map<String, Object> state, Map<String, Object> entry) throws Exception {
        Path p = Path.of((String) entry.get("path")).toAbsolutePath().normalize();
        Path registeredRaw = Path.of((String) state.get("rawRoot")).toAbsolutePath().normalize();
        if (root.startsWith(registeredRaw) || !(p.startsWith(migrationHome(state)) || p.startsWith(registeredRaw))
                || p.equals(root.resolve("events.db")) || p.equals(journal)) throw new IllegalStateException("Unsafe backup path");
        safe(p); return p;
    }

    private Path migrationHome(Map<String, Object> state) {
        String id = (String) state.get("id"); UUID.fromString(id);
        return root.resolve("merged-migration-" + id);
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> entries(Map<String, Object> state) {
        return (List<Map<String, Object>>) state.getOrDefault("files", List.of());
    }
    private Map<String, Object> readState() throws Exception {
        safe(journal);
        if (Files.size(journal) > 16 * 1024 * 1024) throw new IllegalStateException("Migration journal too large");
        Map<String, Object> state = JSON.readValue(Files.readString(journal), new TypeReference<>() {});
        if (!Objects.equals(state.get("version"), 1)
                || (!"complete".equals(state.get("phase")) && !raw.toString().equals(state.get("rawRoot"))))
            throw new IllegalStateException("Migration journal incompatible");
        migrationHome(state);
        return state;
    }
    private void writeState(Map<String, Object> state) throws Exception {
        Path temp = journal.resolveSibling("merged-migration.json.tmp"); safe(temp);
        byte[] bytes = JSON.writeValueAsBytes(state);
        try (var out = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer); out.force(true);
        }
        Files.move(temp, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    static void safe(Path path) throws Exception {
        for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(p)
                    || Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()))
                throw new IllegalStateException("Linked storage path refused");
        }
    }
    private static Connection readOnly(Path path) throws Exception {
        safe(path); return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath().toUri() + "?mode=ro");
    }
    private static void snapshot(Path source, Path target) throws Exception {
        try (var c = readOnly(source); var s = c.createStatement()) {
            s.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
        }
    }
    private static boolean isMerged(Path path) throws Exception {
        try (var c = readOnly(path); var s = c.createStatement(); var r = s.executeQuery("SELECT name FROM sqlite_master WHERE name='merged_storage'")) {
            if (!r.next()) return false;
            try (var check = c.createStatement(); var version = check.executeQuery("SELECT version FROM merged_storage")) {
                if (!version.next() || version.getInt(1) != 2 || version.next()) throw new IllegalStateException("Unsupported event storage version");
            }
            requireColumns(c, "events", Set.of("id", "bucket_id", "timestamp", "duration", "datastr", "app"));
            requireColumns(c, "buckets", Set.of("id", "name", "type", "client", "hostname", "created", "last_updated"));
            requireColumns(c, "event_receipts", Set.of("bucket_id", "identity", "fingerprint", "event_id", "received_at"));
            return true;
        }
    }
    private static void requireColumns(Connection connection, String table, Set<String> expected) throws Exception {
        try (var s = connection.createStatement(); var columns = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            Set<String> actual = new HashSet<>(); while (columns.next()) actual.add(columns.getString("name"));
            if (!actual.equals(expected)) throw new IllegalStateException("Event schema missing or incompatible");
        }
    }
    private static void integrity(Path path) throws Exception {
        try (var c = readOnly(path); var s = c.createStatement(); var r = s.executeQuery("PRAGMA integrity_check")) {
            if (!r.next() || !"ok".equals(r.getString(1)) || r.next()) throw new IllegalStateException("Event database corrupt");
        }
    }
    private static void validateLegacy(Path path) throws Exception {
        try (var c = readOnly(path); var s = c.createStatement(); var rows = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            Set<String> names = new HashSet<>(); while (rows.next()) names.add(rows.getString(1));
            Set<String> required = Set.of("events", "buckets", "schema_migrations", "raw_projection_sources", "projection_event_coverage", "raw_projection_checkpoints");
            Set<String> allowed = new HashSet<>(required); allowed.addAll(Set.of("sqlite_sequence", "content_event_migration_state", "event_receipts"));
            if (!names.containsAll(required) || !allowed.containsAll(names)) throw new IllegalStateException("Unknown legacy event schema");
        }
    }
    private static void validatePersistedPolicy(Connection c) throws Exception {
        try (var s = c.createStatement(); var rows = s.executeQuery("SELECT e.bucket_id,e.datastr,b.client FROM events e LEFT JOIN buckets b ON b.id=e.bucket_id")) {
            while (rows.next()) ContentEventPolicy.validate(rows.getString(1), rows.getString(3),
                    JSON.readValue(rows.getString(2), new TypeReference<Map<String, Object>>() {}));
        }
    }
    private static String digestEvents(Connection c) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id,bucket_id,timestamp,duration,datastr FROM events ORDER BY id")) {
            while (r.next()) for (int i = 1; i <= 5; i++) {
                byte[] bytes = r.getString(i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static long treeSize(Path path) throws Exception {
        if (!Files.exists(path)) return 0;
        long sum = 0; try (var paths = Files.walk(path)) {
            for (Path p : paths.toList()) { safe(p); if (Files.isRegularFile(p)) sum += Files.size(p); }
        } return sum;
    }
    private static void copyTree(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path p : paths.toList()) {
                safe(p); Path relative = source.relativize(p);
                if (Files.isRegularFile(p) && !relative.toString().replace('\\', '/').matches(
                        "catalog\\.db(?:-wal|-shm)?|[0-9]{4}/raw-events-[0-9]{4}-[0-9]{2}\\.(?:db(?:-wal|-shm)?|manifest\\.json)"))
                    throw new IllegalStateException("Unrecognized raw storage file; migration refused");
                Path dest = target.resolve(relative);
                if (Files.isDirectory(p)) Files.createDirectories(dest); else Files.copy(p, dest);
            }
        }
    }
    private static void registerTree(Path root, List<Map<String, Object>> files) throws Exception {
        registerTree(root, files, false);
    }
    private static void registerTree(Path root, List<Map<String, Object>> files, boolean staging) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                safe(p); files.add(Map.of("path", p.toAbsolutePath().toString(), "sha256", RawEventStore.fileSha256(p), "staging", staging));
            }
        }
    }
    @Override public void close() throws java.io.IOException { try { lock.release(); } finally { channel.close(); } }
}
