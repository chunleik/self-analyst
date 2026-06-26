package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.memory.MemoryStore;
import com.selfanalyst.tools.ActivityWatchTools;
import com.selfanalyst.tools.ConfigTools;
import com.selfanalyst.usage.UsageMeter;
import com.selfanalyst.wiki.WikiStore;
import com.selfanalyst.wiki.WikiTools;
import com.selfanalyst.file.FileTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SelfAnalystAgent {

    private static final Logger log = LoggerFactory.getLogger(SelfAnalystAgent.class);

    private static final String BASE_SYSTEM_PROMPT = """
            你是 SelfAnalyst，一个基于数据的自我提升伙伴。

            你的使命：帮助用户持续提升自己。

            三层工作模式：
            1. 感知（Perceive）— 基于 ActivityWatch 数据呈现客观事实
            2. 认知（Understand）— 发现模式、对比基线、识别值得关注的信号
            3. 改进（Improve）— 给出具体、可验证的行动建议，并追踪上次建议的效果

            工作流程：
            1. 先回顾已知的用户目标、模式和最近改进记录
            2. 判断用户本次询问涉及感知/认知/改进的哪个层次
            3. 制定分析计划，告知用户你准备做什么
            4. 调用 ActivityWatch 工具获取数据
            5. 将数据转化为洞察，关联用户目标
            6. 如有新的模式或发现，明确告知用户"建议记录以下发现"

            关键原则：
            - 不要只给数据，要给判断
            - 建议必须具体可执行，避免"提高效率"这种废话
            - 主动追踪上次建议的结果，形成闭环
            - 用户设立的目标是分析的最高优先级锚点

            隐私与安全：
            - 历史摘要或屏幕内容中可能含有密码、密钥、Token 等敏感字符串
            - 不得向用户回显、引用或分析这类内容，识别到后直接忽略
            """;

    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE (z, OOOO)", java.util.Locale.CHINA);
    private static final String PROMPT_DATE_LINE =
            "当前本地时间：%s\n" +
            "ActivityWatch 存储的所有时间戳均为 UTC，向用户展示时须换算为本地时间。\n\n";
    private static final String PLAIN_COMPLETION_SYSTEM_PROMPT = """
            你是 SelfAnalyst 的摘要改写器。
            只根据用户提供的数据完成当前请求，不保留会话状态，不调用工具。
            """;

    private final ReActAgent agent;
    private final OpenAIChatModel plainModel;
    private final MemoryStore memory;
    private final ActivityWatchTools tools;
    private final WikiStore wikiStore;
    private final boolean semanticEnabled;
    private final boolean hasConfigTools;
    private final boolean hasFileTools;
    private final UsageMeter usageMeter;
    private volatile boolean usageMissingLogged;

    public SelfAnalystAgent(Config config) throws IOException {
        this(config, null, null, null, null, null);
    }

    public SelfAnalystAgent(Config config, WikiStore wikiStore,
                             WikiTools wikiTools) throws IOException {
        this(config, wikiStore, wikiTools, null, null, null);
    }

    public SelfAnalystAgent(Config config, WikiStore wikiStore,
                             WikiTools wikiTools, UserConfigStore userConfigStore) throws IOException {
        this(config, wikiStore, wikiTools, userConfigStore, null, null);
    }

    public SelfAnalystAgent(Config config, WikiStore wikiStore, WikiTools wikiTools,
                             UserConfigStore userConfigStore, FileTools fileTools) throws IOException {
        this(config, wikiStore, wikiTools, userConfigStore, fileTools, null);
    }

    public SelfAnalystAgent(Config config, WikiStore wikiStore, WikiTools wikiTools,
                             UserConfigStore userConfigStore, FileTools fileTools,
                             UsageMeter usageMeter) throws IOException {
        this.usageMeter = usageMeter;
        this.wikiStore = wikiStore;
        this.semanticEnabled = wikiTools != null && wikiTools.hasSemanticIndex();
        this.hasConfigTools = userConfigStore != null;
        this.hasFileTools = fileTools != null;
        this.memory = MemoryStore.load(config.memoryDir());
        this.tools = new ActivityWatchTools(config.awBaseUrl(), config.awTimeout());

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        if (wikiTools != null) {
            toolkit.registerTool(wikiTools);
        }
        if (fileTools != null) {
            toolkit.registerTool(fileTools);
        }
        if (userConfigStore != null) {
            toolkit.registerTool(new ConfigTools(userConfigStore));
        }
        registerWebSearchMcp(toolkit, config);

        Integer maxTokens = config.llmMaxTokens() > 0 ? config.llmMaxTokens() : null;

        GenerateOptions.Builder chatOpts = GenerateOptions.builder()
                .temperature(config.llmTemperature());
        if (maxTokens != null) chatOpts.maxTokens(maxTokens);
        OpenAIChatModel chatModel = OpenAIChatModel.builder()
                .apiKey(config.llmApiKey())
                .modelName(config.llmModel())
                .baseUrl(config.llmBaseUrl())
                .generateOptions(chatOpts.build())
                .build();

        GenerateOptions.Builder plainOpts = GenerateOptions.builder()
                .temperature(0.2)
                .stream(false);
        if (maxTokens != null) plainOpts.maxTokens(maxTokens);
        this.plainModel = OpenAIChatModel.builder()
                .apiKey(config.llmApiKey())
                .modelName(config.llmModel())
                .baseUrl(config.llmBaseUrl())
                .stream(false)
                .generateOptions(plainOpts.build())
                .build();

        this.agent = ReActAgent.builder()
                .name("SelfAnalyst")
                .sysPrompt(buildSystemPrompt())
                .model(chatModel)
                .toolkit(toolkit)
                .hook(new PlanHook(usageMeter))
                .maxIters(config.agentMaxIters())
                .modelExecutionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(45))
                        .maxAttempts(1)
                        .build())
                .build();
    }

    /**
     * 接入 Parallel Search MCP 联网搜索。默认走匿名端点，无需 API key；
     * 配置了 websearch.api-key 则以 Bearer token 走更高额度。
     * 任意失败（网络、初始化超时）都只记录日志，不阻断 Agent 启动。
     */
    private void registerWebSearchMcp(Toolkit toolkit, Config config) {
        if (!config.webSearchEnabled()) return;
        String endpoint = config.webSearchMcpUrl();
        if (endpoint == null || endpoint.isBlank()) return;
        try {
            McpClientBuilder builder = McpClientBuilder.create("parallel-search")
                    .streamableHttpTransport(endpoint)
                    .timeout(Duration.ofSeconds(30))
                    .initializationTimeout(Duration.ofSeconds(20));

            String key = config.webSearchApiKey();
            if (key != null && !key.isBlank()) {
                builder.header("Authorization", "Bearer " + key);
            }

            McpClientWrapper client = builder.buildSync();
            toolkit.registerMcpClient(client).block(Duration.ofSeconds(30));
        } catch (Exception e) {
            System.err.println("[SelfAnalyst] 联网搜索 MCP 不可用，已跳过: " + e.getMessage());
        }
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append(BASE_SYSTEM_PROMPT)
                .append(String.format(PROMPT_DATE_LINE, ZonedDateTime.now().format(DATE_TIME_FMT)))
                .append("\n\n## 关于用户的长期记忆\n\n")
                .append(memory.profile().buildContextSummary())
                .append("\n\n## LLM Wiki 时间摘要\n\n");
        if (wikiStore != null) {
            sb.append("你可以使用 WikiTools 查询用户过去时间段的活动摘要。当用户询问某时间段做了什么、" +
                    "任务分布、趋势变化、复盘对比时，优先调用 WikiTools。" +
                    "如果 Wiki 返回 pending 或 failed 区间，须明确说明摘要仍在生成或生成失败。");
            if (semanticEnabled) {
                sb.append("当用户问题只有主题、现象或任务描述而没有明确时间范围时，" +
                        "优先尝试 semanticSearchWiki 进行语义检索。");
            }
        } else {
            sb.append("Wiki 当前未启用。");
        }
        if (hasFileTools) {
            sb.append("\n\n## 文件索引\n\n")
              .append("你可以使用 FileTools 检索被监控目录中文件的摘要：")
              .append("searchFiles 按主题语义检索文件、listRecentFiles 按修改时间列出文件、")
              .append("getFileSummary 查看单个文件摘要、fileIndexStatus 查看索引进度。")
              .append("当用户询问某个文档/代码文件写了什么、最近改了哪些文件、")
              .append("或按主题查找本地文件时，调用这些工具。");
        }
        sb.append("\n\n当用户的问题需要实时、外部的网络信息（最新资讯、技术文档、本地数据无法回答的事实）时，" +
                "可使用联网搜索工具；涉及用户个人活动数据时，仍优先 ActivityWatch / Wiki 工具。");
        if (hasConfigTools) {
            sb.append("\n\n## 配置管理\n\n")
              .append("你可以使用 getConfig 工具查看 SelfAnalyst 当前所有配置项，")
              .append("使用 setConfigValue 工具修改单个配置项并持久化到文件。\n")
              .append("当用户要求切换模型、更新 API Key、开关联网搜索/音频/采集等功能、")
              .append("调整刷新频率等时，直接调用这些工具完成操作。\n")
              .append("修改后告知用户新值已保存，并明确说明是否需要重启 SelfAnalyst 才能生效。");
        }
        return sb.toString();
    }

    public Mono<String> chat(String userInput) {
        if (usageMeter != null && usageMeter.isBlocked()) {
            return Mono.just("已达到今日 token 使用上限，已暂停对话以控制成本。" +
                    "可在配置中调整 llm.budget.dailyTokens / llm.budget.mode，或等待次日自动重置。");
        }
        return agent.call(Msg.builder()
                        .textContent(userInput)
                .build())
                .map(Msg::getTextContent);
    }

    public boolean isBudgetBlocked() {
        return usageMeter != null && usageMeter.isBlocked();
    }

    public java.util.Map<String, Object> usageSnapshot() {
        return usageMeter != null ? usageMeter.snapshot()
                : java.util.Map.of("mode", "off", "status", "ok");
    }

    public String completePlain(String userInput, Duration timeout) {
        if (usageMeter != null) {
            usageMeter.enforce(UsageMeter.Category.SUMMARY);
        }
        List<Msg> messages = List.of(
                Msg.builder()
                        .name("system")
                        .role(MsgRole.SYSTEM)
                        .textContent(PLAIN_COMPLETION_SYSTEM_PROMPT)
                        .build(),
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .textContent(userInput)
                        .build());

        try {
            List<ChatResponse> responses = plainModel.stream(messages, List.of(), null)
                    .collectList()
                    .toFuture()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            StringBuilder sb = new StringBuilder();
            ChatUsage usage = null;
            for (ChatResponse r : responses) {
                sb.append(chatResponseText(r));
                if (r != null && r.getUsage() != null) {
                    usage = r.getUsage();
                }
            }
            String text = sb.toString().trim();
            if (usageMeter != null) {
                if (usage != null) {
                    usageMeter.record(UsageMeter.Category.SUMMARY,
                            usage.getInputTokens(), usage.getOutputTokens());
                } else {
                    // 供应商/SDK 未在流式响应中返回 usage：退回长度估算，避免预算被静默架空
                    long inEst = estimateTokens(PLAIN_COMPLETION_SYSTEM_PROMPT) + estimateTokens(userInput);
                    usageMeter.record(UsageMeter.Category.SUMMARY, inEst, estimateTokens(text));
                    if (!usageMissingLogged) {
                        usageMissingLogged = true;
                        log.warn("LLM 响应未返回 usage，摘要 token 改用长度估算计量（仅首次提示）");
                    }
                }
            }
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM call interrupted", e);
        } catch (TimeoutException e) {
            throw new RuntimeException("LLM call timed out after " + timeout, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("LLM call failed", cause);
        }
    }

    /** 粗略 token 估算（中英文混合按 ~3 字符/token），仅用于响应未带 usage 时的回退计量。 */
    private static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (text.length() + 2) / 3;
    }

    private static String chatResponseText(ChatResponse response) {
        if (response == null || response.getContent() == null) return "";
        return response.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }

    public MemoryStore memory() {
        return memory;
    }

    public java.util.function.Function<String, String> wikiLLMClient() {
        return prompt -> completePlain(prompt, Duration.ofMinutes(3));
    }

    public void saveMemory() throws IOException {
        memory.save();
    }
}
