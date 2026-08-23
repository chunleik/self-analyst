package com.selfanalyst.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationContextMiddlewareTest {

    @Test
    void injectsSummaryAndCurrentDesktopContextWithoutMutatingAgentState() {
        Msg system = message("system", MsgRole.SYSTEM, "base system");
        Msg currentUser = message("user", MsgRole.USER, "current question");
        AgentState state = AgentState.builder()
                .userId("desktop")
                .sessionId("a".repeat(32))
                .summary("earlier decisions")
                .context(List.of(currentUser))
                .build();
        RuntimeContext context = RuntimeContext.builder()
                .userId("desktop")
                .sessionId("a".repeat(32))
                .agentState(state)
                .put(ConversationContextMiddleware.DesktopTurnContext.class,
                        new ConversationContextMiddleware.DesktopTurnContext(
                                Map.of("headline", "focus block")))
                .build();
        ReasoningInput input = new ReasoningInput(
                List.of(system, currentUser), List.of(), null);
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();

        new ConversationContextMiddleware().onReasoning(
                null, context, input, next -> {
                    forwarded.set(next);
                    return Flux.empty();
                }).then().block();

        List<Msg> messages = forwarded.get().messages();
        assertEquals(4, messages.size());
        assertEquals(MsgRole.SYSTEM, messages.get(0).getRole());
        assertEquals("__compaction_summary__", messages.get(1).getName());
        assertTrue(messages.get(1).getTextContent().contains("earlier decisions"));
        assertEquals(ConversationContextMiddleware.DESKTOP_CONTEXT_MSG_NAME,
                messages.get(2).getName());
        assertTrue(messages.get(2).getTextContent().contains("focus block"));
        assertEquals("current question", messages.get(3).getTextContent());

        assertEquals(List.of(currentUser), state.getContext());
        assertFalse(state.getContext().stream().anyMatch(message ->
                ConversationContextMiddleware.DESKTOP_CONTEXT_MSG_NAME.equals(message.getName())));
    }

    private static Msg message(String name, MsgRole role, String text) {
        return Msg.builder().name(name).role(role).textContent(text).build();
    }
}
