package com.selfanalyst.integrations;

import org.junit.jupiter.api.Test;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PinnedSseHttpsClientTest {
    private static final URI TARGET = URI.create("https://mcp.example.com:8443/legacy");
    private static final Duration CONNECT = Duration.ofSeconds(3), LIFETIME = Duration.ofSeconds(4);

    @Test void validatesDerivedPostAuthorityAndAllowsOnlyOpaqueSessionQuery() throws Exception {
        for (String invalid : List.of("http://mcp.example.com:8443/msg", "https://mcp.example.com/msg",
                "https://other.example.com:8443/msg", "//mcp.example.com:8443/msg", "/msg?token=opaque",
                "/msg?sessionId=x&session_id=y", "/msg?sessionId=x%0d", "/msg?", "/msg#frag", "/a/../msg",
                "/%2e%2e/msg", "/a%2fmsg", "/msg?sessionId=x\r\n", "https://me@mcp.example.com:8443/msg")) {
            assertThrows(IOException.class, () -> PinnedHttpsClient.ssePostTarget(TARGET, invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () ->
                PublicEndpointPolicy.validate("https://mcp.example.com:8443/msg?sessionId=synthetic"));
        var resolved = new AtomicInteger();
        FakeSocket socket = new FakeSocket("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n\r\n");
        var client = new PinnedHttpsClient(new PublicEndpointPolicy(host -> {
            assertEquals("mcp.example.com", host);
            resolved.incrementAndGet();
            return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        }), (pinned, timeout, connected) -> {
            assertEquals(URI.create("https://mcp.example.com:8443/bridge/messages"), pinned.uri());
            assertEquals("8.8.8.8", pinned.addresses().getFirst().getHostAddress());
            return socket;
        });
        var derived = PinnedHttpsClient.ssePostTarget(TARGET, "https://mcp.example.com:8443/bridge/messages?sessionId=syn-123");
        assertEquals(202, client.postSse(derived, Map.of("Authorization", "Bearer synthetic"), new byte[0],
                CONNECT, 1024, () -> true, () -> true).status());
        assertEquals(1, resolved.get());
        assertTrue(socket.output.toString(StandardCharsets.US_ASCII).startsWith("POST /bridge/messages?sessionId=syn-123 HTTP/1.1\r\n"));
        assertTrue(socket.output.toString(StandardCharsets.US_ASCII).contains("Host: mcp.example.com:8443\r\n"));
    }

    @Test void streamsAChunkBeforeEofAndClosesThePinnedSocketOnCancellation() throws Exception {
        try (var server = new PipedOutputStream();
             var input = new PipedInputStream(server);
             var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var endpoint = new PublicEndpointPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
            FakeSocket socket = new FakeSocket(input);
            var client = new PinnedHttpsClient(endpoint, (pinned, timeout, connected) -> {
                assertEquals(TARGET, pinned.uri());
                connected.accept(socket);
                return socket;
            });
            var bytes = new ByteArrayOutputStream();
            var event = new CountDownLatch(1);
            try (var stream = new PinnedHttpsClient.EventStream()) {
                var future = worker.submit(() -> {
                    try {
                        client.readEventStream(TARGET, Map.of("Accept", "text/event-stream"), CONNECT, LIFETIME,
                                4096, stream, (data, length) -> {
                                    bytes.write(data, 0, length);
                                    if (bytes.toString(StandardCharsets.UTF_8).endsWith("\n\n")) event.countDown();
                                });
                    } catch (IOException closed) {
                        if (!socket.closed) throw closed;
                    }
                    return null;
                });
                String payload = "event: endpoint\ndata: /messages\n\n";
                String wire = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n" + Integer.toHexString(payload.length()) + "\r\n" + payload + "\r\n";
                server.write(wire.getBytes(StandardCharsets.US_ASCII));
                server.flush();
                assertTrue(event.await(2, TimeUnit.SECONDS));
                assertEquals(payload, bytes.toString(StandardCharsets.UTF_8));
                assertFalse(future.isDone());
                stream.close();
                assertTrue(socket.closed);
                // Release synthetic peer after checking immediate local close.
                server.close();
                future.get(2, TimeUnit.SECONDS);
                assertTrue(socket.output.toString(StandardCharsets.US_ASCII).startsWith("GET /legacy HTTP/1.1\r\n"));
            }
        }
    }

    @Test void postRechecksPublicDnsAndRevocationBeforeSendingAnyBytes() throws Exception {
        var resolves = new AtomicInteger();
        var allow = new AtomicBoolean(true);
        var socket = new FakeSocket("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        var client = new PinnedHttpsClient(new PublicEndpointPolicy(host -> new InetAddress[]{
                InetAddress.getByName(resolves.getAndIncrement() == 0 ? "8.8.8.8" : "127.0.0.1")
        }), (pinned, timeout, connected) -> {
            allow.set(false);
            return socket;
        });
        var post = PinnedHttpsClient.ssePostTarget(TARGET, "/messages?session_id=synthetic");
        assertEquals("endpoint_cancelled", assertThrows(IOException.class, () -> client.postSse(post,
                Map.of("Authorization", "Bearer synthetic"), new byte[0], CONNECT, 4096, allow::get, () -> true)).getMessage());
        assertEquals(0, socket.output.size());
        allow.set(true);
        assertEquals("non_public_endpoint", assertThrows(IOException.class, () -> client.postSse(post,
                Map.of("Authorization", "Bearer synthetic"), new byte[0], CONNECT, 4096, allow::get, () -> true)).getMessage());
        assertEquals(0, socket.output.size());
        assertEquals(2, resolves.get());
    }

    private static final class FakeSocket extends SSLSocket {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final InputStream input;
        volatile boolean closed;
        FakeSocket(String response) { this(new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII))); }
        FakeSocket(InputStream input) { this.input = input; }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public synchronized void setSoTimeout(int timeout) { }
        @Override public synchronized void close() throws IOException { closed = true; input.close(); }
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public String[] getEnabledCipherSuites() { return new String[0]; }
        @Override public void setEnabledCipherSuites(String[] value) { }
        @Override public String[] getSupportedProtocols() { return new String[0]; }
        @Override public String[] getEnabledProtocols() { return new String[0]; }
        @Override public void setEnabledProtocols(String[] value) { }
        @Override public SSLSession getSession() { return null; }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override public void startHandshake() { }
        @Override public void setUseClientMode(boolean mode) { }
        @Override public boolean getUseClientMode() { return true; }
        @Override public void setNeedClientAuth(boolean need) { }
        @Override public boolean getNeedClientAuth() { return false; }
        @Override public void setWantClientAuth(boolean want) { }
        @Override public boolean getWantClientAuth() { return false; }
        @Override public void setEnableSessionCreation(boolean enable) { }
        @Override public boolean getEnableSessionCreation() { return false; }
    }
}
