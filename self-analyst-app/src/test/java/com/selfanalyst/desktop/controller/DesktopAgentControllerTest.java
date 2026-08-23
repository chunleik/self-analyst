package com.selfanalyst.desktop.controller;

import com.selfanalyst.desktop.store.ChatSessionStore;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void transcriptBootstrapStopsBeforeCurrentUserAndSkipsUiOnlyRows() {
        ChatSessionStore.Session session = new ChatSessionStore.Session();
        session.messages = new ArrayList<>(List.of(
                visible("0".repeat(12), "system", "UI context notice", null),
                visible("1".repeat(12), "user", "old question", "sent"),
                visible("2".repeat(12), "assistant", "old answer", "sent"),
                visible("3".repeat(12), "assistant", "old failed answer", "error"),
                visible("4".repeat(12), "user", "current question", "sent"),
                visible("5".repeat(12), "assistant", "thinking", "pending")));

        var history = DesktopAgentController.agentHistoryBeforeCurrentUser(
                session, "4".repeat(12));

        assertEquals(2, history.size());
        assertEquals(MsgRole.USER, history.get(0).getRole());
        assertEquals("old question", history.get(0).getTextContent());
        assertEquals(MsgRole.ASSISTANT, history.get(1).getRole());
        assertEquals("old answer", history.get(1).getTextContent());
        assertThrows(IllegalArgumentException.class,
                () -> DesktopAgentController.agentHistoryBeforeCurrentUser(
                        session, "f".repeat(12)));
        session.messages.add(visible("6".repeat(12), "user", "newer question", "sent"));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopAgentController.agentHistoryBeforeCurrentUser(
                        session, "4".repeat(12)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void usageSnapshotIncludesFreshHeadroomStatsWhenAgentMissing(@TempDir java.nio.file.Path dir) {
        var config = com.selfanalyst.config.Config.testDefaults(dir);
        AtomicInteger statsCalls = new AtomicInteger();
        var headroom = new com.selfanalyst.headroom.HeadroomService(
                true,
                "http://127.0.0.1:8787/v1",
                config.llmBaseUrl(),
                true,
                false,
                (uri, timeout) -> com.selfanalyst.headroom.HeadroomService.ProbeResult.ok("reachable"),
                (uri, timeout) -> com.selfanalyst.headroom.HeadroomService.StatsResult.ok(
                        Map.of("requestCount", statsCalls.incrementAndGet())));
        var ctrl = new DesktopAgentController(null, null, null, null, config, headroom);

        var usage = ctrl.usagePayload();

        assertEquals("off", usage.get("mode"));
        assertTrue(usage.containsKey("headroom"));
        Map<String, Object> headroomPayload = (Map<String, Object>) usage.get("headroom");
        assertEquals(2L, headroomPayload.get("requestCount"));
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
