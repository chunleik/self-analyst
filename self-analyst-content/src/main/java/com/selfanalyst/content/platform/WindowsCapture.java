package com.selfanalyst.content.platform;

import com.selfanalyst.content.capture.ContentCapture;
import com.selfanalyst.content.capture.OcrSampleStore;
import com.selfanalyst.content.capture.ScreenCapturer;
import com.selfanalyst.content.ocr.OcrEngine;
import com.selfanalyst.content.ocr.PaddleOcrEngine;
import com.selfanalyst.content.ocr.TesseractOcrEngine;
import com.selfanalyst.content.uia.UiaTreeWalker;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Windows implementation of PlatformCapture using JNA User32 and UIAutomation,
 * with optional OCR enhancement.
 * <p>
 * Wraps UIA walk in try-catch (fail → null tree, empty text).
 */
public class WindowsCapture implements PlatformCapture {

    private static final Logger log = LoggerFactory.getLogger(WindowsCapture.class);

    private final UiaTreeWalker treeWalker;
    private final ScreenCapturer screenCapturer;
    private final OcrEngine ocrEngine;

    public WindowsCapture() {
        this.treeWalker = new UiaTreeWalker();
        this.screenCapturer = new ScreenCapturer();

        String ocrConfig = System.getProperty("aw.ocr.engine",
                System.getenv().getOrDefault("AW_OCR_ENGINE", "off"));
        Path paddlePath = Path.of("tools/PaddleOCR-json/PaddleOCR-json.exe");
        this.ocrEngine = createOcrEngine(ocrConfig, paddlePath);
    }

    @Override
    public ContentCapture createCapture() {
        return new ContentCapture(screenCapturer, ocrEngine, OcrSampleStore.createDefault());
    }

    /** Select the explicitly configured optional OCR engine; {@code off} returns null. */
    static OcrEngine createOcrEngine(String configured, Path paddlePath) {
        return createOcrEngine(
                configured, paddlePath, PaddleOcrEngine::new, WindowsCapture::createTesseract);
    }

    static OcrEngine createOcrEngine(String configured, Path paddlePath,
                                     Function<Path, OcrEngine> paddleFactory,
                                     Supplier<OcrEngine> tesseractFactory) {
        String mode = configured == null ? "off"
                : configured.strip().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "off" -> null;
            case "paddle" -> createPaddle(paddlePath, paddleFactory, true);
            case "tesseract" -> tesseractFactory.get();
            case "auto" -> {
                OcrEngine paddle = createPaddle(paddlePath, paddleFactory, false);
                yield paddle != null ? paddle : tesseractFactory.get();
            }
            default -> {
                log.warn("忽略未知 OCR 引擎配置 '{}'；继续使用 UIA", configured);
                yield null;
            }
        };
    }

    private static OcrEngine createPaddle(Path paddlePath,
                                          Function<Path, OcrEngine> paddleFactory,
                                          boolean warnIfMissing) {
        if (!Files.exists(paddlePath)) {
            if (warnIfMissing) {
                log.warn("屏幕 OCR 已配置为 paddle，但未找到 {}；继续使用 UIA", paddlePath);
            }
            return null;
        }
        try {
            OcrEngine engine = paddleFactory.apply(paddlePath);
            return engine != null && engine.isAvailable() ? engine : null;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static OcrEngine createTesseract() {
        try {
            TesseractOcrEngine engine = new TesseractOcrEngine();
            return engine.isAvailable() ? engine : null;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    @Override
    public ForegroundWindow getForegroundWindowInfo() {
        try {
            HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) return ForegroundWindow.none();

            long handle = com.sun.jna.Pointer.nativeValue(hwnd.getPointer());
            return new ForegroundWindow(handle, appName(hwnd), windowTitle(hwnd));
        } catch (Exception e) {
            return ForegroundWindow.none();
        }
    }

    private static String appName(HWND hwnd) {
        try {
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

    private static String windowTitle(HWND hwnd) {
        try {
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
