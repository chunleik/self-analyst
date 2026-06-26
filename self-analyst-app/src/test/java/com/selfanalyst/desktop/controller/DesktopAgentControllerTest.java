package com.selfanalyst.desktop.controller;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
