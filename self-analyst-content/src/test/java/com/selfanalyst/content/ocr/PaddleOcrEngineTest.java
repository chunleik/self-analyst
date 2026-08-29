package com.selfanalyst.content.ocr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaddleOcrEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldMatchOcrProcessStartedFromSelfAnalystExecutable() {
        Path executable = tempDir.resolve("self-analyst/tools/PaddleOCR-json/PaddleOCR-json.exe");

        assertTrue(PaddleOcrEngine.isOrphanedSelfAnalystOcrProcess(
                executable,
                Optional.of(executable.toAbsolutePath().normalize().toString()),
                false));
    }

    @Test
    void shouldNotMatchSameNamedExecutableOutsideSelfAnalyst() {
        Path executable = tempDir.resolve("self-analyst/tools/PaddleOCR-json/PaddleOCR-json.exe");
        Path externalExecutable = tempDir.resolve("other/PaddleOCR-json.exe");

        assertFalse(PaddleOcrEngine.isOrphanedSelfAnalystOcrProcess(
                executable,
                Optional.of(externalExecutable.toAbsolutePath().normalize().toString()),
                false));
    }

    @Test
    void shouldNotMatchProcessWhenExecutablePathIsUnavailable() {
        Path executable = tempDir.resolve("self-analyst/tools/PaddleOCR-json/PaddleOCR-json.exe");

        assertFalse(PaddleOcrEngine.isOrphanedSelfAnalystOcrProcess(
                executable,
                Optional.empty(),
                false));
    }

    @Test
    void shouldNotMatchOcrProcessOwnedByAnotherActiveSelfAnalystInstance() {
        Path executable = tempDir.resolve("self-analyst/tools/PaddleOCR-json/PaddleOCR-json.exe");

        assertFalse(PaddleOcrEngine.isOrphanedSelfAnalystOcrProcess(
                executable,
                Optional.of(executable.toAbsolutePath().normalize().toString()),
                true));
    }
}
