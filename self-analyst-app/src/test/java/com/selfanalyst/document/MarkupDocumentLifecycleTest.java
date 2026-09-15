package com.selfanalyst.document;

import com.selfanalyst.desktop.store.ChatSessionStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MarkupDocumentLifecycleTest {
    @TempDir Path temp;

    @Test void newFormatsKeepVersionsSourcesAndRecovery() throws Exception {
        for (String kind : List.of("html", "svg")) {
            Path root = temp.resolve(kind);
            String sessionId, artifactId;
            String source = MarkupDocumentTest.source(kind, kind.equals("html") ? MarkupDocumentTest.HTML : MarkupDocumentTest.SVG);
            try (var chats = ChatSessionStore.openExclusive(root)) {
                var service = new DocumentService(root, chats);
                var request = new ChatSessionStore.CreateRequest();
                var message = new ChatSessionStore.Message(); message.role = "user"; message.content = "生成";
                request.initialMessages = List.of(message);
                var session = chats.create(request); sessionId = session.id;
                String turn = session.messages.getFirst().id;
                var first = service.generate(session.id, turn, kind, "成果", source, null, () -> false); artifactId = first.id();
                assertEquals(first.id(), service.generate(session.id, turn, kind, "成果", source, null, () -> false).id());
                var next = service.generate(session.id, turn, kind, "修改版", source.replace("结果", "成果"), first.id(), () -> false);
                assertEquals(first.id(), next.parentId()); assertEquals(2, next.version());
                assertEquals(DocumentRequest.JSON.readTree(source), DocumentRequest.JSON.readTree(service.readSource(session.id, first.id())));
                assertFalse(chats.getSession(session.id).messages.getFirst().content.contains("<svg"));
                assertThrows(IllegalArgumentException.class, () -> service.generate(session.id, turn, kind, "无效", MarkupDocumentTest.source(kind, "<bad>"), null, () -> false));
                assertEquals("FAILED", service.store().list(session.id, 0, 50).stream().filter(a -> a.name().startsWith("无效")).findFirst().orElseThrow().status());
                assertThrows(CancellationException.class, () -> service.generate(session.id, turn, kind, "取消", source, null, () -> true));
                assertEquals("READY", service.store().get(session.id, first.id()).status());
                var other = chats.create(new ChatSessionStore.CreateRequest());
                assertThrows(IllegalArgumentException.class, () -> service.readSource(other.id, first.id()));
            }
            try (var chats = ChatSessionStore.openExclusive(root)) {
                var service = new DocumentService(root, chats);
                assertEquals("READY", service.store().get(sessionId, artifactId).status());
                service.readContent(sessionId, artifactId, (artifact, input) -> assertTrue(input.readAllBytes().length > 0));
                assertEquals(DocumentRequest.JSON.readTree(source), DocumentRequest.JSON.readTree(service.readSource(sessionId, artifactId)));
                Files.writeString(root.resolve("documents").resolve(sessionId).resolve(artifactId).resolve("source.json"), "x".repeat(DocumentRequest.MAX_SOURCE_BYTES + 1));
                assertThrows(java.io.IOException.class, () -> service.readSource(sessionId, artifactId));
            }
        }
    }
}
