package com.selfanalyst.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LegacyFileSemanticIndexPurgerTest {

    @Test
    void removesValidatedDedicatedLuceneIndex(@TempDir Path tempDir) throws Exception {
        Path index = Files.createDirectories(tempDir.resolve("file-semantic-index"));
        writeCommit(index.resolve("segments_1"));
        Files.writeString(index.resolve("_0.cfs"), "legacy vector");
        Files.writeString(index.resolve("write.lock"), "");

        assertEquals(3, LegacyFileSemanticIndexPurger.purge(index));
        assertFalse(Files.exists(index));
    }

    @Test
    void refusesMixedDirectoryWithoutDeletingAnything(@TempDir Path tempDir) throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("ordinary-directory"));
        Path segment = Files.writeString(directory.resolve("segments_1"), "legacy summary");
        Path underscore = Files.writeString(directory.resolve("_notes.txt"), "unrelated");
        Path ordinary = Files.writeString(directory.resolve("notes.txt"), "unrelated");

        assertThrows(IOException.class, () -> LegacyFileSemanticIndexPurger.purge(directory));
        assertTrue(Files.exists(segment));
        assertTrue(Files.exists(underscore));
        assertTrue(Files.exists(ordinary));
    }

    @Test
    void commitLikeOrdinaryNamesDoNotValidateDirectory(@TempDir Path tempDir) throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("ordinary-directory"));
        Path fakeCommit = Files.writeString(directory.resolve("segments_backup"), "ordinary");
        Path fakeSegment = Files.writeString(directory.resolve("_notes.txt"), "ordinary");

        assertThrows(IOException.class, () -> LegacyFileSemanticIndexPurger.purge(directory));
        assertTrue(Files.exists(fakeCommit));
        assertTrue(Files.exists(fakeSegment));
    }

    @Test
    void interruptedDeletionKeepsCommitPointForSafeRetry(@TempDir Path tempDir) throws Exception {
        Path index = Files.createDirectories(tempDir.resolve("file-semantic-index"));
        Path first = Files.writeString(index.resolve("_0.cfs"), "legacy vector");
        Path second = Files.writeString(index.resolve("_1.cfs"), "legacy vector");
        Path commit = writeCommit(index.resolve("segments_1"));

        assertThrows(IOException.class, () -> LegacyFileSemanticIndexPurger.purge(index, path -> {
            if (path.equals(second)) throw new IOException("simulated locked artifact");
            Files.delete(path);
        }));
        assertFalse(Files.exists(first));
        assertTrue(Files.exists(second));
        assertTrue(Files.exists(commit), "commit point must be deleted last");

        assertEquals(2, LegacyFileSemanticIndexPurger.purge(index));
        assertFalse(Files.exists(index));
    }

    @Test
    void refusesFilesystemRoot(@TempDir Path tempDir) {
        Path root = tempDir.toAbsolutePath().getRoot();
        assertThrows(IOException.class, () -> LegacyFileSemanticIndexPurger.purge(root));
    }

    private static Path writeCommit(Path path) throws IOException {
        return Files.write(path, ByteBuffer.allocate(4).putInt(0x3fd76c17).array());
    }
}
