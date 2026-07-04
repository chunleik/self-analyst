package com.selfanalyst.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

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

    @Test
    void listSortsUpdatedAtDescendingWithNullsLast() throws Exception {
        MemoryStore store = MemoryStore.load(tempDir);
        store.profile().getMemories().add(item("no-time", null));
        store.profile().getMemories().add(item("older", Instant.parse("2026-07-04T01:00:00Z")));
        store.profile().getMemories().add(item("newer", Instant.parse("2026-07-04T02:00:00Z")));
        LongTermMemoryService svc = new LongTermMemoryService(store);

        List<String> ids = svc.list(null, null, null, null).stream()
                .map(GrowthProfile.MemoryItem::id)
                .toList();

        assertEquals(List.of("newer", "older", "no-time"), ids);
    }

    @Test
    void credentialLikeEvidenceIsRejectedOnCreateAndUpdate() throws Exception {
        LongTermMemoryService svc = service();

        assertThrows(IllegalArgumentException.class, () ->
                svc.createManual("fact", "用户偏好中文。", "OPENAI_API_KEY=sk-test-secret", null, "ui_manual", "active"));
        assertTrue(svc.list(null, null, null, null).isEmpty());

        GrowthProfile.MemoryItem item = svc.createManual("fact", "用户偏好简洁。", "safe", null, "ui_manual", "active");
        assertThrows(IllegalArgumentException.class, () ->
                svc.update(item.id(), null, null, "OPENAI_API_KEY=sk-test-secret", null, null, null));
        assertEquals("safe", svc.list(null, null, null, null).getFirst().evidence());
    }

    @Test
    void createRollsBackInMemoryProfileWhenSaveFails() throws Exception {
        GrowthProfile profile = new GrowthProfile();
        MemoryStore store = failingStore(profile);
        LongTermMemoryService svc = new LongTermMemoryService(store);

        assertThrows(IOException.class, () ->
                svc.createManual("note", "save should fail", "manual", null, "ui_manual", "active"));

        assertTrue(profile.getMemories().isEmpty());
    }

    @Test
    void updateRollsBackInMemoryProfileWhenSaveFails() throws Exception {
        GrowthProfile profile = new GrowthProfile();
        GrowthProfile.MemoryItem original = item("mem-1", Instant.parse("2026-07-04T01:00:00Z"));
        profile.getMemories().add(original);
        MemoryStore store = failingStore(profile);
        LongTermMemoryService svc = new LongTermMemoryService(store);

        assertThrows(IOException.class, () ->
                svc.update("mem-1", null, "changed", "changed evidence", 2, "disabled", true));

        assertEquals(List.of(original), profile.getMemories());
    }

    @Test
    void deleteRollsBackInMemoryProfileWhenSaveFails() throws Exception {
        GrowthProfile profile = new GrowthProfile();
        GrowthProfile.MemoryItem original = item("mem-1", Instant.parse("2026-07-04T01:00:00Z"));
        profile.getMemories().add(original);
        MemoryStore store = failingStore(profile);
        LongTermMemoryService svc = new LongTermMemoryService(store);

        assertThrows(IOException.class, () -> svc.delete("mem-1"));

        assertEquals(List.of(original), profile.getMemories());
    }

    private LongTermMemoryService service() throws Exception {
        return new LongTermMemoryService(MemoryStore.load(tempDir));
    }

    private MemoryStore failingStore(GrowthProfile profile) throws Exception {
        Constructor<MemoryStore> constructor = MemoryStore.class.getDeclaredConstructor(Path.class, GrowthProfile.class);
        constructor.setAccessible(true);
        return constructor.newInstance(tempDir, profile);
    }

    private static GrowthProfile.MemoryItem item(String id, Instant updatedAt) {
        return new GrowthProfile.MemoryItem(
                id, "note", "content " + id, "evidence " + id, 5, "active", false,
                "auto", "test", "session-1", List.of(), Instant.parse("2026-07-04T00:00:00Z"), updatedAt);
    }
}
