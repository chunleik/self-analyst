package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.memory.GrowthProfile;
import com.selfanalyst.memory.MemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SelfAnalystAgentMemoryPromptTest {

    @TempDir
    Path tempDir;

    @Test
    void constructorSystemPromptDoesNotEmbedMutableLongTermMemory() throws Exception {
        Config config = Config.testDefaults(tempDir);
        MemoryStore store = MemoryStore.load(config.memoryDir());
        store.profile().getMemories().add(new GrowthProfile.MemoryItem(
                "mem-1", "preference", "用户偏好中文交流。", "manual",
                9, "active", false, "auto", "ui_manual", null, List.of(),
                Instant.parse("2026-07-04T00:00:00Z"), Instant.parse("2026-07-04T00:00:00Z")));
        store.save();

        SelfAnalystAgent agent = new SelfAnalystAgent(config);
        Method method = SelfAnalystAgent.class.getDeclaredMethod("buildSystemPrompt");
        method.setAccessible(true);

        String systemPrompt = (String) method.invoke(agent);

        assertFalse(systemPrompt.contains("用户偏好中文交流。"),
                "mutable long-term memory must only be injected per chat turn");
    }
}
