package com.selfanalyst.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventServerInitializationOrderTest {

    @Test
    void unsafeRawInitializationFailsBeforeProjectionDatabaseIsCreated(@TempDir Path dir)
            throws Exception {
        Path target = Files.createDirectory(dir.resolve("outside"));
        Path data = Files.createDirectory(dir.resolve("data"));
        Path raw = data.resolve("raw");
        try {
            Files.createSymbolicLink(raw, target);
        } catch (Exception unavailable) {
            Process junction = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    raw.toString(), target.toString()).redirectErrorStream(true).start();
            if (junction.waitFor() != 0) throw unavailable;
        }

        try {
            assertThrows(IllegalStateException.class, () -> new EventServer(data, 0, null));
            assertFalse(Files.exists(data.resolve("events.db")));
        } finally {
            try {
                Files.deleteIfExists(raw);
            } catch (Exception junctionDelete) {
                new ProcessBuilder("cmd.exe", "/c", "rmdir", raw.toString()).start().waitFor();
            }
        }
    }
}
