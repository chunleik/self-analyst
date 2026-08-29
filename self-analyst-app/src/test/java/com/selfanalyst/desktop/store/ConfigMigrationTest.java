package com.selfanalyst.desktop.store;

import com.selfanalyst.config.TomlSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Startup migration trigger/idempotency/failure (SPEC-TOML-TST-008/009/010/002/011). */
class ConfigMigrationTest {

    private static void writeProps(Path dir, String content) throws Exception {
        Files.writeString(dir.resolve("config.properties"), content, StandardCharsets.UTF_8);
    }

    @Test
    void convertsAndRenames(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-008: toml generated and old file renamed .bak.
        writeProps(dir, "llm.model=gpt-4o\naw.port=5601\n");

        ConfigMigration.migrateIfNeeded(dir);

        Path toml = dir.resolve("config.toml");
        assertTrue(Files.exists(toml));
        assertFalse(Files.exists(dir.resolve("config.properties")));
        assertTrue(Files.exists(dir.resolve("config.properties.bak")));

        Map<String, String> flat = TomlSupport.parseAndFlatten(
                Files.readString(toml, StandardCharsets.UTF_8));
        assertEquals("gpt-4o", flat.get("llm.model"));
        assertEquals("5601", flat.get("aw.port"));

        assertFalse(Files.exists(dir.resolve("config-history.json")));
    }

    @Test
    void migrationPreservesExistingHistoryFile(@TempDir Path dir) throws Exception {
        byte[] sentinel = "legacy-history-sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("config-history.json"), sentinel);
        writeProps(dir, "llm.model=gpt-4o\n");

        ConfigMigration.migrateIfNeeded(dir);

        assertTrue(Files.exists(dir.resolve("config.toml")));
        assertEquals("legacy-history-sentinel",
                Files.readString(dir.resolve("config-history.json"), StandardCharsets.UTF_8));
    }

    @Test
    void noOpWhenTomlAlreadyExists(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-009: toml present → migration never runs; properties ignored.
        Files.writeString(dir.resolve("config.toml"), "[llm]\nmodel = \"existing\"\n",
                StandardCharsets.UTF_8);
        writeProps(dir, "llm.model=stale\n");

        ConfigMigration.migrateIfNeeded(dir);

        // toml untouched, properties not renamed.
        assertTrue(Files.exists(dir.resolve("config.properties")));
        assertFalse(Files.exists(dir.resolve("config.properties.bak")));
        assertTrue(Files.readString(dir.resolve("config.toml")).contains("existing"));
    }

    @Test
    void noOpWhenPropertiesAbsent(@TempDir Path dir) {
        ConfigMigration.migrateIfNeeded(dir); // nothing to do, must not throw
        assertFalse(Files.exists(dir.resolve("config.toml")));
    }

    @Test
    void chineseAndBackslashValuesSurviveConversion(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-002/011 migration half. In .properties a backslash escapes,
        // so a Windows path is written with an escaped separator (\\).
        writeProps(dir, "aw.data-dir=D:\\\\数据\\\\中文\nllm.model=智谱-glm\n");

        ConfigMigration.migrateIfNeeded(dir);

        Map<String, String> flat = TomlSupport.parseAndFlatten(
                Files.readString(dir.resolve("config.toml"), StandardCharsets.UTF_8));
        assertEquals("D:\\数据\\中文", flat.get("aw.data-dir"));
        assertEquals("智谱-glm", flat.get("llm.model"));
    }

    @Test
    void corruptedSourceLeavesFilesUntouchedWithoutThrowing(@TempDir Path dir) throws Exception {
        // SPEC-TOML-TST-010: a malformed unicode escape makes Properties.load throw;
        // migration must write no toml, not rename, and not propagate the error.
        // Backslash split from 'u' so the Java lexer doesn't parse it as an escape.
        writeProps(dir, "llm.model=" + "\\" + "uZZZZ\n");

        ConfigMigration.migrateIfNeeded(dir); // must not throw

        assertFalse(Files.exists(dir.resolve("config.toml")));
        assertFalse(Files.exists(dir.resolve("config.properties.bak")));
        assertTrue(Files.exists(dir.resolve("config.properties"))); // original intact
    }

    @Test
    void backupRenameFailureLeavesNoTomlHalfMigration(@TempDir Path dir) throws Exception {
        // If archiving config.properties fails after TOML has been generated, startup
        // must not leave config.toml behind, because a later run would treat migration
        // as complete and ignore the still-present properties file.
        writeProps(dir, "llm.model=gpt-4o\n");
        Path blockedBackup = dir.resolve("config.properties.bak");
        Files.createDirectory(blockedBackup);
        Files.writeString(blockedBackup.resolve("locked.txt"), "occupied", StandardCharsets.UTF_8);

        ConfigMigration.migrateIfNeeded(dir);

        assertFalse(Files.exists(dir.resolve("config.toml")));
        assertTrue(Files.exists(dir.resolve("config.properties")));
        assertTrue(Files.isDirectory(dir.resolve("config.properties.bak")));
    }

    @Test
    void secondInvocationIsNoOp(@TempDir Path dir) throws Exception {
        writeProps(dir, "llm.model=gpt-4o\n");
        ConfigMigration.migrateIfNeeded(dir);
        String firstToml = Files.readString(dir.resolve("config.toml"), StandardCharsets.UTF_8);

        // A stray properties file reappearing must not re-trigger while toml exists.
        writeProps(dir, "llm.model=should-be-ignored\n");
        ConfigMigration.migrateIfNeeded(dir);

        assertEquals(firstToml, Files.readString(dir.resolve("config.toml"), StandardCharsets.UTF_8));
        assertTrue(Files.exists(dir.resolve("config.properties"))); // left as-is
    }
}
