package com.selfanalyst.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionalAgentStateCompactorTest {

    private static final String USER_ID = "desktop";
    private static final String SESSION_ID = "a".repeat(32);

    @Test
    void persistsSummaryAndRecentTailOnlyAfterSuccessfulSummary(@TempDir Path tempDir) {
        AtomicInteger calls = new AtomicInteger();
        Model summarizer = respondingModel("summary of first two turns", calls);
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir.resolve("state"));
        ReActAgent agent = ReActAgent.builder()
                .name("test-agent")
                .sysPrompt("test")
                .model(summarizer)
                .stateStore(store)
                .build();
        try {
            AgentState seeded = agent.getAgentState(USER_ID, SESSION_ID);
            seeded.contextMutable().addAll(sixMessages());
            seeded.setReplyId("stable-reply-id");
            seeded.setCurIter(3);
            agent.saveAgentState(USER_ID, SESSION_ID);
            agent.clearStateCache(USER_ID, SESSION_ID);
            TransactionalAgentStateCompactor compactor = new TransactionalAgentStateCompactor(
                    agent, tempDir.resolve("workspace"), summarizer, config());

            assertTrue(compactor.compactIfNeeded(USER_ID, SESSION_ID).block());

            AgentState persisted = store.get(
                    USER_ID, SESSION_ID, "agent_state", AgentState.class).orElseThrow();
            assertEquals("summary of first two turns", persisted.getSummary());
            assertEquals(2, persisted.getContext().size());
            assertEquals("question-3", persisted.getContext().get(0).getTextContent());
            assertEquals("answer-3", persisted.getContext().get(1).getTextContent());
            assertEquals("stable-reply-id", persisted.getReplyId());
            assertEquals(3, persisted.getCurIter());
            assertEquals(1, calls.get());
        } finally {
            agent.close();
            store.close();
        }
    }

    @Test
    void includesRollingSummaryWhenEvaluatingTokenTrigger(@TempDir Path tempDir) {
        AtomicInteger calls = new AtomicInteger();
        Model summarizer = respondingModel("updated rolling summary", calls);
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir.resolve("state"));
        ReActAgent agent = ReActAgent.builder()
                .name("test-agent")
                .sysPrompt("test")
                .model(summarizer)
                .stateStore(store)
                .build();
        try {
            AgentState state = agent.getAgentState(USER_ID, SESSION_ID);
            state.setSummary("older facts " + "x".repeat(2_000));
            state.contextMutable().addAll(List.of(
                    message(MsgRole.USER, "latest question"),
                    message(MsgRole.ASSISTANT, "latest answer")));
            agent.saveAgentState(USER_ID, SESSION_ID);
            agent.clearStateCache(USER_ID, SESSION_ID);
            CompactionConfig tokenConfig = CompactionConfig.builder()
                    .triggerMessages(0)
                    .triggerTokens(100)
                    .keepMessages(2)
                    .keepTokens(20)
                    .flushBeforeCompact(false)
                    .offloadBeforeCompact(false)
                    .prune(null)
                    .build();
            TransactionalAgentStateCompactor compactor = new TransactionalAgentStateCompactor(
                    agent, tempDir.resolve("workspace"), summarizer, tokenConfig);

            assertTrue(compactor.compactIfNeeded(USER_ID, SESSION_ID).block());

            AgentState persisted = store.get(
                    USER_ID, SESSION_ID, "agent_state", AgentState.class).orElseThrow();
            assertEquals("updated rolling summary", persisted.getSummary());
            assertEquals(1, calls.get(),
                    "the rolling summary must count toward the token trigger");
        } finally {
            agent.close();
            store.close();
        }
    }

    @Test
    void restoresPersistedStateWhenAtomicSaveFails(@TempDir Path tempDir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Model summarizer = respondingModel("new summary", calls);
        FailingJsonFileAgentStateStore store =
                new FailingJsonFileAgentStateStore(tempDir.resolve("state"));
        ReActAgent agent = ReActAgent.builder()
                .name("test-agent")
                .sysPrompt("test")
                .model(summarizer)
                .stateStore(store)
                .build();
        try {
            List<Msg> original = sixMessages();
            saveContext(agent, original);
            Path stateFile = tempDir.resolve("state")
                    .resolve(USER_ID).resolve(SESSION_ID).resolve("agent_state.json");
            String before = Files.readString(stateFile);
            store.failSaves = true;
            TransactionalAgentStateCompactor compactor = new TransactionalAgentStateCompactor(
                    agent, tempDir.resolve("workspace"), summarizer, config());

            assertFalse(compactor.compactIfNeeded(USER_ID, SESSION_ID).block());

            store.failSaves = false;
            assertEquals(before, Files.readString(stateFile));
            AgentState reloaded = agent.getAgentState(USER_ID, SESSION_ID);
            assertEquals("", reloaded.getSummary());
            assertEquals(original.stream().map(Msg::getTextContent).toList(),
                    reloaded.getContext().stream().map(Msg::getTextContent).toList());
            assertEquals(1, calls.get());
        } finally {
            store.failSaves = false;
            agent.close();
            store.close();
        }
    }

    @Test
    void preservesOriginalStateWhenHarnessSummarizationFails(@TempDir Path tempDir)
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Model failing = new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet();
                return Flux.error(new IllegalStateException("summary unavailable"));
            }

            @Override
            public String getModelName() {
                return "failing-summary";
            }
        };
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir.resolve("state"));
        ReActAgent agent = ReActAgent.builder()
                .name("test-agent")
                .sysPrompt("test")
                .model(failing)
                .stateStore(store)
                .build();
        List<Msg> original = sixMessages();
        try {
            saveContext(agent, original);
            Path stateFile = tempDir.resolve("state")
                    .resolve(USER_ID).resolve(SESSION_ID).resolve("agent_state.json");
            String before = Files.readString(stateFile);
            TransactionalAgentStateCompactor compactor = new TransactionalAgentStateCompactor(
                    agent, tempDir.resolve("workspace"), failing, config());

            assertFalse(compactor.compactIfNeeded(USER_ID, SESSION_ID).block());

            AgentState persisted = store.get(
                    USER_ID, SESSION_ID, "agent_state", AgentState.class).orElseThrow();
            assertEquals("", persisted.getSummary());
            assertEquals(before, Files.readString(stateFile));
            assertEquals(original.stream().map(Msg::getTextContent).toList(),
                    persisted.getContext().stream().map(Msg::getTextContent).toList());
            assertEquals(1, calls.get());
        } finally {
            agent.close();
            store.close();
        }
    }

    @Test
    void skipsCompactionWhileAToolCallHasNoResult(@TempDir Path tempDir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Model summarizer = respondingModel("must not be used", calls);
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir.resolve("state"));
        ReActAgent agent = ReActAgent.builder()
                .name("test-agent")
                .sysPrompt("test")
                .model(summarizer)
                .stateStore(store)
                .build();
        try {
            List<Msg> pending = new ArrayList<>(sixMessages());
            pending.add(Msg.builder()
                    .name("assistant")
                    .role(MsgRole.ASSISTANT)
                    .content(ToolUseBlock.builder()
                            .id("tool-1")
                            .name("query")
                            .input(Map.of("q", "pending"))
                            .build())
                    .build());
            saveContext(agent, pending);
            Path stateFile = tempDir.resolve("state")
                    .resolve(USER_ID).resolve(SESSION_ID).resolve("agent_state.json");
            String before = Files.readString(stateFile);
            TransactionalAgentStateCompactor compactor = new TransactionalAgentStateCompactor(
                    agent, tempDir.resolve("workspace"), summarizer, config());

            assertFalse(compactor.compactIfNeeded(USER_ID, SESSION_ID).block());

            assertEquals(0, calls.get());
            assertEquals(before, Files.readString(stateFile));
        } finally {
            agent.close();
            store.close();
        }
    }

    private static CompactionConfig config() {
        return CompactionConfig.builder()
                .triggerMessages(5)
                .triggerTokens(0)
                // Force the official cutoff to start at an assistant message; the application
                // layer must expand the tail back to the owning user-turn boundary.
                .keepMessages(1)
                .keepTokens(0)
                .flushBeforeCompact(false)
                .offloadBeforeCompact(false)
                .prune(null)
                .build();
    }

    private static Model respondingModel(String responseText, AtomicInteger calls) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet();
                return Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(responseText).build()))
                        .build());
            }

            @Override
            public String getModelName() {
                return "summary-model";
            }
        };
    }

    private static void saveContext(ReActAgent agent, List<Msg> messages) {
        AgentState state = agent.getAgentState(USER_ID, SESSION_ID);
        state.contextMutable().clear();
        state.contextMutable().addAll(messages);
        agent.saveAgentState(USER_ID, SESSION_ID);
        agent.clearStateCache(USER_ID, SESSION_ID);
    }

    private static List<Msg> sixMessages() {
        List<Msg> messages = new ArrayList<>();
        for (int turn = 1; turn <= 3; turn++) {
            messages.add(message(MsgRole.USER, "question-" + turn));
            messages.add(message(MsgRole.ASSISTANT, "answer-" + turn));
        }
        return List.copyOf(messages);
    }

    private static Msg message(MsgRole role, String text) {
        return Msg.builder()
                .name(role == MsgRole.USER ? "user" : "assistant")
                .role(role)
                .textContent(text)
                .build();
    }

    private static final class FailingJsonFileAgentStateStore
            extends JsonFileAgentStateStore {
        private boolean failSaves;

        private FailingJsonFileAgentStateStore(Path rootDirectory) {
            super(rootDirectory);
        }

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            if (failSaves) {
                throw new IllegalStateException("simulated save failure");
            }
            super.save(userId, sessionId, key, value);
        }
    }
}
