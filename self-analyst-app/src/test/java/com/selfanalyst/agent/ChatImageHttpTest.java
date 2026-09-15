package com.selfanalyst.agent;

import com.fasterxml.jackson.databind.*;
import com.selfanalyst.desktop.controller.*;
import com.selfanalyst.desktop.store.*;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Actual REST -> canonical transcript -> Agent -> OpenAI-compatible wire integration. */
public class ChatImageHttpTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;
    @Test void imageOnlyHttpTurnCanRetryRestoreAndDelete() throws Exception {
        try (var h = new Harness(dir, 0)) {
            String sid = JSON.readTree(h.request("POST", "/desktop/chat/sessions", "{}").body()).path("id").asText();
            var uploaded = h.upload(sid, sample());
            assertEquals(201, uploaded.statusCode());
            var largeImage = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
            var random = new java.util.Random(17);
            for (int y = 0; y < 900; y++) for (int x = 0; x < 900; x++) largeImage.setRGB(x, y, random.nextInt());
            var largeBytes = new ByteArrayOutputStream(); ImageIO.write(largeImage, "png", largeBytes);
            assertTrue(largeBytes.size() > 1024 * 1024);
            assertEquals(201, h.upload(sid, largeBytes.toByteArray()).statusCode(), "binary upload must not inherit the JSON body limit");
            String image = JSON.readTree(uploaded.body()).path("id").asText();
            var appended = h.request("POST", "/desktop/chat/sessions/" + sid + "/messages",
                    "{\"messages\":[{\"role\":\"user\",\"status\":\"sent\",\"content\":\"\",\"imageIds\":[\"" + image
                            + "\"]},{\"role\":\"assistant\",\"status\":\"pending\",\"content\":\"\"}]}");
            assertEquals(201, appended.statusCode(), appended.body());
            var messages = JSON.readTree(appended.body());
            String user = messages.get(0).path("id").asText();
            assertEquals(image, messages.get(0).path("images").get(0).path("id").asText());
            String body = JSON.writeValueAsString(Map.of("sessionId", sid, "userMessageId", user));
            h.rejectImages.set(true);
            var failed = h.request("POST", "/desktop/chat", body);
            assertEquals(400, failed.statusCode(), failed.body());
            assertEquals("error.chat.imageUnsupported", JSON.readTree(failed.body()).path("errorCode").asText());
            h.rejectImages.set(false);
            var reply = h.request("POST", "/desktop/chat", body);
            assertEquals(200, reply.statusCode(), reply.body());
            assertTrue(h.requests.getLast().contains("data:image/png;base64,"));
            int calls = h.requests.size();
            assertEquals(200, h.request("POST", "/desktop/chat", body).statusCode());
            assertEquals(calls, h.requests.size());
            assertEquals(400, h.request("PUT", "/desktop/chat/sessions/" + sid + "/messages/" + messages.get(1).path("id").asText(),
                    "{\"imageIds\":[]}").statusCode());
            assertEquals(413, h.request("POST", "/desktop/chat/sessions/" + sid + "/messages", "x".repeat(262145)).statusCode());
            String read = h.request("GET", "/desktop/chat/sessions/" + sid, null).body();
            assertTrue(read.contains(image));
            assertEquals(200, h.request("DELETE", "/desktop/chat/sessions/" + sid, null).statusCode());
            assertEquals(404, h.request("GET", "/desktop/chat/sessions/" + sid + "/images/" + image, null).statusCode());
        }
    }

    static byte[] sample() throws IOException {
        var image = new BufferedImage(480, 240, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(new Color(233, 240, 250)); g.fillRect(0, 0, 480, 240);
        g.setColor(new Color(55, 100, 170)); g.fillRoundRect(30, 40, 130, 140, 20, 20);
        g.setColor(new Color(70, 160, 120)); g.fillOval(240, 45, 130, 130);
        g.setColor(Color.DARK_GRAY); g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString("Chat image input test", 30, 215); g.dispose();
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }

    static final class Harness implements AutoCloseable {
        final ChatSessionStore store;
        final SelfAnalystAgent agent;
        final DesktopChatSessionController sessions;
        final Javalin app = Javalin.create();
        final HttpServer model;
        final List<String> requests = new CopyOnWriteArrayList<>();
        final AtomicBoolean rejectImages = new AtomicBoolean();
        final HttpClient client = HttpClient.newHttpClient();
        Harness(Path dir, int port) throws Exception {
            model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            model.createContext("/v1/chat/completions", exchange -> {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                boolean reject = rejectImages.get();
                String response = reject ? "{\"error\":{\"message\":\"This model does not support image input\",\"type\":\"invalid_request_error\"}}"
                        : "data: {\"id\":\"chat-img\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"I received the image: a blue rectangle and a green circle.\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"chat-img\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", reject ? "application/json" : "text/event-stream");
                exchange.sendResponseHeaders(reject ? 400 : 200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            model.start();
            var config = ChatImageModelTest.config(dir, "http://127.0.0.1:" + model.getAddress().getPort() + "/v1");
            store = new ChatSessionStore(dir); agent = new SelfAnalystAgent(config);
            var images = new DesktopChatImageController(store.images());
            sessions = new DesktopChatSessionController(store, new com.selfanalyst.desktop.service.ChatSummaryService(), null, config, null);
            var chat = new DesktopAgentController(null, null, agent, null, config, store);
            app.post("/desktop/chat/sessions", sessions::createSession);
            app.get("/desktop/chat/sessions", sessions::listSessions);
            app.get("/desktop/chat/sessions/{id}", sessions::getSession);
            app.put("/desktop/chat/sessions/{id}", sessions::updateSession);
            app.delete("/desktop/chat/sessions/{id}", sessions::deleteSession);
            app.put("/desktop/chat/active-session", sessions::setActiveSession);
            app.post("/desktop/chat/sessions/{id}/messages", sessions::appendMessages);
            app.put("/desktop/chat/sessions/{id}/messages/{msgId}", sessions::updateMessage);
            app.post("/desktop/chat/sessions/{id}/images", images::upload);
            app.get("/desktop/chat/sessions/{id}/images/{image}", images::content);
            app.delete("/desktop/chat/sessions/{id}/images/{image}", images::delete);
            app.post("/desktop/chat", chat::chat);
            app.post("/desktop/chat/stream", chat::chatStream);
            app.get("/desktop/status", ctx -> ctx.json(Map.of("language", "en", "dateLocale", "en-US", "llm", Map.of("configured", true))));
            app.get("/desktop/summary", ctx -> ctx.json(Map.of()));
            app.get("/desktop/tasks", ctx -> ctx.json(List.of()));
            app.get("/desktop/chat/sessions/{id}/documents", ctx -> ctx.json(Map.of("artifacts", List.of())));
            app.get("/sample.png", ctx -> ctx.contentType("image/png").result(sample()));
            app.get("/desktop-ui/locales/{file}", ctx -> ctx.contentType("application/json")
                    .result(Files.readString(Path.of("self-analyst-app/src/main/resources/desktop-ui/locales").resolve(ctx.pathParam("file")))));
            app.get("/desktop-ui/{file}", ctx -> {
                String file = ctx.pathParam("file");
                if (!file.matches("[a-zA-Z0-9.-]+")) { ctx.status(404); return; }
                Path path = Path.of("self-analyst-app/src/main/resources/desktop-ui").resolve(file);
                if (!Files.exists(path)) { ctx.status(404); return; }
                String value = Files.readString(path);
                if (file.equals("index.html")) value = value.replace("</body>", """
                        <div style="position:fixed;top:8px;right:12px;z-index:5000;display:flex;gap:8px">
                        <button id="qa-image" onclick="fetch('/sample.png').then(r=>r.blob()).then(b=>addChatImages([new File([b],'sample.png',{type:'image/png'})]))">Load test image</button>
                        <button onclick="activateDeepChatFallback(deepChatElement());renderChatTab()">Test fallback</button>
                        </div></body>
                        """);
                ctx.contentType(file.endsWith(".js") ? "application/javascript" : file.endsWith(".css") ? "text/css" : "text/html").result(value);
            });
            app.start("127.0.0.1", port);
        }
        HttpResponse<String> request(String method, String path, String body) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                    .header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> upload(String session, byte[] bytes) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/desktop/chat/sessions/" + session + "/images"))
                    .header("Content-Type", "image/png").POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() { app.stop(); agent.close(); store.close(); model.stop(0); }
    }

    /** Isolated browser QA, using synthetic images and a loopback model only. */
    public static void main(String[] args) throws Exception {
        var harness = new Harness(Path.of(args[0]), 5808);
        Runtime.getRuntime().addShutdownHook(new Thread(harness::close));
        System.out.println("Image QA ready at http://127.0.0.1:5808/desktop-ui/index.html");
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
