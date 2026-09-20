package com.selfanalyst.agent;

import com.selfanalyst.i18n.Lang;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicMemoryContextMiddlewareTest {

    @Test
    void enrichesEachNewSystemPromptWithFreshMemory() {
        AtomicReference<String> memory = new AtomicReference<>("用户偏好中文交流。");
        DynamicMemoryContextMiddleware middleware =
                new DynamicMemoryContextMiddleware(Lang.chinese(), memory::get);

        String first = middleware.enrich("base prompt");
        assertTrue(first.startsWith("base prompt"));
        assertTrue(first.contains("用户偏好中文交流。"));

        memory.set("用户希望回答简洁。");
        String second = middleware.enrich("base prompt");
        assertTrue(second.contains("用户希望回答简洁。"));
    }

    @Test
    void refreshesCurrentTimeForEachTurn() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        AtomicReference<ZonedDateTime> now = new AtomicReference<>(
                ZonedDateTime.of(2026, 9, 20, 8, 47, 0, 0, zone));
        DynamicMemoryContextMiddleware middleware =
                new DynamicMemoryContextMiddleware(Lang.chinese(), () -> "", now::get);
        String basePrompt = AgentPrompts.systemPromptTemplate(
                Lang.chinese(), "", false, false, false, false);

        String first = middleware.onSystemPrompt(null, null, basePrompt).block();
        assertTrue(first.contains("2026-09-20 08:47:00"));

        now.set(ZonedDateTime.of(2026, 9, 20, 11, 30, 0, 0, zone));
        String second = middleware.onSystemPrompt(null, null, basePrompt).block();
        assertTrue(second.contains("2026-09-20 11:30:00"));
        assertFalse(second.contains("08:47:00"));
        assertFalse(second.contains(AgentPrompts.CURRENT_TIME_PLACEHOLDER));
    }
}
