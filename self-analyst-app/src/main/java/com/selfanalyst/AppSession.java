package com.selfanalyst;

import com.selfanalyst.agent.SelfAnalystAgent;
import com.selfanalyst.aw.AwServer;
import com.selfanalyst.aw.watcher.WatcherManager;
import com.selfanalyst.config.Config;
import com.selfanalyst.content.ContentWatcher;
import com.selfanalyst.audio.AudioCaptureOptions;
import com.selfanalyst.audio.AudioCaptureManager;
import com.selfanalyst.desktop.DesktopServer;
import com.selfanalyst.desktop.store.ConfigMigration;
import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.usage.UsageMeter;
import com.selfanalyst.wiki.*;
import com.selfanalyst.wiki.semantic.*;
import com.selfanalyst.file.*;
import com.selfanalyst.file.extractor.FileContentExtractorFactory;
import com.selfanalyst.file.semantic.FileSemanticIndex;
import com.selfanalyst.file.semantic.FileEmbeddingWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class AppSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AppSession.class);
    private final Config config;
    private final UsageMeter usageMeter;
    private final SelfAnalystAgent agent;
    private AwServer awServer;
    private WatcherManager watcherManager;
    private ContentWatcher contentWatcher;
    private AudioCaptureManager audioCaptureManager;
    private DesktopServer desktopServer;
    private WikiStore wikiStore;
    private WikiWorker wikiWorker;
    private WikiSummaryWatcher wikiSummaryWatcher;
    private WikiSemanticIndex wikiSemanticIndex;
    private WikiEmbeddingWorker wikiEmbeddingWorker;
    private FileWatchStore fileWatchStore;
    private FileSemanticIndex fileSemanticIndex;
    private FileEmbeddingWorker fileEmbeddingWorker;
    private FileIndexWorker fileIndexWorker;
    private FileWatcher fileWatcher;

    public AppSession() throws IOException {
        // Migrate the user config to TOML before it is first read. AppSession is the
        // single backend entry (CLI + desktop), so "首次读取之前" holds here.
        // SPEC-TOML-MIG-001a.
        ConfigMigration.migrateIfNeeded(Config.resolveMemoryDir());
        this.config = Config.load();
        this.usageMeter = new UsageMeter(config, config.memoryDir());
        if (config.awEmbedded()) {
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

        UserConfigStore userConfigStore = new UserConfigStore(config.memoryDir());

        // File watch: store + semantic index + embedding worker + tools (SPEC-FILE-001/016/017).
        // FileTools must exist before the agent so it can be registered; the watcher/index
        // worker (which need the agent's LLM client) are started after the agent below.
        PathFilter filePathFilter = null;
        FileTools fileTools = null;
        List<Path> fileWatchRoots = List.of();
        if (config.fileWatchEnabled()) {
            try {
                fileWatchRoots = parseWatchRoots(config.fileWatchPaths());
                if (fileWatchRoots.isEmpty()) {
                    log.warn("file.watch.enabled=true 但 file.watch.paths 为空，文件监控未启动");
                } else {
                    fileWatchStore = new FileWatchStore(config.memoryDir().resolve("file-watch.db"));
                    filePathFilter = new PathFilter(config.fileWatchMaxFileSizeKb(),
                            PathFilter.splitCsv(config.fileWatchExcludeDirs()),
                            PathFilter.splitCsv(config.fileWatchExcludeGlobs()),
                            PathFilter.splitCsv(config.fileWatchExtensions()));
                    if (embeddingClient != null && config.fileWatchSemanticEnabled()) {
                        fileSemanticIndex = new FileSemanticIndex(config.fileSemanticIndexDir());
                        fileEmbeddingWorker = new FileEmbeddingWorker(fileWatchStore, fileSemanticIndex,
                                embeddingClient, config.fileWatchWorkerIntervalSeconds());
                    }
                    fileTools = new FileTools(fileWatchStore, fileSemanticIndex,
                            config.fileWatchSemanticEnabled() ? embeddingClient : null,
                            config.wikiSemanticTopK());
                    log.info("FileWatchStore 已初始化 ({} 个监控目录)", fileWatchRoots.size());
                }
            } catch (Exception e) {
                log.warn("文件监控初始化失败，文件功能不可用: {}", e.getMessage());
                fileWatchStore = null;
                fileSemanticIndex = null;
                fileEmbeddingWorker = null;
                fileTools = null;
            }
        }

        SelfAnalystAgent a = null;
        try {
            Supplier<String> audioRuntimeStatus = audioCaptureManager != null
                    ? () -> audioCaptureManager.status().status()
                    : null;
            a = new SelfAnalystAgent(config, wikiStore, wikiTools, userConfigStore, fileTools,
                    usageMeter, audioRuntimeStatus);
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
        if (wikiStore != null && a != null && awServer != null) {
            try {
                WikiFactBuilder factBuilder = new WikiFactBuilder(
                        awServer.eventStore(), config.wikiPromptMaxContentChars());
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
        if (wikiStore != null && awServer != null) {
            try {
                wikiSummaryWatcher = new WikiSummaryWatcher(wikiStore,
                        awServer.bucketStore(), awServer.eventStore());
                wikiSummaryWatcher.start();
                log.info("WikiSummaryWatcher 已启动");
            } catch (Exception e) {
                log.warn("WikiSummaryWatcher 启动失败: {}", e.getMessage());
            }
        }

        // File watch workers (SPEC-FILE-013/012/019). Started after the agent so the
        // summarizer can use its LLM client; init order per spec §4.
        if (fileWatchStore != null && a != null) {
            try {
                if (fileEmbeddingWorker != null) {
                    fileEmbeddingWorker.start();
                    log.info("FileEmbeddingWorker 已启动");
                }
                FileSummarizer fileSummarizer = new FileSummarizer(a.wikiLLMClient());
                FileContentExtractorFactory extractorFactory = new FileContentExtractorFactory();
                fileIndexWorker = new FileIndexWorker(fileWatchStore, filePathFilter,
                        extractorFactory, fileSummarizer, fileEmbeddingWorker, fileWatchRoots,
                        config.fileWatchWorkerIntervalSeconds(), config.fileWatchMaxContentChars(),
                        config.fileWatchMinReindexIntervalMinutes());
                fileIndexWorker.start();
                log.info("FileIndexWorker 已启动");

                String fileHeartbeatUrl = "http://localhost:" + config.awPort();
                fileWatcher = new FileWatcher(fileWatchStore, filePathFilter, fileWatchRoots,
                        fileHeartbeatUrl, config.fileWatchDebounceSeconds(),
                        config.fileWatchHeartbeatThrottleSeconds());
                fileWatcher.start();
                log.info("FileWatcher 已启动");
            } catch (Exception e) {
                log.warn("文件监控 worker 启动失败: {}", e.getMessage());
            }
        }

        if (awServer != null && awServer.app() != null) {
            var memoryStore = agent != null ? agent.memory() : null;
            desktopServer = new DesktopServer(awServer.app(), config, agent,
                    awServer.eventStore(), awServer.bucketStore(), memoryStore,
                    watcherManager, contentWatcher, audioCaptureManager);
            desktopServer.start();
            awServer.registerWebUi();
            log.info(desktopUiStartupLogMessage(config.awPort()));
        }
    }

    private void startEmbeddedAW() {
        try {
            awServer = new AwServer(config.awDataDir(), config.awPort());
            awServer.start(config.awPort());
            log.info("嵌入式 AW 服务已启动 (端口 {})", config.awPort());
            watcherManager = new WatcherManager(
                    "http://localhost:" + config.awPort());
            if (config.collectWindow()) {
                watcherManager.addWindowWatcher();
            }
            if (config.collectAfk()) {
                watcherManager.addAfkWatcher();
            }
            watcherManager.startAll();
            try {
                System.setProperty("ocr.sample.dir",
                        config.ocrSampleDir().toAbsolutePath().toString());
                if (config.ocrExcludedApps() != null && !config.ocrExcludedApps().isBlank()) {
                    System.setProperty("ocr.excluded.apps", config.ocrExcludedApps());
                }
                if (config.ocrTitleStripHeight() > 0) {
                    System.setProperty("ocr.title-strip-height",
                            String.valueOf(config.ocrTitleStripHeight()));
                }
                if (config.collectContent()) {
                    contentWatcher = new ContentWatcher("http://localhost:" + config.awPort(),
                            config.contentPollIntervalMs());
                    contentWatcher.start();
                    log.info("内容采集已启动 (UIA + OCR)");
                } else {
                    log.info("内容采集已按配置禁用 (aw.collection.content=false)");
                }
            } catch (Exception e2) {
                log.warn("内容采集未启动: {}", e2.getMessage());
            }
            try {
                audioCaptureManager = new AudioCaptureManager(
                        "http://localhost:" + config.awPort(),
                        config.audioEnabled(),
                        new AudioCaptureOptions(
                                config.audioWhisperPath(),
                                config.audioVadThreshold(),
                                config.audioChunkSeconds(),
                                config.audioSource(),
                                config.audioEngine(),
                                config.llmBaseUrl(),
                                config.llmApiKey(),
                                config.audioModel()));
                if (!config.audioEnabled()) {
                    log.info("音频采集已按配置禁用 (aw.audio.enabled=false)");
                }
            } catch (Exception e3) {
                log.warn("音频采集控制器未启动: {}", e3.getMessage());
            }
        } catch (Exception e) {
            log.warn("嵌入式 AW 启动失败: {}", e.getMessage());
        }
    }

    static String desktopUiStartupLogMessage(int port) {
        return "Desktop UI 已就绪: http://localhost:" + port + "/desktop-ui/";
    }

    public Config config() { return config; }
    public SelfAnalystAgent agent() { return agent; }

    public void saveAndShutdown() {
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
        if (fileWatcher != null) {
            fileWatcher.shutdown();
        }
        if (fileIndexWorker != null) {
            fileIndexWorker.shutdown();
        }
        if (fileEmbeddingWorker != null) {
            fileEmbeddingWorker.shutdown();
        }
        if (fileSemanticIndex != null) {
            fileSemanticIndex.close();
        }
        if (fileWatchStore != null) {
            fileWatchStore.close();
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
        shutdownEmbeddedAW();
    }

    private void shutdownEmbeddedAW() {
        if (audioCaptureManager != null) {
            audioCaptureManager.shutdown();
        }
        if (contentWatcher != null) {
            contentWatcher.shutdown();
        }
        if (watcherManager != null) {
            watcherManager.stopAll();
        }
        if (awServer != null) {
            awServer.stop();
        }
    }

    /** Parse comma-separated absolute watch paths into existing directories (SPEC-FILE-001). */
    private static List<Path> parseWatchRoots(String csv) {
        List<Path> roots = new ArrayList<>();
        if (csv == null || csv.isBlank()) return roots;
        for (String p : csv.split(",")) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            Path path = Path.of(trimmed);
            if (java.nio.file.Files.isDirectory(path)) {
                roots.add(path);
            } else {
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
