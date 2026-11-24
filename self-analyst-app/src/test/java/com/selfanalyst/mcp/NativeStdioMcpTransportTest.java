package com.selfanalyst.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class NativeStdioMcpTransportTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    @TempDir Path temp;

    @Test
    void validatesWithoutStartingAndRejectsImplicitLookupAndShell() {
        assertEquals(javaCommand("normal").getFirst(), NativeStdioMcpTransport.validateCommand(javaCommand("normal")).getFirst());
        assertThrows(IllegalStateException.class, () -> NativeStdioMcpTransport.validateCommand(List.of("java")));
        assertThrows(IllegalStateException.class, () -> NativeStdioMcpTransport.validateCommand(List.of(temp.resolve("missing").toString())));
        assertThrows(IllegalStateException.class, () ->
                NativeStdioMcpTransport.validateCommand(List.of(javaCommand("normal").getFirst(), "new\nline")));
        String shell = System.getProperty("os.name").startsWith("Windows")
                ? System.getenv("SystemRoot") + "\\System32\\cmd.exe" : "/bin/sh";
        assertThrows(IllegalStateException.class, () -> NativeStdioMcpTransport.validateCommand(List.of(shell)));
        assertThrows(IllegalStateException.class, () ->
                new NativeStdioMcpTransport(javaCommand("normal"), Map.of("a=b", "value"), TIMEOUT, 4096));
    }

    @Test
    void actualSdkInitializesAndUsesOnlyExplicitEnvironment() {
        var transport = new NativeStdioMcpTransport(javaCommand("normal"), Map.of("MCP_TEST_MODE", "allowed"), TIMEOUT, 4096);
        try (var client = McpClient.sync(transport).requestTimeout(TIMEOUT).initializationTimeout(TIMEOUT).build()) {
            client.initialize();
            assertEquals("check", client.listTools().tools().getFirst().name());
            var response = transport.withBusinessGuard(() -> true,
                    () -> client.callTool(new McpSchema.CallToolRequest("check", Map.of())));
            assertFalse(response.isError());
            assertEquals("allowed", ((McpSchema.TextContent) response.content().getFirst()).text());
        }
    }

    @Test
    void unguardedRevokedAndExpiredBusinessFramesNeverReachStdin() throws Exception {
        Path frames = temp.resolve("frames");
        var transport = new NativeStdioMcpTransport(javaCommand("capture", frames.toString()), Map.of(), TIMEOUT, 4096);
        try {
            transport.connect(message -> message).block(TIMEOUT);
            assertDenied(() -> transport.sendMessage(call("unguarded")).block(TIMEOUT));

            var authorized = new AtomicBoolean(true);
            transport.withBusinessGuard(authorized::get, () -> {
                var queued = transport.sendMessage(call("revoked"));
                authorized.set(false);
                assertDenied(() -> queued.block(TIMEOUT));
                return null;
            });

            var expired = transport.withBusinessGuard(() -> true, () -> transport.sendMessage(call("expired")));
            transport.withBusinessGuard(() -> true, () -> {
                assertDenied(() -> expired.block(TIMEOUT));
                transport.sendMessage(call("allowed-marker")).block(TIMEOUT);
                return null;
            });

            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            String observed = "";
            while (!observed.contains("allowed-marker") && System.nanoTime() < deadline) {
                if (Files.isRegularFile(frames)) observed = Files.readString(frames);
                Thread.sleep(10);
            }
            assertTrue(observed.contains("allowed-marker"));
            assertEquals(1, observed.lines().count(), observed);
        } finally {
            transport.closeGracefully().block(TIMEOUT);
        }
    }

    private static void assertDenied(Runnable action) {
        assertEquals("mcp_stdio_not_authorized", assertThrows(IllegalStateException.class, action::run).getMessage());
    }

    private static McpSchema.JSONRPCRequest call(String id) {
        return new McpSchema.JSONRPCRequest("2.0", "tools/call", id, Map.of("name", "test"));
    }

    @Test
    void rejectsExecutableChangeBetweenConstructionAndLaunch() throws Exception {
        var command = new java.util.ArrayList<>(javaCommand("normal"));
        Path copy = temp.resolve("copied-java.exe");
        Files.copy(Path.of(command.getFirst()), copy);
        assertTrue(copy.toFile().setExecutable(true) || Files.isExecutable(copy));
        command.set(0, copy.toString());
        var transport = new NativeStdioMcpTransport(command, Map.of(), TIMEOUT, 4096);
        Files.setLastModifiedTime(copy, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L));
        try {
            assertEquals("mcp_stdio_connect_failed",
                    assertThrows(IllegalStateException.class, () -> transport.connect(message -> message).block(TIMEOUT))
                            .getMessage());
        } finally {
            transport.closeGracefully().block(TIMEOUT);
        }
    }

    @Test
    void rechecksLaunchAuthorizationAfterConstruction() {
        Path pidFile = temp.resolve("child.pid");
        var authorized = new AtomicBoolean(true);
        var transport = new NativeStdioMcpTransport(javaCommand("silent", pidFile.toString()), Map.of(), TIMEOUT,
                4096, authorized::get);
        authorized.set(false);
        try {
            assertThrows(IllegalStateException.class, () -> transport.connect(message -> message).block(TIMEOUT));
            assertFalse(Files.exists(pidFile));
        } finally {
            transport.closeGracefully().block(TIMEOUT);
        }
    }

    @Test
    void sdkInitializationTimeoutDoesNotLeaveChildAfterClose() throws Exception {
        Path pidFile = temp.resolve("child.pid");
        Duration deadline = Duration.ofSeconds(2);
        var transport = new NativeStdioMcpTransport(javaCommand("silent", pidFile.toString()), Map.of(), deadline, 4096);
        try (var client = McpClient.sync(transport).requestTimeout(deadline).initializationTimeout(deadline).build()) {
            assertThrows(RuntimeException.class, client::initialize);
        } finally {
            transport.closeGracefully().block(TIMEOUT);
        }
        awaitExit(readPid(pidFile));
    }

    @Test
    void hardClosesOnOversizedNewlineFrame() throws Exception {
        Path pidFile = temp.resolve("child.pid");
        var transport = new NativeStdioMcpTransport(javaCommand("flood", pidFile.toString()), Map.of(), TIMEOUT, 128);
        try {
            transport.connect(ignored -> Mono.error(new AssertionError("Oversized frame was delivered"))).block(TIMEOUT);
            long pid = readPid(pidFile);
            awaitExit(pid);
            var send = transport.sendMessage(new McpSchema.JSONRPCNotification("2.0", "notifications/initialized", null));
            assertEquals("mcp_stdio_send_failed", assertThrows(IllegalStateException.class, () -> send.block(TIMEOUT)).getMessage());
        } finally {
            transport.closeGracefully().block(TIMEOUT);
        }
    }

    @Test
    void explicitCloseKillsDirectChildAndObservedDescendant() throws Exception {
        Path pidFile = temp.resolve("child.pid");
        var transport = new NativeStdioMcpTransport(javaCommand("tree", pidFile.toString()), Map.of(), TIMEOUT, 4096);
        long descendant;
        try {
            transport.connect(message -> message).block(TIMEOUT);
            descendant = readPid(pidFile);
            assertTrue(ProcessHandle.of(descendant).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            transport.closeGracefully().block(TIMEOUT);
        }
        awaitExit(descendant);
    }

    private static long readPid(Path path) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path)) {
                String pid = Files.readString(path).strip();
                if (pid.matches("[0-9]+")) return Long.parseLong(pid);
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Fixture did not write a PID");
    }

    private static void awaitExit(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            Thread.sleep(10);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private static List<String> javaCommand(String... args) {
        return Fixture.command(args);
    }

    public static class Fixture {
        private static List<String> command(String... args) {
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            try {
                String classes = Path.of(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                var command = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable)
                        .toString(), "-cp", classes, Fixture.class.getName()));
                command.addAll(List.of(args));
                return List.copyOf(command);
            } catch (java.net.URISyntaxException e) {
                throw new AssertionError(e);
            }
        }

        public static void main(String[] args) throws Exception {
            if (args[0].equals("capture")) {
                try (var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    for (String line; (line = in.readLine()) != null;) {
                        Files.writeString(Path.of(args[1]), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    }
                }
                return;
            }
            if (args[0].equals("sleep")) {
                Thread.sleep(Duration.ofMinutes(1).toMillis());
                return;
            }
            if (args[0].equals("tree")) {
                var child = new ProcessBuilder(command("sleep")).start();
                Files.writeString(Path.of(args[1]), Long.toString(child.pid()));
                child.waitFor();
                return;
            }
            if (args[0].equals("flood") || args[0].equals("silent")) {
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                if (args[0].equals("flood")) System.out.println("x".repeat(2048));
                Thread.sleep(Duration.ofMinutes(1).toMillis());
                return;
            }
            // No third-party dependencies in the child, so this also works with a sanitized environment.
            var idPattern = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|[0-9]+)");
            try (var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null;) {
                    var id = idPattern.matcher(line);
                    if (!id.find()) continue;
                    String result;
                    if (line.contains("\"initialize\"")) {
                        result = """
                                {"protocolVersion":"2025-03-26","capabilities":{"tools":{}},
                                "serverInfo":{"name":"native-test","version":"1"}}
                                """;
                    } else if (line.contains("\"tools/list\"")) {
                        result = """
                                {"tools":[{"name":"check","inputSchema":{"type":"object","properties":{}}}]}
                                """;
                    } else {
                        boolean allowed = "allowed".equals(System.getenv("MCP_TEST_MODE"));
                        boolean isolated = System.getenv().keySet().stream().allMatch(
                                key -> key.equals("MCP_TEST_MODE") || key.equalsIgnoreCase("SystemRoot"));
                        result = "{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\""
                                + (allowed && isolated ? "allowed" : "unexpected") + "\"}]}";
                    }
                    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1)
                            + ",\"result\":" + result.replace("\n", "") + "}");
                }
            }
        }
    }
}
