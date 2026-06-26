package com.selfanalyst.content.platform;

import com.selfanalyst.content.capture.ContentCapture;
import com.selfanalyst.content.capture.OcrSampleStore;
import com.selfanalyst.content.capture.ScreenCapturer;
import com.selfanalyst.content.ocr.OcrEngine;
import com.selfanalyst.content.ocr.PaddleOcrEngine;
import com.selfanalyst.content.ocr.TesseractOcrEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import com.selfanalyst.content.uia.UiaTreeWalker;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;

/**
 * Windows implementation of PlatformCapture using JNA User32,
 * UIAutomation, and Tess4J.
 * <p>
 * Wraps UIA walk in try-catch (fail → null tree, empty text).
 */
public class WindowsCapture implements PlatformCapture {

    private final UiaTreeWalker treeWalker;
    private final ScreenCapturer screenCapturer;
    private final OcrEngine ocrEngine;

    public WindowsCapture() {
        this.treeWalker = new UiaTreeWalker();
        this.screenCapturer = new ScreenCapturer();

        String ocrConfig = System.getProperty("aw.ocr.engine",
                System.getenv().getOrDefault("AW_OCR_ENGINE", "auto"));
        Path paddlePath = Path.of("tools/PaddleOCR-json/PaddleOCR-json.exe");
        boolean hasPaddle = Files.exists(paddlePath);
        OcrEngine engine = null;

        if ("paddle".equalsIgnoreCase(ocrConfig) && hasPaddle) {
            engine = new PaddleOcrEngine(paddlePath);
        } else if ("tesseract".equalsIgnoreCase(ocrConfig)) {
            try { engine = new TesseractOcrEngine(); } catch (Exception ignored) {}
        } else if ("auto".equalsIgnoreCase(ocrConfig)) {
            if (hasPaddle) {
                engine = new PaddleOcrEngine(paddlePath);
            } else {
                try { engine = new TesseractOcrEngine(); } catch (Exception ignored) {}
            }
        }

        if (engine == null) engine = image -> "";
        this.ocrEngine = engine;
    }

    @Override
    public ContentCapture createCapture() {
        return new ContentCapture(screenCapturer, ocrEngine, OcrSampleStore.createDefault());
    }

    @Override
    public String getActiveAppName() {
        try {
            HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) return "unknown";

            IntByReference pidRef = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            int pid = pidRef.getValue();

            return ProcessHandle.of(pid)
                .flatMap(ph -> ph.info().command())
                .map(cmd -> {
                    int idx = cmd.lastIndexOf('\\');
                    return idx >= 0 ? cmd.substring(idx + 1) : cmd;
                })
                .orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public String getActiveWindowTitle() {
        try {
            HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) return "";

            char[] buffer = new char[1024];
            int len = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
            if (len > 0) {
                return new String(buffer, 0, len).trim();
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public long getForegroundWindow() {
        try {
            HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            return hwnd != null ? com.sun.jna.Pointer.nativeValue(hwnd.getPointer()) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Walk the accessibility tree for a neutral window handle.
     * Wraps the walk in a try-catch; on failure returns empty result.
     *
     * @param handle Neutral window handle (HWND numeric value); 0 = none.
     * @return Walk result (never null; empty on failure).
     */
    public UiaTreeWalker.UiaWalkResult walkTree(long handle) {
        try {
            return treeWalker.walk(handle);
        } catch (Exception e) {
            return new UiaTreeWalker.UiaWalkResult("", null);
        }
    }
}
