package com.selfanalyst;

import com.selfanalyst.agent.SelfAnalystAgent;
import com.selfanalyst.events.EventServer;
import com.selfanalyst.events.watcher.WatcherManager;
import com.selfanalyst.config.Config;
import com.selfanalyst.content.ContentWatcher;
import com.selfanalyst.desktop.DesktopServer;
import com.selfanalyst.desktop.controller.DesktopFileController;
import com.selfanalyst.desktop.store.ContentEventV2Migration;
import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.usage.UsageMeter;
import com.selfanalyst.wiki.*;
import com.selfanalyst.wiki.semantic.*;
import com.selfanalyst.file.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class AppSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AppSession.class);
    private final Config config;
    private final UsageMeter usageMeter;
    private final SelfAnalystAgent agent;
    private EventServer eventServer;
    private final String desktopToken;
    private final Runnable desktopShutdownSignal;
    private WatcherManager watcherManager;
    private ContentWatcher contentWatcher;
    private DesktopServer desktopServer;
    private WikiStore wikiStore;
    private WikiWorker wikiWorker;
    private WikiSummaryWatcher wikiSummaryWatcher;
    private WikiSemanticIndex wikiSemanticIndex;
    private WikiEmbeddingWorker wikiEmbeddingWorker;
    private FileWatchStore fileWatchStore;
    private FileTools fileTools;
    private FileIndexWorker fileIndexWorker;
    private FileWatcher fileWatcher;
    private PathFilter filePathFilter;
    private boolean fileWatchEnabled;
    private List<Path> fileWatchRoots = List.of();
    private String fileWatchStartupReason;
    private String fileWatchStartupError;
    private String fileWatchInitializationError;
    private boolean contentPersistenceReady = true;
    private String contentMigrationError;
    private final AtomicBoolean closed = new AtomicBoolean();

    public AppSession() throws IOException {
        this(null, () -> {});
    }

    /**
     * Desktop lifecycle routes must be in place before the HTTP server starts, so the
     * token and shutdown signal are supplied up front rather than registered later.
     */
    public AppSession(String desktopToken, Runnable desktopShutdownSignal) throws IOException {
        this(desktopToken, desktopShutdownSignal, Config.load(),
                new UserConfigStore(Config.resolveConfigDir()));
    }

    AppSession(String desktopToken, Runnable desktopShutdownSignal, Config initialConfig,
               UserConfigStore userConfigStore) throws IOException {
        this.desktopToken = desktopToken;
        this.desktopShutdownSignal =
                desktopShutdownSignal != null ? desktopShutdownSignal : () -> {};
        this.config = initialConfig;
        this.usageMeter = new UsageMeter(config, config.memoryDir());
        if (config.eventsEmbedded()) {
            startEmbeddedAW();
        }
        if (config.llmApiKey() == null || config.llmApiKey().isBlank()
                || config.llmApiKey().contains("CHANGE_ME")) {
            log.warn("LLM API key 未配置，Agent 对话功能不可用。");
        }

        // Wiki store
        if (config.wikiEnabled()) {
            try {
                wikiStore = new WikiStore(config.memoryDir().resolve("llm-wiki.db"));
                log.info("WikiStore 已初始化");
            } catch (Exception e) {
                log.warn("WikiStore 初始化失败，Wiki 功能不可用: {}", e.getMessage());
                wikiStore = null;
            }
        }

        // Wiki semantic index + embedding
        EmbeddingClient embeddingClient = null;
        if (wikiStore != null && config.embeddingEnabled() && config.wikiSemanticEnabled()) {
            try {
                wikiSemanticIndex = new WikiSemanticIndex(config.wikiSemanticIndexDir(),
                        config.embeddingDimensions());
                if (config.embeddingApiKey() != null && !config.embeddingApiKey().isBlank()) {
                    embeddingClient = new OpenAiCompatibleEmbeddingClient(
                            config.embeddingBaseUrl(), config.embeddingApiKey(),
                            config.embeddingModel(), config.embeddingDimensions(),
                            Duration.ofSeconds(30), config.embeddingSendEncodingFormat(),
                            usageMeter);
                    log.info("Embedding client 已初始化 (model={})", config.embeddingModel());
                }
            } catch (Exception e) {
                log.warn("语义索引初始化失败，语义检索不可用: {}", e.getMessage());
                wikiSemanticIndex = null;
                embeddingClient = null;
            }
        }

        // WikiTools with optional semantic support
        WikiTools wikiTools = null;
        if (wikiStore != null) {
            wikiTools = new WikiTools(wikiStore, wikiSemanticIndex, embeddingClient,
                    config.wikiSemanticTopK());
        }



        // Metadata-only file store + tools (SPEC-FILE-001/050/060).
        fileTools = null;
        fileWatchEnabled = config.fileWatchEnabled();
        fileWatchRoots = List.copyOf(parseWatchRoots(config.fileWatchPaths()));
        try {
            if (config.fileWatchConfigurationError() != null) {
                throw new IllegalArgumentException(config.fileWatchConfigurationError());
            }
            int purgedLegacyIndexFiles = LegacyFileSemanticIndexPurger.purge(
                    config.legacyFileSemanticIndexDir());
            if (purgedLegacyIndexFiles > 0) {
                log.info("已清理旧文件内容语义索引 ({} 个索引文件)", purgedLegacyIndexFiles);
            }
            // The store and FileTools stay available while collection is disabled so the
            // dedicated settings page can enable the complete pipeline without a restart.
            fileWatchStore = new FileWatchStore(config.memoryDir().resolve("file-watch.db"));
            FileFilterConfig filterConfig = FileFilterConfig.parse(
                    config.fileWatchMaxFileSizeKb(),
                    FileFilterConfig.splitCsv(config.fileWatchExcludeDirs()),
                    FileFilterConfig.splitCsv(config.fileWatchExcludeGlobs()),
                    FileFilterConfig.splitCsv(config.fileWatchExtensions()),
                    config.fileWatchRespectGitIgnore());
            filePathFilter = new PathFilter(filterConfig);
            fileTools = new FileTools(fileWatchStore);
            fileTools.updateWatchRoots(List.of());
            log.info("FileWatchStore 已初始化 ({} 个已配置目录)", fileWatchRoots.size());
        } catch (Exception e) {
            fileWatchInitializationError = e.getMessage();
            fileWatchStartupReason = "initialization_failed";
            fileWatchStartupError = fileWatchInitializationError;
            log.warn("文件监控初始化失败，文件功能不可用: {}", e.getMessage());
            if (fileWatchStore != null) {
                fileWatchStore.close();
            }
            fileWatchStore = null;
            filePathFilter = null;
            fileTools = null;
        }

        SelfAnalystAgent a = null;
        try {
            a = new SelfAnalystAgent(config, wikiStore, wikiTools, userConfigStore, fileTools,
                    usageMeter);
        } catch (Exception e) {
            log.warn("Agent 初始化失败 (API key 无效?): {}", e.getMessage());
        }
        this.agent = a;

        // Wiki embedding worker
        if (wikiStore != null && wikiSemanticIndex != null && embeddingClient != null) {
            try {
                wikiEmbeddingWorker = new WikiEmbeddingWorker(wikiStore, wikiSemanticIndex,
                        embeddingClient, config.embeddingModel(), config.embeddingDimensions(),
                        config.wikiWorkerIntervalSeconds());
                wikiEmbeddingWorker.start();
                log.info("WikiEmbeddingWorker 已启动");
            } catch (Exception e) {
                log.warn("WikiEmbeddingWorker 启动失败: {}", e.getMessage());
                wikiEmbeddingWorker = null;
            }
        }

        // Wiki summarization worker
        if (wikiStore != null && a != null && eventServer != null
                && contentPersistenceReady && eventServer.projectionReady()) {
            try {
                WikiFactBuilder factBuilder = new WikiFactBuilder(
                        eventServer.eventStore(), config.wikiPromptMaxContentChars(), () -> {
                            Object lag = eventServer.rawStatus().get("projectionLagSeconds");
                            return lag instanceof Number number ? number.longValue() : null;
                        });
                WikiSummarizer summarizer = new WikiSummarizer(a.wikiLLMClient());
                wikiWorker = new WikiWorker(wikiStore, factBuilder, summarizer,
                        ZoneId.systemDefault(),
                        Duration.ofMinutes(3),
                        config.wikiWorkerIntervalSeconds(),
                        config.wikiBackfillEnabled(),
                        wikiEmbeddingWorker);
                wikiWorker.start();
                log.info("WikiWorker 已启动");
            } catch (Exception e) {
                log.warn("WikiWorker 启动失败: {}", e.getMessage());
            }
        }

        // Wiki summary buckets (feeds hourly/halfday/daily axes in AW timeline)
        if (wikiStore != null && eventServer != null && eventServer.projectionReady()) {
            try {
                wikiSummaryWatcher = new WikiSummaryWatcher(wikiStore,
                        eventServer.bucketStore(), eventServer.eventStore());
                wikiSummaryWatcher.start();
                log.info("WikiSummaryWatcher 已启动");
            } catch (Exception e) {
                log.warn("WikiSummaryWatcher 启动失败: {}", e.getMessage());
            }
        }

        // Metadata-only workers do not depend on Agent or LLM availability.
        applyFileWatchSettings(fileWatchEnabled, fileWatchRoots);

        if (eventServer != null && eventServer.app() != null) {
            var memoryStore = agent != null ? agent.memory() : null;
            desktopServer = new DesktopServer(eventServer.app(), config, agent,
                    eventServer.eventStore(), memoryStore,
                    watcherManager, contentWatcher,
                    contentPersistenceReady, contentMigrationError,
                    fileWatchStore, this::fileCollectorState, this::applyFileWatchSettings,
                    userConfigStore, wikiStore);
            desktopServer.statusController().setRawStatusSupplier(eventServer::rawStatus);
            desktopServer.start();
            eventServer.registerWebUi();
            log.info(desktopUiStartupLogMessage(config.eventsPort()));
        }
    }

    private void startEmbeddedAW() {
        try {
            eventServer = new EventServer(
                    config.eventsDataDir(), config.eventsRawDir(), config.eventsPort(),
                    config.eventsRawQueryMaxRangeDays(), config.eventsRawQueryMaxPageSize(),
                    config.eventsRawLowDiskWarnBytes(), config.eventsRawLowDiskBlockBytes(),
                    config.eventsRawProjectorBatchSize(),
                    config.eventsRawIntegrityStartupScope() == com.selfanalyst.config.RawIntegrityPolicy.ALL);
            try {
                var migration = ContentEventV2Migration.migrate(eventServer.db());
                contentPersistenceReady = migration.ready();
                log.info("内容事件标题化迁移完成 (scanned={}, sanitized={})",
                        migration.scanned(), migration.sanitized());
            } catch (Exception migrationFailure) {
                contentPersistenceReady = false;
                contentMigrationError = migrationFailure.getMessage();
                log.error("内容事件标题化迁移失败，内容采集与 Wiki 已禁用: {}",
                        contentMigrationError);
            }
            registerDesktopLifecycle();
            eventServer.start(config.eventsPort());
            log.info("嵌入式 AW 服务已启动 (端口 {})", config.eventsPort());
            watcherManager = new WatcherManager(
                    "http://localhost:" + config.eventsPort());
            if (config.collectWindow()) {
                watcherManager.addWindowWatcher();
            }
            if (config.collectAfk()) {
                watcherManager.addAfkWatcher();
            }
            watcherManager.startAll();
            try {
                if (config.collectTitle() && contentPersistenceReady) {
                    contentWatcher = new ContentWatcher("http://localhost:" + config.eventsPort(),
                            config.titlePollIntervalMs());
                    contentWatcher.start();
                    log.info("上下文标题识别已启动 (UIA)");
                } else if (!contentPersistenceReady) {
                    log.warn("上下文标题识别因历史数据迁移失败而禁用");
                } else {
                    log.info("上下文标题识别已按配置禁用 (events.collection.title.enabled=false)");
                }
            } catch (Exception e2) {
                log.warn("上下文标题识别未启动: {}", e2.getMessage());
            }
        } catch (Exception e) {
            if (e instanceof com.selfanalyst.events.raw.RawStartupIntegrityException integrityFailure) {
                throw integrityFailure;
            }
            log.warn("嵌入式 AW 启动失败: {}", e.getMessage());
        }
    }

    static String desktopUiStartupLogMessage(int port) {
        return "Desktop UI 已就绪: http://localhost:" + port + "/desktop-ui/";
    }

    public Config config() { return config; }
    public SelfAnalystAgent agent() { return agent; }

    private void registerDesktopLifecycle() {
        String token = desktopToken;
        Runnable shutdownSignal = desktopShutdownSignal;
        if (eventServer == null || token == null || token.isBlank()) {
            return;
        }
        eventServer.app().get("/desktop/session", ctx -> {
            if (!token.equals(ctx.queryParam("token"))) {
                ctx.status(403).json(java.util.Map.of("error", "Invalid desktop session token"));
                return;
            }
            ctx.header("Set-Cookie", desktopSessionCookie(token));
            ctx.redirect("/desktop-ui/");
        });
        eventServer.app().get("/desktop/lifecycle/health", ctx -> {
            if (!token.equals(ctx.header("X-SelfAnalyst-Token"))) {
                ctx.status(403);
                return;
            }
            ctx.status(204);
        });
        eventServer.app().post("/desktop/lifecycle/shutdown", ctx -> {
            if (!token.equals(ctx.header("X-SelfAnalyst-Token"))) {
                ctx.status(403);
                return;
            }
            ctx.status(202).json(java.util.Map.of("accepted", true));
            Thread.ofPlatform().name("desktop-shutdown-signal").start(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                shutdownSignal.run();
            });
        });
    }

    static String desktopSessionCookie(String token) {
        return "self_analyst_session=" + token
                + "; Path=/desktop; HttpOnly; SameSite=Strict";
    }

    private synchronized void applyFileWatchSettings(boolean enabled, List<Path> requestedRoots) {
        if (closed.get()) {
            throw new IllegalStateException("SelfAnalyst 正在关闭，无法更新文件采集设置");
        }
        stopFileWatchWorkers();
        fileWatchEnabled = enabled;
        fileWatchRoots = requestedRoots == null ? List.of() : requestedRoots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
        fileWatchStartupReason = null;
        fileWatchStartupError = null;
        if (fileTools != null) fileTools.updateWatchRoots(List.of());
        if (!enabled) {
            log.info("文件采集已在运行时禁用");
            return;
        }
        if (fileWatchRoots.isEmpty()) {
            fileWatchStartupReason = "paths_unavailable";
            log.warn("文件采集已启用，但没有可用的监控目录");
            return;
        }
        if (fileWatchStore == null || filePathFilter == null) {
            fileWatchStartupReason = "initialization_failed";
            fileWatchStartupError = fileWatchInitializationError;
            return;
        }
        if (!filePathFilter.hasAllowedExtensions()) {
            fileWatchStartupReason = "extensions_required";
            log.warn("文件采集已启用，但扩展名白名单为空；使用 * 可显式允许全部类型");
            return;
        }
        if (fileTools != null) fileTools.updateWatchRoots(fileWatchRoots);
        try {
            fileWatchStartupReason = "starting";
            fileIndexWorker = new FileIndexWorker(fileWatchStore, filePathFilter,
                    fileWatchRoots, config.fileWatchWorkerIntervalSeconds());
            fileIndexWorker.start();

            String fileHeartbeatUrl = "http://localhost:" + config.eventsPort();
            fileWatcher = new FileWatcher(fileWatchStore, filePathFilter, fileWatchRoots,
                    fileHeartbeatUrl, config.fileWatchDebounceSeconds(),
                    config.fileWatchHeartbeatThrottleSeconds(),
                    fileIndexWorker::requestReconcile);
            fileWatcher.start();
            log.info("文件采集配置已在运行时生效 ({} 个监控目录)", fileWatchRoots.size());
        } catch (Exception e) {
            fileWatchStartupReason = "worker_start_failed";
            fileWatchStartupError = e.getMessage();
            log.warn("文件监控 worker 启动失败: {}", e.getMessage());
            stopFileWatchWorkers();
            if (fileTools != null) fileTools.updateWatchRoots(List.of());
        }
    }

    private synchronized DesktopFileController.CollectorState fileCollectorState() {
        boolean watcherRunning = fileWatcher != null && fileWatcher.isRunning();
        boolean indexWorkerRunning = fileIndexWorker != null && fileIndexWorker.isRunning();
        if ("starting".equals(fileWatchStartupReason) && watcherRunning && indexWorkerRunning) {
            fileWatchStartupReason = null;
            fileWatchStartupError = null;
        } else if ("starting".equals(fileWatchStartupReason)
                && fileWatcher != null && fileWatcher.isRegistrationComplete() && !watcherRunning) {
            fileWatchStartupReason = "worker_start_failed";
            fileWatchStartupError = fileWatcher.registrationError();
        }
        return new DesktopFileController.CollectorState(
                fileWatchEnabled,
                fileWatchRoots,
                watcherRunning,
                indexWorkerRunning,
                fileWatchStartupReason,
                fileWatchStartupError);
    }

    private void stopFileWatchWorkers() {
        if (fileWatcher != null) {
            fileWatcher.shutdown();
            fileWatcher = null;
        }
        if (fileIndexWorker != null) {
            fileIndexWorker.shutdown();
            fileIndexWorker = null;
        }
    }

    public void saveAndShutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (agent != null) agent.beginShutdown();
        try {
            try {
                if (agent != null) {
                    agent.saveMemory();
                }
            } catch (IOException e) {
                log.warn("memory not saved: {}", e.getMessage());
            }
            if (usageMeter != null) {
                usageMeter.flush();
            }
            synchronized (this) {
                stopFileWatchWorkers();
                if (fileWatchStore != null) {
                    fileWatchStore.close();
                }
            }
            if (wikiSummaryWatcher != null) {
                wikiSummaryWatcher.shutdown();
            }
            if (wikiWorker != null) {
                wikiWorker.shutdown();
            }
            if (wikiEmbeddingWorker != null) {
                wikiEmbeddingWorker.shutdown();
            }
            if (wikiSemanticIndex != null) {
                wikiSemanticIndex.close();
            }
            if (wikiStore != null) {
                wikiStore.close();
            }
            if (desktopServer != null) {
                desktopServer.shutdown();
            }
            shutdownEmbeddedAW();
        } finally {
            if (agent != null) {
                agent.close();
            }
        }
    }

    private void shutdownEmbeddedAW() {
        if (contentWatcher != null) {
            contentWatcher.shutdown();
        }
        if (watcherManager != null) {
            watcherManager.stopAll();
        }
        if (eventServer != null) {
            eventServer.stop();
        }
    }

    /** Parse comma-separated absolute watch paths into existing directories (SPEC-FILE-001). */
    static List<Path> parseWatchRoots(String csv) {
        List<Path> roots = new ArrayList<>();
        if (csv == null || csv.isBlank()) return roots;
        for (String p : csv.split(",")) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            try {
                Path path = Path.of(trimmed).toAbsolutePath().normalize();
                if (java.nio.file.Files.isDirectory(path)) {
                    roots.add(path);
                } else {
                    log.warn("file.watch.paths 中的目录不存在或不是目录，已跳过: {}", trimmed);
                }
            } catch (java.nio.file.InvalidPathException invalidPath) {
                log.warn("file.watch.paths 中的目录不存在或不是目录，已跳过: {}", trimmed);
            }
        }
        return roots;
    }

    @Override
    public void close() {
        saveAndShutdown();
    }
}
