package com.selfanalyst.desktop.controller;

import com.selfanalyst.agent.SelfAnalystAgent;
import com.selfanalyst.desktop.store.ChatSessionStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopAgentControllerTest {

    @Test
    void buildChatAgentInputIncludesContextAndAsksForNaturalLanguage() {
        String input = DesktopAgentController.buildChatAgentInput(
                "hi",
                Map.of("currentStatus", Map.of("headline", "Chrome 与终端之间切换频繁")));

        assertTrue(input.contains("当前桌面会话上下文"));
        assertTrue(input.contains("Chrome 与终端之间切换频繁"));
        assertTrue(input.contains("用户消息"));
        assertTrue(input.contains("hi"));
        assertTrue(input.contains("不要输出 JSON"));
    }

    @Test
    void hasAgentStillRunningDetectsWrappedAgentScopeException() {
        RuntimeException wrapped = new RuntimeException(
                "Chat failed",
                new IllegalStateException("Agent is still running, please wait for it to finish"));

        assertTrue(DesktopAgentController.hasAgentStillRunning(wrapped));
        assertFalse(DesktopAgentController.hasAgentStillRunning(new RuntimeException("other")));
    }

    @Test
    void sseFramesPreserveEventBoundariesAndEscapePayloadText() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        DesktopAgentController.writeSseEvent(
                output, "delta", Map.of("text", "line one\nline two"));

        String frame = output.toString(StandardCharsets.UTF_8);
        assertTrue(frame.startsWith("event: delta\n"));
        assertTrue(frame.contains("data: {\"text\":\"line one\\nline two\"}"));
        assertTrue(frame.endsWith("\n\n"));
    }

    @Test
    void transcriptBootstrapStopsBeforeCurrentUserAndSkipsUiOnlyRows() {
        ChatSessionStore.Session session = new ChatSessionStore.Session();
        session.messages = new ArrayList<>(List.of(
                visible("0".repeat(12), "system", "UI context notice", null),
                visible("1".repeat(12), "user", "old question", "sent"),
                visible("2".repeat(12), "assistant", "old answer", "sent"),
                visible("3".repeat(12), "assistant", "old failed answer", "error"),
                visible("4".repeat(12), "user", "current question", "sent"),
                visible("5".repeat(12), "assistant", "thinking", "pending")));
        session.messages.get(4).contextSnapshot = Map.of("snapshot", "server-owned");

        var history = DesktopAgentController.agentHistoryBeforeCurrentUser(
                session, "4".repeat(12));

        assertEquals(2, history.size());
        assertEquals(MsgRole.USER, history.get(0).getRole());
        assertEquals("old question", history.get(0).getTextContent());
        assertEquals(MsgRole.ASSISTANT, history.get(1).getRole());
        assertEquals("old answer", history.get(1).getTextContent());
        var persistedTurn = DesktopAgentController.persistedTurnFromSession(
                session, "4".repeat(12));
        assertEquals("current question", persistedTurn.userInput());
        assertEquals(Map.of("snapshot", "server-owned"), persistedTurn.contextSnapshot());
        assertEquals(history.stream().map(Msg::getId).toList(),
                persistedTurn.existingHistory().stream().map(Msg::getId).toList());
        assertEquals(history.stream().map(Msg::getTextContent).toList(),
                persistedTurn.existingHistory().stream().map(Msg::getTextContent).toList());
        assertThrows(IllegalArgumentException.class,
                () -> DesktopAgentController.agentHistoryBeforeCurrentUser(
                        session, "f".repeat(12)));
        session.messages.add(visible("6".repeat(12), "user", "newer question", "sent"));
        assertThrows(SelfAnalystAgent.StaleChatTurnException.class,
                () -> DesktopAgentController.agentHistoryBeforeCurrentUser(
                        session, "4".repeat(12)));
        assertNull(DesktopAgentController.persistedTurnFromSession(
                null, "4".repeat(12)));
    }

    @Test
    void usagePayloadOmitsRemovedHeadroomSection() {
        DesktopAgentController controller = new DesktopAgentController(
                null, null, null, null, null);

        Map<String, Object> usage = controller.usagePayload();

        assertEquals("off", usage.get("mode"));
        assertFalse(usage.containsKey("headroom"));
    }

    private static ChatSessionStore.Message visible(
            String id, String role, String content, String status) {
        ChatSessionStore.Message message = new ChatSessionStore.Message();
        message.id = id;
        message.role = role;
        message.content = content;
        message.status = status;
        return message;
    }
}
