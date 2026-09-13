package com.selfanalyst.document;

import com.selfanalyst.agent.SelfAnalystAgent;
import com.selfanalyst.config.ConfigResolver;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentAgentIntegrationTest {
    @TempDir Path temp;
    @Test void actualAgentCallsDocumentToolAndReplayKeepsTheSameFile() throws Exception {
        runAgent(false);
    }
    @Test void fileSurvivesFailureOfTheFollowingModelReply() throws Exception {
        runAgent(true);
    }
    private void runAgent(boolean failReply) throws Exception {
        var requests = new AtomicInteger();
        var toolMessages = new java.util.concurrent.atomic.AtomicReference<>("");
        var model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/v1/chat/completions", exchange -> {
            var request = DocumentRequest.JSON.readTree(exchange.getRequestBody());
            var diagnostics = new ArrayList<String>();
            for (var message : request.path("messages")) if (message.path("role").asText().equals("tool")) diagnostics.add(message.path("content").asText());
            toolMessages.set(diagnostics.toString());
            int call = requests.incrementAndGet();
            if (failReply && call == 2) { exchange.sendResponseHeaders(500, -1); exchange.close(); return; }
            String frame;
            if (call == 1) {
                String arguments = DocumentRequest.JSON.writeValueAsString(Map.of("format", "pptx", "title", "工作汇报",
                        "sourceJson", "{\"schemaVersion\":1,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"已经完成本周工作整理\"}]}"));
                frame = chunk(Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "call_document", "type", "function",
                        "function", Map.of("name", "generate_document", "arguments", arguments)))), null)
                        + chunk(Map.of(), "tool_calls");
            } else frame = chunk(Map.of("role", "assistant", "content", "文档已生成，请使用文件卡片保存。"), null) + chunk(Map.of(), "stop");
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] output = (frame + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, output.length); exchange.getResponseBody().write(output); exchange.close();
        });
        model.start();
        try {
            var properties = new Properties(); properties.setProperty("memory.dir", temp.resolve("memory").toString());
            properties.setProperty("llm.api-key", "test-key"); properties.setProperty("llm.model", "test-model");
            properties.setProperty("llm.base-url", "http://127.0.0.1:" + model.getAddress().getPort() + "/v1");
            properties.setProperty("wiki.enabled", "false"); properties.setProperty("websearch.enabled", "false");
            var config = ConfigResolver.resolve(properties, Map.of()).config();
            try (var chats = new ChatSessionStore(config.memoryDir()); var agent = new SelfAnalystAgent(config)) {
                var documents = new DocumentService(config.memoryDir(), chats); agent.registerDocumentTools(documents);
                var session = chats.create(new ChatSessionStore.CreateRequest());
                var message = new ChatSessionStore.Message(); message.role = "user"; message.content = "生成 PPT";
                String turn = chats.appendMessages(session.id, List.of(message)).getFirst().id;
                var input = new SelfAnalystAgent.PersistedDesktopTurn("生成 PPT", null, List.of(), true);
                String response = null;
                if (failReply) assertThrows(RuntimeException.class, () -> agent.chat(session.id, turn, () -> input).block(Duration.ofSeconds(20)));
                else { response = agent.chat(session.id, turn, () -> input).block(Duration.ofSeconds(20)); assertTrue(response.contains("文档已生成")); }
                var first = documents.store().list(session.id, 0, 10);
                assertEquals(1, first.size(), toolMessages.get()); assertEquals("READY", first.getFirst().status());
                int count = requests.get();
                String replay = agent.chat(session.id, turn, () -> input).block(Duration.ofSeconds(10));
                if (!failReply) { assertEquals(response, replay); assertEquals(count, requests.get()); }
                assertEquals(first.getFirst().id(), documents.store().list(session.id, 0, 10).getFirst().id());
            }
        } finally { model.stop(0); }
    }
    private static String chunk(Map<String, Object> delta, String finish) throws java.io.IOException {
        var choice = new LinkedHashMap<String, Object>(); choice.put("index", 0); choice.put("delta", delta); choice.put("finish_reason", finish);
        return "data: " + DocumentRequest.JSON.writeValueAsString(Map.of("id", "chatcmpl-test", "object", "chat.completion.chunk",
                "created", 1, "model", "test-model", "choices", List.of(choice))) + "\n\n";
    }
}
