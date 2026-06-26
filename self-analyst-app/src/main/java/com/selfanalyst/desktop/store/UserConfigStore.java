package com.selfanalyst.desktop.store;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads/writes user-level config overrides at {@code {memoryDir}/config.properties}.
 * <p>
 * Lookup priority (highest first):
 * <ol>
 *   <li>User config file ({@code config.properties})</li>
 *   <li>Built-in classpath resource ({@code application.properties})</li>
 *   <li>Hard-coded defaults</li>
 * </ol>
 */
public class UserConfigStore {

    private static final Logger log = LoggerFactory.getLogger(UserConfigStore.class);

    private final Path filePath;
    private final Properties defaults;

    public UserConfigStore(Path memoryDir) {
        this.filePath = memoryDir.resolve("config.properties");
        this.defaults = loadClasspathDefaults();
    }

    /**
     * Returns the effective properties: classpath defaults merged with
     * user overrides (user wins).
     */
    public Properties load() {
        Properties merged = new Properties();
        merged.putAll(defaults);
        Properties user = loadUser();
        merged.putAll(user);
        return merged;
    }

    /**
     * Returns only the user-saved properties (no defaults mixed in).
     */
    public Properties loadUser() {
        Properties props = new Properties();
        if (Files.exists(filePath)) {
            // UTF-8 to match saveRaw()/save() which write UTF-8, so non-ASCII
            // values (中文 paths/model names) decode correctly at runtime.
            try (Reader r = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                log.warn("Failed to load user config: {}", e.getMessage());
            }
        }
        return props;
    }

    /**
     * Writes the given properties to the user config file atomically
     * (write to temp, then rename). Keys are grouped by section prefix
     * with blank lines between sections.
     */
    public void save(Properties updates) throws IOException {
        Files.createDirectories(filePath.getParent());
        Path tmp = filePath.getParent().resolve(filePath.getFileName() + ".tmp");

        // Section order and their title comments
        List<String> sectionOrder = List.of(
                "llm.", "aw.", "wiki.", "embedding.", "agent.", "desktop.");

        // Group keys by section prefix
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        for (String prefix : sectionOrder) {
            sections.put(prefix, new TreeMap<>());
        }
        Map<String, String> unsorted = new TreeMap<>(); // keys that don't match any section

        for (String key : updates.stringPropertyNames()) {
            boolean matched = false;
            for (String prefix : sectionOrder) {
                if (key.startsWith(prefix)) {
                    sections.get(prefix).put(key, updates.getProperty(key));
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                unsorted.put(key, updates.getProperty(key));
            }
        }

        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write("# SelfAnalyst Desktop User Config");
            w.newLine();

            boolean first = true;
            for (var entry : sections.entrySet()) {
                Map<String, String> keys = entry.getValue();
                if (keys.isEmpty()) continue;
                if (!first) {
                    w.newLine(); // blank line between sections
                }
                first = false;
                String sectionName = sectionPrefixToName(entry.getKey());
                w.write("# " + sectionName);
                w.newLine();
                for (var kv : keys.entrySet()) {
                    w.write(escapePropKey(kv.getKey()) + "=" + escapePropValue(kv.getValue()));
                    w.newLine();
                }
            }

            if (!unsorted.isEmpty()) {
                w.newLine();
                w.write("# Other");
                w.newLine();
                for (var kv : unsorted.entrySet()) {
                    w.write(escapePropKey(kv.getKey()) + "=" + escapePropValue(kv.getValue()));
                    w.newLine();
                }
            }
        }

        Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sectionPrefixToName(String prefix) {
        return switch (prefix) {
            case "llm." -> "LLM";
            case "aw." -> "ActivityWatch";
            case "wiki." -> "Wiki";
            case "embedding." -> "Embedding";
            case "agent." -> "Agent";
            case "desktop." -> "Desktop";
            default -> prefix.substring(0, prefix.length() - 1);
        };
    }

    /**
     * Escape special characters in a property key for .properties format.
     */
    private static String escapePropKey(String key) {
        return key.replace("\\", "\\\\")
                  .replace(" ", "\\ ")
                  .replace("\t", "\\t")
                  .replace("\f", "\\f")
                  .replace("=", "\\=")
                  .replace(":", "\\:")
                  .replace("#", "\\#")
                  .replace("!", "\\!");
    }

    /**
     * Escape special characters in a property value for .properties format.
     */
    private static String escapePropValue(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")
                    .replace("\f", "\\f");
    }

    /**
     * Returns a single key from the merged (effective) properties.
     */
    public String get(String key, String defaultValue) {
        return load().getProperty(key, defaultValue);
    }

    /**
     * Sets a single key in the user config and persists immediately.
     */
    public void set(String key, String value) throws IOException {
        Properties user = loadUser();
        if (value == null || value.isBlank()) {
            user.remove(key);
        } else {
            user.setProperty(key, value);
        }
        save(user);
    }

    /** Path to the underlying config file. */
    public Path filePath() {
        return filePath;
    }

    /**
     * Reads the raw user config file text verbatim (UTF-8), preserving comments,
     * blank lines and key order. Returns {@code ""} when the file does not exist.
     * <p>
     * Unlike {@link #loadUser()} (which parses via {@code Properties}), this is a
     * byte-faithful text read dedicated to the raw-edit path. SPEC-CFGUI-API-001a.
     */
    public String readRaw() throws IOException {
        if (!Files.exists(filePath)) {
            return "";
        }
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    /**
     * Writes the given text verbatim (UTF-8) to the user config file atomically
     * (temp file + rename), without section reordering, escaping or
     * {@code Properties.store}. The whole text lands on disk unchanged so that
     * {@code readRaw(saveRaw(x)) == x}. SPEC-CFGUI-API-002b.
     */
    public void saveRaw(String text) throws IOException {
        Files.createDirectories(filePath.getParent());
        Path tmp = filePath.getParent().resolve(filePath.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Properties loadClasspathDefaults() {
        Properties props = new Properties();
        try (InputStream in = UserConfigStore.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // No classpath resource – use empty defaults
        }
        return props;
    }
}
