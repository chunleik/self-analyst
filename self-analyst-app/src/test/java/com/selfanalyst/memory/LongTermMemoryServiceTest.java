package com.selfanalyst.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LongTermMemoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createManualMemoryDefaultsToActiveAndPersists() throws Exception {
        LongTermMemoryService svc = service();
        GrowthProfile.MemoryItem created = svc.createManual("preference", "  用户偏好简洁回答。 ", "用户手动添加", null, "ui_manual", "active");

        assertEquals("active", created.status());
        assertEquals("用户偏好简洁回答。", created.content());
        assertEquals(10, created.confidence());
        assertEquals(1, svc.list(null, null, null, null).size());
    }

    @Test
    void duplicateContentIsNotCreatedTwice() throws Exception {
        LongTermMemoryService svc = service();
        svc.createManual("preference", "用户偏好中文。", "one", null, "ui_manual", "active");
        svc.createManual("preference", " 用户偏好中文。 ", "two", null, "ui_manual", "active");

        assertEquals(1, svc.list(null, null, null, null).size());
    }

    @Test
    void secretLikeContentIsRejected() throws Exception {
        LongTermMemoryService svc = service();
        assertThrows(IllegalArgumentException.class, () ->
                svc.createManual("fact", "OPENAI_API_KEY=sk-test-secret", "bad", null, "ui_manual", "active"));
        assertTrue(svc.list(null, null, null, null).isEmpty());
    }

    @Test
    void updateStatusApprovesPendingAndDeleteRemovesItem() throws Exception {
        LongTermMemoryService svc = service();
        GrowthProfile.MemoryItem item = svc.createManual("pattern", "用户可能晚上容易分心。", "manual", null, "ui_manual", "pending");

        GrowthProfile.MemoryItem active = svc.update(item.id(), null, null, null, 8, "active", null);
        assertEquals("active", active.status());

        assertTrue(svc.delete(item.id()));
        assertTrue(svc.list(null, null, null, null).isEmpty());
    }

    @Test
    void listFiltersByStatusTypeSessionAndText() throws Exception {
        LongTermMemoryService svc = service();
        svc.createManual("preference", "用户偏好中文。", "manual", "s1", "chat_manual", "active");
        svc.createManual("goal", "每天深度工作三小时。", "manual", "s2", "chat_manual", "pending");

        assertEquals(1, svc.list("active", null, null, null).size());
        assertEquals(1, svc.list(null, "goal", null, null).size());
        assertEquals(1, svc.list(null, null, "s1", null).size());
        assertEquals(1, svc.list(null, null, null, "深度").size());
    }

    private LongTermMemoryService service() throws Exception {
        return new LongTermMemoryService(MemoryStore.load(tempDir));
    }
}
