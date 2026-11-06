package com.selfanalyst.chatgpt;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChatGptInferenceGateTest {
    @Test void quotaPausesOnlySelectedAccountUntilExplicitResume() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var gate = new ChatGptInferenceGate();
        try (var client = HttpClient.newHttpClient(); var model = gate.guard("account-a", new ChatGptResponsesModel("model", () -> "test-token",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"), client, Duration.ofSeconds(2)))) {
            var messages = List.of(Msg.builder().role(MsgRole.USER).textContent("test").build());
            assertThrows(RuntimeException.class, () -> model.stream(messages, List.of(), null).collectList().block(Duration.ofSeconds(3)));
            assertFalse(gate.available("account-a")); assertTrue(gate.available("account-b"));
            assertThrows(RuntimeException.class, () -> model.stream(messages, List.of(), null).collectList().block(Duration.ofSeconds(3)));
            assertEquals(1, calls.get());
            gate.resume("account-a");
            assertTrue(gate.available("account-a"));
            assertThrows(RuntimeException.class, () -> model.stream(messages, List.of(), null).collectList().block(Duration.ofSeconds(3)));
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }
}
