package com.selfanalyst.content.capture;

import com.selfanalyst.content.ocr.OcrEngine;
import com.sun.jna.platform.win32.WinDef.HWND;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContentCaptureTest {

    private final String previousTitleStripHeight =
            System.getProperty("ocr.title-strip-height");
    private final String previousForceRefresh =
            System.getProperty("ocr.force-refresh-ms");

    @AfterEach
    void restoreTitleStripHeight() {
        if (previousTitleStripHeight == null) {
            System.clearProperty("ocr.title-strip-height");
        } else {
            System.setProperty("ocr.title-strip-height", previousTitleStripHeight);
        }
        if (previousForceRefresh == null) {
            System.clearProperty("ocr.force-refresh-ms");
        } else {
            System.setProperty("ocr.force-refresh-ms", previousForceRefresh);
        }
    }

    @Test
    void wideTitleStripIsTiledWithoutReducingTextHeight() {
        System.setProperty("ocr.title-strip-height", "80");
        BufferedImage screenshot = solidImage(1582, 993, Color.WHITE);
        RecordingOcrEngine ocr = new RecordingOcrEngine(
                "WorkE\nui/index.html\nshared\n国",
                "shared\nWorkBuddy-个人中心\nDeepSeek|深度求索\n+");
        ContentCapture capture = new ContentCapture(
                new StubScreenCapturer(screenshot), ocr, null);

        ContentResult result = capture.capture(1L, "Weixin.exe", "Weixin", null, "");

        assertEquals(List.of(new Dimension(960, 80), new Dimension(686, 80)),
                ocr.inputSizes);
        assertEquals("WorkE\nui/index.html\nshared\nWorkBuddy-个人中心\nDeepSeek|深度求索",
                result.textContent());
        assertEquals("ocr", result.source());
    }

    @Test
    void fullWindowModeKeepsSingleCharacterText() {
        System.setProperty("ocr.title-strip-height", "0");
        BufferedImage screenshot = solidImage(1200, 100, Color.WHITE);
        RecordingOcrEngine ocr = new RecordingOcrEngine("+");
        ContentCapture capture = new ContentCapture(
                new StubScreenCapturer(screenshot), ocr, null);

        ContentResult result = capture.capture(1L, "Weixin.exe", "Weixin", null, "");

        assertEquals(List.of(new Dimension(960, 80)), ocr.inputSizes);
        assertEquals("+", result.textContent());
    }

    @Test
    void unchangedTitleStripReusesOcrButTitleStripChangesInvalidateIt() {
        System.setProperty("ocr.title-strip-height", "80");
        System.setProperty("ocr.force-refresh-ms", "60000");
        BufferedImage screenshot = solidImage(800, 200, Color.WHITE);
        RecordingOcrEngine ocr = new RecordingOcrEngine("first", "changed");
        MutableNanoClock clock = new MutableNanoClock();
        ContentCapture capture = new ContentCapture(
                new StubScreenCapturer(screenshot), ocr, null, clock);

        assertEquals("first", capture.capture(
                1L, "Weixin.exe", "Weixin", null, "").textContent());
        screenshot.setRGB(10, 150, Color.BLACK.getRGB());
        assertEquals("first", capture.capture(
                1L, "Weixin.exe", "Weixin", null, "").textContent());
        assertEquals(1, ocr.inputSizes.size(), "body-only changes must not rerun title OCR");

        screenshot.setRGB(10, 20, Color.BLACK.getRGB());
        assertEquals("changed", capture.capture(
                1L, "Weixin.exe", "Weixin", null, "").textContent());
        assertEquals(2, ocr.inputSizes.size());
    }

    @Test
    void unchangedImageIsForcedToRefreshAfterSixtySeconds() {
        System.setProperty("ocr.title-strip-height", "80");
        System.setProperty("ocr.force-refresh-ms", "60000");
        BufferedImage screenshot = solidImage(800, 200, Color.WHITE);
        RecordingOcrEngine ocr = new RecordingOcrEngine("first", "refreshed");
        MutableNanoClock clock = new MutableNanoClock();
        ContentCapture capture = new ContentCapture(
                new StubScreenCapturer(screenshot), ocr, null, clock);

        assertEquals("first", capture.capture(
                1L, "Weixin.exe", "Weixin", null, "").textContent());
        clock.advanceMillis(59_999);
        assertEquals("first", capture.capture(
                1L, "Weixin.exe", "Weixin", null, "").textContent());
        assertEquals(1, ocr.inputSizes.size());

        clock.advanceMillis(1);
        assertEquals("refreshed", capture.capture(
                1L, "Weixin.exe", "Weixin", null, "").textContent());
        assertEquals(2, ocr.inputSizes.size());
    }

    @Test
    void emptyOcrResultRetriesAfterFiveSeconds() {
        System.setProperty("ocr.title-strip-height", "80");
        System.setProperty("ocr.force-refresh-ms", "60000");
        BufferedImage screenshot = solidImage(800, 200, Color.WHITE);
        RecordingOcrEngine ocr = new RecordingOcrEngine("", "recovered");
        MutableNanoClock clock = new MutableNanoClock();
        ContentCapture capture = new ContentCapture(
                new StubScreenCapturer(screenshot), ocr, null, clock);

        assertEquals("", capture.capture(
                1L, "Weixin.exe", "Weixin", null, "").textContent());
        clock.advanceMillis(4_999);
        assertEquals("", capture.capture(
                1L, "Weixin.exe", "Weixin", null, "").textContent());
        assertEquals(1, ocr.inputSizes.size());

        clock.advanceMillis(1);
        assertEquals("recovered", capture.capture(
                1L, "Weixin.exe", "Weixin", null, "").textContent());
        assertEquals(2, ocr.inputSizes.size());
    }

    @Test
    void windowTitleChangeInvalidatesCachedOcrImmediately() {
        System.setProperty("ocr.title-strip-height", "80");
        BufferedImage screenshot = solidImage(800, 200, Color.WHITE);
        RecordingOcrEngine ocr = new RecordingOcrEngine("first", "second");
        MutableNanoClock clock = new MutableNanoClock();
        ContentCapture capture = new ContentCapture(
                new StubScreenCapturer(screenshot), ocr, null, clock);

        assertEquals("first", capture.capture(
                1L, "Weixin.exe", "Title A", null, "").textContent());
        assertEquals("second", capture.capture(
                1L, "Weixin.exe", "Title B", null, "").textContent());
        assertEquals(2, ocr.inputSizes.size());
    }

    @Test
    void cacheHitDoesNotCreateAnotherRawDebugSample(@TempDir Path tempDir) throws Exception {
        System.setProperty("ocr.title-strip-height", "80");
        BufferedImage screenshot = solidImage(800, 200, Color.WHITE);
        RecordingOcrEngine ocr = new RecordingOcrEngine("text");
        CountingSampleStore samples = new CountingSampleStore(tempDir);
        ContentCapture capture = new ContentCapture(
                new StubScreenCapturer(screenshot), ocr, samples, new MutableNanoClock());
        try {
            ContentResult first = capture.capture(
                    1L, "Weixin.exe", "Weixin", null, "");
            ContentResult cached = capture.capture(
                    1L, "Weixin.exe", "Weixin", null, "");

            assertNotNull(first.sampleId());
            assertNull(cached.sampleId());
            assertEquals(1, samples.submissions);
            assertEquals(1, ocr.inputSizes.size());
        } finally {
            capture.close();
        }
    }

    @Test
    void emptyResultRetryWindowStartsWhenSlowOcrCompletes() {
        System.setProperty("ocr.title-strip-height", "80");
        BufferedImage screenshot = solidImage(800, 200, Color.WHITE);
        MutableNanoClock clock = new MutableNanoClock();
        AtomicInteger calls = new AtomicInteger();
        OcrEngine slowEmptyOcr = image -> {
            calls.incrementAndGet();
            clock.advanceMillis(5_000);
            return "";
        };
        ContentCapture capture = new ContentCapture(
                new StubScreenCapturer(screenshot), slowEmptyOcr, null, clock);

        assertEquals("", capture.capture(
                1L, "Weixin.exe", "Weixin", null, "").textContent());
        assertEquals("", capture.capture(
                1L, "Weixin.exe", "Weixin", null, "").textContent());
        assertEquals(1, calls.get(), "empty-result retry delay starts after OCR completes");
    }

    private static BufferedImage solidImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static final class StubScreenCapturer extends ScreenCapturer {
        private final BufferedImage screenshot;

        private StubScreenCapturer(BufferedImage screenshot) {
            this.screenshot = screenshot;
        }

        @Override
        public BufferedImage captureWindow(HWND hwnd) {
            return screenshot;
        }
    }

    private static final class RecordingOcrEngine implements OcrEngine {
        private final List<String> responses;
        private final List<Dimension> inputSizes = new ArrayList<>();
        private int nextResponse;

        private RecordingOcrEngine(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String recognize(BufferedImage image) {
            inputSizes.add(new Dimension(image.getWidth(), image.getHeight()));
            return responses.get(nextResponse++);
        }
    }

    private static final class MutableNanoClock implements LongSupplier {
        private long nanos;

        @Override
        public long getAsLong() {
            return nanos;
        }

        private void advanceMillis(long millis) {
            nanos += millis * 1_000_000L;
        }
    }

    private static final class CountingSampleStore extends OcrSampleStore {
        private int submissions;

        private CountingSampleStore(Path dir) throws Exception {
            super(dir);
        }

        @Override
        public boolean submit(BufferedImage image, String app, String title,
                              String ocrText, int uiaChars, String sampleId, long ocrMs) {
            submissions++;
            return true;
        }
    }
}
