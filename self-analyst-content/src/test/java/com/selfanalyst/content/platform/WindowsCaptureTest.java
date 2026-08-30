package com.selfanalyst.content.platform;

import com.selfanalyst.content.ocr.OcrEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class WindowsCaptureTest {

    @Test
    void offDoesNotCreateOcrEvenWhenPaddleExists(@TempDir Path dir) throws Exception {
        Path paddle = Files.createFile(dir.resolve("PaddleOCR-json.exe"));

        assertNull(WindowsCapture.createOcrEngine("off", paddle));
    }

    @Test
    void missingExplicitPaddleDegradesToUia(@TempDir Path dir) {
        assertNull(WindowsCapture.createOcrEngine(
                "paddle", dir.resolve("missing-PaddleOCR-json.exe")));
    }

    @Test
    void unknownModeDegradesToUia(@TempDir Path dir) {
        assertNull(WindowsCapture.createOcrEngine("unknown", dir.resolve("unused.exe")));
    }

    @Test
    void autoFallsBackWhenExistingPaddleCannotStart(@TempDir Path dir) throws Exception {
        Path paddle = Files.createFile(dir.resolve("PaddleOCR-json.exe"));
        var unavailablePaddle = new OcrEngine() {
            @Override
            public String recognize(BufferedImage image) {
                return "";
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
        OcrEngine tesseract = image -> "fallback";

        var selected = WindowsCapture.createOcrEngine(
                "auto", paddle, ignored -> unavailablePaddle, () -> tesseract);

        assertSame(tesseract, selected);
    }
}
