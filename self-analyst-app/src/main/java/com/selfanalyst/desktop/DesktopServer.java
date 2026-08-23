package com.selfanalyst.desktop;

import com.selfanalyst.agent.SelfAnalystAgent;
import com.selfanalyst.audio.AudioCaptureManager;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.watcher.WatcherManager;
import com.selfanalyst.config.Config;
import com.selfanalyst.content.ContentWatcher;
import com.selfanalyst.desktop.controller.*;
import com.selfanalyst.desktop.service.BehaviorAdviceService;
import com.selfanalyst.desktop.service.ChatSummaryService;
import com.selfanalyst.desktop.service.MemoryExtractionService;
import com.selfanalyst.desktop.service.SummaryService;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.desktop.store.TaskStore;
import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.headroom.HeadroomService;
import com.selfanalyst.memory.LongTermMemoryService;
import com.selfanalyst.memory.MemoryStore;
import io.javalin.Javalin;
import io.javalin.http.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Desktop dashboard API server.
 * <p>
 * Registers {@code /desktop/*} routes on the <b>existing</b> Javalin instance
 * shared with {@code AwServer} — no separate port is opened.
 * <p>
 * Typical wiring:
 * <pre>{@code
 *   AwServer awServer = new AwServer(dataDir, port);
 *   Javalin app = awServer.app(); // need a getter on AwServer
 *   SelfAnalystAgent agent = new SelfAnalystAgent(config);
 *   DesktopServer desktop = new DesktopServer(app, config, agent,
 *           awServer.eventStore(),
 *           agent.memory(),
 *           watcherManager, contentWatcher, audioCaptureManager);
 *   desktop.start(); // registers routes, no listen()
 *   awServer.start();
 * }</pre>
 */
public class DesktopServer {

    private static final Logger log = LoggerFactory.getLogger(DesktopServer.class);

    private final Javalin app;
    private final DesktopAgentController agentCtrl;
    private final DesktopConfigController configCtrl;
    private final DesktopTaskController taskCtrl;
    private final DesktopStatusController statusCtrl;
    private final DesktopAudioController audioCtrl;
    private final DesktopAudioEventsController audioEventsCtrl;
    private final DesktopChatSessionController chatSessionCtrl;
    private final DesktopMemoryController memoryCtrl;

    /**
     * Create and register all desktop API routes.
     *
     * @param app            existing Javalin instance (shared with AwServer)
     * @param config         application configuration
     * @param agent          LLM agent (nullable – chat/summary enhancement disabled when null)
     * @param eventStore     AW event store for querying activity data
     * @param memoryStore    optional memory store for goal context in summaries
     * @param watcherManager optional – for collector status reporting
     * @param contentWatcher optional – for collector status reporting
     * @param audioCaptureManager optional – for runtime audio collector control/reporting
     */
    public DesktopServer(Javalin app,
                         Config config,
                         SelfAnalystAgent agent,
                         EventStore eventStore,
                         MemoryStore memoryStore,
                         WatcherManager watcherManager,
                         ContentWatcher contentWatcher,
                         AudioCaptureManager audioCaptureManager) {
        this(app, config, agent, eventStore, null, memoryStore,
                watcherManager, contentWatcher, audioCaptureManager, null);
    }

    public DesktopServer(Javalin app,
                         Config config,
                         SelfAnalystAgent agent,
                         EventStore eventStore,
                         BucketStore bucketStore,
                         MemoryStore memoryStore,
                         WatcherManager watcherManager,
                         ContentWatcher contentWatcher,
                         AudioCaptureManager audioCaptureManager) {
        this(app, config, agent, eventStore, bucketStore, memoryStore,
                watcherManager, contentWatcher, audioCaptureManager, null);
    }

    public DesktopServer(Javalin app,
                         Config config,
                         SelfAnalystAgent agent,
                         EventStore eventStore,
                         BucketStore bucketStore,
                         MemoryStore memoryStore,
                         WatcherManager watcherManager,
                         ContentWatcher contentWatcher,
                         AudioCaptureManager audioCaptureManager,
                         HeadroomService headroomService) {
        this.app = app;

        Path memoryDir = config.memoryDir();
        TaskStore taskStore = new TaskStore(memoryDir);
        UserConfigStore userConfigStore = new UserConfigStore(memoryDir);
        ChatSessionStore chatSessionStore = new ChatSessionStore(memoryDir);
        SummaryService summaryService = new SummaryService(eventStore, memoryStore);
        BehaviorAdviceService adviceService = new BehaviorAdviceService();
        LongTermMemoryService longTermMemoryService = memoryStore != null
                ? new LongTermMemoryService(memoryStore)
                : null;
        MemoryExtractionService memoryExtractionService = longTermMemoryService != null
                ? new MemoryExtractionService(longTermMemoryService, config.effectiveLanguage())
                : null;

        this.agentCtrl = new DesktopAgentController(summaryService, adviceService, agent, taskStore,
                config, headroomService, chatSessionStore);
        this.configCtrl = new DesktopConfigController(config, userConfigStore, headroomService);
        this.taskCtrl = new DesktopTaskController(taskStore);
        this.statusCtrl = new DesktopStatusController(
                config, watcherManager, contentWatcher, audioCaptureManager, headroomService);
        this.audioCtrl = new DesktopAudioController(audioCaptureManager);
        this.audioEventsCtrl = new DesktopAudioEventsController(
                eventStore, bucketStore, audioCaptureManager);
        this.memoryCtrl = longTermMemoryService != null
                ? new DesktopMemoryController(longTermMemoryService)
                : null;

        ChatSummaryService chatSummaryService = new ChatSummaryService(config.effectiveLanguage());
        this.chatSessionCtrl = new DesktopChatSessionController(
                chatSessionStore, chatSummaryService, agent, config, memoryExtractionService);
    }

    /**
     * Register all routes on the shared Javalin instance.
     * Call this <b>before</b> {@code AwServer.start()}.
     */
    public void start() {
        // ── Desktop frontend static files (from classpath) ───
        app.get("/desktop-ui/{f}", ctx -> {
            String file = ctx.pathParam("f");
            String path = "/desktop-ui/" + file;
            try (var in = getClass().getResourceAsStream(path)) {
                if (in != null) {
                    ctx.result(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
                    if (file.endsWith(".css")) ctx.contentType("text/css");
                    else if (file.endsWith(".js")) ctx.contentType("application/javascript");
                    else if (file.endsWith(".html")) ctx.contentType("text/html");
                    else if (file.endsWith(".svg")) ctx.contentType("image/svg+xml");
                } else {
                    ctx.status(404);
                }
            }
        });
        app.get("/desktop-ui", ctx -> ctx.redirect("/desktop-ui/index.html"));
        app.get("/desktop-ui/", ctx -> ctx.redirect("/desktop-ui/index.html"));

        // ── Agent tab ────────────────────────────────────────
        app.get("/desktop/summary", agentCtrl::getSummary);
        app.post("/desktop/chat", agentCtrl::chat);
        app.get("/desktop/usage", agentCtrl::getUsage);

        // ── Config tab ───────────────────────────────────────
        app.get("/desktop/config", configCtrl::getConfig);
        app.put("/desktop/config", configCtrl::putConfig);
        app.get("/desktop/config/raw", configCtrl::getRawConfig);
        app.put("/desktop/config/raw", configCtrl::putRawConfig);
        app.get("/desktop/config/history", configCtrl::getConfigHistory);
        app.get("/desktop/config/history/{id}", configCtrl::getConfigVersion);
        app.post("/desktop/config/test-llm", configCtrl::testLlm);
        app.post("/desktop/config/test-embedding", configCtrl::testEmbedding);

        // ── Tasks CRUD ───────────────────────────────────────
        app.get("/desktop/tasks", taskCtrl::listTasks);
        app.post("/desktop/tasks", taskCtrl::createTask);
        app.put("/desktop/tasks/{id}", taskCtrl::updateTask);
        app.post("/desktop/tasks/{id}/complete", taskCtrl::completeTask);
        app.post("/desktop/tasks/{id}/archive", taskCtrl::archiveTask);
        app.delete("/desktop/tasks/{id}", taskCtrl::deleteTask);

        // ── Chat sessions CRUD ───────────────────────────────
        app.get   ("/desktop/chat/sessions",                      chatSessionCtrl::listSessions);
        app.post  ("/desktop/chat/sessions",                      chatSessionCtrl::createSession);
        app.get   ("/desktop/chat/sessions/{id}",                 chatSessionCtrl::getSession);
        app.put   ("/desktop/chat/sessions/{id}",                 chatSessionCtrl::updateSession);
        app.delete("/desktop/chat/sessions/{id}",                 chatSessionCtrl::deleteSession);
        app.post  ("/desktop/chat/sessions/{id}/messages",        chatSessionCtrl::appendMessages);
        app.put   ("/desktop/chat/sessions/{id}/messages/{msgId}", chatSessionCtrl::updateMessage);
        app.put   ("/desktop/chat/active-session",                chatSessionCtrl::setActiveSession);
        app.put   ("/desktop/chat/sessions/{id}/memory-policy",   chatSessionCtrl::setMemoryPolicy);

        // ── Long-term memory ────────────────────────────────────
        if (memoryCtrl != null) {
            app.get   ("/desktop/memory",                     memoryCtrl::list);
            app.post  ("/desktop/memory",                     memoryCtrl::create);
            app.put   ("/desktop/memory/{id}",                memoryCtrl::update);
            app.delete("/desktop/memory/{id}",                memoryCtrl::delete);
            app.post  ("/desktop/chat/sessions/{id}/memory", memoryCtrl::createFromSession);
        }

        // ── Status ───────────────────────────────────────────
        app.get("/desktop/status", statusCtrl::getStatus);
        app.post("/desktop/audio", audioCtrl::setAudioCapture);
        app.get("/desktop/audio/events", audioEventsCtrl::getEvents);

        // Catch-all exception handler so no error returns an empty body
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception on {} {}", ctx.method(), ctx.path(), e);
            try {
                String msg = e.getMessage();
                if (msg == null) msg = e.getClass().getName();
                ctx.status(500).result("{\"error\":\"" + escapeJson(msg) + "\"}").contentType("application/json");
            } catch (Throwable ignored) {
                ctx.status(500).result("{\"error\":\"Internal server error\"}").contentType("application/json");
            }
        });

        log.info("Registered /desktop/* routes on shared Javalin instance");
    }

    public void shutdown() {
        if (statusCtrl != null) {
            statusCtrl.close();
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 20);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) { sb.append(String.format("\\u%04x", (int) c)); }
                    else { sb.append(c); }
            }
        }
        return sb.toString();
    }

    /** Expose controllers for testing or advanced use. */
    public DesktopAgentController agentController() { return agentCtrl; }
    public DesktopConfigController configController() { return configCtrl; }
    public DesktopTaskController taskController() { return taskCtrl; }
    public DesktopStatusController statusController() { return statusCtrl; }
    public DesktopAudioController audioController() { return audioCtrl; }
    public DesktopChatSessionController chatSessionController() { return chatSessionCtrl; }
    public DesktopMemoryController memoryController() { return memoryCtrl; }
}
