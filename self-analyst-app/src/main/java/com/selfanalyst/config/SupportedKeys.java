package com.selfanalyst.config;

import com.selfanalyst.config.TomlSupport.KeyType;

import java.util.LinkedHashMap;

/**
 * Single source of truth for user-overridable config keys → (default value,
 * declared type). Consumed by the raw-edit template, unknown-key detection, TOML
 * type validation, and TOML generation so they all read one definition. Keep this
 * aligned with {@link Config#load()} and the desktop-only settings persisted by
 * {@code UserConfigStore}.
 *
 * <p>Network/LLM-sending features remain opt-in by default: wiki, embedding,
 * file watch, and web search are all listed with {@code false} where they cause
 * external processing or broader data capture.
 */
public final class SupportedKeys {

    private SupportedKeys() {}

    /** A supported key's default value and declared TOML type. */
    public record Spec(String defaultValue, KeyType type) {}

    /** Human-readable help rendered above each entry in the editable config template. */
    public record Description(String zh, String en) {}

    private static final LinkedHashMap<String, Spec> KEYS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Description> DESCRIPTIONS = new LinkedHashMap<>();

    private static void put(String key, String def, KeyType type) {
        KEYS.put(key, new Spec(def, type));
    }

    private static void describe(String key, String zh, String en) {
        DESCRIPTIONS.put(key, new Description(zh, en));
    }

    static {
        put("log.dir", "./logs", KeyType.STRING);
        put("memory.dir", "./data/memory", KeyType.STRING);

        put("llm.api-key", "", KeyType.STRING);
        put("llm.base-url", "https://api.openai.com/v1", KeyType.STRING);
        put("llm.model", "gpt-4o", KeyType.STRING);
        put("llm.temperature", "0.7", KeyType.FLOAT);
        put("llm.max-tokens", "2048", KeyType.INTEGER);
        put("llm.agent.maxIters", "8", KeyType.INTEGER);
        put("llm.budget.mode", "warn", KeyType.STRING);
        put("llm.budget.dailyTokens", "100000000", KeyType.INTEGER);
        put("llm.budget.warnRatio", "0.8", KeyType.FLOAT);

        put("agent.summaryRefreshMinutes", "5", KeyType.INTEGER);
        put("agent.allowAgentTasks", "false", KeyType.BOOLEAN);
        put("agent.cacheSummaries", "true", KeyType.BOOLEAN);
        put("agent.compaction.enabled", "true", KeyType.BOOLEAN);
        put("agent.compaction.triggerMessages", "30", KeyType.INTEGER);
        put("agent.compaction.triggerTokens", "60000", KeyType.INTEGER);
        put("agent.compaction.keepMessages", "10", KeyType.INTEGER);
        put("agent.compaction.keepTokens", "12000", KeyType.INTEGER);

        put("desktop.hideToTray", "true", KeyType.BOOLEAN);
        put("desktop.autoOpenWindow", "true", KeyType.BOOLEAN);
        put("desktop.autoStartBackend", "true", KeyType.BOOLEAN);
        put("desktop.summary.maxTimelineLlm", "4", KeyType.INTEGER);

        put("aw.mode", "embedded", KeyType.STRING);
        put("aw.port", "5700", KeyType.INTEGER);
        put("aw.base-url", "http://localhost:5700/api/0", KeyType.STRING);
        put("aw.timeout", "15000", KeyType.INTEGER);
        put("aw.data-dir", "./data/aw-data", KeyType.STRING);
        put("aw.collection.window", "true", KeyType.BOOLEAN);
        put("aw.collection.afk", "true", KeyType.BOOLEAN);
        put("aw.collection.content", "true", KeyType.BOOLEAN);
        put("aw.collection.content.pollMs", "500", KeyType.INTEGER);

        put("wiki.enabled", "false", KeyType.BOOLEAN);
        put("wiki.backfill.enabled", "false", KeyType.BOOLEAN);
        put("wiki.worker.intervalSeconds", "60", KeyType.INTEGER);
        put("wiki.prompt.maxContentChars", "12000", KeyType.INTEGER);
        put("wiki.topApps.limit", "10", KeyType.INTEGER);
        put("wiki.semantic.enabled", "true", KeyType.BOOLEAN);
        put("wiki.semantic.index-dir", "./data/memory/wiki-semantic-index", KeyType.STRING);
        put("wiki.semantic.topK", "8", KeyType.INTEGER);

        put("embedding.enabled", "false", KeyType.BOOLEAN);
        put("embedding.base-url", "https://api.openai.com/v1", KeyType.STRING);
        put("embedding.api-key", "", KeyType.STRING);
        put("embedding.model", "text-embedding-3-small", KeyType.STRING);
        put("embedding.dimensions", "1024", KeyType.INTEGER);
        put("embedding.send-encoding-format", "true", KeyType.BOOLEAN);

        put("file.watch.enabled", "false", KeyType.BOOLEAN);
        put("file.watch.paths", "", KeyType.STRING);
        put("file.watch.maxFileSizeKb", "512", KeyType.INTEGER);
        put("file.watch.maxContentChars", "8000", KeyType.INTEGER);
        put("file.watch.worker.intervalSeconds", "60", KeyType.INTEGER);
        put("file.watch.debounceSeconds", "5", KeyType.INTEGER);
        put("file.watch.minReindexIntervalMinutes", "5", KeyType.INTEGER);
        put("file.watch.heartbeatThrottleSeconds", "5", KeyType.INTEGER);
        put("file.watch.extensions", "", KeyType.LIST);
        put("file.watch.excludeDirs", "", KeyType.LIST);
        put("file.watch.excludeGlobs", "", KeyType.LIST);
        put("file.watch.semantic.enabled", "true", KeyType.BOOLEAN);
        put("file.watch.semantic.index-dir", "./data/memory/file-semantic-index", KeyType.STRING);

        put("websearch.enabled", "false", KeyType.BOOLEAN);
        put("websearch.mcp-url", "https://search.parallel.ai/mcp", KeyType.STRING);
        put("websearch.api-key", "", KeyType.STRING);

        put("app.language", "auto", KeyType.STRING);

        describe("log.dir", "应用日志的保存目录。", "Directory where application logs are stored.");
        describe("memory.dir", "用户数据、记忆与相关索引的根目录。", "Root directory for user data, memories, and related indexes.");

        describe("llm.api-key", "LLM 服务的 API 密钥；留空时尝试读取环境变量。", "API key for the LLM service; when empty, the environment variable is used.");
        describe("llm.base-url", "OpenAI 兼容 LLM API 的基础地址。", "Base URL of the OpenAI-compatible LLM API.");
        describe("llm.model", "用于对话、总结和 Agent 的模型名称。", "Model name used for chat, summaries, and the agent.");
        describe("llm.temperature", "模型采样温度，值越高输出越随机（0–2）。", "Model sampling temperature; higher values are more random (0–2).");
        describe("llm.max-tokens", "单次 LLM 响应的最大 token 数；0 表示不限。", "Maximum tokens in one LLM response; 0 means unlimited.");
        describe("llm.agent.maxIters", "Agent 单次任务允许的最大推理迭代次数。", "Maximum reasoning iterations allowed for one agent task.");
        describe("llm.budget.mode", "Token 预算模式：off、warn 或 block。", "Token budget mode: off, warn, or block.");
        describe("llm.budget.dailyTokens", "每日 token 预算；0 表示不限。", "Daily token budget; 0 means unlimited.");
        describe("llm.budget.warnRatio", "达到每日预算此比例时发出警告（0–1）。", "Warn when this fraction of the daily budget is reached (0–1).");

        describe("agent.summaryRefreshMinutes", "Agent 状态摘要的刷新间隔（分钟）。", "Refresh interval for agent status summaries, in minutes.");
        describe("agent.allowAgentTasks", "是否允许 Agent 创建和更新任务。", "Whether the agent may create and update tasks.");
        describe("agent.cacheSummaries", "是否缓存生成的摘要以减少重复调用。", "Whether to cache generated summaries to reduce repeated calls.");
        describe("agent.compaction.enabled", "是否自动压缩过长的对话上下文。", "Whether to compact long conversation context automatically.");
        describe("agent.compaction.triggerMessages", "消息数达到此值时触发上下文压缩；0 表示禁用此条件。", "Compact when this message count is reached; 0 disables this trigger.");
        describe("agent.compaction.triggerTokens", "Token 数达到此值时触发上下文压缩；0 表示禁用此条件。", "Compact when this token count is reached; 0 disables this trigger.");
        describe("agent.compaction.keepMessages", "压缩后保留的最近消息数。", "Number of recent messages retained after compaction.");
        describe("agent.compaction.keepTokens", "压缩后保留的最近 token 数。", "Number of recent tokens retained after compaction.");

        describe("desktop.hideToTray", "关闭主窗口时是否隐藏到系统托盘。", "Whether closing the main window hides it in the system tray.");
        describe("desktop.autoOpenWindow", "启动桌面端后是否自动打开主窗口。", "Whether to open the main window automatically at startup.");
        describe("desktop.autoStartBackend", "启动桌面端时是否自动启动后端服务。", "Whether to start the backend service automatically with the desktop app.");
        describe("desktop.summary.maxTimelineLlm", "每次摘要最多使用 LLM 精炼的时间线条目数；0 表示禁用。", "Maximum timeline entries refined by the LLM per summary; 0 disables it.");

        describe("aw.mode", "ActivityWatch 运行模式：embedded 或 external。", "ActivityWatch mode: embedded or external.");
        describe("aw.port", "内嵌 ActivityWatch 服务监听端口。", "Listening port for the embedded ActivityWatch service.");
        describe("aw.base-url", "ActivityWatch HTTP API 基础地址。", "Base URL of the ActivityWatch HTTP API.");
        describe("aw.timeout", "ActivityWatch HTTP 请求超时时间（毫秒）。", "Timeout for ActivityWatch HTTP requests, in milliseconds.");
        describe("aw.data-dir", "ActivityWatch 数据文件目录。", "Directory for ActivityWatch data files.");
        describe("aw.collection.window", "是否采集活动窗口与应用信息。", "Whether to collect active-window and application information.");
        describe("aw.collection.afk", "是否采集用户离开/活跃状态。", "Whether to collect user AFK/active status.");
        describe("aw.collection.content", "是否识别并保存活动窗口的上下文标题；不会保存 UIA 原始正文。", "Whether to identify and save active-window context titles without persisting raw UIA body text.");
        describe("aw.collection.content.pollMs", "前台窗口元数据检查与上下文标题心跳间隔（毫秒）。", "Interval for foreground-window metadata checks and context-title heartbeats, in milliseconds.");

        describe("wiki.enabled", "是否启用个人 Wiki 摘要生成。", "Whether to enable personal wiki summary generation.");
        describe("wiki.backfill.enabled", "是否为历史活动补生成 Wiki 内容。", "Whether to backfill wiki content for historical activity.");
        describe("wiki.worker.intervalSeconds", "Wiki 后台任务运行间隔（秒）。", "Interval between wiki background runs, in seconds.");
        describe("wiki.prompt.maxContentChars", "单次 Wiki 提示词包含的最大内容字符数。", "Maximum content characters included in one wiki prompt.");
        describe("wiki.topApps.limit", "Wiki 摘要统计的高频应用数量上限。", "Maximum number of top applications included in wiki summaries.");
        describe("wiki.semantic.enabled", "是否为 Wiki 内容启用语义检索。", "Whether to enable semantic search for wiki content.");
        describe("wiki.semantic.index-dir", "Wiki 语义索引的保存目录。", "Directory where the wiki semantic index is stored.");
        describe("wiki.semantic.topK", "Wiki 语义检索返回的候选数量。", "Number of candidates returned by wiki semantic search.");

        describe("embedding.enabled", "是否启用 Embedding 与语义检索功能。", "Whether to enable embeddings and semantic search.");
        describe("embedding.base-url", "OpenAI 兼容 Embedding API 的基础地址。", "Base URL of the OpenAI-compatible embedding API.");
        describe("embedding.api-key", "Embedding 服务的 API 密钥；留空时复用 LLM 密钥。", "API key for the embedding service; when empty, the LLM key is reused.");
        describe("embedding.model", "生成向量使用的 Embedding 模型名称。", "Embedding model name used to generate vectors.");
        describe("embedding.dimensions", "Embedding 向量维度。", "Number of dimensions in generated embedding vectors.");
        describe("embedding.send-encoding-format", "是否向服务发送 encoding_format 参数。", "Whether to send the encoding_format parameter to the service.");

        describe("file.watch.enabled", "是否监控本地文件并建立内容索引。", "Whether to watch local files and index their content.");
        describe("file.watch.paths", "要监控的文件或目录路径，以逗号分隔。", "Comma-separated files or directories to watch.");
        describe("file.watch.maxFileSizeKb", "允许索引的单个文件最大大小（KB）。", "Maximum size of one indexed file, in KB.");
        describe("file.watch.maxContentChars", "每个文件最多提取并索引的字符数。", "Maximum characters extracted and indexed from each file.");
        describe("file.watch.worker.intervalSeconds", "文件监控后台扫描间隔（秒）。", "Background file-watch scan interval, in seconds.");
        describe("file.watch.debounceSeconds", "文件变更后的防抖等待时间（秒）。", "Debounce delay after a file change, in seconds.");
        describe("file.watch.minReindexIntervalMinutes", "同一文件两次重建索引的最小间隔（分钟）。", "Minimum interval between reindexing the same file, in minutes.");
        describe("file.watch.heartbeatThrottleSeconds", "文件监控心跳事件的最小间隔（秒）。", "Minimum interval between file-watch heartbeat events, in seconds.");
        describe("file.watch.extensions", "允许索引的文件扩展名列表。", "List of file extensions allowed for indexing.");
        describe("file.watch.excludeDirs", "扫描时排除的目录名列表。", "List of directory names excluded from scanning.");
        describe("file.watch.excludeGlobs", "扫描时排除的 glob 模式列表。", "List of glob patterns excluded from scanning.");
        describe("file.watch.semantic.enabled", "是否为文件内容启用语义索引。", "Whether to enable semantic indexing for file content.");
        describe("file.watch.semantic.index-dir", "文件语义索引的保存目录。", "Directory where the file semantic index is stored.");

        describe("websearch.enabled", "是否允许 Agent 使用网络搜索。", "Whether to allow the agent to use web search.");
        describe("websearch.mcp-url", "网络搜索 MCP 服务地址。", "URL of the web-search MCP service.");
        describe("websearch.api-key", "网络搜索服务的 API 密钥。", "API key for the web-search service.");

        describe("app.language", "界面与提示词语言：auto、zh 或 en；修改后需重启。", "UI and prompt language: auto, zh, or en; restart required after changing it.");

        if (!DESCRIPTIONS.keySet().equals(KEYS.keySet())) {
            throw new IllegalStateException("Every supported key must have a bilingual description");
        }
    }

    /** All supported keys → default value, in declaration order. */
    public static LinkedHashMap<String, String> defaults() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        KEYS.forEach((k, v) -> m.put(k, v.defaultValue()));
        return m;
    }

    /** All supported keys → declared type, in declaration order. */
    public static LinkedHashMap<String, KeyType> types() {
        LinkedHashMap<String, KeyType> m = new LinkedHashMap<>();
        KEYS.forEach((k, v) -> m.put(k, v.type()));
        return m;
    }

    /** All supported keys → bilingual descriptions, in declaration order. */
    public static LinkedHashMap<String, Description> descriptions() {
        return new LinkedHashMap<>(DESCRIPTIONS);
    }

    /** Whether {@code key} is in the supported whitelist. */
    public static boolean contains(String key) {
        return KEYS.containsKey(key);
    }
}
