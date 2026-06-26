package com.selfanalyst.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
}
