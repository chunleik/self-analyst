package com.selfanalyst.memory;

import com.fasterxml.jackson.databind.JsonMappingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSaveAndLoadProfile() throws Exception {
        MemoryStore store = MemoryStore.load(tempDir);

        store.profile().getGoals().add(
                GrowthProfile.Goal.create("减少社交媒体", "浏览器-社交", 45, 15));
        store.profile().getPatterns().add(
                new GrowthProfile.KnownPattern("上午专注度高", "连续5天上午编码>3h",
                        LocalDate.of(2026, 6, 1), 8));
        store.save();

        MemoryStore loaded = MemoryStore.load(tempDir);
        assertEquals(1, loaded.profile().getGoals().size());
        assertEquals("减少社交媒体", loaded.profile().getGoals().getFirst().description());
        assertEquals(1, loaded.profile().getPatterns().size());
        assertEquals("上午专注度高", loaded.profile().getPatterns().getFirst().description());
    }

    @Test
    void shouldHandleEmptyProfile() throws Exception {
        MemoryStore store = MemoryStore.load(tempDir);
        assertNotNull(store.profile());
        assertTrue(store.profile().getGoals().isEmpty());

        String summary = store.profile().buildContextSummary();
        assertTrue(summary.contains("暂无"));
    }

    @Test
    void shouldRoundTripImprovementLog() throws Exception {
        MemoryStore store = MemoryStore.load(tempDir);
        store.profile().getLogs().add(
                new GrowthProfile.ImprovementLog("goal-1", "试行集中会议",
                        "会议时间从3h降到2h", LocalDate.of(2026, 6, 2)));
        store.save();

        MemoryStore loaded = MemoryStore.load(tempDir);
        assertEquals(1, loaded.profile().getLogs().size());
        assertEquals("试行集中会议", loaded.profile().getLogs().getFirst().action());
    }

    @Test
    void shouldRoundTripMemoryItems() throws Exception {
        MemoryStore store = MemoryStore.load(tempDir);
        store.profile().getMemories().add(new GrowthProfile.MemoryItem(
                "mem-1", "preference", "用户偏好使用中文交流。", "用户明确说明",
                9, "active", false, "auto", "chat_auto", "session-1",
                List.of("msg-1"), java.time.Instant.parse("2026-07-04T00:00:00Z"),
                java.time.Instant.parse("2026-07-04T00:00:00Z")));
        store.save();

        MemoryStore loaded = MemoryStore.load(tempDir);
        assertEquals(1, loaded.profile().getMemories().size());
        assertEquals("用户偏好使用中文交流。", loaded.profile().getMemories().getFirst().content());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void failedSaveKeepsExistingMemoryFileReadable() throws Exception {
        MemoryStore store = MemoryStore.load(tempDir);
        store.profile().getGoals().add(
                GrowthProfile.Goal.create("减少社交媒体", "浏览器-社交", 45, 15));
        store.save();
        Path file = tempDir.resolve("memory.json");
        String original = Files.readString(file);

        ((List) store.profile().getGoals()).add(new Object() {
            public Object getSelf() {
                return this;
            }
        });

        assertThrows(JsonMappingException.class, store::save);
        assertEquals(original, Files.readString(file));
        assertEquals(1, MemoryStore.load(tempDir).profile().getGoals().size());
    }
}
