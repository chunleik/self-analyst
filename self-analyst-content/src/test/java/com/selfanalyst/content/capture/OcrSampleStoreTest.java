package com.selfanalyst.content.capture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrSampleStoreTest {

    @TempDir
    Path tempDir;

    private final String previousEnabled = System.getProperty("ocr.sample.enabled");
    private final String previousDir = System.getProperty("ocr.sample.dir");

    @AfterEach
    void restoreProperties() {
        restoreProperty("ocr.sample.enabled", previousEnabled);
        restoreProperty("ocr.sample.dir", previousDir);
    }

    @Test
    void debugSamplesAreDisabledByDefault() {
        Path sampleDir = tempDir.resolve("disabled-samples");
        System.clearProperty("ocr.sample.enabled");
        System.setProperty("ocr.sample.dir", sampleDir.toString());

        OcrSampleStore store = OcrSampleStore.createDefault();
        try {
            assertNull(store);
            assertFalse(Files.exists(sampleDir));
        } finally {
            if (store != null) store.close();
        }
    }

    @Test
    void enabledDebugSamplesKeepTheOriginalScreenshot() throws Exception {
        Path sampleDir = tempDir.resolve("enabled-samples");
        System.setProperty("ocr.sample.enabled", "true");
        System.setProperty("ocr.sample.dir", sampleDir.toString());
        BufferedImage original = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);

        OcrSampleStore store = OcrSampleStore.createDefault();
        assertNotNull(store);
        assertTrue(store.submit(original, "app", "title", "text", 0, "sample", 1));
        store.close();

        BufferedImage saved = ImageIO.read(sampleDir.resolve("slot_00.png").toFile());
        assertEquals(320, saved.getWidth());
        assertEquals(240, saved.getHeight());
        String metadata = Files.readString(sampleDir.resolve("slot_00.json"));
        assertTrue(metadata.contains("\"sample_id\":\"sample\""));
        assertTrue(metadata.contains("\"ocr_text\":\"text\""));
        assertFalse(store.submit(original, "app", "title", "late", 0, "late", 1));
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
