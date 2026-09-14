package com.selfanalyst.desktop.service;

import com.selfanalyst.memory.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PendingMemoryReviewTest {
    @TempDir Path dir;
    private static final String KEEP = "{\"durable\":true,\"supported\":true,\"sensitive\":false,\"confidence\":9}";
    private LongTermMemoryService memory() throws Exception { return new LongTermMemoryService(MemoryStore.load(dir)); }
    private GrowthProfile.MemoryItem pending(LongTermMemoryService service, String content) throws Exception {
        return service.createExtracted("project", content, "用户连续多次说明", 9, false, "confirm", "pending", "s", List.of());
    }
    @Test void reviewsAndPersistsWithoutChatAndDoesNotRepeat() throws Exception {
        var service = memory(); var item = pending(service, "长期维护项目");
        try (var review = new PendingMemoryReview(service)) {
            assertTrue(review.reviewBatch((p,t) -> KEEP));
            assertEquals(item.id(), memory().list("active",null,null,null).getFirst().id());
            assertTrue(review.reviewBatch((p,t) -> { fail("already reviewed"); return null; }));
        }
    }
    @Test void discardsOneOffButPreservesTechnicalFailures() throws Exception {
        var service = memory(); pending(service, "今天导出表格");
        try (var review = new PendingMemoryReview(service)) {
            assertFalse(review.reviewBatch((p,t) -> "bad json"));
            assertEquals(1, service.list("pending",null,null,null).size());
            assertFalse(review.reviewBatch((p,t) -> { throw new IllegalStateException("budget blocked"); }));
            assertTrue(review.reviewBatch((p,t) -> KEEP.replace("true", "false")));
            assertTrue(memory().list(null,null,null,null).isEmpty());
        }
    }
    @Test void concurrentCorrectionAndDeletionWin() throws Exception {
        var service = memory(); var item = pending(service,"原项目");
        try (var review = new PendingMemoryReview(service)) {
            review.reviewBatch((p,t) -> {
                assertDoesNotThrow(() -> service.correct(item.id(), item.content(), "新项目", false)); return KEEP;
            });
            assertEquals("新项目", service.list(null,null,null,null).getFirst().content());
            var next = pending(service,"另一个项目");
            review.reviewBatch((p,t) -> { assertDoesNotThrow(() -> service.delete(next.id())); return KEEP; });
            assertEquals(1, service.list(null,null,null,null).size());
        }
    }
    @Test void failedSaveRestoresPendingAndCanRetry() throws Exception {
        var service = memory(); pending(service,"长期项目");
        Files.delete(dir.resolve("memory.json")); Files.createDirectory(dir.resolve("memory.json"));
        Files.writeString(dir.resolve("memory.json/block"), "block");
        try (var review = new PendingMemoryReview(service)) {
            assertFalse(review.reviewBatch((p,t) -> KEEP));
            assertEquals(1, service.list("pending",null,null,null).size());
            Files.delete(dir.resolve("memory.json/block")); Files.delete(dir.resolve("memory.json"));
            assertTrue(review.reviewBatch((p,t) -> KEEP));
            assertEquals(1, memory().list("active",null,null,null).size());
        }
    }
    @Test void credentialsAreFilteredBeforeModel() throws Exception {
        var service = memory();
        service.profile().getMemories().add(new GrowthProfile.MemoryItem("bad", "fact", "api_key=secret123", "", 9, "pending", false,"confirm","legacy",null,List.of(),null,null));
        try (var review = new PendingMemoryReview(service)) {
            assertTrue(review.reviewBatch((p,t) -> { fail("credential sent"); return null; }));
            assertTrue(service.list(null,null,null,null).isEmpty());
        }
    }
}
