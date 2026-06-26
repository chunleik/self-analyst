package com.selfanalyst.desktop.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores recent snapshots of the user config text at
 * {@code {memoryDir}/config-history.json}. Each successful raw save appends a
 * version; only the most recent {@link #MAX_VERSIONS} are retained (oldest
 * pruned). SPEC-CFGUI-VER-DEC-001/003.
 */
public class ConfigHistoryStore {

    /** Retention cap: keep the most recent N versions. */
    public static final int MAX_VERSIONS = 10;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;

    /** A single saved snapshot. Newest-first ordering is preserved on disk. */
    public record ConfigVersion(String id, String name, String summary, long savedAt, String text) {}

    /** Manifest wrapper for (de)serialization. */
    private record Manifest(List<ConfigVersion> versions) {}

    public ConfigHistoryStore(Path memoryDir) {
        this.filePath = memoryDir.resolve("config-history.json");
    }

    /** All versions, newest-first. Empty when no history yet. */
    public synchronized List<ConfigVersion> list() {
        return new ArrayList<>(readAll());
    }

    /** Look up a version by id. */
    public synchronized Optional<ConfigVersion> get(String id) {
        for (ConfigVersion v : readAll()) {
            if (v.id().equals(id)) return Optional.of(v);
        }
        return Optional.empty();
    }

    /**
     * Append a new snapshot (newest-first) and prune to {@link #MAX_VERSIONS}.
     * SPEC-CFGUI-VER-API-003.
     */
    public synchronized ConfigVersion add(String name, String summary, String text) throws IOException {
        List<ConfigVersion> versions = new ArrayList<>(readAll());
        ConfigVersion v = new ConfigVersion(
                UUID.randomUUID().toString(), name, summary, System.currentTimeMillis(),
                redactSensitiveValues(text));
        versions.add(0, v); // newest first
        while (versions.size() > MAX_VERSIONS) {
            versions.remove(versions.size() - 1); // drop oldest
        }
        writeAll(versions);
        return v;
    }

    /** Replace a version's summary (used by async LLM refinement). No-op if id gone. */
    public synchronized void updateSummary(String id, String summary) throws IOException {
        List<ConfigVersion> versions = new ArrayList<>(readAll());
        boolean changed = false;
        for (int i = 0; i < versions.size(); i++) {
            ConfigVersion v = versions.get(i);
            if (v.id().equals(id)) {
                versions.set(i, new ConfigVersion(v.id(), v.name(), summary, v.savedAt(), v.text()));
                changed = true;
                break;
            }
        }
        if (changed) writeAll(versions);
    }

    /** Underlying manifest file path. */
    public Path filePath() {
        return filePath;
    }

    private List<ConfigVersion> readAll() {
        if (!Files.exists(filePath)) return List.of();
        try {
            String json = Files.readString(filePath, StandardCharsets.UTF_8);
            if (json.isBlank()) return List.of();
            Manifest m = MAPPER.readValue(json, new TypeReference<Manifest>() {});
            if (m.versions() == null) return List.of();
            List<ConfigVersion> redacted = new ArrayList<>();
            for (ConfigVersion v : m.versions()) {
                redacted.add(new ConfigVersion(v.id(), v.name(), v.summary(), v.savedAt(),
                        redactSensitiveValues(v.text())));
            }
            return redacted;
        } catch (IOException e) {
            return List.of(); // corrupt/unreadable history must not break config editing
        }
    }

    static String redactSensitiveValues(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("(?<=\\n)", -1)) {
            out.append(redactSensitiveLine(line));
        }
        return out.toString();
    }

    private static String redactSensitiveLine(String line) {
        if (line == null || line.isEmpty()) return "";
        String ending = "";
        String body = line;
        if (body.endsWith("\n")) {
            ending = "\n";
            body = body.substring(0, body.length() - 1);
            if (body.endsWith("\r")) {
                ending = "\r\n";
                body = body.substring(0, body.length() - 1);
            }
        }
        String trimmed = body.stripLeading();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return line;
        }
        int eq = body.indexOf('=');
        int colon = body.indexOf(':');
        int sep = eq;
        if (sep < 0 || (colon >= 0 && colon < sep)) {
            sep = colon;
        }
        if (sep < 0) return line;
        String key = body.substring(0, sep).trim().toLowerCase();
        if (!isSensitiveKey(key)) return line;
        return body.substring(0, sep + 1) + ending;
    }

    private static boolean isSensitiveKey(String key) {
        return key.endsWith("api-key") || key.endsWith("apikey")
                || key.endsWith("token") || key.endsWith("secret");
    }

    private void writeAll(List<ConfigVersion> versions) throws IOException {
        Files.createDirectories(filePath.getParent());
        Path tmp = filePath.getParent().resolve(filePath.getFileName() + ".tmp");
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(new Manifest(versions));
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
    }
}
