package com.selfanalyst.ontology;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.async.AsyncSession;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** A synthetic loopback Bolt peer, not a real server, account or user dataset. */
class Neo4jSnapshotWriterTimeoutTest {
    @Test void unfinishedOrFailedCleanupBlocksRetryWithoutCreatingAnotherDriver() {
        var closed = new CompletableFuture<Void>();
        var sessions = proxy(AsyncSession.class, (target, method, args) -> switch (method.getName()) {
            case "runAsync" -> new CompletableFuture<>();
            case "closeAsync" -> CompletableFuture.completedFuture(null);
            default -> throw new AssertionError("Unexpected synthetic session call: " + method.getName());
        });
        var driver = proxy(Driver.class, (target, method, args) -> switch (method.getName()) {
            case "session" -> sessions;
            case "closeAsync" -> closed;
            default -> throw new AssertionError("Unexpected synthetic driver call: " + method.getName());
        });
        var created = new AtomicInteger();
        var writer = new Neo4jSnapshotWriter((config, password) -> { created.incrementAndGet(); return driver; });
        var config = new Neo4jSyncConfig(true, "bolt://127.0.0.1:7687", "neo4j", "synthetic", "ENV", "synthetic", 1);
        var payload = Neo4jSnapshotWriter.prepare(new Ontology.ExportSnapshot(List.of(), List.of(), Map.of(), Map.of()));
        var failure = assertTimeoutPreemptively(Duration.ofSeconds(9), () ->
                assertThrows(IllegalStateException.class, () -> writer.write(config, "synthetic", payload)));
        assertEquals("neo4j.timeout", failure.getMessage());
        assertTrue(writer.pendingCleanup());
        assertEquals("neo4j.busy", assertThrows(IllegalStateException.class,
                () -> writer.write(config, "synthetic", payload)).getMessage());
        assertEquals(1, created.get());
        closed.completeExceptionally(new IllegalStateException("synthetic close failed"));
        assertTrue(writer.pendingCleanup(), "Failed cleanup cannot authorize another driver");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    @Test void stalledAuthenticatedConnectionStopsAtClientDeadlineWithoutSendingCommit() throws Exception {
        for (int stalledRun : List.of(1, 4)) {
            try (var peer = new StalledBoltPeer(stalledRun)) {
                var writer = new Neo4jSnapshotWriter();
                var config = new Neo4jSyncConfig(true, "bolt://127.0.0.1:" + peer.port(),
                        "neo4j", "synthetic", "SYNTHETIC_PASSWORD", "timeout-test", 2);
                var payload = Neo4jSnapshotWriter.prepare(new Ontology.ExportSnapshot(List.of(), List.of(), Map.of(), Map.of()));
                var failure = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                        assertThrows(IllegalStateException.class, () -> writer.write(config, "synthetic-only", payload)));
                assertEquals("neo4j.timeout", failure.getMessage());
                assertTrue(peer.disconnected.await(3, TimeUnit.SECONDS), "Driver must close the established connection");
                assertEquals(stalledRun, peer.runs.get(), "No later query may run after timeout");
                assertEquals(0, peer.commits.get(), "No delayed COMMIT may publish a timed-out transaction");
                assertFalse(writer.pendingCleanup(), "Successful driver cleanup must permit a future manual retry");
                if (peer.failure != null) throw new AssertionError(peer.failure);
            }
        }
    }

    private static final class StalledBoltPeer implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        private final Thread worker;
        private volatile Socket socket;
        private volatile Throwable failure;
        final AtomicInteger runs = new AtomicInteger(), commits = new AtomicInteger();
        final CountDownLatch disconnected = new CountDownLatch(1);

        StalledBoltPeer(int stalledRun) throws IOException {
            worker = Thread.ofVirtual().start(() -> {
                try (Socket connected = server.accept()) {
                    socket = connected;
                    var input = new DataInputStream(connected.getInputStream());
                    var output = new DataOutputStream(connected.getOutputStream());
                    if (input.readNBytes(20).length != 20) throw new EOFException();
                    output.writeInt(0x00000404); output.flush(); // Negotiate supported Bolt 4.4.
                    while (true) {
                        byte[] message = message(input);
                        int signature = Byte.toUnsignedInt(message[1]);
                        if (signature == 0x01) { // HELLO accepts synthetic auth, supplies no receive-timeout hint.
                            reply(output, "b170a2867365727665728b4e656f346a2f342e342e308d636f6e6e656374696f6e5f69648973796e746865746963");
                        } else if (signature == 0x10) { // RUN
                            if (runs.incrementAndGet() < stalledRun) {
                                structure(output, 0x70, Map.of("fields", runs.get() == 3
                                        ? List.of("name", "labelsOrTypes", "properties", "type") : List.of()));
                            }
                        } else if ((signature == 0x3f || signature == 0x2f) && runs.get() < stalledRun) { // PULL / DISCARD
                            if (runs.get() == 3) {
                                structure(output, 0x71, List.of("selfanalyst_entity_identity", List.of("SelfAnalystEntity"), List.of("namespace", "id"), "UNIQUENESS"));
                                structure(output, 0x71, List.of("selfanalyst_sync_identity", List.of("SelfAnalystSync"), List.of("namespace"), "UNIQUENESS"));
                            }
                            reply(output, "b170a0");
                        } else if (signature == 0x11) { // BEGIN
                            reply(output, "b170a0");
                        } else if (signature == 0x0f && runs.get() < stalledRun) { // RESET between autocommit queries
                            reply(output, "b170a0");
                        } else if (signature == 0x12) {
                            commits.incrementAndGet();
                        }
                        // Deliberately do not reply to the stalled RUN, later PULL, or RESET.
                        // Driver GOODBYE does not expect a reply and must still disconnect.
                    }
                } catch (EOFException | SocketException expectedDisconnect) {
                    // Closing the driver must close this local socket even with a request outstanding.
                } catch (Throwable error) { failure = error; }
                finally { disconnected.countDown(); }
            });
        }
        int port() { return server.getLocalPort(); }
        private static byte[] message(DataInputStream input) throws IOException {
            var bytes = new ByteArrayOutputStream();
            int size;
            while ((size = input.readUnsignedShort()) != 0) bytes.write(input.readNBytes(size));
            if (bytes.size() == 0) return message(input);
            return bytes.toByteArray();
        }
        private static void reply(DataOutputStream output, String hex) throws IOException {
            byte[] bytes = HexFormat.of().parseHex(hex);
            output.writeShort(bytes.length); output.write(bytes); output.writeShort(0); output.flush();
        }
        private static void structure(DataOutputStream output, int signature, Object value) throws IOException {
            var bytes = new ByteArrayOutputStream();
            var data = new DataOutputStream(bytes);
            data.writeByte(0xb1); data.writeByte(signature); pack(data, value);
            output.writeShort(bytes.size()); output.write(bytes.toByteArray()); output.writeShort(0); output.flush();
        }
        private static void pack(DataOutputStream data, Object value) throws IOException {
            if (value instanceof String text) {
                byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (bytes.length < 16) data.writeByte(0x80 + bytes.length);
                else { data.writeByte(0xd0); data.writeByte(bytes.length); }
                data.write(bytes);
            } else if (value instanceof List<?> list) {
                data.writeByte(0x90 + list.size()); for (Object item : list) pack(data, item);
            } else if (value instanceof Map<?, ?> map) {
                data.writeByte(0xa0 + map.size());
                for (var entry : map.entrySet()) { pack(data, entry.getKey()); pack(data, entry.getValue()); }
            } else throw new IllegalArgumentException("Unsupported synthetic PackStream value");
        }
        @Override public void close() throws Exception {
            server.close();
            if (socket != null) socket.close();
            worker.join(3000);
        }
    }
}
