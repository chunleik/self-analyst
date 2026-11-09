package com.selfanalyst.integrations;

import org.junit.jupiter.api.Test;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.HandshakeCompletedListener;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PinnedHttpsClientTest {
    static final URI TARGET = URI.create("https://mcp.example.com/rpc");
    private PublicEndpointPolicy policy() { return new PublicEndpointPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")}); }
    @Test void directPinnedRequestPreservesOriginalHostAndBoundsChunkedBody() throws Exception {
        FakeSocket socket = new FakeSocket("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n");
        var client = new PinnedHttpsClient(policy(), (pinned, timeout, connected) -> {
            assertEquals(TARGET, pinned.uri()); assertEquals("8.8.8.8", pinned.addresses().getFirst().getHostAddress()); return socket;
        });
        var response = client.exchange(TARGET, "POST", Map.of("Authorization", "Bearer synthetic"), new byte[0], Duration.ofSeconds(1), 4096);
        assertEquals(200, response.status()); assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), response.body());
        String sent = socket.output.toString(StandardCharsets.US_ASCII);
        assertTrue(sent.contains("Host: mcp.example.com\r\n")); assertTrue(sent.contains("Connection: close\r\n")); assertTrue(socket.closed);
    }
    @Test void redirectNeverForwardsBearerOrUsesSecondConnection() {
        AtomicInteger connections = new AtomicInteger();
        var client = new PinnedHttpsClient(policy(), (pinned, timeout, connected) -> {
            connections.incrementAndGet(); return new FakeSocket("HTTP/1.1 302 Found\r\nLocation: http://169.254.169.254/\r\nContent-Length: 0\r\n\r\n");
        });
        IOException error = assertThrows(IOException.class, () -> client.exchange(TARGET, "POST", Map.of("Authorization", "Bearer synthetic"), new byte[0], 4096));
        assertEquals("redirect_rejected", error.getMessage()); assertEquals(1, connections.get());
    }
    @Test void dnsRebindingBlocksSecondConnectionBeforeCredentialTransmission() throws Exception {
        AtomicInteger resolutions = new AtomicInteger(), connections = new AtomicInteger();
        var changed = new PublicEndpointPolicy(host -> new InetAddress[]{InetAddress.getByName(resolutions.getAndIncrement() == 0 ? "8.8.8.8" : "127.0.0.1")});
        var client = new PinnedHttpsClient(changed, (pinned, timeout, connected) -> { connections.incrementAndGet(); return new FakeSocket("HTTP/1.1 204 No Content\r\n\r\n"); });
        client.exchange(TARGET, "POST", Map.of("Authorization", "Bearer synthetic"), new byte[0], 4096);
        assertThrows(IOException.class, () -> client.exchange(TARGET, "POST", Map.of("Authorization", "Bearer synthetic"), new byte[0], 4096));
        assertEquals(1, connections.get());
    }
    @Test void rejectsOversizedCompressedAmbiguousAndMalformedResponses() {
        for (String response : java.util.List.of("HTTP/1.1 200 OK\r\nContent-Length: 99999\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 200 OK\n\n")) {
            var client = new PinnedHttpsClient(policy(), (pinned, timeout, connected) -> new FakeSocket(response));
            assertThrows(IOException.class, () -> client.exchange(TARGET, "POST", Map.of(), new byte[0], 16));
        }
    }
    @Test void slowDripHasAbsoluteDeadlineAndClosesSocket() {
        FakeSocket socket = new FakeSocket("") {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    final byte[] data = "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc".getBytes(StandardCharsets.US_ASCII); int position;
                    @Override public int read() throws IOException {
                        try { Thread.sleep(20); } catch (InterruptedException ignored) { throw new IOException("interrupted"); }
                        return position < data.length ? data[position++] : -1;
                    }
                };
            }
        };
        var client = new PinnedHttpsClient(policy(), (pinned, timeout, connected) -> socket);
        long start = System.nanoTime();
        assertThrows(IOException.class, () -> client.exchange(TARGET, "POST", Map.of(), new byte[0], Duration.ofMillis(70), 1024));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000);
        assertTrue(socket.closed);
    }
    @Test void requestHeaderInjectionFailsBeforeNetwork() {
        AtomicInteger connections = new AtomicInteger();
        var client = new PinnedHttpsClient(policy(), (pinned, timeout, connected) -> { connections.incrementAndGet(); return new FakeSocket(""); });
        for (Map<String, String> headers : java.util.List.of(Map.of("Authorization", "Bearer bad\r\nHost: evil"), Map.of("Host", "evil")))
            assertThrows(IOException.class, () -> client.exchange(TARGET, "POST", headers, new byte[0], 1024));
        assertEquals(0, connections.get());
    }
    private static class FakeSocket extends SSLSocket {
        final ByteArrayOutputStream output = new ByteArrayOutputStream(); final InputStream input; volatile boolean closed;
        FakeSocket(String response) { input = new ByteArrayInputStream(response.getBytes(StandardCharsets.ISO_8859_1)); }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public synchronized void setSoTimeout(int timeout) { }
        @Override public synchronized void close() { closed = true; }
        public String[] getSupportedCipherSuites() { return new String[0]; }
        public String[] getEnabledCipherSuites() { return new String[0]; }
        public void setEnabledCipherSuites(String[] value) { }
        public String[] getSupportedProtocols() { return new String[0]; }
        public String[] getEnabledProtocols() { return new String[0]; }
        public void setEnabledProtocols(String[] value) { }
        public SSLSession getSession() { return null; }
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        public void startHandshake() { }
        public void setUseClientMode(boolean mode) { }
        public boolean getUseClientMode() { return true; }
        public void setNeedClientAuth(boolean need) { }
        public boolean getNeedClientAuth() { return false; }
        public void setWantClientAuth(boolean want) { }
        public boolean getWantClientAuth() { return false; }
        public void setEnableSessionCreation(boolean enable) { }
        public boolean getEnableSessionCreation() { return false; }
    }
}
