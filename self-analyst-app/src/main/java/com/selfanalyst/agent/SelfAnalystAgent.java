package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.headroom.HeadroomService;
import com.selfanalyst.i18n.Lang;
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
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class SelfAnalystAgent implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SelfAnalystAgent.class);

    private final ReActAgent agent;
    private final OpenAIChatModel plainModel;
    private final MemoryStore memory;
    private final ActivityWatchTools tools;
    private final WikiStore wikiStore;
    private final boolean semanticEnabled;
    private final boolean hasConfigTools;
    private final boolean hasFileTools;
    private final UsageMeter usageMeter;
    private final HeadroomService headroomService;
    private final McpClientWrapper webSearchMcpClient;
    private final Lang lang;
    private final AtomicBoolean chatRunning = new AtomicBoolean();
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
        this(config, wikiStore, wikiTools, userConfigStore, fileTools, usageMeter, null);
    }

    public SelfAnalystAgent(Config config, WikiStore wikiStore, WikiTools wikiTools,
                             UserConfigStore userConfigStore, FileTools fileTools,
                             UsageMeter usageMeter,
                             Supplier<String> audioRuntimeStatusSupplier) throws IOException {
        this(config, wikiStore, wikiTools, userConfigStore, fileTools, usageMeter,
                audioRuntimeStatusSupplier, null);
    }

    public SelfAnalystAgent(Config config, WikiStore wikiStore, WikiTools wikiTools,
                             UserConfigStore userConfigStore, FileTools fileTools,
                             UsageMeter usageMeter,
                             Supplier<String> audioRuntimeStatusSupplier,
                             HeadroomService headroomService) throws IOException {
        this.usageMeter = usageMeter;
        this.headroomService = headroomService;
        this.lang = config.effectiveLanguage();
        this.wikiStore = wikiStore;
        this.semanticEnabled = wikiTools != null && wikiTools.hasSemanticIndex();
        this.hasConfigTools = userConfigStore != null;
        this.hasFileTools = fileTools != null;
        this.memory = MemoryStore.load(config.memoryDir());
        this.tools = new ActivityWatchTools(config.awBaseUrl(), config.awTimeout());

        // AgentScope 2.x executes multiple tool calls in parallel by default. Keep the
        // 1.x sequential semantics because several tools share local stores/connections.
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        toolkit.registerTool(tools);
        if (wikiTools != null) {
            toolkit.registerTool(wikiTools);
        }
        if (fileTools != null) {
            toolkit.registerTool(fileTools);
        }
        if (userConfigStore != null) {
            toolkit.registerTool(new ConfigTools(userConfigStore, audioRuntimeStatusSupplier,
                    headroomService != null ? headroomService::runtimeStatusLine : null));
        }
        McpClientWrapper registeredWebSearchMcpClient = registerWebSearchMcp(toolkit, config);

        OpenAIChatModel builtPlainModel;
        ReActAgent builtAgent;
        try {
            Integer maxTokens = config.llmMaxTokens() > 0 ? config.llmMaxTokens() : null;
            String llmBaseUrl = effectiveLlmBaseUrl(config, headroomService);

            GenerateOptions.Builder chatOpts = GenerateOptions.builder()
                    .temperature(config.llmTemperature());
            if (maxTokens != null) chatOpts.maxTokens(maxTokens);
            OpenAIChatModel chatModel = OpenAIChatModel.builder()
                    .apiKey(config.llmApiKey())
                    .modelName(config.llmModel())
                    .baseUrl(llmBaseUrl)
                    .generateOptions(chatOpts.build())
                    .build();

            GenerateOptions.Builder plainOpts = GenerateOptions.builder()
                    .temperature(0.2)
                    .stream(false);
            if (maxTokens != null) plainOpts.maxTokens(maxTokens);
            builtPlainModel = OpenAIChatModel.builder()
                    .apiKey(config.llmApiKey())
                    .modelName(config.llmModel())
                    .baseUrl(llmBaseUrl)
                    .stream(false)
                    .generateOptions(plainOpts.build())
                    .build();

            builtAgent = ReActAgent.builder()
                    .name("SelfAnalyst")
                    .sysPrompt(buildSystemPrompt())
                    .model(chatModel)
                    .toolkit(toolkit)
                    .middlewares(List.of(
                            new DynamicMemoryContextMiddleware(
                                    lang, () -> memory.profile().buildContextSummary()),
                            new PlanMiddleware(usageMeter)))
                    .maxIters(config.agentMaxIters())
                    .modelExecutionConfig(ExecutionConfig.builder()
                            .timeout(Duration.ofSeconds(45))
                            .maxAttempts(1)
                            .build())
                    .build();
        } catch (RuntimeException | Error failure) {
            if (registeredWebSearchMcpClient != null) {
                try {
                    registeredWebSearchMcpClient.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
        this.plainModel = builtPlainModel;
        this.agent = builtAgent;
        this.webSearchMcpClient = registeredWebSearchMcpClient;
    }

    static String effectiveLlmBaseUrl(Config config, HeadroomService headroomService) {
        return headroomService != null ? headroomService.effectiveLlmBaseUrl() : config.llmBaseUrl();
    }

    /**
     * 接入 Parallel Search MCP 联网搜索。默认走匿名端点，无需 API key；
     * 配置了 websearch.api-key 则以 Bearer token 走更高额度。
     * 任意失败（网络、初始化超时）都只记录日志，不阻断 Agent 启动。
     */
    private McpClientWrapper registerWebSearchMcp(Toolkit toolkit, Config config) {
        if (!config.webSearchEnabled()) return null;
        String endpoint = config.webSearchMcpUrl();
        if (endpoint == null || endpoint.isBlank()) return null;
        McpClientWrapper client = null;
        try {
            McpClientBuilder builder = McpClientBuilder.create("parallel-search")
                    .streamableHttpTransport(endpoint)
                    .timeout(Duration.ofSeconds(30))
                    .initializationTimeout(Duration.ofSeconds(20));

            String key = config.webSearchApiKey();
            if (key != null && !key.isBlank()) {
                builder.header("Authorization", "Bearer " + key);
            }

            client = builder.buildSync();
            toolkit.registerMcpClient(client).block(Duration.ofSeconds(30));
            return client;
        } catch (Exception e) {
            if (client != null) {
                try {
                    client.close();
                } catch (RuntimeException closeFailure) {
                    e.addSuppressed(closeFailure);
                    log.warn("关闭失败的联网搜索 MCP 客户端时出错: {}",
                            closeFailure.getMessage());
                }
            }
            System.err.println("[SelfAnalyst] 联网搜索 MCP 不可用，已跳过: " + e.getMessage());
            return null;
        }
    }

    private String buildSystemPrompt() {
        return AgentPrompts.systemPrompt(lang, "",
                wikiStore != null, semanticEnabled, hasFileTools, hasConfigTools,
                ZonedDateTime.now());
    }

    public Mono<String> chat(String userInput) {
        return runExclusiveChat(() -> {
            if (usageMeter != null && usageMeter.isBlocked()) {
                return Mono.just(lang == Lang.EN
                        ? "The daily token budget has been reached; the conversation is paused to control cost. "
                          + "You can adjust llm.budget.dailyTokens / llm.budget.mode in the configuration, "
                          + "or wait for the automatic daily reset."
                        : "已达到今日 token 使用上限，已暂停对话以控制成本。"
                          + "可在配置中调整 llm.budget.dailyTokens / llm.budget.mode，或等待次日自动重置。");
            }
            return agent.call(Msg.builder()
                            .name("user")
                            .role(MsgRole.USER)
                            .textContent(userInput)
                    .build())
                    .map(Msg::getTextContent);
        });
    }

    Mono<String> runExclusiveChat(Supplier<Mono<String>> action) {
        return Mono.using(
                () -> {
                    if (!chatRunning.compareAndSet(false, true)) {
                        throw new IllegalStateException("Agent is still running");
                    }
                    return Boolean.TRUE;
                },
                ignored -> Mono.defer(action),
                ignored -> chatRunning.set(false),
                true);
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
        String plainSystemPrompt = AgentPrompts.plainCompletionPrompt(lang);
        List<Msg> messages = List.of(
                Msg.builder()
                        .name("system")
                        .role(MsgRole.SYSTEM)
                        .textContent(plainSystemPrompt)
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
                    long inEst = estimateTokens(plainSystemPrompt) + estimateTokens(userInput);
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

    @Override
    public void close() {
        try {
            agent.close();
        } finally {
            if (webSearchMcpClient != null) {
                try {
                    webSearchMcpClient.close();
                } catch (RuntimeException closeFailure) {
                    log.warn("关闭联网搜索 MCP 客户端时出错: {}", closeFailure.getMessage());
                }
            }
        }
    }
}
