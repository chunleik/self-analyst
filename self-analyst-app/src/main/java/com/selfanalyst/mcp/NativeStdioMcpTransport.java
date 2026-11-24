package com.selfanalyst.mcp;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Newline-delimited MCP over a locally approved native executable. This is not an OS sandbox. */
public final class NativeStdioMcpTransport implements McpClientTransport {
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    private static final Set<String> SHELLS = Set.of("sh", "bash", "zsh", "dash", "ash", "ksh", "csh",
            "tcsh", "fish", "cmd", "powershell", "pwsh", "busybox", "wsl", "mshta", "wscript", "cscript",
            "env", "start", "rundll32");
    private static final int MAX_REQUEST_BYTES = 65_536;
    private static final int MAX_FRAME_LIMIT = 4 * 1_048_576;

    private final JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(1_048_576).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
    private final List<String> argv;
    private final Executable executable;
    private final Map<String, String> environment;
    private final Duration timeout;
    private final int maxBytes;
    private final BooleanSupplier launchStillAuthorized;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ReentrantLock businessLock = new ReentrantLock();
    private final ThreadLocal<BusinessLease> businessLease = new ThreadLocal<>();
    private final Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();
    private volatile Process process;
    private volatile Thread reader;
    private volatile Thread reaper;

    private static final class BusinessDenied extends IllegalStateException {
        BusinessDenied() { super("mcp_stdio_not_authorized"); }
    }

    private record BusinessLease(BooleanSupplier stillAuthorized, AtomicBoolean active) {
        BusinessLease(BooleanSupplier stillAuthorized) { this(stillAuthorized, new AtomicBoolean(true)); }

        void check() {
            try {
                if (active.get() && stillAuthorized.getAsBoolean() && active.get()) return;
            } catch (RuntimeException ignored) {
                // Never expose a guard failure's internal details to the SDK.
            }
            throw new BusinessDenied();
        }
    }

    public NativeStdioMcpTransport(List<String> argv, Map<String, String> environment,
                                   Duration timeout, int maxBytes) {
        this(argv, environment, timeout, maxBytes, () -> true);
    }

    public NativeStdioMcpTransport(List<String> argv, Map<String, String> environment,
                                   Duration timeout, int maxBytes, BooleanSupplier launchStillAuthorized) {
        this.argv = validateCommand(argv);
        this.executable = executable(this.argv.getFirst());
        this.launchStillAuthorized = Objects.requireNonNull(launchStillAuthorized);
        validateEnvironment(environment);
        this.environment = Map.copyOf(environment);
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0
                || maxBytes < 1 || maxBytes > MAX_FRAME_LIMIT) {
            throw error("mcp_stdio_invalid_limits");
        }
        this.timeout = timeout;
        this.maxBytes = maxBytes;
    }

    /** Does not start a process. Keep the executable as an absolute path, never search PATH. */
    public static List<String> validateCommand(List<String> argv) {
        if (argv == null || argv.isEmpty() || argv.size() > 128 || argv.getFirst() == null || argv.getFirst().isBlank()
                || argv.stream().anyMatch(arg -> arg == null || arg.length() > 8192 || arg.indexOf('\0') >= 0
                    || arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0
                    || (WINDOWS && arg.indexOf('"') >= 0))) {
            throw error("mcp_stdio_invalid_command");
        }
        executable(argv.getFirst());
        return List.copyOf(argv);
    }

    private record Executable(Path realPath, Object key, long size, FileTime modified) { }

    private static Executable executable(String value) {
        try {
            Path path = Path.of(value);
            if (!localAbsolute(path)) throw error("mcp_stdio_invalid_command");
            Path real = path.toRealPath();
            if (!localAbsolute(real)) throw error("mcp_stdio_invalid_command");
            var attrs = Files.readAttributes(real, BasicFileAttributes.class);
            if (!attrs.isRegularFile() || !Files.isExecutable(real)) {
                throw error("mcp_stdio_invalid_command");
            }
            // Check the configured path and target so an alias to a known shell is also rejected.
            checkExecutableName(path);
            checkExecutableName(real);
            try (var stream = Files.newInputStream(real)) {
                byte[] header = stream.readNBytes(4);
                if (!nativeHeader(header)) throw error("mcp_stdio_invalid_command");
            }
            return new Executable(real, attrs.fileKey(), attrs.size(), attrs.lastModifiedTime());
        } catch (IOException | InvalidPathException | SecurityException e) {
            throw error("mcp_stdio_invalid_command");
        }
    }

    private static boolean localAbsolute(Path path) {
        return path.isAbsolute() && (!WINDOWS || !path.getRoot().toString().startsWith("\\\\"));
    }

    private static boolean nativeHeader(byte[] header) {
        if (header.length != 4) return false;
        if (WINDOWS) return header[0] == 'M' && header[1] == 'Z';
        return switch (ByteBuffer.wrap(header).getInt()) {
            // ELF, Mach-O (both endiannesses and word sizes), and universal Mach-O.
            case 0x7f454c46, 0xfeedface, 0xcefaedfe, 0xfeedfacf, 0xcffaedfe,
                 0xcafebabe, 0xbebafeca, 0xcafebabf, 0xbfbafeca -> true;
            default -> false;
        };
    }

    private static void checkExecutableName(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if ((WINDOWS && !name.endsWith(".exe")) || name.endsWith(".cmd") || name.endsWith(".bat")
                || name.endsWith(".ps1") || SHELLS.contains(name.replaceFirst("\\.exe$", ""))) {
            throw error("mcp_stdio_invalid_command");
        }
    }

    private static void validateEnvironment(Map<String, String> env) {
        Set<String> names = new HashSet<>();
        if (env == null || env.size() > 64) throw error("mcp_stdio_invalid_environment");
        env.forEach((key, value) -> {
            if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || value == null
                    || value.length() > 8192 || value.indexOf('\0') >= 0
                    || !names.add(WINDOWS ? key.toUpperCase(Locale.ROOT) : key)
                    || (WINDOWS && key.equalsIgnoreCase("SystemRoot"))) {
                throw error("mcp_stdio_invalid_environment");
            }
        });
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.<Void>fromRunnable(() -> start(handler))
                .subscribeOn(Schedulers.boundedElastic()).timeout(timeout)
                .doOnCancel(this::terminate).doOnError(ignored -> terminate())
                .onErrorMap(ignored -> error("mcp_stdio_connect_failed"));
    }

    private void start(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        if (closed.get() || !started.compareAndSet(false, true)) throw error("mcp_stdio_closed");
        if (!Objects.equals(executable, executable(argv.getFirst()))) throw error("mcp_stdio_command_changed");
        var command = new java.util.ArrayList<>(argv);
        command.set(0, executable.realPath().toString());
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        var env = builder.environment();
        env.clear();
        if (WINDOWS) {
            // The Windows loader may need this non-secret OS directory.
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot != null) env.put("SystemRoot", systemRoot);
        }
        env.putAll(environment);
        try {
            if (closed.get() || !launchStillAuthorized.getAsBoolean()) throw error("mcp_stdio_not_authorized");
            Process launched = builder.start();
            process = launched;
            if (closed.get()) {
                kill(launched);
                throw error("mcp_stdio_closed");
            }
            reaper = Thread.ofVirtual().start(() -> watchDescendants(launched));
            reader = Thread.ofVirtual().start(() -> read(launched, handler));
            ready.complete(null);
        } catch (IOException | SecurityException e) {
            throw error("mcp_stdio_connect_failed");
        }
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        boolean business = message instanceof McpSchema.JSONRPCRequest request && "tools/call".equals(request.method());
        // Capture the specific invocation before it is scheduled; no later invocation can renew it.
        BusinessLease lease = business ? businessLease.get() : null;
        if (business && lease == null) return Mono.error(new BusinessDenied());
        // The SDK subscribes to connect independently of initial sendMessage.
        return Mono.fromFuture(ready, true).then(Mono.<Void>fromRunnable(() -> write(message, lease)))
                .subscribeOn(Schedulers.boundedElastic()).timeout(timeout)
                .doOnCancel(this::terminate)
                .doOnError(failure -> { if (!(failure instanceof BusinessDenied)) terminate(); })
                .onErrorMap(failure -> failure instanceof BusinessDenied ? failure : error("mcp_stdio_send_failed"));
    }

    /** Attach an authorization lease for one synchronous SDK business operation, not discovery. */
    public <T> T withBusinessGuard(BooleanSupplier stillAuthorized, Supplier<T> action) {
        boolean acquired = false;
        BusinessLease lease = new BusinessLease(Objects.requireNonNull(stillAuthorized));
        try {
            if (businessLock.isHeldByCurrentThread()) throw new BusinessDenied();
            acquired = businessLock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired || closed.get()) throw new BusinessDenied();
            lease.check();
            businessLease.set(lease);
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessDenied();
        } finally {
            lease.active().set(false);
            if (acquired) {
                businessLease.remove();
                businessLock.unlock();
            }
        }
    }

    private void write(McpSchema.JSONRPCMessage message, BusinessLease lease) {
        boolean acquired = false;
        try {
            acquired = writeLock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS);
            Process launched = process;
            if (!acquired || closed.get() || launched == null || !launched.isAlive()) throw error("mcp_stdio_closed");
            byte[] payload = mapper.writeValueAsBytes(message);
            if (payload.length > MAX_REQUEST_BYTES) throw error("mcp_stdio_request_limit");
            var stream = launched.getOutputStream();
            if (lease != null) lease.check();
            stream.write(payload);
            stream.write('\n');
            stream.flush();
        } catch (IOException e) {
            throw error("mcp_stdio_send_failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw error("mcp_stdio_send_failed");
        } finally {
            if (acquired) writeLock.unlock();
        }
    }

    private void read(Process launched, Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        try (var stream = new BufferedInputStream(launched.getInputStream())) {
            var frame = new ByteArrayOutputStream();
            for (int next; !closed.get() && (next = stream.read()) != -1;) {
                if (next != '\n') {
                    if (frame.size() >= maxBytes) throw error("mcp_stdio_response_limit");
                    frame.write(next);
                    continue;
                }
                String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(frame.toByteArray())).toString();
                frame.reset();
                var message = McpSchema.deserializeJsonRpcMessage(mapper, json);
                // Server-initiated sampling, elicitation and roots are not enabled.
                if (!(message instanceof McpSchema.JSONRPCResponse || message instanceof McpSchema.JSONRPCNotification)) {
                    throw error("mcp_stdio_server_request_unsupported");
                }
                handler.apply(Mono.just(message)).block(timeout);
            }
        } catch (IOException | RuntimeException ignored) {
            // Never log server stdout/stderr, parser excerpts, environment or command arguments.
        } finally {
            terminate();
        }
    }

    private void watchDescendants(Process launched) {
        try {
            while (!closed.get() && launched.isAlive()) {
                descendants.removeIf(child -> !child.isAlive());
                launched.descendants().forEach(descendants::add);
                Thread.sleep(25);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            terminate();
        }
    }

    private void terminate() {
        if (!closed.compareAndSet(false, true)) return;
        ready.completeExceptionally(error("mcp_stdio_closed"));
        Process launched = process;
        if (launched != null) kill(launched);
        interrupt(reader);
        interrupt(reaper);
    }

    private void kill(Process launched) {
        // Use ProcessHandle so stream shutdown cannot block waiting for an active pipe writer.
        try {
            launched.descendants().forEach(descendants::add);
            descendants.forEach(NativeStdioMcpTransport::destroy);
            // A cooperative runtime can reap its children before the parent is killed.
            if (!descendants.isEmpty() && launched.isAlive()) {
                try {
                    launched.waitFor(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            launched.descendants().forEach(descendants::add);
        } catch (SecurityException | UnsupportedOperationException ignored) {
            // Still terminate the root and any previously identified children.
        } finally {
            destroy(launched.toHandle());
            descendants.forEach(NativeStdioMcpTransport::destroy);
            descendants.clear();
        }
    }

    private static void destroy(ProcessHandle handle) {
        try {
            if (handle.isAlive()) handle.destroyForcibly();
        } catch (SecurityException | UnsupportedOperationException ignored) {
            // Best effort for processes observable by the host user.
        }
    }

    private static void interrupt(Thread thread) {
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
    }

    private static IllegalStateException error(String code) {
        return new IllegalStateException(code);
    }

    @Override public <T> T unmarshalFrom(Object data, TypeRef<T> type) { return mapper.convertValue(data, type); }
    @Override public List<String> protocolVersions() { return List.of("2025-06-18", "2025-03-26"); }
    @Override public Mono<Void> closeGracefully() { return Mono.fromRunnable(this::terminate); }
}
