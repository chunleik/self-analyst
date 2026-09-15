package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ChatImageModelTest {
    @TempDir Path dir;
    @Test void onlyExplicitVisionErrorsAreClassifiedAsUnsupported() {
        assertEquals("CHAT_IMAGE_UNSUPPORTED", SelfAnalystAgent.safeModelFailure(new RuntimeException("400 model does not support image input")).getMessage());
        for (String message : List.of("400 invalid argument", "401 unsupported image authorization", "connection reset", "403 vision access denied"))
            assertNotEquals("CHAT_IMAGE_UNSUPPORTED", SelfAnalystAgent.safeModelFailure(new RuntimeException(message)).getMessage());
    }
    @Test void realWireImagesSurviveRestartAndReplayWithoutBase64InState() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] data = ("data: {\"id\":\"chat-image\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"image seen\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"chat-image\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        server.start();
        try (var store = new ChatSessionStore(dir)) {
            String session = store.create(null).id;
            List<ChatImageStore.Image> images = new ArrayList<>();
            for (String format : List.of("png", "jpeg")) {
                var out = new ByteArrayOutputStream();
                ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), format, out);
                images.add(store.images().upload(session, new ByteArrayInputStream(out.toByteArray()), "image/" + format));
            }
            Config config = config(dir, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var turn = new SelfAnalystAgent.PersistedDesktopTurn("", null, List.of(), true, images);
            try (var agent = new SelfAnalystAgent(config)) {
                assertEquals("image seen", agent.chat(session, "a".repeat(12), () -> turn).block(Duration.ofSeconds(10)));
                assertEquals("image seen", agent.chat(session, "a".repeat(12), () -> turn).block(Duration.ofSeconds(10)));
                assertEquals(1, requests.size());
            }
            try (var files = Files.walk(dir.resolve("agent-state"))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String value = Files.readString(file);
                    assertFalse(value.contains("data:image"));
                    assertTrue(value.contains("selfanalyst-image:"));
                }
            }
            try (var agent = new SelfAnalystAgent(config)) {
                assertEquals("image seen", agent.chat(session, "b".repeat(12), "Describe again").block(Duration.ofSeconds(10)));
            }
            assertEquals(2, requests.size());
            for (String request : requests) {
                var tree = new ObjectMapper().readTree(request);
                assertTrue(request.contains("data:image/png;base64,"));
                assertTrue(request.contains("data:image/jpeg;base64,"));
                assertFalse(request.contains("selfanalyst-image:"));
                assertTrue(tree.path("messages").isArray());
            }
            Files.delete(dir.resolve("chat-images").resolve(session).resolve(images.getFirst().id()));
            try (var agent = new SelfAnalystAgent(config)) {
                assertThrows(ChatImageModel.ImageUnavailableException.class,
                        () -> agent.chat(session, "c".repeat(12), "Read the original again").block(Duration.ofSeconds(10)));
                assertEquals(2, requests.size(), "missing image must not silently become a text-only request");
            }
        } finally { server.stop(0); }
    }

    static Config config(Path dir, String url) throws Exception {
        Config base = Config.testDefaults(dir);
        var components = Config.class.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length]; Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            var component = components[i]; types[i] = component.getType();
            values[i] = switch (component.getName()) {
                case "memoryDir" -> dir;
                case "llmApiKey" -> "test-key";
                case "llmBaseUrl" -> url;
                case "llmModel" -> "vision-test";
                case "agentCompactionEnabled", "webSearchEnabled" -> false;
                default -> component.getAccessor().invoke(base);
            };
        }
        return Config.class.getDeclaredConstructor(types).newInstance(values);
    }
}
