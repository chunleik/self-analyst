package com.selfanalyst;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** 数据准入期间及会话存续期间唯一持有的进程锁。 */
public final class RuntimeStorageGuard implements AutoCloseable {
    public enum Failure {
        DATA_IN_USE(20), DATA_LOCK_UNAVAILABLE(21), DATA_FORMAT_UNSUPPORTED(22),
        DATA_FORMAT_INVALID(23), DATA_ROOT_UNAVAILABLE(24);

        private final int exitCode;
        Failure(int exitCode) { this.exitCode = exitCode; }
        public int exitCode() { return exitCode; }
    }

    public static final class StorageException extends IOException {
        private final Failure failure;
        public StorageException(Failure failure, String message) {
            super(failure.name() + ": " + message);
            this.failure = failure;
        }
        public Failure failure() { return failure; }
    }

    @FunctionalInterface
    interface CompatibilityCheck {
        void verify(Path dataRoot) throws IOException;
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path dataRoot;
    private final FileChannel channel;
    private final FileLock lock;

    private RuntimeStorageGuard(Path dataRoot, FileChannel channel, FileLock lock) {
        this.dataRoot = dataRoot;
        this.channel = channel;
        this.lock = lock;
    }

    public static RuntimeStorageGuard acquire(Path dataRoot) throws IOException {
        return acquire(dataRoot, RuntimeStorageCompatibility::verify);
    }

    static RuntimeStorageGuard acquire(Path requestedRoot, CompatibilityCheck compatibility) throws IOException {
        Path root;
        try {
            Files.createDirectories(requestedRoot);
            root = requestedRoot.toRealPath();
        } catch (IOException | SecurityException e) {
            throw new StorageException(Failure.DATA_ROOT_UNAVAILABLE, "数据目录不可用");
        }
        FileChannel channel = null;
        FileLock lock;
        try {
            channel = FileChannel.open(root.resolve("app.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            lock = channel.tryLock();
            if (lock == null) throw new OverlappingFileLockException();
        } catch (OverlappingFileLockException e) {
            if (channel != null) channel.close();
            throw new StorageException(Failure.DATA_IN_USE, "数据目录正在被另一个实例使用");
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            if (channel != null) channel.close();
            throw new StorageException(Failure.DATA_LOCK_UNAVAILABLE, "无法取得数据目录独占锁");
        }
        RuntimeStorageGuard guard = new RuntimeStorageGuard(root, channel, lock);
        try {
            guard.admit(compatibility);
            return guard;
        } catch (IOException | RuntimeException e) {
            try { guard.close(); } catch (IOException closeFailure) { e.addSuppressed(closeFailure); }
            throw e;
        }
    }

    private void admit(CompatibilityCheck compatibility) throws IOException {
        Path marker = dataRoot.resolve("storage-format.json");
        try {
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                requireRegular(marker);
                if (Files.size(marker) > 1024) throw invalid();
                var document = JSON.readTree(Files.readAllBytes(marker));
                if (document == null || !document.isObject() || document.size() != 1
                        || !document.has("formatVersion") || !document.get("formatVersion").isIntegralNumber()) {
                    throw invalid();
                }
                if (!document.get("formatVersion").canConvertToInt()
                        || (document.get("formatVersion").intValue() != 1
                            && document.get("formatVersion").intValue() != 2)) {
                    throw new StorageException(Failure.DATA_FORMAT_UNSUPPORTED, "当前程序不支持此数据格式");
                }
                return;
            }
            // 即使留下临时标记，每次仍重新验证旧数据，不能将其当作准入证据。
            compatibility.verify(dataRoot);
            Path temporary = dataRoot.resolve("storage-format.json.tmp");
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) requireRegular(temporary);
            try (var output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                var bytes = ByteBuffer.wrap("{\"formatVersion\":1}\n".getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
        } catch (StorageException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid();
        }
    }

    private static void requireRegular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw invalid();
    }

    private static StorageException invalid() {
        return new StorageException(Failure.DATA_FORMAT_INVALID, "数据格式无法确认或格式标记不可用");
    }

    public Path dataRoot() { return dataRoot; }

    /** 事件迁移提交后发布；旧版只接受版本 1，因此不会继续写入新格式。 */
    public synchronized void publishMergedFormat() throws IOException {
        if (!lock.isValid()) throw new StorageException(Failure.DATA_LOCK_UNAVAILABLE, "格式升级需要数据根锁");
        Path marker = dataRoot.resolve("storage-format.json");
        requireRegular(marker);
        Path temporary = dataRoot.resolve("storage-format.json.tmp");
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) requireRegular(temporary);
        try (var output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            var bytes = ByteBuffer.wrap("{\"formatVersion\":2}\n".getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) output.write(bytes);
            output.force(true);
        }
        Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public synchronized void close() throws IOException {
        // 不删除锁文件：其他进程必须继续对同一个文件对象加锁。
        try {
            if (lock.isValid()) lock.release();
        } finally {
            channel.close();
        }
    }
}
