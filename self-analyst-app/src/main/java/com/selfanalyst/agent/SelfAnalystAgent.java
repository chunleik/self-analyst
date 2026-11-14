package com.selfanalyst.agent;

import com.selfanalyst.chatgpt.ChatGptSessionService;
import com.selfanalyst.chatgpt.ChatGptResponsesModel;
import io.agentscope.core.model.Model;
import com.selfanalyst.config.Config;
import com.selfanalyst.config.ConfigApplicationService;
import com.selfanalyst.config.LlmSettings;
import com.selfanalyst.config.LlmRuntimeManager;
import com.selfanalyst.i18n.Lang;
import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.memory.MemoryStore;
import com.selfanalyst.tools.EventQueryTools;
import com.selfanalyst.tools.ConfigTools;
import com.selfanalyst.usage.UsageMeter;
import com.selfanalyst.wiki.WikiStore;
import com.selfanalyst.wiki.WikiTools;
import com.selfanalyst.wiki.WikiSummaryPipeline.CallFailure;
import com.selfanalyst.wiki.WikiSummaryPipeline.Completion;
import com.selfanalyst.wiki.WikiSummaryPipeline.FailureKind;
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
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class SelfAnalystAgent implements AutoCloseable {
    private volatile AgentObservability observability;
    public void setObservability(AgentObservability value) { observability = value; }

    private static final Logger log = LoggerFactory.getLogger(SelfAnalystAgent.class);
    private static final String DESKTOP_USER_ID = "desktop";
    private static final String LEGACY_SESSION_ID = "legacy_default";
    private static final Pattern DESKTOP_SESSION_ID = Pattern.compile("^[a-f0-9]{32}$");
    private static final Pattern DESKTOP_MESSAGE_ID = Pattern.compile("^[a-f0-9]{12}$");

    private volatile ReActAgent agent;
    private final Config startupConfig;
    private final Toolkit toolkit;
    private final ConfigApplicationService configuration;
    private final LlmRuntimeManager<AgentRuntime> runtimes;
    private final ChatGptSessionService chatGptSessions;
    private final com.selfanalyst.chatgpt.ChatGptInferenceGate chatGptGate = new com.selfanalyst.chatgpt.ChatGptInferenceGate();
    private final AtomicBoolean closed = new AtomicBoolean();
    private record AgentRuntime(ReActAgent agent, Model plain, Model chat,
                                TransactionalAgentStateCompactor compactor, OwnedModelTransport transport) implements AutoCloseable {
        @Override public void close() {
            try { agent.close(); }
            finally { if (transport != null) transport.close(); }
            if (chat instanceof ChatGptResponsesModel responses) responses.close();
            if (plain instanceof ChatGptResponsesModel responses) responses.close();
            if (chat instanceof com.selfanalyst.chatgpt.ChatGptInferenceGate.GuardedModel guarded) guarded.close();
            if (plain instanceof com.selfanalyst.chatgpt.ChatGptInferenceGate.GuardedModel guarded) guarded.close();
        }
    }
    /** The SDK default follows redirects, which would change an approved mail-data recipient. */
    private static final class OwnedModelTransport implements AutoCloseable {
        private final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30)).build();
        private final io.agentscope.core.model.transport.JdkHttpTransport transport =
                new io.agentscope.core.model.transport.JdkHttpTransport(client,
                        io.agentscope.core.model.transport.HttpTransportConfig.defaults());
        @Override public void close() { transport.close(); client.shutdownNow(); }
    }
    private final MemoryStore memory;
    private final EventQueryTools tools;
    private final WikiStore wikiStore;
    private final boolean semanticEnabled;
    private final boolean hasConfigTools;
    private final boolean hasFileTools;
    private final UsageMeter usageMeter;
    private volatile AgentIntegrationGateway integrationGateway;
    private volatile com.selfanalyst.integrations.policy.MailSessionPolicy mailSessionPolicy;
    private volatile LlmSettings leasedChatSettings;
    private final java.util.Map<ReActAgent, Set<String>> integrationNames = new java.util.WeakHashMap<>();

    public void setIntegrationGateway(AgentIntegrationGateway gateway) { this.integrationGateway = gateway; }
    public void setMailSessionPolicy(com.selfanalyst.integrations.policy.MailSessionPolicy policy) {
        this.mailSessionPolicy = java.util.Objects.requireNonNull(policy);
    }
    public String currentModelTarget() { return com.selfanalyst.integrations.policy.ModelRecipient.from(runtimes.settings()); }

    private void requireChatModelTarget(LlmSettings settings) {
        var policy = mailSessionPolicy;
        var active = activeDesktopChat.get();
        if (policy != null && active != null && !LEGACY_SESSION_ID.equals(active.sessionId))
            policy.requireModelTarget(active.sessionId, com.selfanalyst.integrations.policy.ModelRecipient.from(settings));
    }

    private void registerNativeTools(Object toolObject) {
        Toolkit discovered = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        discovered.registerTool(toolObject);
        for (String name : discovered.getToolNames()) {
            var guarded = AgentObservability.nativeTool(new MailNativeToolGuard(discovered.getTool(name), () -> mailSessionPolicy, () -> integrationGateway), () -> observability);
            toolkit.registerAgentTool(guarded);
            if (agent != null && agent.getToolkit() != toolkit) agent.getToolkit().registerAgentTool(guarded);
        }
    }

    private void installTurnTools(AgentIntegrationGateway.TurnContext context, AgentIntegrationGateway gateway) {
        List<io.agentscope.core.tool.AgentTool> snapshot = gateway == null || LEGACY_SESSION_ID.equals(context.sessionId()) ? List.of() : List.copyOf(gateway.snapshot(context));
        java.util.Set<String> names = new java.util.HashSet<>();
        for (var tool : snapshot) {
            if (toolkit.getToolNames().contains(tool.getName()) || !names.add(tool.getName()))
                throw new IllegalStateException("INTEGRATION_TOOL_NAME_COLLISION");
        }
        // Only the exclusive chat gate publishes a new surface, before prepareRun. No in-flight mutation.
        for (String old : integrationNames.getOrDefault(agent, Set.of())) agent.getToolkit().removeTool(old);
        for (var tool : snapshot) agent.getToolkit().registerAgentTool(tool);
        integrationNames.put(agent, Set.copyOf(names));
    }

    private final AgentStateStore agentStateStore;
    private volatile TransactionalAgentStateCompactor stateCompactor;
    private final Lang lang;
    private final AtomicBoolean chatRunning = new AtomicBoolean();
    private final AtomicReference<ActiveDesktopChat> activeDesktopChat = new AtomicReference<>();
    private volatile boolean usageMissingLogged;
    private volatile com.selfanalyst.desktop.service.SummaryRepairChatService summaryRepairChatService;

    public void setSummaryRepairChatService(com.selfanalyst.desktop.service.SummaryRepairChatService service) {
        this.summaryRepairChatService = service;
    }
    // 仅在既有应用级 gate 内设置，整轮（包括工具内保存）保持不变。
    private volatile boolean runtimesForChatAvailable;

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
        this(config, wikiStore, wikiTools, userConfigStore, fileTools, usageMeter,
                new com.selfanalyst.config.PrivacyRuntime(config).modelPolicy());
    }

    public SelfAnalystAgent(Config config, WikiStore wikiStore, WikiTools wikiTools,
                             UserConfigStore userConfigStore, FileTools fileTools,
                             UsageMeter usageMeter,
                             java.util.function.Supplier<com.selfanalyst.wiki.WikiPrivacyPolicy> privacy) throws IOException {
        this.startupConfig = config;
        this.chatGptSessions = new ChatGptSessionService(config.memoryDir().resolve("chatgpt-auth"));
        this.configuration = userConfigStore == null ? null : userConfigStore.application(config);
        this.usageMeter = usageMeter;
        this.lang = config.effectiveLanguage();
        this.wikiStore = wikiStore;
        this.semanticEnabled = wikiTools != null && wikiTools.hasSemanticIndex();
        this.hasConfigTools = userConfigStore != null;
        this.hasFileTools = fileTools != null;
        this.memory = MemoryStore.load(config.memoryDir());
        this.tools = new EventQueryTools(config.eventsBaseUrl(), config.eventsTimeout(), privacy);

        // AgentScope 2.x executes multiple tool calls in parallel by default. Keep the
        // 1.x sequential semantics because several tools share local stores/connections.
        this.toolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        registerNativeTools(tools);
        if (wikiTools != null) {
            registerNativeTools(wikiTools);
        }
        if (fileTools != null) {
            registerNativeTools(fileTools);
        }
        if (userConfigStore != null) {
            registerNativeTools(new ConfigTools(userConfigStore));
        }
        Path stateRoot = chatStateRoot(config.memoryDir());
        AgentStateStore builtStateStore = new JsonFileAgentStateStore(stateRoot);
        this.agentStateStore = builtStateStore;
        try {
            this.runtimes = new LlmRuntimeManager<>(
                    configuration == null ? new Object() : configuration.commitLock(),
                    LlmSettings.from(config), this::buildRuntime, this::connectionAvailable);
            try (var lease = runtimes.acquire()) { selectRuntime(lease); }
            if (configuration != null) configuration.attach(runtimes);
        } catch (RuntimeException | Error failure) {
            builtStateStore.close();
            chatGptSessions.close();
            throw failure;
        }
    }

    private AgentRuntime buildRuntime(LlmSettings settings) {
        OwnedModelTransport ownedTransport = settings.usesChatGpt() ? null : new OwnedModelTransport();
        try {
        Config config = startupConfig;
        Model builtPlainModel;
        Model chatModel;
        ReActAgent builtAgent;
            String llmBaseUrl = settings.baseUrl();

            if (settings.usesChatGpt()) {
                // Capture the account in the leased runtime; refresh never changes this identity.
                java.util.function.Supplier<String> token = () -> chatGptSessions.accessToken(settings.chatgptAccount());
                String selectedModel = settings.chatgptModel().isBlank() ? "UNCONFIGURED" : settings.chatgptModel();
                chatModel = chatGptGate.guard(settings.chatgptAccount(), chatGptModel(selectedModel, token, false));
                // Plain/summary callers pass their own (often longer) timeout; do not cut them at 45s.
                builtPlainModel = chatGptGate.guard(settings.chatgptAccount(), chatGptModel(selectedModel, token, true));
            } else {
            GenerateOptions.Builder chatOpts = GenerateOptions.builder()
                    .temperature(settings.temperature());
            chatModel = OpenAIChatModel.builder()
                    .httpTransport(ownedTransport.transport)
                    .apiKey((settings.available() ? settings.apiKey() : "UNCONFIGURED"))
                    .modelName(settings.model())
                    .baseUrl(llmBaseUrl)
                    .generateOptions(chatOpts.build())
                    .build();

            GenerateOptions.Builder plainOpts = GenerateOptions.builder()
                    .temperature(0.2)
                    // Each outer reservation covers one HTTP attempt. Keep the SDK's timeout
                    // and other execution defaults, but let the durable caller own retries.
                    .executionConfig(ExecutionConfig.mergeConfigs(
                            ExecutionConfig.builder().maxAttempts(1).build(), ExecutionConfig.MODEL_DEFAULTS))
                    .stream(false);
            builtPlainModel = OpenAIChatModel.builder()
                    .httpTransport(ownedTransport.transport)
                    .apiKey((settings.available() ? settings.apiKey() : "UNCONFIGURED"))
                    .modelName(settings.model())
                    .baseUrl(llmBaseUrl)
                    .stream(false)
                    .generateOptions(plainOpts.build())
                    .build();

            }

            builtAgent = ReActAgent.builder()
                    .name("SelfAnalyst")
                    .sysPrompt(buildSystemPrompt())
                    .model(AgentObservability.model(new ChatImageModel(new MailGuardedModel(chatModel, () -> requireChatModelTarget(settings)), config.memoryDir()), () -> observability, "model.generation"))
                    .toolkit(toolkit)
                    .middlewares(List.of(
                            new ConversationContextMiddleware(),
                            new DynamicMemoryContextMiddleware(
                                    lang, () -> memory.profile().buildContextSummary()),
                            new PlanMiddleware(usageMeter, () -> observability)))
                    .stateStore(agentStateStore)
                    .defaultSessionId(LEGACY_SESSION_ID)
                    .maxIters(config.agentMaxIters())
                    .modelExecutionConfig(ExecutionConfig.builder()
                            .timeout(Duration.ofSeconds(45))
                            .maxAttempts(1)
                            .build())
                    .build();

        TransactionalAgentStateCompactor compactor;
        try {
        if (config.agentCompactionEnabled()) {
            io.agentscope.core.model.Model compactionModel = AgentObservability.model(new UsageMeteredModel(
                    new MailGuardedModel(builtPlainModel, () -> requireChatModelTarget(settings)), usageMeter, UsageMeter.Category.SUMMARY), () -> observability, "context.compact");
            CompactionConfig compactionConfig = CompactionConfig.builder()
                    .triggerMessages(config.agentCompactionTriggerMessages())
                    .triggerTokens(config.agentCompactionTriggerTokens())
                    .keepMessages(config.agentCompactionKeepMessages())
                    .keepTokens(config.agentCompactionKeepTokens())
                    .flushBeforeCompact(false)
                    .offloadBeforeCompact(false)
                    .model(compactionModel)
                    .build();
            compactor = new TransactionalAgentStateCompactor(
                    builtAgent,
                    config.memoryDir().resolve("agent-workspace").resolve("self-analyst-chat"),
                    compactionModel,
                    compactionConfig);
        } else {
            compactor = null;
        }
            return new AgentRuntime(builtAgent, builtPlainModel, chatModel, compactor, ownedTransport);
        } catch (RuntimeException | Error failure) {
            builtAgent.close();
            throw failure;
        }
        } catch (RuntimeException | Error failure) {
            if (ownedTransport != null) ownedTransport.close();
            throw failure;
        }
    }

    static ChatGptResponsesModel chatGptModel(String model, java.util.function.Supplier<String> token, boolean plain) {
        return new ChatGptResponsesModel(model, token,
                plain ? ChatGptResponsesModel.PLAIN_TIMEOUT : ChatGptResponsesModel.CHAT_TIMEOUT);
    }

    private void selectRuntime(LlmRuntimeManager<AgentRuntime>.Lease lease) {
        leasedChatSettings = lease.settings();
        agent = lease.resource().agent();
        stateCompactor = lease.resource().compactor();
        runtimesForChatAvailable = connectionAvailable(lease.settings());
    }

    private boolean connectionAvailable(LlmSettings settings) {
        try { return settings.available() && (!settings.usesChatGpt() || (chatGptSessions.isConnected(settings.chatgptAccount()) && chatGptGate.available(settings.chatgptAccount()))); }
        catch (RuntimeException unavailable) { return false; }
    }
    public ChatGptSessionService chatGptSessions() { return chatGptSessions; }
    public com.selfanalyst.chatgpt.ChatGptInferenceGate chatGptGate() { return chatGptGate; }

    public LlmSettings llmSettings() { return runtimes.settings(); }
    public boolean isLlmAvailable() { return runtimes.available(); }
    public ConfigApplicationService configuration() { return configuration; }

    private String buildSystemPrompt() {
        return AgentPrompts.systemPromptTemplate(lang, "",
                wikiStore != null, semanticEnabled, hasFileTools, hasConfigTools)
                + "\n" + com.selfanalyst.i18n.Messages.text(lang, "memory.clarification");
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
            String userInput, Object contextSnapshot, List<Msg> existingHistory, boolean managedDesktop,
            List<com.selfanalyst.desktop.store.ChatImageStore.Image> images) {
        public PersistedDesktopTurn(String userInput, Object contextSnapshot, List<Msg> existingHistory, boolean managedDesktop) {
            this(userInput, contextSnapshot, existingHistory, managedDesktop, List.of());
        }
        public PersistedDesktopTurn(String userInput, Object contextSnapshot, List<Msg> existingHistory) {
            this(userInput, contextSnapshot, existingHistory, false);
        }
        public PersistedDesktopTurn {
            userInput = userInput != null ? userInput : "";
            existingHistory = existingHistory != null ? List.copyOf(existingHistory) : null;
            images = images == null ? List.of() : List.copyOf(images);
        }
    }

    public void registerMemoryTools(com.selfanalyst.memory.LongTermMemoryService service) {
        var tools = new com.selfanalyst.memory.MemoryTools(service);
        registerNativeTools(tools);
    }

    public void registerOntologyTools(com.selfanalyst.ontology.OntologyService service) {
        var ontologyTools = new com.selfanalyst.ontology.OntologyTools(service);
        registerNativeTools(ontologyTools);
    }

    public void registerDocumentTools(com.selfanalyst.document.DocumentService service) {
        var documents = new com.selfanalyst.document.DocumentTools(service);
        registerNativeTools(documents);
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
        java.util.concurrent.atomic.AtomicReference<AgentIntegrationGateway.TurnContext> integrationTurn = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<com.selfanalyst.document.DocumentExecution> documents = new java.util.concurrent.atomic.AtomicReference<>();
        return runExclusiveStream(activeChat, () -> Flux.defer(() -> {
            PersistedDesktopTurn turn = persistedTurnSupplier.get();
            if (turn == null) throw new ChatSessionUnavailableException(sessionId);
            if (observability != null) observability.prepare(sessionId, turn.existingHistory() != null && !turn.existingHistory().isEmpty(),
                    mailSessionPolicy != null && !LEGACY_SESSION_ID.equals(sessionId) && mailSessionPolicy.containsMail(sessionId), !turn.images().isEmpty());
            var gateway = integrationGateway;
            var turnContext = new AgentIntegrationGateway.TurnContext(sessionId, userMessageId, turn.managedDesktop(),
                    com.selfanalyst.integrations.policy.ModelRecipient.from(leasedChatSettings));
            integrationTurn.set(turnContext);
            activeChat.admit(() -> { if (gateway != null) gateway.endTurn(turnContext); });
            if (activeChat.cancelled()) throw new ChatCancelledException(userMessageId);
            // Check the actual lease before compaction; target changes require a trusted new grant.
            try { requireChatModelTarget(leasedChatSettings); }
            catch (com.selfanalyst.integrations.policy.MailSessionPolicy.ModelConsentRequiredException denied) {
                try {
                    if (gateway == null || !turn.managedDesktop()) throw denied;
                    gateway.requestModelConsent(turnContext);
                    if (activeChat.cancelled()) throw new ChatCancelledException(userMessageId);
                    requireChatModelTarget(leasedChatSettings);
                } catch (RuntimeException unavailable) {
                    if (activeChat.cancelled()) throw new ChatCancelledException(userMessageId);
                    return Flux.just(ChatStreamEvent.result(com.selfanalyst.i18n.Messages.text(lang, "mail.modelConsentRequired")));
                }
            }
            installTurnTools(turnContext, gateway);
            if (activeChat.cancelled()) throw new ChatCancelledException(userMessageId);
            // Resolve server-owned binding before ordinary chat, tools or compaction. Client
            // context is display data and can neither grant nor remove this routing constraint.
            var repair = summaryRepairChatService;
            if (repair != null && repair.isBound(sessionId)) {
                return repairChat(sessionId, userMessageId, turn, activeChat, repair);
            }
            var documentExecution = new com.selfanalyst.document.DocumentExecution(
                    sessionId, userMessageId, turn.managedDesktop(), activeChat::cancelled);
            documents.set(documentExecution);
            if (usageMeter != null && usageMeter.isBlocked()) {
                return Flux.just(ChatStreamEvent.result(budgetBlockedMessage()));
            }
            seedSessionHistoryIfAbsent(sessionId, turn.existingHistory());
            CallPreparation prepared;
            try {
                prepared = prepareCall(sessionId, userMessageId, turn.userInput(), turn.images());
            } catch (RuntimeException | Error failure) {
                agent.clearStateCache(DESKTOP_USER_ID, sessionId);
                throw failure;
            }
            if (prepared.completedReply() != null) {
                agent.clearStateCache(DESKTOP_USER_ID, sessionId);
                return Flux.just(ChatStreamEvent.result(prepared.completedReply()));
            }
            if (!runtimesForChatAvailable) {
                agent.clearStateCache(DESKTOP_USER_ID, sessionId);
                return Flux.error(new com.selfanalyst.wiki.usage.LlmUnavailableException());
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
                        .sessionId(sessionId)
                        .put(com.selfanalyst.document.DocumentExecution.class, documentExecution);
                if (integrationTurn.get() != null) contextBuilder.put(AgentIntegrationGateway.TurnContext.class, integrationTurn.get());
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
                    // Bind this exact run before subscribing, so a stop during admission is
                    // remembered without leaking an interrupt into a later session turn.
                    var run = agent.prepareRun(prepared.messages(), context);
                    if (!activeChat.beginModelCall(run::interrupt)) {
                        return Flux.error(new ChatCancelledException(userMessageId));
                    }
                    return Flux.using(
                                () -> run,
                                activeRun -> activeRun.stream()
                                        .onErrorMap(SelfAnalystAgent::safeModelFailure)
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
        }).doOnTerminate(() -> {
            var execution = documents.get();
            if (execution != null) execution.closeAndAwait();
        }).doOnCancel(() -> {
            var execution = documents.get();
            if (execution != null) execution.closeAndAwait();
        })).transform(SelfAnalystAgent::requireTerminalChatResult)
                .transformDeferred(source -> observability == null ? source : observability.trace("selfanalyst.turn", sessionId, () -> source));
    }

    private Flux<ChatStreamEvent> repairChat(String sessionId, String userMessageId,
            PersistedDesktopTurn turn, ActiveDesktopChat active,
            com.selfanalyst.desktop.service.SummaryRepairChatService repair) {
        return Flux.defer(() -> {
            seedSessionHistoryIfAbsent(sessionId, turn.existingHistory());
            CallPreparation prepared = prepareCall(sessionId, userMessageId, turn.userInput(), List.of());
            if (prepared.completedReply() != null) {
                return Flux.just(ChatStreamEvent.result(prepared.completedReply()));
            }
            return Mono.fromCallable(() -> {
                Thread worker = Thread.currentThread();
                if (!active.beginModelCall(ignored -> worker.interrupt())) throw new ChatCancelledException(userMessageId);
                try {
                    String response = repair.complete(sessionId, userMessageId, active::cancelled);
                    if (active.cancelled()) throw new ChatCancelledException(userMessageId);
                    AgentState state = agent.getAgentState(DESKTOP_USER_ID, sessionId);
                    state.contextMutable().addAll(prepared.messages());
                    state.contextMutable().add(Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                            .textContent(response).build());
                    agent.saveAgentState(DESKTOP_USER_ID, sessionId);
                    if (!active.completeModelCall()) throw new ChatCancelledException(userMessageId);
                    return ChatStreamEvent.result(response);
                } finally {
                    // PlainTask restores InterruptedException's flag. Clear only our explicit
                    // cancellation before the gate's durable rollback touches interruptible files.
                    if (active.cancelled()) Thread.interrupted();
                }
            }).flux();
        }).doOnTerminate(() -> agent.clearStateCache(DESKTOP_USER_ID, sessionId))
                .doOnCancel(() -> agent.clearStateCache(DESKTOP_USER_ID, sessionId));
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
        return com.selfanalyst.i18n.Messages.text(lang, "budget.blocked");
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
            String sessionId, String userMessageId, String userInput,
            List<com.selfanalyst.desktop.store.ChatImageStore.Image> images) {
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
                .content(imageContent(userInput, sessionId, images));
        if (userMessageId != null) builder.id(userMessageId);
        return new CallPreparation(List.of(builder.build()), null);
    }

    private record CallPreparation(List<Msg> messages, String completedReply) {
    }

    public static List<ContentBlock> imageContent(String text, String session,
            List<com.selfanalyst.desktop.store.ChatImageStore.Image> images) {
        return ChatImageModel.content(text, session, images);
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
                    try {
                        var lease = runtimes.acquire();
                        selectRuntime(lease);
                        return lease;
                    } catch (RuntimeException failure) {
                        chatRunning.set(false);
                        throw failure;
                    }
                },
                ignored -> Mono.defer(action),
                lease -> {
                    try { lease.close(); } finally { chatRunning.set(false); }
                },
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
                    try {
                        var lease = runtimes.acquire();
                        selectRuntime(lease);
                        return lease;
                    } catch (RuntimeException failure) {
                        if (desktopChat != null) activeDesktopChat.compareAndSet(desktopChat, null);
                        chatRunning.set(false);
                        throw failure;
                    }
                },
                ignored -> Flux.defer(action),
                lease -> {
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
                        try { lease.close(); } finally { chatRunning.set(false); }
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
        return active.cancel(cancellation);
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
        // Interruption belongs to the single AgentRun, not the persisted conversation.
        // A retry receives a fresh RunControl; only durable turn state needs rollback.
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
        private Consumer<Msg> dedicatedInterrupt;
        private Thread admissionThread;
        private Runnable integrationCleanup = () -> {};
        private boolean integrationCleaned;
        private boolean admissionInterrupted;

        private synchronized void admit(Runnable cleanup) {
            integrationCleanup = cleanup;
            admissionThread = Thread.currentThread();
            if (lifecycle != 0) finishIntegrations();
        }
        private void finishIntegrations() {
            if (!integrationCleaned) { integrationCleaned = true; integrationCleanup.run(); }
        }

        private ActiveDesktopChat(String sessionId, String userMessageId) {
            this.sessionId = sessionId;
            this.userMessageId = userMessageId;
        }

        private boolean matches(String sessionId, String userMessageId) {
            return Objects.equals(this.sessionId, sessionId)
                    && Objects.equals(this.userMessageId, userMessageId);
        }

        private synchronized boolean cancel(Msg message) {
            if (lifecycle != 0) return false;
            lifecycle = 1;
            if (modelCallStarted) {
                dedicatedInterrupt.accept(message);
            } else if (admissionThread != null) {
                admissionInterrupted = true;
                admissionThread.interrupt();
            }
            finishIntegrations();
            return true;
        }

        private synchronized boolean beginModelCall(Consumer<Msg> interrupt) {
            if (lifecycle != 0) return false;
            dedicatedInterrupt = interrupt;
            admissionThread = null;
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
            if (admissionInterrupted && admissionThread == Thread.currentThread()) Thread.interrupted();
            // A cancellation may race registration inside snapshot; repeat cleanup before releasing the gate.
            integrationCleanup.run();
            integrationCleaned = true;
            boolean rollbackRequired = lifecycle == 1 && modelCallStarted;
            lifecycle = 2;
            return rollbackRequired;
        }
    }

    public java.util.Map<String, Object> usageSnapshot() {
        return usageMeter != null ? usageMeter.snapshot()
                : java.util.Map.of("mode", "off", "status", "ok");
    }

    public final class PlainTask implements com.selfanalyst.desktop.service.SummaryPromptService.SummaryTextSession {
        private final LlmRuntimeManager<AgentRuntime>.Lease lease = runtimes.acquire();
        private final String sessionId;
        private PlainTask() { this(null); }
        private PlainTask(String sessionId) { this.sessionId = sessionId; }
        @Override public boolean permitsMailOrigin(String id) {
            if (sessionId == null || !sessionId.equals(id) || mailSessionPolicy == null) return false;
            try { mailSessionPolicy.requireModelTarget(id, com.selfanalyst.integrations.policy.ModelRecipient.from(lease.settings())); return true; }
            catch (RuntimeException denied) { return false; }
        }
        public String complete(String input, Duration timeout) {
            return completeDetailed(input, timeout).text();
        }
        public void preflight() {
            if (sessionId != null) {
                if (mailSessionPolicy == null) throw new IllegalStateException("MAIL_PROVENANCE_UNAVAILABLE");
                mailSessionPolicy.requireModelTarget(sessionId, com.selfanalyst.integrations.policy.ModelRecipient.from(lease.settings()));
            }
            if (!connectionAvailable(lease.settings())) throw new com.selfanalyst.wiki.usage.LlmUnavailableException();
            if (usageMeter != null) usageMeter.enforce(UsageMeter.Category.SUMMARY);
        }
        public Completion completeDetailed(String input, Duration timeout) {
            preflight();
            return completePlainDetailed(lease.resource().plain(), input, timeout, sessionId);
        }
        public boolean available() { return connectionAvailable(lease.settings()); }
        public String cacheIdentity() { return plainCacheIdentity(lease.settings(), lang.code(), AgentPrompts.plainCompletionPrompt(lang)); }
        public String configurationRevision() { return Long.toString(lease.revision()); }
        public int requestOverheadChars() { return AgentPrompts.plainCompletionPrompt(lang).length() + 128; }
        public long estimateInputTokens(String input) {
            return estimateTokens(AgentPrompts.plainCompletionPrompt(lang)) + estimateTokens(input) + 32;
        }
        @Override public void close() { lease.close(); }
    }

    /** Stable across process restarts and credential rotation; contains no plaintext configuration. */
    static String plainCacheIdentity(LlmSettings settings, String language, String systemPrompt) {
        try {
            java.net.URI uri = java.net.URI.create(settings.requestBaseUrl()).normalize();
            String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            int port = uri.getPort();
            if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            String endpoint = new java.net.URI(scheme, null, host, port, path, uri.getQuery(), null).toASCIIString();
            String source = settings.provider() + ":" + (settings.usesChatGpt() ? settings.chatgptAccount() : "")
                    + ":" + endpoint.length() + ":" + endpoint + settings.requestModel().length() + ":" + settings.requestModel()
                    + ":plain-v1:0.2:" + language.length() + ":" + language
                    + ":" + systemPrompt.length() + ":" + systemPrompt;
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "plain-sha256-" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.GeneralSecurityException | java.net.URISyntaxException error) {
            throw new IllegalStateException("WIKI_MODEL_IDENTITY_INVALID");
        }
    }

    public PlainTask plainTask() { return new PlainTask(); }
    public PlainTask plainTask(String sessionId) { return new PlainTask(requireDesktopSessionId(sessionId)); }

    public String completePlain(String userInput, Duration timeout) {
        try (PlainTask task = plainTask()) { return task.complete(userInput, timeout); }
    }

    private Completion completePlainDetailed(Model plainModel, String userInput, Duration timeout, String sessionId) {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new CallFailure(FailureKind.INPUT, "WIKI_TIMEOUT_INVALID", true);
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

        AtomicReference<ChatUsage> receivedUsage = new AtomicReference<>();
        StringBuffer receivedText = new StringBuffer();
        java.util.concurrent.CompletableFuture<List<ChatResponse>> request = null;
        boolean completed = false;
        try {
            // Subscribe only after the caller's preflight. Once entered, a synchronous SDK error
            // is conservatively treated as a possibly dispatched request as well.
            Flux<ChatResponse> observed = AgentObservability.model(plainModel, () -> observability, "model.generation").stream(messages, List.of(), null);
            if (observability != null) {
                observability.prepare(sessionId, true, sessionId != null && mailSessionPolicy != null && mailSessionPolicy.containsMail(sessionId), false);
                Flux<ChatResponse> original = observed;
                observed = observability.trace("summary.generate", sessionId, () -> original.timeout(timeout));
            }
            request = observed.doOnNext(response -> {
                receivedText.append(chatResponseText(response));
                if (response != null && response.getUsage() != null) receivedUsage.set(response.getUsage());
            }).collectList().toFuture();
            request.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            completed = true;
            ChatUsage usage = receivedUsage.get();
            return new Completion(receivedText.toString().trim(),
                    usage == null ? null : (long) usage.getInputTokens(), usage == null ? null : (long) usage.getOutputTokens());
        } catch (InterruptedException e) {
            if (request != null) request.cancel(true);
            Thread.currentThread().interrupt();
            throw plainCallFailure(FailureKind.INTERRUPTED, "WIKI_MODEL_INTERRUPTED", receivedUsage.get());
        } catch (TimeoutException e) {
            if (request != null) request.cancel(true);
            throw plainCallFailure(FailureKind.TIMEOUT, "WIKI_MODEL_TIMEOUT", receivedUsage.get());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TimeoutException) throw plainCallFailure(FailureKind.TIMEOUT, "WIKI_MODEL_TIMEOUT", receivedUsage.get());
            throw plainTransportFailure(e.getCause(), receivedUsage.get());
        } catch (RuntimeException e) {
            throw plainTransportFailure(e, receivedUsage.get());
        } finally {
            if (usageMeter != null) {
                ChatUsage usage = receivedUsage.get();
                if (usage != null) {
                    usageMeter.record(UsageMeter.Category.SUMMARY, usage.getInputTokens(), usage.getOutputTokens());
                } else {
                    long output = estimateTokens(receivedText.toString());
                    if (!completed) output = Math.max(output, startupConfig.wikiSummaryOutputTokenReserve());
                    usageMeter.record(UsageMeter.Category.SUMMARY,
                            estimateTokens(plainSystemPrompt) + estimateTokens(userInput) + 32, output,
                            completed ? UsageMeter.Source.ESTIMATED : UsageMeter.Source.RESERVED);
                    if (!usageMissingLogged) {
                        usageMissingLogged = true;
                        log.warn("LLM 响应未返回 usage，摘要 token 使用估算；未知失败保留输出预留（仅首次提示）");
                    }
                }
            }
        }
    }

    private static CallFailure plainCallFailure(FailureKind kind, String code, ChatUsage usage) {
        return new CallFailure(kind, code, false,
                usage == null ? null : (long) usage.getInputTokens(), usage == null ? null : (long) usage.getOutputTokens());
    }

    private static CallFailure plainTransportFailure(Throwable failure, ChatUsage usage) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int depth = 0; depth < 16 && current != null && visited.add(current); depth++) {
            if (current instanceof ChatGptResponsesModel.ChatGptResponsesException chatgpt) {
                FailureKind kind = switch (chatgpt.code()) {
                    case AUTHENTICATION, ACCESS_DENIED, RATE_LIMIT, INVALID_REQUEST -> FailureKind.CONFIGURATION;
                    case TIMEOUT -> FailureKind.TIMEOUT;
                    case CANCELLED -> FailureKind.INTERRUPTED;
                    default -> null;
                };
                if (kind != null) return plainCallFailure(kind, "WIKI_CHATGPT_" + chatgpt.code().name(), usage);
            }
            // SDK messages can include response bodies. Only typed status codes may influence
            // classification, including when ModelException or Reactor wraps the provider error.
            Integer status = current instanceof ModelHttpException http ? http.getStatusCode()
                    : current instanceof HttpTransportException transport ? transport.getStatusCode() : null;
            if (status != null && status >= 100 && status <= 599) {
                FailureKind kind = Set.of(400, 401, 402, 403, 404, 422).contains(status)
                        ? FailureKind.CONFIGURATION : FailureKind.TRANSPORT;
                return plainCallFailure(kind, "WIKI_MODEL_HTTP_" + status, usage);
            }
            current = current.getCause();
        }
        return plainCallFailure(FailureKind.TRANSPORT, "WIKI_MODEL_FAILURE", usage);
    }

    static RuntimeException safeModelFailure(Throwable failure) {
        if (failure instanceof ChatCancelledException cancelled) return cancelled;
        String message = failure == null ? "" : String.valueOf(failure.getMessage());
        if (failure instanceof ChatImageModel.ImageUnavailableException) return (RuntimeException) failure;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (!Pattern.compile("\\b(401|403)\\b").matcher(lower).find()
                && (lower.contains("image") || lower.contains("vision") || lower.contains("multimodal"))
                && (lower.contains("not support") || lower.contains("unsupported") || lower.contains("does not support")))
            return new RuntimeException("CHAT_IMAGE_UNSUPPORTED");
        var status = Pattern.compile("\\b(400|401|403|404|408|429|500|502|503|504)\\b").matcher(message);
        return new RuntimeException(status.find() ? "LLM 调用失败 (HTTP " + status.group(1) + ")"
                : "LLM 调用失败，请检查模型配置或网络连接");
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

    public void beginShutdown() {
        if (configuration != null) configuration.close();
        runtimes.close();
        chatGptSessions.close();
        ActiveDesktopChat active = activeDesktopChat.get();
        if (active != null) {
            active.cancel(Msg.builder().name("user").role(MsgRole.USER)
                    .textContent("应用正在关闭").build());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        beginShutdown();
        runtimes.awaitIdle();
        agentStateStore.close();
    }
}
