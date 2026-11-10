package com.selfanalyst.integrations;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Independent, serialized, optimistic store. Instantiate only after runtime-root admission. */
public final class IntegrationStore implements AutoCloseable {
    public static final int MAX_CONFIG_BYTES = 1048576;
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                            .maxStringLength(65536).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    @FunctionalInterface interface AtomicReplace { void move(Path source, Path target) throws IOException; }
    private final Path file;
    private final ReentrantLock lock;
    private final AtomicReplace replace;
    private volatile boolean closed;

    public IntegrationStore(Path runtimeRoot) throws IOException { this(runtimeRoot, IntegrationStore::atomicMove); }
    IntegrationStore(Path root, AtomicReplace replace) throws IOException {
        file = root.toAbsolutePath().normalize().resolve("config/integrations.json");
        this.replace = replace;
        lock = LOCKS.computeIfAbsent(file, ignored -> new ReentrantLock());
        readConfig(file); // No mkdir, lock file, credentials, migration or network side effect.
    }
    public Path path() { return file; }
    public IntegrationConfig snapshot() throws IOException {
        lock.lock();
        try { checkOpen(); return readConfig(file); }
        finally { lock.unlock(); }
    }
    public static IntegrationConfig parse(String json) throws IOException {
        if (json == null) throw new IOException("invalid_configuration");
        return parse(json.getBytes(StandardCharsets.UTF_8));
    }
    private static IntegrationConfig parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_CONFIG_BYTES) throw new IOException("configuration_limit");
        try {
            var document = JSON.readTree(bytes);
            if (document == null || !document.isObject()) throw new IOException("invalid_configuration");
            // Schema 1 predates folderScope. Only absence has legacy semantics; explicit null,
            // invalid types, unknown fields and all other missing fields still fail strict binding.
            if (document.path("mailAccounts").isArray()) {
                for (var account : document.path("mailAccounts")) {
                    if (account.isObject() && !account.has("folderScope"))
                        ((com.fasterxml.jackson.databind.node.ObjectNode) account).put("folderScope", "selected");
                }
            }
            return JSON.treeToValue(document, IntegrationConfig.class);
        }
        catch (Exception invalid) { throw new IOException("invalid_or_unsupported_configuration"); }
    }
    public static IntegrationConfig readConfig(Path path) throws IOException {
        checkAncestors(path.toAbsolutePath().normalize());
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return IntegrationConfig.empty();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("invalid_configuration_file");
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return parse(in.readNBytes(MAX_CONFIG_BYTES + 1));
        }
    }
    public IntegrationConfig save(long expectedRevision, IntegrationConfig candidate) throws IOException {
        return save(expectedRevision, candidate, () -> {});
    }
    @FunctionalInterface public interface CommitGuard { void check() throws IOException; }
    /** Trusted application guard is rechecked at the atomic commit boundary, never supplied by JSON. */
    public IntegrationConfig save(long expectedRevision, IntegrationConfig candidate, CommitGuard guard) throws IOException {
        if (candidate == null || candidate.revision() != expectedRevision) throw new IOException("revision_conflict");
        lock.lock();
        try {
            checkOpen();
            checkAncestors(file);
            // Validate the existing file before making any directories or lock files.
            IntegrationConfig previous = readConfig(file);
            if (previous.revision() != expectedRevision) throw new IOException("revision_conflict");
            Files.createDirectories(file.getParent());
            Path lockFile = file.resolveSibling(".integrations.lock");
            checkAncestors(lockFile);
            try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS); var ignored = channel.lock()) {
                checkOpen();
                previous = readConfig(file);
                if (previous.revision() != expectedRevision || expectedRevision == Long.MAX_VALUE)
                    throw new IOException("revision_conflict");
                IntegrationConfig committed = candidate.withRevision(expectedRevision + 1);
                byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(committed);
                if (bytes.length > MAX_CONFIG_BYTES) throw new IOException("configuration_limit");
                Path temporary = file.resolveSibling(".integrations-" + UUID.randomUUID() + ".tmp");
                try {
                    durableWrite(temporary, bytes);
                    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                        Path backupTemp = file.resolveSibling(".integrations-" + UUID.randomUUID() + ".tmp");
                        Path backup = file.resolveSibling("integrations.json.bak");
                        checkAncestors(backup);
                        try {
                            durableWrite(backupTemp, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(previous));
                            atomicMove(backupTemp, backup);
                        } finally { Files.deleteIfExists(backupTemp); }
                    }
                    guard.check();
                    replace.move(temporary, file); // No non-atomic fallback on unsupported filesystems.
                    return committed;
                } finally { Files.deleteIfExists(temporary); }
            }
        } finally { lock.unlock(); }
    }
    public IntegrationConfig importMetadata(long expectedRevision, String json) throws IOException {
        return save(expectedRevision, parse(json).portable().withRevision(expectedRevision));
    }
    public String exportMetadata() throws IOException {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot().portable());
    }
    /** Ordinary UI status: no machine references and only credential availability, never secret bytes. */
    public java.util.Map<String, Object> publicMetadata(CredentialStore credentials) throws IOException {
        IntegrationConfig config = snapshot();
        var servers = config.mcpServers().stream().map(server -> {
            var node = JSON.valueToTree(server);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("credentialRef");
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("credentialConfigured",
                    credentials.configured(server.credentialRef(), server.credentialTarget()));
            return node;
        }).toList();
        var accounts = config.mailAccounts().stream().map(account -> {
            var node = JSON.valueToTree(account);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("credentialRef");
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("credentialConfigured",
                    credentials.configured(account.credentialRef(), account.credentialTarget()));
            return node;
        }).toList();
        return java.util.Map.of("schemaVersion", config.schemaVersion(), "revision", config.revision(),
                "mcpServers", servers, "mailAccounts", accounts, "toolApprovals", config.toolApprovals());
    }
    private static void durableWrite(Path target, byte[] bytes) throws IOException {
        try (var out = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) out.write(buffer);
            out.force(true);
        }
    }
    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static void checkAncestors(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("configuration_symlink_rejected");
        }
    }
    private void checkOpen() throws IOException { if (closed) throw new IOException("store_closed"); }
    @Override public void close() { lock.lock(); try { closed = true; } finally { lock.unlock(); } }
}
