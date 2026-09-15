package com.selfanalyst.desktop.store;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;

/** User-selected images; all mutations share the chat writer lock. */
public final class ChatImageStore {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final String PREFIX = "selfanalyst-image:";
    private final ChatSessionStore store;
    private final Path root;
    private final Path stateRoot;
    private java.util.concurrent.ScheduledExecutorService maintenance;

    public void startMaintenance() {
        synchronized (store) {
            if (maintenance != null) return;
            cleanup();
            maintenance = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "chat-image-cleanup"); thread.setDaemon(true); return thread;
            });
            maintenance.scheduleWithFixedDelay(() -> {
                try { cleanup(); } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(ChatImageStore.class).warn("Chat image cleanup deferred");
                }
            }, 1, 1, java.util.concurrent.TimeUnit.HOURS);
        }
    }
    void close() { if (maintenance != null) maintenance.shutdownNow(); }

    public record Image(String id, String mimeType, long size, int width, int height, String url) {}

    ChatImageStore(ChatSessionStore store, Path memoryDir) {
        this.store = store;
        root = memoryDir.resolve("chat-images").toAbsolutePath().normalize();
        stateRoot = memoryDir.resolve("agent-state/self-analyst-chat");
    }

    static void schema(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            // message_id deliberately has no FK: transcript retention and AgentState differ.
            s.execute("CREATE TABLE IF NOT EXISTS chat_images (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, "
                    + "message_id TEXT, position INTEGER, mime TEXT NOT NULL, size INTEGER NOT NULL, "
                    + "width INTEGER NOT NULL, height INTEGER NOT NULL, created_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS chat_images_session ON chat_images(session_id,message_id)");
        }
    }

    public Image upload(String session, InputStream input, String mime) throws IOException {
        synchronized (store) {
            requireSession(session);
            if (!Set.of("image/png", "image/jpeg").contains(Objects.toString(mime, "")))
                throw new IllegalArgumentException("PNG/JPEG images only");
            cleanup();
            Path dir = root.resolve(session);
            Files.createDirectories(dir);
            Path temporary = Files.createTempFile(dir, "upload-", ".tmp");
            Path target = null;
            boolean saved = false;
            try {
                long size = 0;
                try (var out = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        size += count;
                        if (size > MAX_BYTES) throw new IllegalArgumentException("Image exceeds 5 MiB");
                        out.write(buffer, 0, count);
                    }
                }
                int[] dimensions = validate(temporary, mime);
                String id = UUID.randomUUID().toString().replace("-", "");
                target = dir.resolve(id);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                long bytes = size;
                Image result = store.documentTransaction(c -> {
                    try (var s = c.prepareStatement("INSERT INTO chat_images(id,session_id,mime,size,width,height,created_at) VALUES(?,?,?,?,?,?,?)")) {
                        s.setString(1, id); s.setString(2, session); s.setString(3, mime);
                        s.setLong(4, bytes); s.setInt(5, dimensions[0]); s.setInt(6, dimensions[1]);
                        s.setLong(7, System.currentTimeMillis()); s.executeUpdate();
                    }
                    return new Image(id, mime, bytes, dimensions[0], dimensions[1], url(session, id));
                });
                saved = true;
                return result;
            } finally {
                Files.deleteIfExists(temporary);
                if (!saved && target != null) Files.deleteIfExists(target);
            }
        }
    }

    public static int[] validate(Path file, String mime) throws IOException {
        if (Files.size(file) == 0 || Files.size(file) > MAX_BYTES) throw new IllegalArgumentException("Invalid image size");
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("Invalid image");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!("png".equals(format) && "image/png".equals(mime))
                        && !("jpeg".equals(format) && "image/jpeg".equals(mime)))
                    throw new IllegalArgumentException("Image format does not match MIME type");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 20_000_000)
                    throw new IllegalArgumentException("Image exceeds 20 megapixels");
                var decoded = reader.read(0);
                if (decoded == null) throw new IllegalArgumentException("Invalid image");
                decoded.flush();
                return new int[]{width, height};
            } finally { reader.dispose(); }
        }
    }

    private void requireSession(String session) {
        if (!ChatSessionStore.isGeneratedSessionId(session) || store.pendingDeletionIds().contains(session)
                || store.getSession(session) == null) throw new IllegalArgumentException("Unknown image session");
    }

    static void bind(Connection c, String session, ChatSessionStore.Message message) throws SQLException {
        if (message.imageIds == null || message.imageIds.isEmpty()) return;
        if (!"user".equals(message.role) || message.imageIds.size() > 4
                || new HashSet<>(message.imageIds).size() != message.imageIds.size())
            throw new IllegalArgumentException("Invalid image references (maximum 4)");
        for (int i = 0; i < message.imageIds.size(); i++) {
            String id = message.imageIds.get(i);
            if (!ChatSessionStore.isGeneratedSessionId(id)) throw new IllegalArgumentException("Invalid image ID");
            try (var s = c.prepareStatement("UPDATE chat_images SET message_id=?,position=? WHERE id=? AND session_id=? AND message_id IS NULL")) {
                s.setString(1, message.id); s.setInt(2, i); s.setString(3, id); s.setString(4, session);
                if (s.executeUpdate() != 1) throw new IllegalArgumentException("Image is unavailable or already attached");
            }
        }
        message.images = list(c, session, message.id);
        message.imageIds = null;
    }

    static List<Image> list(Connection c, String session, String message) throws SQLException {
        try (var s = c.prepareStatement("SELECT * FROM chat_images WHERE session_id=? AND message_id=? ORDER BY position")) {
            s.setString(1, session); s.setString(2, message);
            try (var r = s.executeQuery()) {
                List<Image> images = new ArrayList<>();
                while (r.next()) images.add(image(r));
                return images;
            }
        }
    }

    private static Image image(ResultSet r) throws SQLException {
        return new Image(r.getString("id"), r.getString("mime"), r.getLong("size"),
                r.getInt("width"), r.getInt("height"), url(r.getString("session_id"), r.getString("id")));
    }
    private static String url(String session, String id) { return "/desktop/chat/sessions/" + session + "/images/" + id; }

    public Image detail(String session, String id) {
        synchronized (store) {
            requireSession(session);
            if (!ChatSessionStore.isGeneratedSessionId(id)) throw new IllegalArgumentException("Invalid image ID");
            return store.documentTransaction(c -> {
                try (var s = c.prepareStatement("SELECT * FROM chat_images WHERE session_id=? AND id=?")) {
                    s.setString(1, session); s.setString(2, id);
                    try (var r = s.executeQuery()) { return r.next() ? image(r) : null; }
                }
            });
        }
    }

    public byte[] read(String session, String id) throws IOException {
        synchronized (store) {
            Image image = detail(session, id);
            if (image == null) throw new FileNotFoundException("Image unavailable");
            Path file = root.resolve(session).resolve(id);
            if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent())
                    || !file.toRealPath().startsWith(root.toRealPath())
                    || Files.size(file) != image.size()) throw new IOException("Image unavailable");
            return Files.readAllBytes(file);
        }
    }

    public void removeDraft(String session, String id) {
        synchronized (store) {
            requireSession(session);
            if (!ChatSessionStore.isGeneratedSessionId(id)) throw new IllegalArgumentException("Invalid image ID");
            store.documentTransaction(c -> {
                try (var s = c.prepareStatement("SELECT message_id FROM chat_images WHERE session_id=? AND id=?")) {
                    s.setString(1, session); s.setString(2, id);
                    try (var r = s.executeQuery()) {
                        if (r.next() && r.getString(1) != null) throw new IllegalArgumentException("Image already attached");
                    }
                }
                delete(c, session, id); return null;
            });
        }
    }

    void deleteSession(String session) {
        store.documentTransaction(c -> {
            try (var s = c.prepareStatement("SELECT id FROM chat_images WHERE session_id=?")) {
                s.setString(1, session);
                List<String> ids = new ArrayList<>();
                try (var r = s.executeQuery()) { while (r.next()) ids.add(r.getString(1)); }
                for (String id : ids) delete(c, session, id);
            }
            return null;
        });
    }

    private void delete(Connection c, String session, String id) throws IOException, SQLException {
        Files.deleteIfExists(root.resolve(session).resolve(id));
        try (var s = c.prepareStatement("DELETE FROM chat_images WHERE session_id=? AND id=?")) {
            s.setString(1, session); s.setString(2, id); s.executeUpdate();
        }
    }

    /** Conservative GC: a corrupt/unreadable model state keeps the corresponding images. */
    public void cleanup() {
        synchronized (store) {
            store.documentTransaction(c -> {
                List<String[]> candidates = new ArrayList<>();
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT id,session_id,message_id,created_at FROM chat_images")) {
                    while (r.next()) {
                        String message = r.getString(3);
                        if (message == null && r.getLong(4) >= System.currentTimeMillis() - 86_400_000L) continue;
                        candidates.add(new String[]{r.getString(1), r.getString(2), message});
                    }
                }
                var states = new JsonFileAgentStateStore(stateRoot);
                try {
                    for (String[] row : candidates) {
                        if (row[2] != null) {
                            try (var s = c.prepareStatement("SELECT 1 FROM messages WHERE session_id=? AND id=?")) {
                                s.setString(1, row[1]); s.setString(2, row[2]);
                                try (var r = s.executeQuery()) { if (r.next()) continue; }
                            }
                            try {
                                var state = states.get("desktop", row[1], "agent_state", AgentState.class).orElse(null);
                                if (state != null && state.getContext().stream().flatMap(m -> m.getContent().stream())
                                        .anyMatch(b -> b instanceof ImageBlock image && image.getSource() instanceof URLSource source
                                                && (PREFIX + row[1] + "/" + row[0]).equals(source.getUrl()))) continue;
                            } catch (RuntimeException unavailable) { continue; }
                        }
                        delete(c, row[1], row[0]);
                    }
                } finally { states.close(); }
                if (Files.isDirectory(root)) {
                    try (var files = Files.walk(root, 2)) {
                        for (Path file : files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                            String session = file.getParent().getFileName().toString();
                            String id = file.getFileName().toString();
                            if (!ChatSessionStore.isGeneratedSessionId(session)
                                    || Files.getLastModifiedTime(file).toMillis() > System.currentTimeMillis() - 86_400_000L) continue;
                            try (var query = c.prepareStatement("SELECT 1 FROM chat_images WHERE session_id=? AND id=?")) {
                                query.setString(1, session); query.setString(2, id);
                                try (var result = query.executeQuery()) { if (result.next()) continue; }
                            }
                            if (ChatSessionStore.isGeneratedSessionId(id) || (id.startsWith("upload-") && id.endsWith(".tmp")))
                                Files.deleteIfExists(file);
                        }
                    }
                }
                return null;
            });
        }
    }
}
