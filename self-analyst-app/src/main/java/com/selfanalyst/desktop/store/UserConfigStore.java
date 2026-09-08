package com.selfanalyst.desktop.store;

import com.selfanalyst.config.SupportedKeys;
import com.selfanalyst.config.TomlSupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads/writes portable user config overrides at {@code ./data/config/config.toml}
 * (SPEC-TOML-FMT-001a). The internal namespace stays flat dotted keys; TOML
 * tables are flattened on load and regenerated on structured save via
 * {@link TomlSupport}.
 * <p>
 * Lookup priority (highest first):
 * <ol>
 *   <li>User config file ({@code config.toml})</li>
 *   <li>Built-in classpath resource ({@code application.properties})</li>
 *   <li>Hard-coded defaults</li>
 * </ol>
 */
public class UserConfigStore {

    private static final Logger log = LoggerFactory.getLogger(UserConfigStore.class);

    private final Path filePath;
    private final Properties defaults;
    private com.selfanalyst.config.ConfigApplicationService application;

    public synchronized com.selfanalyst.config.ConfigApplicationService application(com.selfanalyst.config.Config initial) {
        if (application == null) application = new com.selfanalyst.config.ConfigApplicationService(this, initial);
        return application;
    }
    public com.selfanalyst.config.ConfigApplicationService application() {
        return application(com.selfanalyst.config.Config.load(filePath.getParent()));
    }

    public UserConfigStore(Path configDir) {
        this.filePath = configDir.resolve("config.toml");
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
     * Returns only the user-saved properties (no defaults mixed in), parsed from
     * TOML and flattened to dotted keys. A missing/unreadable/invalid file yields
     * an empty set (same forgiving posture as before). SPEC-TOML-LOAD-002b.
     */
    public Properties loadUser() {
        Properties props = new Properties();
        if (Files.exists(filePath)) {
            try {
                // UTF-8 (TOML mandates it) so 中文 values decode correctly at runtime.
                String text = Files.readString(filePath, StandardCharsets.UTF_8);
                TomlSupport.parseAndFlatten(text).forEach(props::setProperty);
            } catch (IOException | RuntimeException e) {
                log.warn("Failed to load user config: {}", e.getMessage());
            }
        }
        return props;
    }

    /**
     * Regenerates the whole {@code config.toml} from the given properties and writes
     * it atomically (temp file + rename). Keys are grouped into section tables by
     * {@link TomlSupport#generateToml}; comments are not preserved (structured saves
     * never were). SPEC-TOML-API-002a/b, SPEC-TOML-DEC-004.
     */
    public void save(Properties updates) throws IOException {
        Files.createDirectories(filePath.getParent());
        Path tmp = filePath.getParent().resolve(filePath.getFileName() + ".tmp");

        // Sorted for deterministic, diff-friendly output within each section table.
        LinkedHashMap<String, String> flat = new LinkedHashMap<>();
        for (String key : new TreeSet<>(updates.stringPropertyNames())) {
            flat.put(key, updates.getProperty(key));
        }
        String toml = TomlSupport.generateToml(flat, SupportedKeys.types());

        Files.writeString(tmp, toml, StandardCharsets.UTF_8);
        Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
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
     * Unlike {@link #loadUser()} (which parses via TOML), this is a byte-faithful
     * text read dedicated to the raw-edit path. SPEC-TOML-API-001d (read side).
     */
    public String readRaw() throws IOException {
        if (!Files.exists(filePath)) {
            return "";
        }
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    /**
     * Writes the given text verbatim (UTF-8) to the user config file atomically
     * (temp file + rename), without reordering, escaping or regeneration. The whole
     * text lands on disk unchanged so that {@code readRaw(saveRaw(x)) == x}.
     * SPEC-TOML-API-001d (write side).
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
