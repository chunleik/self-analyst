package com.selfanalyst.document;

import com.selfanalyst.desktop.store.ChatSessionStore;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentPublicationTest {
    @TempDir Path temp;
    private static final String SOURCE = "{\"schemaVersion\":1,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"完整文档\"}]}";
    @Test void writeAndMoveFailuresNeverPublishPartialFiles() throws Exception {
        for (String stage : List.of("written", "moved")) {
            Path root = temp.resolve(stage);
            try (var chats = new ChatSessionStore(root)) {
                var service = new DocumentService(root, chats, at -> { if (at.equals(stage)) throw new java.io.IOException("injected"); });
                var session = chats.create(new ChatSessionStore.CreateRequest());
                var user = new ChatSessionStore.Message(); user.role = "user"; user.content = "生成";
                String turn = chats.appendMessages(session.id, List.of(user)).getFirst().id;
                assertThrows(java.io.IOException.class, () -> service.generate(session.id, turn, "markdown", "失败测试", SOURCE, null, () -> false));
                var artifact = service.store().list(session.id, 0, 10).getFirst();
                assertEquals("FAILED", artifact.status());
                assertFalse(Files.exists(root.resolve("documents").resolve(session.id).resolve(artifact.id())));
            }
        }
    }
    @Test void sessionDeletionBeforeCommitDoesNotPublish() throws Exception {
        try (var chats = new ChatSessionStore(temp)) {
            var session = chats.create(new ChatSessionStore.CreateRequest());
            var service = new DocumentService(temp, chats, stage -> { if (stage.equals("moved")) chats.beginDeletion(session.id); });
            var user = new ChatSessionStore.Message(); user.role = "user"; user.content = "生成";
            String turn = chats.appendMessages(session.id, List.of(user)).getFirst().id;
            assertThrows(IllegalArgumentException.class, () -> service.generate(session.id, turn, "markdown", "测试", SOURCE, null, () -> false));
            assertTrue(service.store().readyIds().isEmpty());
            chats.deletePendingTranscript(session.id); assertTrue(chats.finishDeletion(session.id));
        }
    }
    @Test void pruningMessagesKeepsArtifactsAndDownloadDefersCleanup() throws Exception {
        try (var chats = new ChatSessionStore(temp)) {
            var service = new DocumentService(temp, chats);
            var session = chats.create(new ChatSessionStore.CreateRequest());
            var user = new ChatSessionStore.Message(); user.role = "user"; user.content = "生成";
            String turn = chats.appendMessages(session.id, List.of(user)).getFirst().id;
            var artifact = service.generate(session.id, turn, "markdown", "报告", SOURCE, null, () -> false);
            for (int i = 0; i < 105; i++) {
                var next = new ChatSessionStore.Message(); next.role = "user"; next.content = "后续";
                var reply = new ChatSessionStore.Message(); reply.role = "assistant"; reply.content = "回复"; reply.status = "pending";
                var saved = chats.appendMessages(session.id, List.of(next, reply));
                chats.updateMessage(session.id, saved.getLast().id, "回复", "sent", null, List.of());
            }
            assertTrue(chats.getSession(session.id).messages.stream().noneMatch(m -> m.id.equals(turn)));
            assertEquals("READY", service.store().get(session.id, artifact.id()).status());
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            var download = CompletableFuture.runAsync(() -> {
                try { service.readContent(session.id, artifact.id(), (a, input) -> {
                    started.countDown();
                    try { if (!release.await(3, TimeUnit.SECONDS)) throw new java.io.IOException("timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                }); } catch (Exception e) { throw new CompletionException(e); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            chats.beginDeletion(session.id); chats.deletePendingTranscript(session.id);
            assertFalse(chats.finishDeletion(session.id));
            release.countDown(); download.get(2, TimeUnit.SECONDS);
            assertTrue(chats.finishDeletion(session.id)); assertFalse(Files.exists(temp.resolve("documents").resolve(session.id)));
        }
    }
}
