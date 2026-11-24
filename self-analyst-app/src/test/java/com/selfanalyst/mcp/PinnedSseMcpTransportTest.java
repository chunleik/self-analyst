package com.selfanalyst.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.integrations.PinnedHttpsClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PinnedSseMcpTransportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final URI URL = URI.create("https://mcp.example.com/sse");
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test void actualSdkUsesLegacyInitializationAndMatchesFragmentedResponsesWithoutWaitingForGetEof() {
        BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch getClosed = new CountDownLatch(1);
        var transport = new PinnedSseMcpTransport((uri, headers, connect, lifetime, maximum, stream, consume) -> {
            assertEquals(URL, uri);
            assertEquals("Bearer synthetic", headers.get("Authorization"));
            assertEquals("text/event-stream", headers.get("Accept"));
            try {
                emit(consume, "\uFEFF: heartbeat\r\nevent: endpoint\r\ndata: /messages?sessionId=sse-demo\r\n\r\n");
                for (;;) {
                    String reply = replies.take();
                    // SSE data lines join with a newline; this one splits safely before the JSON object.
                    emit(consume, "event: message\r\ndata: \r\ndata: " + reply + "\r\n\r\n");
                }
            } catch (InterruptedException closed) {
                Thread.currentThread().interrupt();
            } finally { getClosed.countDown(); }
        }, (target, headers, bytes, timeout, maximum, authorize, startSend) -> {
            if (!authorize.getAsBoolean() || !startSend.getAsBoolean()) throw new IOException("refused");
            assertEquals("Bearer synthetic", headers.get("Authorization"));
            assertEquals("application/json", headers.get("Content-Type"));
            var request = JSON.readTree(bytes);
            if (request.has("id")) {
                Object result = switch (request.path("method").asText()) {
                    case "initialize" -> {
                        assertEquals("2024-11-05", request.path("params").path("protocolVersion").asText());
                        yield Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of("tools", Map.of()),
                                "serverInfo", Map.of("name", "synthetic", "version", "1"));
                    }
                    case "tools/list" -> Map.of("tools", List.of(Map.of("name", "echo", "description", "Synthetic",
                            "inputSchema", Map.of("type", "object", "properties", Map.of()))));
                    case "tools/call" -> {
                        calls.incrementAndGet();
                        yield Map.of("content", List.of(Map.of("type", "text", "text", "模拟结果")), "isError", false);
                    }
                    default -> throw new AssertionError(request.path("method"));
                };
                replies.add(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result)));
            }
            return new PinnedHttpsClient.Response(202, Map.of(), "Accepted".getBytes(StandardCharsets.US_ASCII));
        }, URL, TIMEOUT, TIMEOUT, 65536, () -> "synthetic");

        try (var client = McpClient.sync(transport).requestTimeout(TIMEOUT).initializationTimeout(TIMEOUT).build()) {
            assertEquals("2024-11-05", client.initialize().protocolVersion());
            assertTrue(transport.isOpen());
            assertEquals("echo", client.listTools().tools().getFirst().name());
            var request = new McpSchema.CallToolRequest("echo", Map.of());
            assertThrows(RuntimeException.class, () -> client.callTool(request));
            assertEquals(0, calls.get());
            assertFalse(transport.withBusinessGuard(() -> true, () -> client.callTool(request)).isError());
            assertEquals(1, calls.get());
        }
        assertDoesNotThrow(() -> assertTrue(getClosed.await(2, TimeUnit.SECONDS)));
        assertFalse(transport.isOpen());
    }

    @Test void revocationAtWriteGateAndAmbiguousPostFailureDoNotWriteOrRetry() {
        for (boolean revoke : List.of(true, false)) {
            var permission = new AtomicBoolean(true);
            var writes = new AtomicInteger();
            var attempts = new AtomicInteger();
            var streams = new AtomicInteger();
            var transport = new PinnedSseMcpTransport((uri, header, connect, life, max, stream, consume) -> {
                streams.incrementAndGet();
                emit(consume, "event: endpoint\ndata: /messages?session_id=opaque\n\n");
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException closed) { Thread.currentThread().interrupt(); }
            }, (target, headers, body, timeout, maximum, authorize, startSend) -> {
                attempts.incrementAndGet();
                assertTrue(authorize.getAsBoolean());
                if (revoke) permission.set(false);
                if (!startSend.getAsBoolean()) throw new IOException("aborted");
                writes.incrementAndGet();
                throw new IOException("uncertain_delivery");
            }, URL, TIMEOUT, TIMEOUT, 65536, () -> null);
            try {
                transport.connect(handler -> handler).block(TIMEOUT);
                assertThrows(RuntimeException.class, () -> transport.withBusinessGuard(permission::get, () ->
                        transport.sendMessage(new McpSchema.JSONRPCRequest("2.0", "tools/call", 1, Map.of())).block(TIMEOUT)));
                assertEquals(revoke ? 0 : 1, writes.get());
                assertEquals(1, attempts.get());
                assertFalse(transport.isOpen());
                assertThrows(RuntimeException.class, () -> transport.connect(handler -> handler).block(TIMEOUT));
                assertEquals(1, streams.get());
            } finally { transport.close(); }
        }
    }

    @Test void refusesUntrustedEndpointBeforeAnyPostAndClosesOnTimeoutOrOversize() {
        for (String frame : List.of(
                "event: endpoint\ndata: https://intruder.example.com/messages?sessionId=opaque\n\n",
                "event: endpoint\ndata: /messages?sessionId=one&sessionId=two\n\n",
                "event: endpoint\ndata: /messages?sessionId=" + "x".repeat(512) + "\n\n",
                ": no endpoint")) {
            var posts = new AtomicInteger();
            Duration initialize = Duration.ofMillis(250);
            var transport = new PinnedSseMcpTransport((uri, header, connect, life, max, stream, consume) -> {
                emit(consume, frame);
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException closed) { Thread.currentThread().interrupt(); }
            }, (target, headers, body, timeout, maximum, authorize, startSend) -> {
                posts.incrementAndGet();
                throw new AssertionError("untrusted endpoint reached POST");
            }, URL, TIMEOUT, initialize, 256, () -> null);
            try {
                assertThrows(RuntimeException.class, () -> transport.connect(handler -> Mono.empty()).block(TIMEOUT));
                assertEquals(0, posts.get());
                assertFalse(transport.isOpen());
            } finally { transport.close(); }
        }
    }

    @Test void neverDeliversAnEarlyEventWhenThePostIsRejected() {
        var start = new CountDownLatch(1);
        var emitted = new CountDownLatch(1);
        var gotResponse = new AtomicBoolean();
        var transport = new PinnedSseMcpTransport((uri, header, connect, life, max, stream, consume) -> {
            emit(consume, "event: endpoint\ndata: /messages?sessionId=opaque\n\n");
            try {
                assertTrue(start.await(2, TimeUnit.SECONDS));
                emit(consume, "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n");
                emitted.countDown();
                new CountDownLatch(1).await();
            } catch (InterruptedException closed) { Thread.currentThread().interrupt(); }
        }, (target, headers, body, timeout, maximum, authorize, startSend) -> {
            assertTrue(authorize.getAsBoolean());
            assertTrue(startSend.getAsBoolean());
            start.countDown();
            try { assertTrue(emitted.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(); }
            assertFalse(gotResponse.get());
            return new PinnedHttpsClient.Response(403, Map.of(), new byte[0]);
        }, URL, TIMEOUT, TIMEOUT, 4096, () -> null);
        try {
            transport.connect(message -> message.doOnNext(ignored -> gotResponse.set(true))).block(TIMEOUT);
            assertThrows(RuntimeException.class, () ->
                    transport.sendMessage(new McpSchema.JSONRPCRequest("2.0", "tools/list", 1, Map.of())).block(TIMEOUT));
            assertFalse(gotResponse.get());
            assertFalse(transport.isOpen());
        } finally { transport.close(); }
    }

    @Test void queuesAnEarlySendUntilEndpointAndKeepsReadinessAfterCancellingAnotherSend() throws Exception {
        var opened = new CountDownLatch(1);
        var publishEndpoint = new CountDownLatch(1);
        var writes = new AtomicInteger();
        var transport = new PinnedSseMcpTransport((uri, header, connect, life, max, stream, consume) -> {
            opened.countDown();
            try {
                publishEndpoint.await();
                emit(consume, "event: endpoint\ndata: /messages?sessionId=delayed\n\n");
                new CountDownLatch(1).await();
            } catch (InterruptedException closed) { Thread.currentThread().interrupt(); }
        }, (target, headers, body, timeout, maximum, authorize, startSend) -> {
            assertTrue(authorize.getAsBoolean());
            assertTrue(startSend.getAsBoolean());
            writes.incrementAndGet();
            return new PinnedHttpsClient.Response(202, Map.of(), new byte[0]);
        }, URL, TIMEOUT, TIMEOUT, 4096, () -> null);

        try {
            var connect = transport.connect(message -> message).toFuture();
            assertTrue(opened.await(2, TimeUnit.SECONDS));
            var premature = new McpSchema.JSONRPCRequest("2.0", "initialize", 1, Map.of());
            var first = transport.sendMessage(premature).toFuture();
            var cancelled = transport.sendMessage(premature).toFuture();
            assertTrue(cancelled.cancel(true));
            assertThrows(TimeoutException.class, () -> first.get(100, TimeUnit.MILLISECONDS));
            assertEquals(0, writes.get());
            publishEndpoint.countDown();
            connect.get(2, TimeUnit.SECONDS);
            first.get(2, TimeUnit.SECONDS);
            assertTrue(transport.isOpen());
            assertEquals(1, writes.get());
        } finally {
            publishEndpoint.countDown();
            transport.close();
        }
    }

    private static void emit(PinnedHttpsClient.EventBytes consumer, String text) throws IOException {
        for (byte octet : text.getBytes(StandardCharsets.UTF_8)) consumer.accept(new byte[]{octet}, 1);
    }
}
