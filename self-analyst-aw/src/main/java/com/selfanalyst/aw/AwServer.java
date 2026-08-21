package com.selfanalyst.aw;

import com.selfanalyst.aw.controller.*;
import com.selfanalyst.aw.log.ServerLog;
import com.selfanalyst.aw.settings.SettingsManager;
import com.selfanalyst.aw.store.*;
import com.selfanalyst.aw.webui.WebUiHandler;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class AwServer {
    private final Javalin app;
    private final Database db;
    private final BucketStore bucketStore;
    private final EventStore eventStore;
    private final SettingsManager settings;
    private final ServerLog serverLog;
    private final int port;
    private final String desktopToken;
    private final AtomicBoolean stopped = new AtomicBoolean();

    public AwServer(Path dataDir, int port) {
        this(dataDir, port, System.getenv("SELF_ANALYST_DESKTOP_TOKEN"));
    }

    AwServer(Path dataDir, int port, String desktopToken) {
        this.port = port;
        this.desktopToken = desktopToken;
        this.db = new Database(dataDir);
        PulseTimeConfig pulseConfig = PulseTimeConfig.DEFAULT;
        this.eventStore = new EventStore(db, pulseConfig);
        this.bucketStore = new BucketStore(db);
        this.settings = new SettingsManager(dataDir.resolve("settings.json"));
        this.serverLog = new ServerLog(500);

        InfoController infoCtrl = new InfoController();
        LogController logCtrl = new LogController(serverLog);
        BucketController bucketCtrl = new BucketController(bucketStore, eventStore);
        EventController eventCtrl = new EventController(eventStore, bucketStore);
        HeartbeatController heartbeatCtrl = new HeartbeatController(eventStore, bucketStore);
        QueryController queryCtrl = new QueryController(eventStore, bucketStore);
        ExportController exportCtrl = new ExportController(bucketStore, eventStore);
        SettingsController settingsCtrl = new SettingsController(settings);

        this.app = Javalin.create(cfg -> {
            cfg.http.defaultContentType = "application/json";
        });

        // Enforce the loopback trust boundary before any API route can mutate state.
        this.app.before(ctx -> {
            if (!LocalRequestGuard.isAllowedHost(ctx.header("Host"), port)) {
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

        serverLog.info("AwServer initialized with " + bucketStore.listAll().size() + " buckets");
    }

    /** Register web UI routes — call AFTER desktop API registration. */
    public void registerWebUi() {
        WebUiHandler webUi = new WebUiHandler();
        app.get("/", ctx -> ctx.redirect("/index.html"));
        app.get("/<path>", webUi::handleStatic);
    }

    public void start() {
        app.start("127.0.0.1", port);
    }

    public void start(int port) {
        app.start("127.0.0.1", port);
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

    public SettingsManager settings() {
        return settings;
    }

    public ServerLog serverLog() {
        return serverLog;
    }

    /** The Javalin instance for registering additional routes (e.g. desktop API). */
    public Javalin app() {
        return app;
    }

    public static AwServer createAndStart(Path dataDir, int port) {
        AwServer server = new AwServer(dataDir, port);
        server.start(port);
        return server;
    }
}
