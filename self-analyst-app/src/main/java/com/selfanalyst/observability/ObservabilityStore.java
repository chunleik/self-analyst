package com.selfanalyst.observability;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

final class ObservabilityStore {
    static final ObjectMapper JSON =
            JsonMapper.builder(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(16)
                                                    .maxStringLength(65536)
                                                    .build())
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .build();

    record Saved(ObservabilityConfig config, String secretRef) {
        Saved {
            Objects.requireNonNull(config);
            if (secretRef != null && !secretRef.matches("windows:[a-f0-9-]{36}"))
                throw new IllegalArgumentException("invalid_reference");
        }
    }

    final Path file;

    ObservabilityStore(Path root) {
        file = root.toAbsolutePath().normalize().resolve("config/observability.json");
    }

    Saved read() throws IOException {
        check(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            return new Saved(ObservabilityConfig.defaults(), null);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 131072)
            throw new IOException("config_unavailable");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return JSON.readValue(input.readNBytes(131073), Saved.class);
        } catch (RuntimeException invalid) {
            throw new IOException("config_unavailable");
        }
    }

    Saved write(long expected, Saved candidate) throws IOException {
        check(file);
        Saved before = read();
        if (before.config().revision() != expected
                || candidate.config().revision() != expected + 1
                || expected == Long.MAX_VALUE) throw new IOException("revision_conflict");
        Files.createDirectories(file.getParent());
        Path lock = file.resolveSibling(".observability.lock");
        check(lock);
        try (var channel =
                        FileChannel.open(
                                lock,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS);
                var ignored = channel.lock()) {
            if (read().config().revision() != expected) throw new IOException("revision_conflict");
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(candidate);
            Path temp = file.resolveSibling(".observability-" + UUID.randomUUID() + ".tmp");
            try {
                try (var out =
                        FileChannel.open(
                                temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer b = ByteBuffer.wrap(bytes);
                    while (b.hasRemaining()) out.write(b);
                    out.force(true);
                }
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            return candidate;
        }
    }

    private static void check(Path path) throws IOException {
        for (Path p = path; p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("config_unavailable");
    }
}
