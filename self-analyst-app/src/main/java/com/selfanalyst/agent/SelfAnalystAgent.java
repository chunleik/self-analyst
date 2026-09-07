package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.i18n.Lang;
import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.memory.MemoryStore;
import com.selfanalyst.tools.EventQueryTools;
import com.selfanalyst.tools.ConfigTools;
import com.selfanalyst.usage.UsageMeter;
import com.selfanalyst.wiki.WikiStore;
import com.selfanalyst.wiki.WikiTools;
import com.selfanalyst.file.FileTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class SelfAnalystAgent implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SelfAnalystAgent.class);
    private static final String DESKTOP_USER_ID = "desktop";
    private static final String LEGACY_SESSION_ID = "legacy_default";
    private static final Pattern DESKTOP_SESSION_ID = Pattern.compile("^[a-f0-9]{32}$");
    private static final Pattern DESKTOP_MESSAGE_ID = Pattern.compile("^[a-f0-9]{12}$");

    private final ReActAgent agent;
    private final OpenAIChatModel plainModel;
    private final MemoryStore memory;
    private final EventQueryTools tools;
    private final WikiStore wikiStore;
    private final boolean semanticEnabled;
    private final boolean hasConfigTools;
    private final boolean hasFileTools;
    private final UsageMeter usageMeter;
    private final McpClientWrapper webSearchMcpClient;
    private final AgentStateStore agentStateStore;
    private final TransactionalAgentStateCompactor stateCompactor;
    private final Lang lang;
    private final AtomicBoolean chatRunning = new AtomicBoolean();
    private final AtomicReference<ActiveDesktopChat> activeDesktopChat = new AtomicReference<>();
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
        this.lang = config.effectiveLanguage();
        this.wikiStore = wikiStore;
        this.semanticEnabled = wikiTools != null && wikiTools.hasSemanticIndex();
        this.hasConfigTools = userConfigStore != null;
        this.hasFileTools = fileTools != null;
        this.memory = MemoryStore.load(config.memoryDir());
        this.tools = new EventQueryTools(config.awBaseUrl(), config.awTimeout());

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
            toolkit.registerTool(new ConfigTools(userConfigStore));
        }
        Path stateRoot = chatStateRoot(config.memoryDir());
        AgentStateStore builtStateStore = new JsonFileAgentStateStore(stateRoot);
        McpClientWrapper registeredWebSearchMcpClient = registerWebSearchMcp(toolkit, config);

        OpenAIChatModel builtPlainModel;
        ReActAgent builtAgent;
        try {
            Integer maxTokens = config.llmMaxTokens() > 0 ? config.llmMaxTokens() : null;
            String llmBaseUrl = config.llmBaseUrl();

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
                            new ConversationContextMiddleware(),
                            new DynamicMemoryContextMiddleware(
                                    lang, () -> memory.profile().buildContextSummary()),
                            new PlanMiddleware(usageMeter)))
                    .stateStore(builtStateStore)
                    .defaultSessionId(LEGACY_SESSION_ID)
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
            try {
                builtStateStore.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        this.plainModel = builtPlainModel;
        this.agent = builtAgent;
        this.webSearchMcpClient = registeredWebSearchMcpClient;
        this.agentStateStore = builtStateStore;
        if (config.agentCompactionEnabled()) {
            io.agentscope.core.model.Model compactionModel = new UsageMeteredModel(
                    builtPlainModel, usageMeter, UsageMeter.Category.SUMMARY);
            CompactionConfig compactionConfig = CompactionConfig.builder()
                    .triggerMessages(config.agentCompactionTriggerMessages())
                    .triggerTokens(config.agentCompactionTriggerTokens())
                    .keepMessages(config.agentCompactionKeepMessages())
                    .keepTokens(config.agentCompactionKeepTokens())
                    .flushBeforeCompact(false)
                    .offloadBeforeCompact(false)
                    .model(compactionModel)
                    .build();
            this.stateCompactor = new TransactionalAgentStateCompactor(
                    builtAgent,
                    config.memoryDir().resolve("agent-workspace").resolve("self-analyst-chat"),
                    compactionModel,
                    compactionConfig);
        } else {
            this.stateCompactor = null;
        }
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
        return chatInternal(LEGACY_SESSION_ID, null,
                () -> new PersistedDesktopTurn(userInput, null, null));
    }

    public Mono<String> chat(String sessionId, String userInput) {
        return chat(sessionId, null, userInput);
    }

    public Mono<String> chat(String sessionId, String userMessageId, String userInput) {
        String validatedSessionId = requireDesktopSessionId(sessionId);
        String validatedMessageId = requireDesktopMessageId(userMessageId);
        return chatInternal(validatedSessionId, validatedMessageId,
                () -> new PersistedDesktopTurn(userInput, null, null));
    }

    /**
     * Calls one persisted desktop session. The history supplier is evaluated only after the
     * application-wide chat gate has been acquired. Returning {@code null} means that the visible
     * session was deleted before execution; a non-null list is also used to lazily seed chats that
     * predate AgentState persistence.
     */
    public Mono<String> chat(
            String sessionId,
            String userMessageId,
            String userInput,
            Supplier<List<Msg>> existingSessionHistorySupplier) {
        String validatedSessionId = requireDesktopSessionId(sessionId);
        String validatedMessageId = requireDesktopMessageId(userMessageId);
        return chatInternal(validatedSessionId, validatedMessageId, () -> {
            List<Msg> history = existingSessionHistorySupplier != null
                    ? existingSessionHistorySupplier.get() : null;
            return existingSessionHistorySupplier != null && history == null
                    ? null : new PersistedDesktopTurn(userInput, null, history);
        });
    }

    /** Session-owned turn loaded under the application gate. */
    public record PersistedDesktopTurn(
            String userInput, Object contextSnapshot, List<Msg> existingHistory) {
        public PersistedDesktopTurn {
            userInput = userInput != null ? userInput : "";
            existingHistory = existingHistory != null ? List.copyOf(existingHistory) : null;
        }
    }

    public Mono<String> chat(
            String sessionId,
            String userMessageId,
            Supplier<PersistedDesktopTurn> persistedTurnSupplier) {
        String validatedSessionId = requireDesktopSessionId(sessionId);
        String validatedMessageId = requireDesktopMessageId(userMessageId);
        if (persistedTurnSupplier == null) {
            throw new IllegalArgumentException("Persisted desktop turn supplier is required");
        }
        return chatInternal(validatedSessionId, validatedMessageId, persistedTurnSupplier);
    }

    /** Streams one persisted desktop turn as model deltas followed by its canonical result. */
    public Flux<ChatStreamEvent> chatStream(
            String sessionId,
            String userMessageId,
            Supplier<PersistedDesktopTurn> persistedTurnSupplier) {
        String validatedSessionId = requireDesktopSessionId(sessionId);
        String validatedMessageId = requireDesktopMessageId(userMessageId);
        if (persistedTurnSupplier == null) {
            throw new IllegalArgumentException("Persisted desktop turn supplier is required");
        }
        return chatStreamInternal(validatedSessionId, validatedMessageId, persistedTurnSupplier);
    }

    public enum ChatStreamEventType { DELTA, RESULT }

    public record ChatStreamEvent(ChatStreamEventType type, String text) {
        public ChatStreamEvent {
            Objects.requireNonNull(type, "type");
            text = text != null ? text : "";
        }

        static ChatStreamEvent delta(String text) {
            return new ChatStreamEvent(ChatStreamEventType.DELTA, text);
        }

        static ChatStreamEvent result(String text) {
            return new ChatStreamEvent(ChatStreamEventType.RESULT, text);
        }
    }

    private Mono<String> chatInternal(
            String sessionId,
            String userMessageId,
            Supplier<PersistedDesktopTurn> persistedTurnSupplier) {
        return chatStreamInternal(sessionId, userMessageId, persistedTurnSupplier)
                .filter(event -> event.type() == ChatStreamEventType.RESULT)
                .map(ChatStreamEvent::text)
                .takeLast(1)
                .single();
    }

    private Flux<ChatStreamEvent> chatStreamInternal(
            String sessionId,
            String userMessageId,
            Supplier<PersistedDesktopTurn> persistedTurnSupplier) {
        ActiveDesktopChat activeChat = new ActiveDesktopChat(sessionId, userMessageId);
        return runExclusiveStream(activeChat, () -> Flux.defer(() -> {
            PersistedDesktopTurn turn = persistedTurnSupplier.get();
            if (turn == null) throw new ChatSessionUnavailableException(sessionId);
            if (usageMeter != null && usageMeter.isBlocked()) {
                return Flux.just(ChatStreamEvent.result(budgetBlockedMessage()));
            }
            seedSessionHistoryIfAbsent(sessionId, turn.existingHistory());
            CallPreparation prepared;
            try {
                prepared = prepareCall(sessionId, userMessageId, turn.userInput());
            } catch (RuntimeException | Error failure) {
                agent.clearStateCache(DESKTOP_USER_ID, sessionId);
                throw failure;
            }
            if (prepared.completedReply() != null) {
                agent.clearStateCache(DESKTOP_USER_ID, sessionId);
                return Flux.just(ChatStreamEvent.result(prepared.completedReply()));
            }
            Mono<Boolean> compact = stateCompactor != null
                    ? stateCompactor.compactIfNeeded(DESKTOP_USER_ID, sessionId)
                    : Mono.just(false);
            return compact.thenMany(Flux.defer(() -> {
                if (usageMeter != null && usageMeter.isBlocked()) {
                    agent.clearStateCache(DESKTOP_USER_ID, sessionId);
                    return Flux.just(ChatStreamEvent.result(budgetBlockedMessage()));
                }
                RuntimeContext.Builder contextBuilder = RuntimeContext.builder()
                        .userId(DESKTOP_USER_ID)
                        .sessionId(sessionId);
                if (turn.contextSnapshot() != null) {
                    contextBuilder.put(ConversationContextMiddleware.DesktopTurnContext.class,
                            new ConversationContextMiddleware.DesktopTurnContext(
                                    turn.contextSnapshot()));
                }
                RuntimeContext context = contextBuilder.build();
                // With a persistent store every call reloads its slot. Eagerly evict the local
                // cache after completion/error/cancel so long-running desktop sessions stay bounded.
                return Flux.defer(() -> {
                    StringBuilder streamedText = new StringBuilder();
                    if (!activeChat.beginModelCall()) {
                        return Flux.error(new ChatCancelledException(userMessageId));
                    }
                    return Flux.using(
                                () -> context,
                                activeContext -> agent.streamEvents(
                                                prepared.messages(), activeContext)
                                        .handle((event, sink) -> {
                                            if (event instanceof AgentResultEvent) {
                                                if (activeChat.completeModelCall()) {
                                                    mapStreamEvent(event, sink, streamedText,
                                                            sessionId, userMessageId);
                                                }
                                            } else if (!activeChat.cancelled()) {
                                                mapStreamEvent(event, sink, streamedText,
                                                        sessionId, userMessageId);
                                            }
                                        }),
                                ignored -> agent.clearStateCache(DESKTOP_USER_ID, sessionId),
                                true);
                });
            }));
        })).transform(SelfAnalystAgent::requireTerminalChatResult);
    }

    private static Flux<ChatStreamEvent> requireTerminalChatResult(
            Flux<ChatStreamEvent> source) {
        return Flux.defer(() -> {
            AtomicBoolean resultSeen = new AtomicBoolean();
            return source
                    .doOnNext(event -> {
                        if (event.type() == ChatStreamEventType.RESULT) {
                            resultSeen.set(true);
                        }
                    })
                    .concatWith(Flux.defer(() -> resultSeen.get()
                            ? Flux.empty()
                            : Flux.error(new EmptyAgentResponseException())));
        });
    }

    private void mapStreamEvent(
            AgentEvent event,
            reactor.core.publisher.SynchronousSink<ChatStreamEvent> sink,
            StringBuilder streamedText,
            String sessionId,
            String userMessageId) {
        if (event instanceof TextBlockDeltaEvent delta) {
            if (delta.getDelta() != null && !delta.getDelta().isEmpty()) {
                streamedText.append(delta.getDelta());
                sink.next(ChatStreamEvent.delta(delta.getDelta()));
            }
        } else if (event instanceof AgentResultEvent result) {
            sink.next(ChatStreamEvent.result(
                    finalizeChatResponse(
                            sessionId, userMessageId, result.getResult(), streamedText.toString())));
        }
    }

    String finalizeChatResponse(
            String sessionId, String userMessageId, Msg finalMessage, String streamedText) {
        String finalText = finalMessage != null ? finalMessage.getTextContent() : null;
        if (finalText != null && !finalText.isBlank()) return finalText;
        String fallback = canonicalResponseText(finalText, streamedText);
        return persistFallbackTerminalReply(
                sessionId, userMessageId, finalMessage, fallback);
    }

    private String persistFallbackTerminalReply(
            String sessionId, String userMessageId, Msg finalMessage, String fallback) {
        AgentState state = agent.getAgentState(DESKTOP_USER_ID, sessionId);
        List<Msg> context = state.contextMutable();
        int userIndex = -1;
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg candidate = context.get(i);
            if (candidate.getRole() == MsgRole.USER
                    && (userMessageId == null || userMessageId.equals(candidate.getId()))) {
                userIndex = i;
                break;
            }
        }
        if (userIndex < 0) throw new EmptyAgentResponseException();

        int terminalIndex = -1;
        for (int i = userIndex + 1; i < context.size(); i++) {
            Msg candidate = context.get(i);
            if (candidate.getRole() == MsgRole.USER) break;
            if (candidate.getRole() != MsgRole.ASSISTANT
                    || candidate.getGenerateReason() == GenerateReason.TOOL_CALLS
                    || candidate.getContent().stream().anyMatch(ToolUseBlock.class::isInstance)) {
                continue;
            }
            terminalIndex = i;
            if (finalMessage != null && finalMessage.getId() != null
                    && finalMessage.getId().equals(candidate.getId())) {
                break;
            }
        }
        if (terminalIndex < 0) throw new EmptyAgentResponseException();

        Msg terminal = context.get(terminalIndex);
        if (terminal.getTextContent() != null && !terminal.getTextContent().isBlank()) {
            return terminal.getTextContent();
        }
        List<ContentBlock> replacement = new ArrayList<>();
        terminal.getContent().stream()
                .filter(block -> !(block instanceof TextBlock))
                .forEach(replacement::add);
        replacement.add(TextBlock.builder().text(fallback).build());
        context.set(terminalIndex, terminal.withContent(replacement));
        agent.saveAgentState(DESKTOP_USER_ID, sessionId);
        return fallback;
    }

    static String canonicalResponseText(String finalText, String streamedText) {
        if (finalText != null && !finalText.isBlank()) return finalText;
        if (streamedText != null && !streamedText.isBlank()) return streamedText;
        throw new EmptyAgentResponseException();
    }

    private String budgetBlockedMessage() {
        return lang == Lang.EN
                ? "The daily token budget has been reached; the conversation is paused to control cost. "
                  + "You can adjust llm.budget.dailyTokens / llm.budget.mode in the configuration, "
                  + "or wait for the automatic daily reset."
                : "已达到今日 token 使用上限，已暂停对话以控制成本。"
                  + "可在配置中调整 llm.budget.dailyTokens / llm.budget.mode，或等待次日自动重置。";
    }

    private void seedSessionHistoryIfAbsent(String sessionId, List<Msg> existingHistory) {
        if (existingHistory == null) return;
        // Check the actual authoritative key, not just the directory. A corrupt state read is
        // deliberately allowed to fail the call instead of being silently overwritten by a fresh
        // transcript migration.
        AgentState persisted = agentStateStore.get(
                DESKTOP_USER_ID, sessionId, "agent_state", AgentState.class).orElse(null);
        if (persisted != null) {
            if (!Objects.equals(DESKTOP_USER_ID, persisted.getUserId())
                    || !Objects.equals(sessionId, persisted.getSessionId())) {
                throw new IllegalStateException(
                        "Persisted AgentState identity does not match its desktop session slot");
            }
            return;
        }
        if (existingHistory.isEmpty()) return;
        AgentState state = agent.getAgentState(DESKTOP_USER_ID, sessionId);
        try {
            if (state.getContext().isEmpty()) {
                state.contextMutable().addAll(List.copyOf(existingHistory));
                agent.saveAgentState(DESKTOP_USER_ID, sessionId);
                log.info("Migrated {} transcript messages into AgentState for session {}",
                        existingHistory.size(), sessionId);
            }
        } finally {
            agent.clearStateCache(DESKTOP_USER_ID, sessionId);
        }
    }

    private CallPreparation prepareCall(
            String sessionId, String userMessageId, String userInput) {
        if (userMessageId != null) {
            List<Msg> context = agent.getAgentState(DESKTOP_USER_ID, sessionId).getContext();
            for (int i = context.size() - 1; i >= 0; i--) {
                Msg existingUser = context.get(i);
                if (existingUser.getRole() != MsgRole.USER
                        || !userMessageId.equals(existingUser.getId())) {
                    continue;
                }
                String completedReply = null;
                boolean hasLaterUserTurn = false;
                for (int j = i + 1; j < context.size(); j++) {
                    Msg candidate = context.get(j);
                    if (candidate.getRole() == MsgRole.USER) {
                        hasLaterUserTurn = true;
                        break;
                    }
                    if (candidate.getRole() == MsgRole.ASSISTANT
                            && candidate.getGenerateReason() != GenerateReason.TOOL_CALLS
                            && candidate.getContent().stream()
                                    .noneMatch(ToolUseBlock.class::isInstance)
                            && candidate.getTextContent() != null
                            && !candidate.getTextContent().isBlank()) {
                        // AgentScope records every reasoning assistant turn before tool execution.
                        // Keep walking so retries return the terminal assistant for this user turn.
                        completedReply = candidate.getTextContent();
                    }
                }
                if (completedReply == null && hasLaterUserTurn) {
                    throw new StaleChatTurnException(userMessageId);
                }
                return new CallPreparation(List.of(), completedReply);
            }
        }
        Msg.Builder builder = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(userInput);
        if (userMessageId != null) builder.id(userMessageId);
        return new CallPreparation(List.of(builder.build()), null);
    }

    private record CallPreparation(List<Msg> messages, String completedReply) {
    }

    public void deleteChatSessionState(String sessionId) {
        deleteChatSessionStateThen(sessionId, () -> null);
    }

    /**
     * Deletes hidden AgentState and then performs the visible transcript mutation without releasing
     * the application chat gate between those operations.
     */
    public <T> T deleteChatSessionStateThen(String sessionId, Supplier<T> transcriptDeletion) {
        return deleteChatSessionStateWithIntent(sessionId, () -> { }, transcriptDeletion);
    }

    /**
     * Runs the durable deletion intent, AgentState deletion and transcript deletion under the same
     * application chat gate. A busy gate rejects before the intent is written, so HTTP 409 never
     * means that an irreversible deletion has already started.
     */
    public <T> T deleteChatSessionStateWithIntent(
            String sessionId,
            Runnable durableIntent,
            Supplier<T> transcriptDeletion) {
        String validated = requireDesktopSessionId(sessionId);
        return runExclusive(() -> Mono.fromCallable(() -> {
            durableIntent.run();
            agent.clearStateCache(DESKTOP_USER_ID, validated);
            agentStateStore.delete(DESKTOP_USER_ID, validated);
            return transcriptDeletion.get();
        })).block();
    }

    boolean hasChatSessionState(String sessionId) {
        return agentStateStore.exists(DESKTOP_USER_ID, requireDesktopSessionId(sessionId));
    }

    private static String requireDesktopSessionId(String sessionId) {
        if (sessionId == null || !DESKTOP_SESSION_ID.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("Invalid desktop chat session id");
        }
        return sessionId;
    }

    private static String requireDesktopMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) return null;
        if (!DESKTOP_MESSAGE_ID.matcher(messageId).matches()) {
            throw new IllegalArgumentException("Invalid desktop chat message id");
        }
        return messageId;
    }

    /** Delete persisted desktop AgentState even when the model/agent failed to initialize. */
    public static void deletePersistedChatSessionState(Path memoryDir, String sessionId) {
        String validated = requireDesktopSessionId(sessionId);
        Path root = chatStateRoot(memoryDir);
        if (!Files.exists(root)) return;
        AgentStateStore store = new JsonFileAgentStateStore(root);
        try {
            store.delete(DESKTOP_USER_ID, validated);
        } finally {
            store.close();
        }
    }

    private static Path chatStateRoot(Path memoryDir) {
        return memoryDir.resolve("agent-state")
                .resolve("self-analyst-chat")
                .toAbsolutePath()
                .normalize();
    }

    Mono<String> runExclusiveChat(Supplier<Mono<String>> action) {
        return runExclusive(action);
    }

    <T> Flux<T> runExclusiveChatStream(Supplier<Flux<T>> action) {
        return runExclusiveStream(action);
    }

    <T> Flux<T> runExclusiveDesktopChatStream(
            String sessionId, String userMessageId, Supplier<Flux<T>> action) {
        return runExclusiveStream(new ActiveDesktopChat(sessionId, userMessageId), action);
    }

    private <T> Mono<T> runExclusive(Supplier<Mono<T>> action) {
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

    private <T> Flux<T> runExclusiveStream(Supplier<Flux<T>> action) {
        return runExclusiveStream(null, action);
    }

    private <T> Flux<T> runExclusiveStream(
            ActiveDesktopChat desktopChat, Supplier<Flux<T>> action) {
        return Flux.using(
                () -> {
                    if (!chatRunning.compareAndSet(false, true)) {
                        throw new IllegalStateException("Agent is still running");
                    }
                    if (desktopChat != null && !activeDesktopChat.compareAndSet(null, desktopChat)) {
                        chatRunning.set(false);
                        throw new IllegalStateException("Agent is still running");
                    }
                    return Boolean.TRUE;
                },
                ignored -> Flux.defer(action),
                ignored -> {
                    boolean cancelled = desktopChat != null && desktopChat.close();
                    try {
                        if (cancelled) {
                            rollbackCancelledTurn(
                                    desktopChat.sessionId, desktopChat.userMessageId);
                        }
                    } finally {
                        if (desktopChat != null) {
                            agent.clearStateCache(DESKTOP_USER_ID, desktopChat.sessionId);
                            activeDesktopChat.compareAndSet(desktopChat, null);
                        }
                        chatRunning.set(false);
                    }
                },
                true);
    }

    public static final class ChatSessionUnavailableException extends IllegalStateException {
        public ChatSessionUnavailableException(String sessionId) {
            super("Desktop chat session no longer exists: " + sessionId);
        }
    }

    public static final class StaleChatTurnException extends IllegalStateException {
        public StaleChatTurnException(String userMessageId) {
            super("Cannot resume a non-terminal turn after a newer user message: "
                    + userMessageId);
        }
    }

    public static final class ChatCancelledException extends IllegalStateException {
        public ChatCancelledException(String userMessageId) {
            super("Desktop chat turn was cancelled: " + userMessageId);
        }
    }

    public static final class EmptyAgentResponseException extends IllegalStateException {
        public EmptyAgentResponseException() {
            super("Model returned no text");
        }
    }

    public boolean isBudgetBlocked() {
        return usageMeter != null && usageMeter.isBlocked();
    }

    /** Requests cancellation only when the ids still identify the live desktop call. */
    public boolean cancelChat(String sessionId, String userMessageId) {
        String validated = requireDesktopSessionId(sessionId);
        String validatedMessage = requireDesktopMessageId(userMessageId);
        ActiveDesktopChat active = activeDesktopChat.get();
        if (active == null || !active.matches(validated, validatedMessage)) {
            return false;
        }
        Msg cancellation = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("用户取消了当前回复")
                .build();
        return active.cancel(
                () -> agent.interrupt(DESKTOP_USER_ID, validated, cancellation));
    }

    void rollbackCancelledTurn(String sessionId, String userMessageId) {
        AgentState state = agent.getAgentState(DESKTOP_USER_ID, sessionId);
        List<Msg> context = state.contextMutable();
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg message = context.get(i);
            if (message.getRole() == MsgRole.USER && userMessageId.equals(message.getId())) {
                context.subList(i, context.size()).clear();
                break;
            }
        }
        state.interruptControl().reset();
        state.setShutdownInterrupted(false);
        state.setCurIter(0);
        state.setReplyId(null);
        agent.saveAgentState(DESKTOP_USER_ID, sessionId);
    }

    private static final class ActiveDesktopChat {
        private final String sessionId;
        private final String userMessageId;
        private volatile int lifecycle;
        private boolean modelCallStarted;

        private ActiveDesktopChat(String sessionId, String userMessageId) {
            this.sessionId = sessionId;
            this.userMessageId = userMessageId;
        }

        private boolean matches(String sessionId, String userMessageId) {
            return Objects.equals(this.sessionId, sessionId)
                    && Objects.equals(this.userMessageId, userMessageId);
        }

        private synchronized boolean cancel(Runnable interrupt) {
            if (lifecycle != 0) return false;
            lifecycle = 1;
            if (modelCallStarted) interrupt.run();
            return true;
        }

        private synchronized boolean beginModelCall() {
            if (lifecycle != 0) return false;
            modelCallStarted = true;
            return true;
        }

        private synchronized boolean completeModelCall() {
            if (lifecycle != 0 || !modelCallStarted) return false;
            lifecycle = 3;
            return true;
        }

        private boolean cancelled() {
            return lifecycle == 1;
        }

        private synchronized boolean close() {
            boolean rollbackRequired = lifecycle == 1 && modelCallStarted;
            lifecycle = 2;
            return rollbackRequired;
        }
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

    /** jtokkit (cl100k_base) 精确计数，仅用于响应未带 usage 时的回退计量。 */
    private static long estimateTokens(String text) {
        return TokenEstimator.estimateTokens(text);
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
            try {
                if (webSearchMcpClient != null) {
                    try {
                        webSearchMcpClient.close();
                    } catch (RuntimeException closeFailure) {
                        log.warn("关闭联网搜索 MCP 客户端时出错: {}", closeFailure.getMessage());
                    }
                }
            } finally {
                agentStateStore.close();
            }
        }
    }
}
