package com.selfanalyst.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Removes files created by the retired Lucene file-content index. */
public final class LegacyFileSemanticIndexPurger {

    private static final int LUCENE_CODEC_MAGIC = 0x3fd76c17;
    private static final Pattern COMMIT_POINT = Pattern.compile("segments_[0-9a-z]+");
    private static final Pattern PENDING_COMMIT = Pattern.compile("pending_segments_[0-9a-z]+");
    private static final Pattern SEGMENT_FILE = Pattern.compile(
            "_[0-9a-z]+(?:_[A-Za-z0-9-]+)*\\.([a-z0-9]+)");
    private static final Set<String> LUCENE_EXTENSIONS = Set.of(
            "si", "cfs", "cfe", "fnm", "fdx", "fdt", "fdm",
            "tim", "tip", "tmd", "doc", "pos", "pay",
            "dvd", "dvm", "nvd", "nvm", "tvd", "tvx", "tvm",
            "dim", "kdd", "kdi", "kdm", "liv",
            "vec", "vem", "vex", "veq");

    private LegacyFileSemanticIndexPurger() {}

    public static int purge(Path configuredIndexDir) throws IOException {
        return purge(configuredIndexDir, Files::delete);
    }

    static int purge(Path configuredIndexDir, ArtifactDeleter deleter) throws IOException {
        if (configuredIndexDir == null) return 0;
        Path dir = configuredIndexDir.toAbsolutePath().normalize();
        if (dir.getParent() == null || dir.equals(dir.getRoot())) {
            throw new IOException("拒绝清理不安全的旧文件语义索引目录: " + dir);
        }
        if (!Files.exists(dir)) return 0;
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir)) {
            throw new IOException("旧文件语义索引路径不是目录: " + dir);
        }

        List<Path> artifacts = new ArrayList<>();
        boolean hasLuceneCommitPoint = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry)
                        || !isLuceneArtifact(name)) {
                    throw new IOException("拒绝清理包含非 Lucene 文件的目录: " + dir);
                }
                artifacts.add(entry);
                if (COMMIT_POINT.matcher(name).matches()) {
                    if (!hasLuceneHeader(entry)) {
                        throw new IOException("拒绝清理 commit header 无效的目录: " + dir);
                    }
                    hasLuceneCommitPoint = true;
                }
            }
        }
        if (artifacts.isEmpty()) return 0;
        if (!hasLuceneCommitPoint) {
            throw new IOException("拒绝清理无法验证的旧文件语义索引目录: " + dir);
        }

        // Keep a committed segments_N file until every data artifact is gone so
        // interrupted cleanup remains recognizable and retryable at next startup.
        artifacts.sort(Comparator
                .comparing((Path path) -> COMMIT_POINT.matcher(
                        path.getFileName().toString()).matches())
                .thenComparing(path -> path.getFileName().toString()));
        for (Path artifact : artifacts) deleter.delete(artifact);
        Files.delete(dir);
        return artifacts.size();
    }

    private static boolean isLuceneArtifact(String name) {
        return "write.lock".equals(name)
                || COMMIT_POINT.matcher(name).matches()
                || PENDING_COMMIT.matcher(name).matches()
                || isSegmentFile(name);
    }

    private static boolean isSegmentFile(String name) {
        var matcher = SEGMENT_FILE.matcher(name);
        return matcher.matches() && LUCENE_EXTENSIONS.contains(matcher.group(1));
    }

    private static boolean hasLuceneHeader(Path commitPoint) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(Integer.BYTES);
        try (SeekableByteChannel channel = Files.newByteChannel(
                commitPoint, StandardOpenOption.READ)) {
            while (header.hasRemaining() && channel.read(header) >= 0) {
                // Read only the fixed four-byte Lucene codec magic.
            }
        }
        if (header.position() != Integer.BYTES) return false;
        header.flip();
        return header.getInt() == LUCENE_CODEC_MAGIC;
    }

    @FunctionalInterface
    interface ArtifactDeleter {
        void delete(Path path) throws IOException;
    }
}
