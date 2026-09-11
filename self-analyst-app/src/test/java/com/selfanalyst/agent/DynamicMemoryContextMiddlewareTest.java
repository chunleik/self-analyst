package com.selfanalyst.agent;

import com.selfanalyst.i18n.Lang;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

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
}
