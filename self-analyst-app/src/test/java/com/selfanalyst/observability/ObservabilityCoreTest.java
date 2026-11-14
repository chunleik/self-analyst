package com.selfanalyst.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.integrations.*;

import io.opentelemetry.context.Context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class ObservabilityCoreTest {
    @TempDir Path root;
    @org.junit.jupiter.api.BeforeEach void canonicalTemporaryRoot() throws Exception {
        root = root.toRealPath();
    }

    static class Vault implements CredentialStore {
        final Map<String, char[]> values = new HashMap<>();

        public String write(CredentialTarget target, char[] value, String mode) {
            String ref = "windows:" + UUID.randomUUID();
            values.put(ref, value.clone());
            return ref;
        }

        public Optional<char[]> read(String ref, CredentialTarget target) {
            return Optional.ofNullable(values.get(ref)).map(char[]::clone);
        }

        public void delete(String ref) {
            values.remove(ref);
        }

        public void close() {}
    }

    static ObservabilityConfig config(String endpoint, String mode, Set<String> categories) {
        return new ObservabilityConfig(
                1,
                0,
                endpoint,
                "pk-synthetic",
                1,
                mode,
                categories,
                Set.of("mcp-a"),
                Set.of("mail-a"),
                "all-runtime",
                "",
                "");
    }

    static ObservabilityService.Content content(String category, boolean mail) {
        return new ObservabilityService.Content(
                "hello",
                "world",
                category,
                mail ? Set.of() : Set.of("mcp-a"),
                mail ? Set.of("mail-a") : Set.of(),
                mail,
                false,
                false);
    }

    @Test
    void metadataAndRuntimeConsentAreFailClosed() throws Exception {
        var policy = new ObservabilityEndpointPolicy();
        var transport =
                new ObservabilityTransport(policy) {
                    @Override
                    public int send(
                            ObservabilityEndpointPolicy.ApprovedEndpoint e,
                            String a,
                            byte[] b,
                            java.util.function.BooleanSupplier allowed) {
                        return 200;
                    }
                };
        Vault vault = new Vault();
        try (var service = new ObservabilityService(root, vault, true, policy, transport)) {
            service.save(0, config("http://127.0.0.1:12345", "metadata", Set.of()));
            service.credential(1, "synthetic-secret".toCharArray());
            service.enable(2, 1, (String) service.preview(2).get("confirmationToken"));
            var span = service.begin("model.generation", "session", Context.root());
            assertFalse(span.allowContent(content("modelText", false)));
            span.finish("success", 10, 2, "actual", content("modelText", false));
            service.disable(2);
            assertFalse((Boolean) service.snapshot().get("enabled"));
            assertFalse(span.allowContent(content("modelText", false)));
            assertThrows(
                    IOException.class,
                    () -> service.save(0, config("http://127.0.0.1:12345", "metadata", Set.of())));
            assertFalse(
                    Files.readString(root.resolve("config/observability.json"))
                            .contains("synthetic-secret"));
        }
        try (var service =
                new ObservabilityService(
                        root, vault, true, new ObservabilityEndpointPolicy(), null)) {
            assertFalse((Boolean) service.snapshot().get("enabled"));
        }
    }

    @Test
    void catalogAndHeaderScopesDoNotRequireUnrelatedCategories() throws Exception {
        try (var service =
                new ObservabilityService(
                        root, new Vault(), true, new ObservabilityEndpointPolicy(), null)) {
            service.save(
                    0,
                    config(
                            "http://127.0.0.1:12345",
                            "content",
                            Set.of("toolCatalog", "mailHeaders")));
            service.credential(1, "synthetic-secret".toCharArray());
            service.enable(2, 1, (String) service.preview(2).get("confirmationToken"));
            var span = service.begin("mcp.discover", "session", Context.root());
            assertTrue(span.allowContent(content("toolCatalog", false)));
            assertTrue(span.allowContent(content("mailHeaders", true)));
            assertFalse(span.allowContent(content("mailBody", true)));
            assertFalse(span.allowContent(content("modelText", true)));
            span.finish("success", -1, -1, null, null);
        }
    }

    @Test
    void previewIsBoundToRevisionAndRevocation() throws Exception {
        try (var service =
                new ObservabilityService(
                        root, new Vault(), true, new ObservabilityEndpointPolicy(), null)) {
            service.save(0, config("http://127.0.0.1:12345", "metadata", Set.of()));
            service.credential(1, "synthetic-secret".toCharArray());
            var preview = service.preview(2);
            assertEquals("loopback", ((Map<?, ?>) preview.get("destination")).get("category"));
            assertEquals(
                    "system-trust", ((Map<?, ?>) preview.get("destination")).get("caFingerprint"));
            service.disable(2);
            assertThrows(
                    IOException.class,
                    () -> service.enable(2, 1, (String) preview.get("confirmationToken")));
            assertThrows(IOException.class, () -> service.enable(2, 1, "not-a-token"));
        }
    }

    @Test
    void malformedConfigurationPreserved() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Path file = root.resolve("config/observability.json");
        Files.writeString(file, "not-json-secret");
        try (var service = new ObservabilityService(root)) {
            assertEquals(
                    "unavailable", ((Map<?, ?>) service.snapshot().get("status")).get("state"));
            assertThrows(IOException.class, () -> service.save(0, ObservabilityConfig.defaults()));
        }
        assertEquals("not-json-secret", Files.readString(file));
    }

    @Test
    void endpointRejectsMetadataAndPublicToPrivateRebinding() throws Exception {
        for (String special :
                List.of(
                        "2002:c000:0201::",
                        "2001:0:4136:e378:8000:63bf:3fff:fdd2",
                        "2001:db8::1",
                        "192.88.99.1")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ObservabilityEndpointPolicy.category(InetAddress.getByName(special)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> ObservabilityEndpointPolicy.category(InetAddress.getByName("168.63.129.16")));
        AtomicReference<String> address = new AtomicReference<>("8.8.8.8");
        var policy =
                new ObservabilityEndpointPolicy(
                        host -> new InetAddress[] {InetAddress.getByName(address.get())});
        try {
            var approved =
                    policy.approve(config("https://telemetry.example", "metadata", Set.of()));
            address.set("10.0.0.2");
            assertThrows(IOException.class, () -> policy.verify(approved));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ObservabilityEndpointPolicy.normalize("http://10.0.0.2"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ObservabilityEndpointPolicy.normalize("https://user:secret@example.com"));
        } finally {
            policy.close();
        }
    }

    @Test
    void cancellationDuringDnsNeverConnects() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var policy =
                new ObservabilityEndpointPolicy(
                        host -> {
                            if (calls.incrementAndGet() > 1) {
                                resolving.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IOException("cancelled");
                                }
                            }
                            return new InetAddress[] {InetAddress.getLoopbackAddress()};
                        });
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var transport = new ObservabilityTransport(policy)) {
            server.setSoTimeout(250);
            var approved =
                    policy.approve(
                            config(
                                    "http://127.0.0.1:" + server.getLocalPort(),
                                    "metadata",
                                    Set.of()));
            var task =
                    CompletableFuture.runAsync(
                            () ->
                                    assertThrows(
                                            IOException.class,
                                            () ->
                                                    transport.send(
                                                            approved,
                                                            "Basic synthetic",
                                                            "{}".getBytes())));
            assertTrue(resolving.await(2, TimeUnit.SECONDS));
            transport.cancel();
            release.countDown();
            task.get(3, TimeUnit.SECONDS);
            assertThrows(SocketTimeoutException.class, server::accept);
        } finally {
            release.countDown();
            policy.close();
        }
    }

    @Test
    void cancellationClosesSocketEvenWhileWriteIsBlocked() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), closed = new CountDownLatch(1);
        Socket blocked =
                new Socket() {
                    @Override
                    public OutputStream getOutputStream() {
                        return new OutputStream() {
                            @Override
                            public void write(int ignored) throws IOException {
                                writing.countDown();
                                try {
                                    closed.await();
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                                throw new IOException("closed");
                            }
                        };
                    }

                    @Override
                    public void close() {
                        closed.countDown();
                    }
                };
        Class<?> type =
                Class.forName("com.selfanalyst.observability.ObservabilityTransport$Exchange");
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object exchange = constructor.newInstance();
        var attach = type.getDeclaredMethod("attach", Socket.class);
        attach.setAccessible(true);
        attach.invoke(exchange, blocked);
        var write = type.getDeclaredMethod("write", byte[].class, byte[].class);
        write.setAccessible(true);
        var cancel = type.getDeclaredMethod("cancel");
        cancel.setAccessible(true);
        var task =
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                write.invoke(exchange, new byte[] {1}, new byte[] {2});
                            } catch (ReflectiveOperationException expected) {
                            }
                        });
        assertTrue(writing.await(1, TimeUnit.SECONDS));
        try {
            assertTimeoutPreemptively(
                    java.time.Duration.ofMillis(500), () -> cancel.invoke(exchange));
        } finally {
            closed.countDown();
        }
        task.get(1, TimeUnit.SECONDS);
    }

    @Test
    void partialSuccessIsNotCountedAsSuccess() throws Exception {
        var policy = new ObservabilityEndpointPolicy();
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var transport = new ObservabilityTransport(policy)) {
            var response =
                    CompletableFuture.runAsync(
                            () -> {
                                try (var socket = server.accept()) {
                                    var in =
                                            new BufferedReader(
                                                    new InputStreamReader(socket.getInputStream()));
                                    String line;
                                    while (!(line = in.readLine()).isEmpty()) {}
                                    byte[] body =
                                            "{\"partialSuccess\":{\"rejectedSpans\":\"1\",\"errorMessage\":\"secret receiver detail\"}}"
                                                    .getBytes();
                                    socket.getOutputStream()
                                            .write(
                                                    ("HTTP/1.1 200 OK\r\n"
                                                         + "Content-Type: application/json\r\n"
                                                         + "Content-Length: "
                                                                    + body.length
                                                                    + "\r\n\r\n")
                                                            .getBytes());
                                    socket.getOutputStream().write(body);
                                } catch (IOException failure) {
                                    throw new RuntimeException(failure);
                                }
                            });
            var approved =
                    policy.approve(
                            config(
                                    "http://127.0.0.1:" + server.getLocalPort(),
                                    "metadata",
                                    Set.of()));
            assertEquals(
                    "receiver_partial_rejection",
                    assertThrows(
                                    IOException.class,
                                    () ->
                                            transport.send(
                                                    approved, "Basic synthetic", "{}".getBytes()))
                            .getMessage());
            response.get(3, TimeUnit.SECONDS);
        } finally {
            policy.close();
        }
    }

    @Test
    void retriesOnlyExplicitOtlpRetryableStatuses() throws Exception {
        AtomicInteger status = new AtomicInteger(500), calls = new AtomicInteger();
        var policy = new ObservabilityEndpointPolicy();
        var transport =
                new ObservabilityTransport(policy) {
                    @Override
                    public int send(
                            ObservabilityEndpointPolicy.ApprovedEndpoint endpoint,
                            String auth,
                            byte[] body,
                            java.util.function.BooleanSupplier authorized) {
                        calls.incrementAndGet();
                        return status.get();
                    }
                };
        try (var service = new ObservabilityService(root, new Vault(), true, policy, transport)) {
            service.save(0, config("http://127.0.0.1:12345", "metadata", Set.of()));
            service.credential(1, "synthetic-secret".toCharArray());
            service.enable(2, 1, (String) service.preview(2).get("confirmationToken"));
            var worker = ObservabilityService.class.getDeclaredField("worker");
            worker.setAccessible(true);
            ((ScheduledExecutorService) worker.get(service)).shutdownNow();
            for (int code : new int[] {200, 204, 207, 400, 401, 500, 501, 429, 502, 503, 504}) {
                status.set(code);
                calls.set(0);
                service.begin("model.generation", "s", Context.root())
                        .finish("success", 1, 1, "actual", null);
                service.drain();
                assertEquals(
                        Set.of(429, 502, 503, 504).contains(code) ? 3 : 1,
                        calls.get(),
                        "status=" + code);
            }
            assertEquals(1L, ((Map<?, ?>) service.snapshot().get("status")).get("accepted"));
        }
    }

    @Test
    void successRequiresHttp200AndJsonObjectResponse() throws Exception {
        assertEquals(200, response(200, "application/json", "{}"));
        assertEquals(200, response(200, "application/json; charset=utf-8", "{}"));
        assertEquals(200, response(200, "application/json", "{\"futureField\":true}"));
        assertEquals(
                200,
                response(
                        200,
                        "application/json",
                        "{\"partialSuccess\":{\"rejectedSpans\":\"0\",\"errorMessage\":\"warning"
                                + " must not be stored\"}}"));
        for (int code : new int[] {204, 207})
            assertEquals(
                    "invalid_response",
                    assertThrows(IOException.class, () -> response(code, "application/json", "{}"))
                            .getMessage());
        for (String body : new String[] {"", " ", "[]", "not json"})
            assertThrows(IOException.class, () -> response(200, "application/json", body));
        assertThrows(IOException.class, () -> response(200, null, "{}"));
        assertThrows(IOException.class, () -> response(200, "text/html", "{}"));
    }

    private static int response(int status, String type, String payload) throws Exception {
        var policy = new ObservabilityEndpointPolicy();
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var transport = new ObservabilityTransport(policy)) {
            var responder =
                    CompletableFuture.runAsync(
                            () -> {
                                try (var socket = server.accept()) {
                                    var input =
                                            new BufferedReader(
                                                    new InputStreamReader(socket.getInputStream()));
                                    while (!input.readLine().isEmpty()) {}
                                    byte[] body =
                                            payload.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8);
                                    String headers =
                                            "HTTP/1.1 "
                                                    + status
                                                    + " Test\r\n"
                                                    + (type == null
                                                            ? ""
                                                            : "Content-Type: " + type + "\r\n")
                                                    + "Content-Length: "
                                                    + body.length
                                                    + "\r\n\r\n";
                                    socket.getOutputStream()
                                            .write(
                                                    (headers + payload)
                                                            .getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .UTF_8));
                                } catch (IOException failure) {
                                    throw new RuntimeException(failure);
                                }
                            });
            var endpoint =
                    policy.approve(
                            config(
                                    "http://127.0.0.1:" + server.getLocalPort(),
                                    "metadata",
                                    Set.of()));
            try {
                return transport.send(endpoint, "Basic synthetic", "{}".getBytes());
            } finally {
                responder.get(3, TimeUnit.SECONDS);
            }
        } finally {
            policy.close();
        }
    }

    @Test
    void authenticationHeadersAndJsonCredentialsAreRedacted() throws Exception {
        var method =
                ObservabilityService.class.getDeclaredMethod(
                        "safeText", String.class, String.class);
        method.setAccessible(true);
        String input =
                "Authorization: Bearer sentinelBearer\n"
                    + "Cookie: sid=sentinelSid; token=sentinelCookie\n"
                    + "{\"api_key\":\"sentinelKey\",\"nested\":{\"password\":\"sentinelPassword\"}}\n"
                    + "Basic sentinelBasic\n"
                    + "credential=sentinelRuntime";
        String cleaned = (String) method.invoke(null, input, "sentinelRuntime");
        for (String sentinel :
                new String[] {
                    "sentinelBearer",
                    "sentinelSid",
                    "sentinelCookie",
                    "sentinelKey",
                    "sentinelPassword",
                    "sentinelBasic",
                    "sentinelRuntime"
                }) assertFalse(cleaned.contains(sentinel), sentinel);
    }

    @Test
    void syntheticOtlpHasValidIdsAndStringTimestamps() throws Exception {
        var json = new ObjectMapper().readTree(ObservabilityOtlp.synthetic());
        var span = json.path("resourceSpans").get(0).path("scopeSpans").get(0).path("spans").get(0);
        assertTrue(span.path("traceId").asText().matches("[0-9a-f]{32}"));
        assertTrue(span.path("spanId").asText().matches("[0-9a-f]{16}"));
        assertTrue(span.path("startTimeUnixNano").isTextual());
        assertEquals(1, span.path("kind").asInt());
    }
}
