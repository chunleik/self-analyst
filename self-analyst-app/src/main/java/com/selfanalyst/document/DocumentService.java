package com.selfanalyst.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfanalyst.desktop.store.ChatSessionStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 受管文件发布；所有外部目录保存交给桌面壳的显式另存为。 */
public final class DocumentService {
    private final Path root;
    private final DocumentStore store;
    private DocumentDataSources dataSources;
    private final DocumentRenderer renderer = new DocumentRenderer();
    private final java.util.concurrent.locks.ReentrantLock contentLease = new java.util.concurrent.locks.ReentrantLock();
    private final PublicationProbe publicationProbe;
    private static final long MAX_TOTAL = 1024L * 1024 * 1024;

    public DocumentService(Path memoryDir, ChatSessionStore chats) throws IOException {
        this(memoryDir, chats, stage -> {});
    }
    @FunctionalInterface interface PublicationProbe { void at(String stage) throws IOException; }
    DocumentService(Path memoryDir, ChatSessionStore chats, PublicationProbe probe) throws IOException {
        publicationProbe = probe;
        root = memoryDir.toAbsolutePath().normalize().resolve("documents");
        createSafeDirectory(root);
        store = new DocumentStore(chats);
        recover();
        chats.setDocumentDeletion(this::deleteSession);
    }

    public DocumentStore store() { return store; }
    public void setDataSources(DocumentDataSources sources) { this.dataSources = sources; }

    public synchronized DocumentStore.Artifact generate(String session, String turn, String format, String title,
                                                        String source, String parent, BooleanSupplier cancelled) throws IOException {
        var request = DocumentRequest.parse(format, title, source);
        var budget = new DocumentBudget(cancelled);
        return generatePrepared(session, turn, request.format(), title, request.source(), parent, budget,
                directory -> new DocumentDataSources.Prepared(request, () -> {}));
    }

    public synchronized DocumentStore.Artifact export(String session, String turn, String format, String title,
                                                      String queryJson, String parent, BooleanSupplier cancelled) throws IOException {
        if (dataSources == null) throw new IllegalArgumentException("数据导出服务不可用");
        var query = dataSources.parse(queryJson);
        var targetFormat = DocumentFormat.parse(format);
        if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > 160)
            throw new IllegalArgumentException("文档标题须为 1 至 160 个字符");
        var budget = new DocumentBudget(cancelled);
        return generatePrepared(session, turn, targetFormat, title, dataSources.descriptor(query), parent, budget,
                directory -> dataSources.prepare(query, targetFormat, title, directory, budget));
    }

    @FunctionalInterface private interface Prepare { DocumentDataSources.Prepared create(Path directory) throws Exception; }
    private DocumentStore.Artifact generatePrepared(String session, String turn, DocumentFormat format, String title,
                                                    JsonNode keySource, String parent, DocumentBudget budget, Prepare prepare) throws IOException {
        budget.check();
        String hash = digest((format + "\n" + title + "\n" + Objects.toString(parent, "") + "\n"
                + canonical(keySource)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String name = safeName(title) + "." + format.extension;
        var job = store.begin(session, turn, hash, name, format.name(), parent);
        if (job.status().equals("READY")) { verify(job); return job; }
        Path directory = directory(session, job.id());
        try {
            if (usedBytes() + 2 * DocumentBudget.MAX_FILE_BYTES + DocumentRequest.MAX_SOURCE_BYTES > MAX_TOTAL)
                throw new IllegalArgumentException("文档存储接近 1 GiB，请清理不再需要的会话");
            deleteTree(directory);
            createSafeDirectory(directory);
            Path temporary = directory.resolve("content.part");
            String metadata;
            try (var prepared = prepare.create(directory)) {
                renderer.render(prepared.request(), temporary, budget);
                Files.writeString(directory.resolve("source.json"), prepared.request().source().toString(), StandardOpenOption.CREATE_NEW);
                metadata = prepared.request().source().path("metadata").isObject() ? prepared.request().source().get("metadata").toString() : "{}";
            }
            publicationProbe.at("written");
            budget.check();
            DocumentFormatVerifier.verify(format, temporary, budget);
            String checksum = digest(temporary);
            Files.move(temporary, directory.resolve("content"), StandardCopyOption.ATOMIC_MOVE);
            publicationProbe.at("moved");
            budget.check();
            return store.publish(session, job.id(), Files.size(directory.resolve("content")), checksum, metadata);
        } catch (Exception failure) {
            boolean cancelledNow = failure instanceof CancellationException;
            String message = failure instanceof IllegalArgumentException ? failure.getMessage()
                    : cancelledNow ? "生成已取消" : "文件生成失败，请重试并检查可用磁盘空间";
            store.fail(session, job.id(), cancelledNow, message);
            deleteTree(directory);
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IOException(message, failure);
        }
    }

    public synchronized String readSource(String session, String id) throws IOException {
        var artifact = requireReady(session, id);
        verify(artifact);
        Path source = directory(session, id).resolve("source.json");
        requireRegular(source);
        if (Files.size(source) > DocumentRequest.MAX_SOURCE_BYTES) throw new IOException("文档生成源超限");
        JsonNode node = DocumentRequest.JSON.readTree(Files.readString(source));
        if (node.has("exportQuery")) return node.get("exportQuery").toString();
        return node.toString();
    }

    /** 回调内持有文件读取租约，避免删除与打开句柄竞态。 */
    public synchronized void readContent(String session, String id, ContentConsumer consumer) throws IOException {
        contentLease.lock();
        try {
            var artifact = requireReady(session, id);
            Path path = verify(artifact);
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { consumer.accept(artifact, input); }
        } finally { contentLease.unlock(); }
    }
    @FunctionalInterface public interface ContentConsumer {
        void accept(DocumentStore.Artifact artifact, InputStream content) throws IOException;
    }
    private DocumentStore.Artifact requireReady(String session, String id) {
        var artifact = store.get(session, id);
        if (artifact == null || !artifact.status().equals("READY")) throw new IllegalArgumentException("文档不存在或尚未生成成功");
        return artifact;
    }
    private Path verify(DocumentStore.Artifact artifact) throws IOException {
        Path content = directory(artifact.sessionId(), artifact.id()).resolve("content");
        java.nio.file.attribute.BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(content, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException failure) {
            store.invalidate(artifact.sessionId(), artifact.id());
            throw failure;
        }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() != artifact.size()) {
            store.invalidate(artifact.sessionId(), artifact.id());
            throw new IOException("文档文件缺失或损坏，请重新生成");
        }
        // 临时读取失败直接交给调用方重试，只有已证实的内容损坏才撤销 READY。
        if (!digest(content).equals(artifact.sha256())) {
            store.invalidate(artifact.sessionId(), artifact.id());
            throw new IOException("文档文件缺失或损坏，请重新生成");
        }
        return content;
    }

    public void deleteSession(String session) {
        if (!ChatSessionStore.isGeneratedSessionId(session)) throw new IllegalArgumentException("无效会话");
        // 删除协调持有会话锁；不等待反向获取该锁的下载租约，保留 intent 后重试。
        if (!contentLease.tryLock()) throw new IllegalStateException("文档正在保存，清理将在稍后继续");
        try { deleteTree(root.resolve(session)); store.delete(session); }
        catch (IOException failure) { throw new IllegalStateException("文档清理未完成", failure); }
        finally { contentLease.unlock(); }
    }
    private void recover() throws IOException {
        Set<String> ready = store.readyIds();
        try (var sessions = Files.newDirectoryStream(root)) {
            for (Path session : sessions) {
                if (!ChatSessionStore.isGeneratedSessionId(session.getFileName().toString())) continue;
                requireDirectory(session);
                try (var files = Files.newDirectoryStream(session)) {
                    for (Path file : files) {
                        if (!ready.contains(file.getFileName().toString())) deleteTree(file);
                        else Files.deleteIfExists(file.resolve("content.part"));
                    }
                }
            }
        }
    }
    private long usedBytes() throws IOException {
        try (var paths = Files.walk(root)) {
            long total = 0;
            for (var iterator = paths.iterator(); iterator.hasNext();) {
                Path path = iterator.next();
                if (Files.isSymbolicLink(path)) throw new IOException("文档目录不允许链接");
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) total += Files.size(path);
            }
            return total;
        }
    }
    private Path directory(String session, String id) throws IOException {
        if (!ChatSessionStore.isGeneratedSessionId(session) || !ChatSessionStore.isGeneratedSessionId(id))
            throw new IllegalArgumentException("无效文档标识");
        requireDirectory(root);
        Path directory = root.resolve(session).resolve(id);
        for (Path path = root.resolve(session); path != null && path.startsWith(root); path = path.getParent())
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) requireDirectory(path);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) requireDirectory(directory);
        return directory;
    }
    private static void createSafeDirectory(Path path) throws IOException {
        for (Path parent = path; parent != null; parent = parent.getParent())
            if (Files.isSymbolicLink(parent)) throw new IOException("文档目录不允许链接");
        Files.createDirectories(path); requireDirectory(path);
    }
    private static void requireDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("文档目录不可用");
    }
    private static void requireRegular(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("文档文件不可用");
    }
    private void deleteTree(Path path) throws IOException {
        if (!path.normalize().startsWith(root) || path.equals(root)) throw new IOException("无效文档清理路径");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }
    private static String safeName(String title) {
        String name = title.replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_").replaceAll("[. ]+$", "");
        if (name.isBlank() || name.matches("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(\\..*)?")) name = "文档_" + name;
        return name;
    }
    static String canonical(JsonNode node) {
        if (node.isObject()) {
            var result = DocumentRequest.JSON.createObjectNode();
            var keys = new TreeSet<String>(); node.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) try { result.set(key, DocumentRequest.JSON.readTree(canonical(node.get(key)))); }
            catch (IOException impossible) { throw new IllegalStateException(impossible); }
            return result.toString();
        }
        if (node.isArray()) {
            var result = DocumentRequest.JSON.createArrayNode();
            for (JsonNode item : node) try { result.add(DocumentRequest.JSON.readTree(canonical(item))); }
            catch (IOException impossible) { throw new IllegalStateException(impossible); }
            return result.toString();
        }
        return node.toString();
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String digest(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }
    private static String digest(Path file) throws IOException {
        var digest = sha256();
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
