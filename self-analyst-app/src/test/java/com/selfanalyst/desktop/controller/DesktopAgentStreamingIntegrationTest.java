package com.selfanalyst.desktop.controller;

import com.selfanalyst.agent.SelfAnalystAgent;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.RecordComponent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopAgentStreamingIntegrationTest {

    @Test
    void streamsAgentDeltasAndFinalResultOverSse(@TempDir Path tempDir) throws Exception {
        CountDownLatch releaseModelCompletion = new CountDownLatch(1);
        HttpServer modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelServer.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(chunk("Hello").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                if (!releaseModelCompletion.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release model completion");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            exchange.getResponseBody().write(modelSseAfterFirstChunk()
                    .getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        modelServer.start();

        Config config = withOverrides(Config.testDefaults(tempDir), Map.of(
                "llmApiKey", "test-key",
                "llmBaseUrl", "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1",
                "llmModel", "test-model"));
        ChatSessionStore store = new ChatSessionStore(config.memoryDir());
        ChatSessionStore.Session session = store.create(new ChatSessionStore.CreateRequest());
        ChatSessionStore.Message user = new ChatSessionStore.Message();
        user.role = "user";
        user.content = "stream please";
        user.status = "sent";
        ChatSessionStore.Message pending = new ChatSessionStore.Message();
        pending.role = "assistant";
        pending.content = "thinking";
        pending.status = "pending";
        List<ChatSessionStore.Message> saved = store.appendMessages(
                session.id, List.of(user, pending));

        Javalin app = null;
        try (SelfAnalystAgent agent = new SelfAnalystAgent(config)) {
            DesktopAgentController controller = new DesktopAgentController(
                    null, null, agent, null, config, store);
            app = Javalin.create();
            app.post("/desktop/chat/stream", controller::chatStream);
            app.start("127.0.0.1", 0);

            String requestJson = """
                    {"message":"stream please","context":{},"sessionId":"%s",\
                    "userMessageId":"%s"}
                    """.formatted(session.id, saved.getFirst().id);
            HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + app.port()
                                    + "/desktop/chat/stream"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type")
                    .orElse("").startsWith("text/event-stream"));
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            CompletableFuture<String> firstFrame = CompletableFuture.supplyAsync(() -> {
                try {
                    return reader.readLine() + "\n" + reader.readLine() + "\n" + reader.readLine();
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
            assertEquals("event: delta\ndata: {\"text\":\"Hello\"}\n",
                    firstFrame.get(2, TimeUnit.SECONDS),
                    "the first delta must arrive before the model completes");
            releaseModelCompletion.countDown();
            String remainingBody = reader.lines().reduce("", (left, line) -> left + line + "\n");
            assertTrue(remainingBody.contains(
                    "event: delta\ndata: {\"text\":\" streaming\"}"));
            assertTrue(remainingBody.contains(
                    "event: result\ndata: {\"message\":\"Hello streaming\""));
        } finally {
            releaseModelCompletion.countDown();
            if (app != null) app.stop();
            store.close();
            modelServer.stop(0);
        }
    }

    private static String modelSseAfterFirstChunk() {
        return chunk(" streaming")
                + "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,"
                + "\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
    }

    private static String chunk(String text) {
        return "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"role\":\"assistant\",\"content\":\"" + text
                + "\"},\"finish_reason\":null}]}\n\n";
    }

    private static Config withOverrides(Config base, Map<String, Object> overrides)
            throws Exception {
        RecordComponent[] components = Config.class.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            types[i] = component.getType();
            values[i] = overrides.containsKey(component.getName())
                    ? overrides.get(component.getName())
                    : component.getAccessor().invoke(base);
        }
        return Config.class.getDeclaredConstructor(types).newInstance(values);
    }
}
