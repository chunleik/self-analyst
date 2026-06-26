package com.selfanalyst.aw.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    @Test
    void rejectsBucketIdsThatWouldEscapeDataDirectory(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("aw-data");
        try (Database db = new Database(dataDir)) {
            assertThrows(IllegalArgumentException.class,
                    () -> db.bucketConnection("..\\outside"));
            assertThrows(IllegalArgumentException.class,
                    () -> db.bucketConnection("../outside"));
        }
        assertTrue(Files.notExists(dir.resolve("outside.db")));
    }
}
