package com.selfanalyst.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies AgentScope Harness compaction transactionally: failed summaries never replace history.
 */
final class TransactionalAgentStateCompactor {

    private static final Logger log = LoggerFactory.getLogger(TransactionalAgentStateCompactor.class);
    private static final String SIMPLE_SUMMARY_PREFIX =
            "Here is a summary of the conversation to date:\n\n";
    private static final int MAX_SUMMARY_CHARS = 16_000;

    private final ReActAgent agent;
    private final ConversationCompactor compactor;
    private final CompactionConfig config;

    TransactionalAgentStateCompactor(
            ReActAgent agent, Path workspace, io.agentscope.core.model.Model model,
            CompactionConfig config) {
        this.agent = agent;
        this.config = config;
        WorkspaceManager workspaceManager = new WorkspaceManager(workspace);
        this.compactor = new ConversationCompactor(
                model, new MemoryFlushManager(workspaceManager, model));
    }

    Mono<Boolean> compactIfNeeded(String userId, String sessionId) {
        return Mono.using(
                () -> Boolean.TRUE,
                ignored -> Mono.defer(() -> {
                            AgentState state = agent.getAgentState(userId, sessionId);
                            List<Msg> originalContext = state.getContext();
                            String originalSummary = state.getSummary();
                            if (originalContext.isEmpty() || state.isShutdownInterrupted()
                                    || hasUnmatchedToolCalls(originalContext)) {
                                return Mono.just(false);
                            }

                            List<Msg> compactionInput = new ArrayList<>();
                            if (originalSummary != null && !originalSummary.isBlank()) {
                                compactionInput.add(Msg.builder()
                                        .name(ConversationCompactor.SUMMARY_MSG_NAME)
                                        .role(io.agentscope.core.message.MsgRole.USER)
                                        .textContent(SIMPLE_SUMMARY_PREFIX + originalSummary)
                                        .build());
                            }
                            compactionInput.addAll(originalContext);
                            if (!shouldCompact(originalContext, compactionInput)) {
                                return Mono.just(false);
                            }
                            RuntimeContext context = RuntimeContext.builder()
                                    .userId(userId)
                                    .sessionId(sessionId)
                                    .build();

                            return compactor.compactIfNeeded(
                                            context, compactionInput, config,
                                            agent.getName(), sessionId)
                                    .flatMap(result -> applyIfUsable(
                                            state, originalContext, originalSummary,
                                            userId, sessionId, compactionInput, result));
                        })
                    .onErrorResume(error -> {
                        log.warn("AgentState compaction skipped for session {}: {}",
                                sessionId, error.getMessage());
                        return Mono.just(false);
                    }),
                ignored -> agent.clearStateCache(userId, sessionId),
                true);
    }

    private Mono<Boolean> applyIfUsable(
            AgentState state,
            List<Msg> originalContext,
            String originalSummary,
            String userId,
            String sessionId,
            List<Msg> compactionInput,
            Optional<List<Msg>> result) {
        if (result.isEmpty() || result.get().isEmpty()) return Mono.just(false);
        List<Msg> compacted = result.get();
        Msg summaryMessage = compacted.getFirst();
        if (!ConversationCompactor.SUMMARY_MSG_NAME.equals(summaryMessage.getName())) {
            return Mono.just(false);
        }
        String summary = extractSummary(summaryMessage.getTextContent());
        if (summary == null) {
            log.warn("AgentState compaction summary was unavailable; preserving original context");
            return Mono.just(false);
        }
        List<Msg> tail = compacted.stream()
                .skip(1)
                .filter(message -> !ConversationCompactor.SUMMARY_MSG_NAME.equals(message.getName()))
                .toList();
        List<Msg> expandedTail = expandToUserTurnBoundary(compactionInput, tail);
        if (expandedTail.isEmpty()) return Mono.just(false);

        return Mono.fromCallable(() -> {
            if (!Objects.equals(originalSummary, state.getSummary())
                    || !originalContext.equals(state.getContext())) {
                log.warn("AgentState changed while compacting session {}; preserving latest state",
                        sessionId);
                return false;
            }
            state.setSummary(summary);
            state.contextMutable().clear();
            state.contextMutable().addAll(expandedTail);
            agent.saveAgentState(userId, sessionId);
            log.info("Compacted AgentState session {} from {} to {} messages",
                    sessionId, originalContext.size(), expandedTail.size());
            return true;
        });
    }

    private static String extractSummary(String text) {
        if (text == null || text.isBlank()
                || text.contains("(Summarization failed:")
                || text.contains("(Summary unavailable)")) {
            return null;
        }
        String summary = text.startsWith(SIMPLE_SUMMARY_PREFIX)
                ? text.substring(SIMPLE_SUMMARY_PREFIX.length()) : text;
        summary = summary.strip();
        if (summary.isEmpty()) return null;
        return summary.length() <= MAX_SUMMARY_CHARS
                ? summary : summary.substring(0, MAX_SUMMARY_CHARS);
    }

    private static boolean hasUnmatchedToolCalls(List<Msg> messages) {
        Map<String, Integer> uses = new HashMap<>();
        Map<String, Integer> results = new HashMap<>();
        for (Msg message : messages) {
            for (ContentBlock block : message.getContent()) {
                if (block instanceof ToolUseBlock use && use.getId() != null) {
                    uses.merge(use.getId(), 1, Integer::sum);
                } else if (block instanceof ToolResultBlock result && result.getId() != null) {
                    results.merge(result.getId(), 1, Integer::sum);
                }
            }
        }
        return !uses.equals(results);
    }

    private static List<Msg> expandToUserTurnBoundary(
            List<Msg> original, List<Msg> compactedTail) {
        if (compactedTail.isEmpty()
                || compactedTail.getFirst().getRole()
                        == io.agentscope.core.message.MsgRole.USER) {
            return compactedTail;
        }
        String firstId = compactedTail.getFirst().getId();
        int firstIndex = -1;
        for (int i = 0; i < original.size(); i++) {
            Msg candidate = original.get(i);
            if (firstId != null && firstId.equals(candidate.getId())) {
                firstIndex = i;
                break;
            }
        }
        if (firstIndex <= 0) return compactedTail;
        for (int i = firstIndex - 1; i >= 0; i--) {
            Msg candidate = original.get(i);
            if (candidate.getRole() == io.agentscope.core.message.MsgRole.USER
                    && !ConversationCompactor.SUMMARY_MSG_NAME.equals(candidate.getName())) {
                List<Msg> expanded = new ArrayList<>(original.subList(i, firstIndex));
                expanded.addAll(compactedTail);
                return List.copyOf(expanded);
            }
        }
        return compactedTail;
    }

    private boolean shouldCompact(List<Msg> context, List<Msg> modelVisibleContext) {
        return (config.getTriggerMessages() > 0
                    && context.size() >= config.getTriggerMessages())
                || (config.getTriggerTokens() > 0
                    && TokenCounterUtil.calculateToken(modelVisibleContext)
                        >= config.getTriggerTokens());
    }
}
