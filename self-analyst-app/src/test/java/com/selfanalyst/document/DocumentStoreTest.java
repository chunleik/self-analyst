package com.selfanalyst.document;

import com.selfanalyst.desktop.store.ChatSessionStore;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentStoreTest {
    @TempDir Path temp;
    private static final String SOURCE = "{\"schemaVersion\":1,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"中文正文\"}]}";
    private static ChatSessionStore.Session session(ChatSessionStore store) {
        var request = new ChatSessionStore.CreateRequest(); request.title = "测试";
        var message = new ChatSessionStore.Message(); message.role = "user"; message.content = "生成文档";
        request.initialMessages = List.of(message);
        return store.create(request);
    }
    @Test void versionsRetryOwnershipAndRestart() throws Exception {
        String sessionId, artifactId;
        try (var chats = ChatSessionStore.openExclusive(temp)) {
            var service = new DocumentService(temp, chats);
            var session = session(chats); sessionId = session.id;
            String turn = session.messages.getFirst().id;
            var first = service.generate(session.id, turn, "markdown", "报告", SOURCE, null, () -> false);
            artifactId = first.id();
            assertEquals(1, first.version());
            assertEquals(first.id(), service.generate(session.id, turn, "markdown", "报告", SOURCE, null, () -> false).id());
            var next = service.generate(session.id, turn, "markdown", "报告新版", SOURCE, first.id(), () -> false);
            assertEquals(2, next.version());
            assertNotEquals(first.id(), next.id());
            var another = session(chats);
            assertThrows(IllegalArgumentException.class, () -> service.readSource(another.id, first.id()));
            assertEquals(2, service.store().list(session.id, 0, 50).size());
            assertThrows(IllegalStateException.class, () -> ChatSessionStore.openExclusive(temp));
        }
        try (var chats = ChatSessionStore.openExclusive(temp)) {
            var service = new DocumentService(temp, chats);
            assertEquals("READY", service.store().get(sessionId, artifactId).status());
            service.readContent(sessionId, artifactId, (artifact, input) -> assertTrue(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("中文正文")));
            assertTrue(service.readSource(sessionId, artifactId).contains("中文正文"));
        }
    }
    @Test void failedJobsRecoverAndDeletionKeepsExternalCopy() throws Exception {
        String id, jobId;
        Path saved = temp.resolve("用户副本.md");
        try (var chats = ChatSessionStore.openExclusive(temp)) {
            var service = new DocumentService(temp, chats);
            var session = session(chats); id = session.id;
            var job = service.store().begin(id, session.messages.getFirst().id, "pending", "test.pdf", "PDF", null);
            jobId = job.id();
            Path part = temp.resolve("documents").resolve(id).resolve(jobId); Files.createDirectories(part); Files.writeString(part.resolve("content.part"), "half");
            var artifact = service.generate(id, session.messages.getFirst().id, "markdown", "报告", SOURCE, null, () -> false);
            service.readContent(id, artifact.id(), (a, input) -> Files.copy(input, saved));
        }
        try (var chats = ChatSessionStore.openExclusive(temp)) {
            var service = new DocumentService(temp, chats);
            assertEquals("FAILED", service.store().get(id, jobId).status());
            assertFalse(Files.exists(temp.resolve("documents").resolve(id).resolve(jobId)));
            chats.beginDeletion(id); chats.deletePendingTranscript(id); assertTrue(chats.finishDeletion(id));
            assertFalse(Files.exists(temp.resolve("documents").resolve(id)));
            assertTrue(Files.exists(saved));
        }
    }
    @Test void corruptedReadyFileIsNeverServed() throws Exception {
        try (var chats = ChatSessionStore.openExclusive(temp)) {
            var service = new DocumentService(temp, chats); var session = session(chats);
            var artifact = service.generate(session.id, session.messages.getFirst().id, "markdown", "报告", SOURCE, null, () -> false);
            Files.writeString(temp.resolve("documents").resolve(session.id).resolve(artifact.id()).resolve("content"), "broken");
            assertThrows(java.io.IOException.class, () -> service.readContent(session.id, artifact.id(), (a, input) -> fail("损坏文件不能返回")));
            assertEquals("FAILED", service.store().get(session.id, artifact.id()).status());
            assertEquals("READY", service.generate(session.id, session.messages.getFirst().id, "markdown", "报告", SOURCE, null, () -> false).status());
        }
    }
}
