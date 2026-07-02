package com.selfanalyst.desktop.store;

import com.selfanalyst.config.SupportedKeys;
import com.selfanalyst.config.TomlSupport;

import java.io.IOException;
import java.io.Reader;
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
 * One-shot startup migration of the user-level config from {@code config.properties}
 * to {@code config.toml} (SPEC-TOML-MIG-001). Runs before the config is first read.
 * Idempotent: once {@code config.toml} exists it never touches anything again.
 * <p>
 * Any failure while parsing/writing/renaming is isolated — the partial temp file is
 * removed, both original files are left untouched, and startup continues (the loader
 * then falls back to {@code config.properties} per SPEC-TOML-MIG-002a). The history
 * snapshot is best-effort and does not fail the migration.
 */
public final class ConfigMigration {

    private static final Logger log = LoggerFactory.getLogger(ConfigMigration.class);

    /** Deterministic summary for the auto-migration snapshot. SPEC-TOML-MIG-001d. */
    static final String MIGRATION_SUMMARY = "从 config.properties 自动迁移";

    private ConfigMigration() {}

    /**
     * Convert {@code {memoryDir}/config.properties} → {@code config.toml} exactly once.
     * No-op when {@code config.toml} already exists or {@code config.properties} is
     * absent. SPEC-TOML-MIG-001a/b/c/d.
     */
    public static void migrateIfNeeded(Path memoryDir) {
        Path toml = memoryDir.resolve("config.toml");
        Path properties = memoryDir.resolve("config.properties");

        if (Files.exists(toml)) {
            return; // already migrated — stale properties (if any) is ignored
        }
        if (!Files.exists(properties)) {
            return; // nothing to migrate
        }

        Path tmp = memoryDir.resolve("config.toml.tmp");
        try {
            // Parse old file with UTF-8 (matches SPEC-CFGUI-DEC-005 write encoding).
            Properties old = new Properties();
            try (Reader r = Files.newBufferedReader(properties, StandardCharsets.UTF_8)) {
                old.load(r);
            }

            LinkedHashMap<String, String> flat = new LinkedHashMap<>();
            for (String key : new TreeSet<>(old.stringPropertyNames())) {
                flat.put(key, old.getProperty(key));
            }
            String tomlText = TomlSupport.generateToml(flat, SupportedKeys.types());

            // Write TOML to a temp file first, archive the old file, then publish
            // config.toml. Publishing last avoids a half-migrated state where TOML
            // exists but the old properties file could not be archived.
            Files.writeString(tmp, tomlText, StandardCharsets.UTF_8);
            Path backup = memoryDir.resolve("config.properties.bak");
            Files.move(properties, backup, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, toml, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                try {
                    Files.move(backup, properties, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException restore) {
                    log.error("config.properties 迁移失败后恢复备份也失败: {}", restore.getMessage());
                }
                throw e;
            }
            log.info("已将 config.properties 迁移为 config.toml（原文件备份为 config.properties.bak）");

            recordSnapshot(memoryDir, tomlText);
        } catch (Exception e) {
            // Isolate the failure: drop any partial temp, leave both files intact.
            log.error("config.properties → config.toml 迁移失败，保持原文件不变: {}", e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {}
        }
    }

    /** Best-effort migration history snapshot; its failure does not fail migration. */
    private static void recordSnapshot(Path memoryDir, String tomlText) {
        try {
            String name = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .format(java.time.Instant.now().atZone(java.time.ZoneId.systemDefault()));
            new ConfigHistoryStore(memoryDir).add(
                    name, MIGRATION_SUMMARY, tomlText, ConfigHistoryStore.FORMAT_TOML);
        } catch (Exception e) {
            log.warn("迁移快照写入失败（迁移本身已成功）: {}", e.getMessage());
        }
    }
}
