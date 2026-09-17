package com.selfanalyst.events;

import com.selfanalyst.events.controller.*;
import com.selfanalyst.events.log.ServerLog;
import com.selfanalyst.events.settings.SettingsManager;
import com.selfanalyst.events.projection.RawEventProjector;
import com.selfanalyst.events.store.*;
import com.selfanalyst.events.webui.WebUiHandler;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class EventServer {
    private final Javalin app;
    private final Database db;
    private final BucketStore bucketStore;
    private final EventStore eventStore;
    private final MergedStorageMigration migration;
    private final MergedEventStore mergedStore;
    private final Path dataDirectory;
    private final com.selfanalyst.events.raw.RawDiskSpaceMonitor diskMonitor;
    private long previousSize;
    private long previousSampleNanos;
    private final SettingsManager settings;
    private final ServerLog serverLog;
    private volatile int port;
    private final String desktopToken;
    private final boolean projectionReady;
    private final AtomicBoolean stopped = new AtomicBoolean();

    public EventServer(Path dataDir, int port) {
        this(dataDir, port, System.getenv("SELF_ANALYST_DESKTOP_TOKEN"));
    }

    public EventServer(Path dataDir, Path rawDir, int port,
                    int queryMaxRangeDays, int queryMaxPageSize,
                    long lowDiskWarnBytes, long lowDiskBlockBytes,
                    int projectorBatchSize) {
        this(dataDir, rawDir, port, queryMaxRangeDays, queryMaxPageSize,
                lowDiskWarnBytes, lowDiskBlockBytes, projectorBatchSize, false);
    }

    public EventServer(Path dataDir, Path rawDir, int port,
                       int queryMaxRangeDays, int queryMaxPageSize,
                       long lowDiskWarnBytes, long lowDiskBlockBytes,
                       int projectorBatchSize, boolean verifyAllOnStartup) {
        this(dataDir, rawDir, port, System.getenv("SELF_ANALYST_DESKTOP_TOKEN"),
                null, queryMaxRangeDays, queryMaxPageSize,
                lowDiskWarnBytes, lowDiskBlockBytes, projectorBatchSize, verifyAllOnStartup);
    }

    EventServer(Path dataDir, int port, String desktopToken) {
        this(dataDir, port, desktopToken, null);
    }

    EventServer(Path dataDir, int port, String desktopToken,
             RawEventProjector projectorOverride) {
        this(dataDir, dataDir.resolve("raw"), port, desktopToken, projectorOverride,
                31, 1000, 10_737_418_240L, 1_073_741_824L, 1000, false);
    }

    private EventServer(Path dataDir, Path rawDir, int port, String desktopToken,
                     RawEventProjector projectorOverride,
                     int queryMaxRangeDays, int queryMaxPageSize,
                     long lowDiskWarnBytes, long lowDiskBlockBytes,
                     int projectorBatchSize, boolean verifyAllOnStartup) {
        this.port = port;
        this.desktopToken = desktopToken;
        if (lowDiskBlockBytes <= 0 || lowDiskWarnBytes <= lowDiskBlockBytes)
            throw new IllegalArgumentException("Invalid disk thresholds");
        PulseTimeConfig pulseConfig = PulseTimeConfig.DEFAULT;
        this.dataDirectory = dataDir;
        this.migration = new MergedStorageMigration(dataDir, rawDir);
        this.db = new Database(dataDir);
        this.eventStore = new EventStore(db, pulseConfig);
        this.mergedStore = new MergedEventStore(db, pulseConfig.pulsetime());
        this.diskMonitor = new com.selfanalyst.events.raw.RawDiskSpaceMonitor(dataDir, lowDiskWarnBytes, lowDiskBlockBytes);
        mergedStore.setWritableCheck(diskMonitor::requireWritable);
        this.bucketStore = new BucketStore(db);
        this.settings = new SettingsManager(dataDir.resolve("settings.json"));
        this.serverLog = new ServerLog(500);

        InfoController infoCtrl = new InfoController();
        LogController logCtrl = new LogController(serverLog);
        BucketController bucketCtrl = new BucketController(bucketStore, eventStore);
        this.projectionReady = true;
        EventController eventCtrl = new EventController(eventStore, bucketStore, mergedStore);
        HeartbeatController heartbeatCtrl = new HeartbeatController(eventStore, bucketStore, mergedStore);
        QueryController queryCtrl = new QueryController(eventStore, bucketStore);
        ExportController exportCtrl = new ExportController(bucketStore, eventStore, mergedStore);
        SettingsController settingsCtrl = new SettingsController(settings);


        this.app = Javalin.create(cfg -> {
            cfg.http.defaultContentType = "application/json";
        });
        app.get("/desktop/raw-events", ctx -> ctx.status(410).json(Map.of(
                "error", "RAW_STORAGE_RETIRED", "message", "请使用合并事件查询或导出")));
        app.post("/desktop/raw-rebuild", ctx -> ctx.status(410).json(Map.of(
                "error", "RAW_STORAGE_RETIRED", "message", "合并事件库需要从有效备份恢复")));
        app.get("/desktop/storage/backups", ctx -> ctx.json(migration.backupStatus()));
        app.get("/desktop/storage/status", ctx -> ctx.json(rawStatus()));
        app.post("/desktop/storage/backups/cleanup", ctx -> {
            try {
                var body = ctx.bodyAsClass(Map.class);
                ctx.json(migration.cleanBackups((String) body.get("migrationId")));
            } catch (IllegalArgumentException e) { ctx.status(400).json(Map.of("error", "请确认迁移备份清理范围")); }
            catch (Exception e) { ctx.status(409).json(Map.of("error", "备份清理未完成，活动数据已保留")); }
        });

        // Enforce the loopback trust boundary before any API route can mutate state.
        this.app.before(ctx -> {
            if (!LocalRequestGuard.isAllowedHost(ctx.header("Host"), this.port)) {
                ctx.status(403).json(Map.of("error", "Invalid Host header"));
                ctx.skipRemainingHandlers();
                return;
            }

            String origin = ctx.header("Origin");
            if (origin != null && !LocalRequestGuard.isAllowedOrigin(origin)) {
                ctx.status(403).json(Map.of("error", "Origin is not allowed"));
                ctx.skipRemainingHandlers();
                return;
            }
            if (origin != null) {
                ctx.header("Access-Control-Allow-Origin", origin);
                ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-SelfAnalyst-Token");
                ctx.header("Vary", "Origin");
            }
            if (LocalRequestGuard.isSensitiveRawPath(ctx.path())
                    && !LocalRequestGuard.hasConfiguredDesktopToken(desktopToken)) {
                ctx.status(503).json(Map.of("error", "Raw event capability unavailable"));
                ctx.skipRemainingHandlers();
                return;
            }
            if (LocalRequestGuard.isProtectedDesktopPath(ctx.path())
                    && !LocalRequestGuard.hasDesktopCredential(
                            desktopToken, ctx.header("X-SelfAnalyst-Token"), ctx.cookie("self_analyst_session"))) {
                ctx.status(401).json(Map.of("error", "Desktop authentication required"));
                ctx.skipRemainingHandlers();
                return;
            }
            if (ctx.method() == HandlerType.OPTIONS) {
                ctx.status(204).result("");
                ctx.skipRemainingHandlers();
            }
            if (LocalRequestGuard.isProtectedDesktopPath(ctx.path())
                    && LocalRequestGuard.hasConfiguredDesktopToken(desktopToken)) {
                ctx.attribute("selfanalyst.managedDesktop", true);
            }
        });

        // aw-client (JS lib) sends requests to /0/... without the /api prefix.
        // Register all API routes at both /api/0 and /0.
        for (String prefix : new String[]{"/api/0", "/0"}) {
            app.get(prefix + "/info", infoCtrl::handle);
            app.get(prefix + "/log", logCtrl::handle);
            app.get(prefix + "/buckets/", bucketCtrl::list);
            app.post(prefix + "/buckets/{id}", bucketCtrl::create);
            app.get(prefix + "/buckets/{id}", bucketCtrl::get);
            app.delete(prefix + "/buckets/{id}", bucketCtrl::delete);
            app.get(prefix + "/buckets/{id}/events", eventCtrl::query);
            app.post(prefix + "/buckets/{id}/events", eventCtrl::insert);
            app.post(prefix + "/buckets/{id}/heartbeat", heartbeatCtrl::handle);
            app.post(prefix + "/query/", queryCtrl::handle);
            app.get(prefix + "/export", exportCtrl::exportAll);
            app.post(prefix + "/import", exportCtrl::importAll);
            app.get(prefix + "/buckets/{id}/export", exportCtrl::exportBucket);
            app.get(prefix + "/settings", settingsCtrl::handle);
        }

        serverLog.info("EventServer initialized with " + bucketStore.listAll().size() + " buckets");
    }

    /** Register web UI routes — call AFTER desktop API registration. */
    public void registerWebUi() {
        WebUiHandler webUi = new WebUiHandler();
        app.get("/", ctx -> ctx.redirect("/index.html"));
        app.get("/<path>", webUi::handleStatic);
    }

    public void start() {
        app.start("127.0.0.1", port);
        port = app.port();
    }

    public void start(int port) {
        app.start("127.0.0.1", port);
        this.port = app.port();
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        app.stop();
        try {
            db.close();
        } catch (Exception error) {
            serverLog.error("Failed to close event service database: " + error.getMessage());
        }
        try { mergedStore.close(); } catch (Exception ignored) { }
        try { migration.close(); } catch (Exception ignored) { }

    }

    public Database db() {
        return db;
    }

    public BucketStore bucketStore() {
        return bucketStore;
    }

    public EventStore eventStore() {
        return eventStore;
    }

    public synchronized Map<String, Object> rawStatus() {
        Map<String, Object> status = new java.util.LinkedHashMap<>(migration.backupStatus());
        status.put("mode", "merged"); status.put("status", "running");
        status.put("databaseHealth", mergedStore.writeFailed() ? "write_failed" : "verified_at_startup");
        if (mergedStore.writeFailed() || "failed".equals(status.get("migration"))) status.put("status", "degraded");
        try {
            Path file = dataDirectory.resolve("events.db");
            status.put("activeBytes", java.nio.file.Files.size(file));
            long auxiliary = 0;
            for (String suffix : new String[]{"-wal", "-shm"}) {
                Path p = dataDirectory.resolve("events.db" + suffix);
                if (java.nio.file.Files.exists(p)) auxiliary += java.nio.file.Files.size(p);
            }
            status.put("auxiliaryBytes", auxiliary);
            status.put("usableBytes", java.nio.file.Files.getFileStore(dataDirectory).getUsableSpace());
            var disk = diskMonitor.sample();
            status.put("diskWarning", disk != com.selfanalyst.events.raw.RawDiskSpaceMonitor.State.NORMAL);
            if (disk == com.selfanalyst.events.raw.RawDiskSpaceMonitor.State.BLOCKED) status.put("status", "blocked");
            long size = java.nio.file.Files.size(file) + auxiliary;
            long now = System.nanoTime();
            status.put("growthBytesPerSecond", previousSampleNanos == 0 ? 0.0
                    : (size - previousSize) / Math.max(0.001, (now - previousSampleNanos) / 1_000_000_000.0));
            previousSampleNanos = now; previousSize = size;
        } catch (Exception e) { status.put("status", "failed"); }
        return status;
    }

    public boolean projectionReady() {
        return projectionReady;
    }

    public SettingsManager settings() {
        return settings;
    }

    public ServerLog serverLog() {
        return serverLog;
    }

    /** Actual listening port; resolves an ephemeral port requested with {@code 0}. */
    public int port() {
        return port;
    }

    /** The Javalin instance for registering additional routes (e.g. desktop API). */
    public Javalin app() {
        return app;
    }

    public static EventServer createAndStart(Path dataDir, int port) {
        EventServer server = new EventServer(dataDir, port);
        server.start(port);
        return server;
    }
}
