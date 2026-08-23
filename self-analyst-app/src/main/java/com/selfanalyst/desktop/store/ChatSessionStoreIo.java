package com.selfanalyst.desktop.store;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

/** Injectable file-operation boundary for deterministic crash-window tests. */
interface ChatSessionStoreIo {

    enum PathStatus { EXISTS, MISSING }

    void createDirectories(Path directory) throws IOException;

    Path createTempFile(Path directory, String prefix, String suffix) throws IOException;

    void writeJson(ObjectMapper mapper, Path file, Object value) throws IOException;

    void atomicReplace(Path source, Path target) throws IOException;

    boolean deleteIfExists(Path path) throws IOException;

    List<Path> list(Path directory) throws IOException;

    PathStatus status(Path path) throws IOException;

    static ChatSessionStoreIo nio() {
        return NioChatSessionStoreIo.INSTANCE;
    }

    enum NioChatSessionStoreIo implements ChatSessionStoreIo {
        INSTANCE;

        @Override
        public void createDirectories(Path directory) throws IOException {
            Files.createDirectories(directory);
        }

        @Override
        public Path createTempFile(Path directory, String prefix, String suffix)
                throws IOException {
            return Files.createTempFile(directory, prefix, suffix);
        }

        @Override
        public void writeJson(ObjectMapper mapper, Path file, Object value) throws IOException {
            mapper.writeValue(file.toFile(), value);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }

        @Override
        public void atomicReplace(Path source, Path target) throws IOException {
            Files.move(source, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        }

        @Override
        public boolean deleteIfExists(Path path) throws IOException {
            return Files.deleteIfExists(path);
        }

        @Override
        public List<Path> list(Path directory) throws IOException {
            try (Stream<Path> paths = Files.list(directory)) {
                return paths.toList();
            }
        }

        @Override
        public PathStatus status(Path path) throws IOException {
            if (Files.notExists(path)) return PathStatus.MISSING;
            if (Files.exists(path)) return PathStatus.EXISTS;
            throw new IOException("Unable to determine path status: " + path);
        }
    }
}
