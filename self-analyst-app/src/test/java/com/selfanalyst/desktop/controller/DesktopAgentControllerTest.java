package com.selfanalyst.desktop.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
