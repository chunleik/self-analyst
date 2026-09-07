package com.selfanalyst.events;

import com.selfanalyst.events.controller.*;
import com.selfanalyst.events.log.ServerLog;
import com.selfanalyst.events.settings.SettingsManager;
import com.selfanalyst.events.projection.EventProjector;
import com.selfanalyst.events.projection.EventIngestionService;
import com.selfanalyst.events.projection.HeartbeatIngestionService;
import com.selfanalyst.events.projection.RawEventProjector;
import com.selfanalyst.events.projection.ProjectionRecoveryService;
import com.selfanalyst.events.raw.RawEventStore;
import com.selfanalyst.events.raw.RawEventQueryService;
import com.selfanalyst.events.raw.RawStorageStatusService;
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
    private final RawEventStore rawEventStore;
    private final RawEventQueryService rawEventQueries;
    private final RawStorageStatusService rawStatus;
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
        this(dataDir, rawDir, port, System.getenv("SELF_ANALYST_DESKTOP_TOKEN"),
                null, queryMaxRangeDays, queryMaxPageSize,
                lowDiskWarnBytes, lowDiskBlockBytes, projectorBatchSize);
    }

    EventServer(Path dataDir, int port, String desktopToken) {
        this(dataDir, port, desktopToken, null);
    }

    EventServer(Path dataDir, int port, String desktopToken,
             RawEventProjector projectorOverride) {
        this(dataDir, dataDir.resolve("raw"), port, desktopToken, projectorOverride,
                31, 1000, 10_737_418_240L, 1_073_741_824L, 1000);
    }

    private EventServer(Path dataDir, Path rawDir, int port, String desktopToken,
                     RawEventProjector projectorOverride,
                     int queryMaxRangeDays, int queryMaxPageSize,
                     long lowDiskWarnBytes, long lowDiskBlockBytes,
                     int projectorBatchSize) {
        this.port = port;
        this.desktopToken = desktopToken;
        PulseTimeConfig pulseConfig = PulseTimeConfig.DEFAULT;
        this.rawEventStore = new RawEventStore(rawDir);
        this.rawEventQueries = new RawEventQueryService(
                rawDir, queryMaxRangeDays, queryMaxPageSize);
        this.rawStatus = new RawStorageStatusService(rawDir,
                dataDir.resolve(Database.PROJECTION_FILENAME), lowDiskWarnBytes, lowDiskBlockBytes);
        this.db = new Database(dataDir);
        this.eventStore = new EventStore(db, pulseConfig);
        this.bucketStore = new BucketStore(db);
        this.settings = new SettingsManager(dataDir.resolve("settings.json"));
        this.serverLog = new ServerLog(500);

        InfoController infoCtrl = new InfoController();
        LogController logCtrl = new LogController(serverLog);
        BucketController bucketCtrl = new BucketController(bucketStore, eventStore);
        RawEventProjector projector = projectorOverride != null
                ? projectorOverride
                : new EventProjector(db, pulseConfig.pulsetime(), projectorBatchSize, "v1");
        boolean recovered = true;
        if (projector instanceof EventProjector eventProjector) {
            try (ProjectionRecoveryService recovery = new ProjectionRecoveryService(
                    rawDir, eventProjector)) {
                recovery.recoverPending();
            } catch (Exception recoveryFailure) {
                recovered = false;
            }
        }
        this.projectionReady = recovered;
        EventController eventCtrl = new EventController(eventStore, bucketStore,
                new EventIngestionService(rawEventStore, projector));
        HeartbeatController heartbeatCtrl = new HeartbeatController(eventStore, bucketStore,
                new HeartbeatIngestionService(rawEventStore, projector));
        QueryController queryCtrl = new QueryController(eventStore, bucketStore);
        ExportController exportCtrl = new ExportController(
                bucketStore, eventStore, rawEventStore, projector);
        SettingsController settingsCtrl = new SettingsController(settings);
        RawEventController rawEventCtrl = new RawEventController(rawEventQueries);

        this.app = Javalin.create(cfg -> {
            cfg.http.defaultContentType = "application/json";
        });
        app.get("/desktop/raw-events", rawEventCtrl::query);

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
            serverLog.error("Failed to close ActivityWatch database: " + error.getMessage());
        }
        try {
            rawEventStore.close();
        } catch (Exception error) {
            serverLog.error("Failed to close raw event store: " + error.getClass().getSimpleName());
        }
        try {
            rawEventQueries.close();
        } catch (Exception error) {
            serverLog.error("Failed to close raw event queries: " + error.getClass().getSimpleName());
        }
        try {
            rawStatus.close();
        } catch (Exception error) {
            serverLog.error("Failed to close raw status: " + error.getClass().getSimpleName());
        }
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

    public RawEventStore rawEventStore() {
        return rawEventStore;
    }

    public Map<String, Object> rawStatus() {
        return rawStatus.snapshot();
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
