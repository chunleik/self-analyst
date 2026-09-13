package com.selfanalyst.events;

import com.selfanalyst.document.DocumentService;
import com.selfanalyst.desktop.controller.DesktopDocumentController;
import com.selfanalyst.desktop.store.ChatSessionStore;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DesktopDocumentControllerTest {
    @TempDir Path temp;
    @Test void actualDesktopGuardProtectsMetadataAndBinary() throws Exception {
        var server = new EventServer(temp.resolve("events"), 0, "test-document-token");
        try (AutoCloseable serverCleanup = server::stop; var chats = new ChatSessionStore(temp.resolve("memory"))) {
            var service = new DocumentService(temp.resolve("memory"), chats);
            var session = chats.create(new ChatSessionStore.CreateRequest());
            var user = new ChatSessionStore.Message(); user.role = "user"; user.content = "生成";
            String turn = chats.appendMessages(session.id, List.of(user)).getFirst().id;
            var artifact = service.generate(session.id, turn, "markdown", "中文报告", "{\"schemaVersion\":1,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"内容\"}]}", null, () -> false);
            var controller = new DesktopDocumentController(service);
            String route = "/desktop/chat/sessions/{id}/documents/{artifact}";
            server.app().get(route, controller::detail); server.app().get(route + "/content", controller::content);
            server.start(0);
            String url = "http://127.0.0.1:" + server.port() + "/desktop/chat/sessions/" + session.id + "/documents/" + artifact.id();
            var client = HttpClient.newHttpClient();
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(url + "?token=test-document-token")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var response = client.send(HttpRequest.newBuilder(URI.create(url + "/content")).header("X-SelfAnalyst-Token", "test-document-token").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode()); assertTrue(response.body().contains("内容"));
            assertTrue(response.headers().firstValue("Content-Disposition").orElseThrow().contains("filename*=UTF-8''"));
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(url)).header("Cookie", "self_analyst_session=test-document-token").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var other = chats.create(new ChatSessionStore.CreateRequest());
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(url.replace(session.id, other.id))).header("X-SelfAnalyst-Token", "test-document-token").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void noConfiguredTokenFailsClosed() throws Exception {
        var server = new EventServer(temp.resolve("events"), 0, null);
        try (AutoCloseable serverCleanup = server::stop; var chats = new ChatSessionStore(temp.resolve("memory"))) {
            var controller = new DesktopDocumentController(new DocumentService(temp.resolve("memory"), chats));
            server.app().get("/desktop/chat/sessions/{id}/documents", controller::list); server.start(0);
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                    + "/desktop/chat/sessions/" + "a".repeat(32) + "/documents")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode());
        }
    }
}
