package com.selfanalyst.content.capture;

import com.selfanalyst.content.ocr.OcrEngine;
import com.selfanalyst.content.thin.ThinDetector;
import com.selfanalyst.content.uia.UiaNode;
import com.sun.jna.platform.win32.WinDef.HWND;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrator that ties UIA tree analysis, thin detection,
 * screen capture, OCR, and hybrid merging together.
 * <p>
 * SPEC-WCH-002: capture() flow.
 */
public class ContentCapture {

    private static final Logger log = LoggerFactory.getLogger(ContentCapture.class);

    private final ThinDetector thin = new ThinDetector();
    private final HybridMerger merger = new HybridMerger();
    private final int titleStripHeight = parseTitleStripHeight();
    private final ScreenCapturer screen;
    private final OcrEngine ocr;
    private final OcrSampleStore sampleStore;

    /**
     * Create a ContentCapture with explicit dependencies.
     *
     * @param screen      Screen capturer (may be null).
     * @param ocr         OCR engine (may be null; OCR step skipped if null).
     * @param sampleStore OCR sample store (may be null; samples skipped if null).
     */
    public ContentCapture(ScreenCapturer screen, OcrEngine ocr, OcrSampleStore sampleStore) {
        this.screen = screen;
        this.ocr = ocr;
        this.sampleStore = sampleStore;
    }

    /** Backwards-compatible constructor — no sample store. */
    public ContentCapture(ScreenCapturer screen, OcrEngine ocr) {
        this(screen, ocr, null);
    }

    /**
     * Create a ContentCapture with default ScreenCapturer, TesseractOcrEngine,
     * and OcrSampleStore (path from OCR_SAMPLE_DIR env / ocr.sample.dir property).
     */
    public ContentCapture() {
        this.screen = new ScreenCapturer();
        OcrEngine engine;
        try {
            engine = new com.selfanalyst.content.ocr.TesseractOcrEngine();
        } catch (Exception e) {
            engine = null; // Tesseract not installed — OCR unavailable
        }
        this.ocr = engine;
        this.sampleStore = OcrSampleStore.createDefault();
    }

    /**
     * Run the full capture pipeline for the given window and UIA data.
     *
     * @param handle  Neutral window handle (used for screen capture when thin); 0 = none.
     * @param app     Application executable name.
     * @param title   Window title.
     * @param uiaTree UIA node tree (may be null).
     * @param uiaText UIA-extracted text (may be null).
     * @return ContentResult with merged text and metadata.
     */
    /** Returns true if this app or window title should be skipped entirely (no UIA, no OCR, no heartbeat). */
    public boolean isExcluded(String app, String title) {
        return thin.isExcluded(app) || thin.isTitleExcluded(title);
    }

    /** @deprecated Use {@link #isExcluded(String, String)} to also check window title. */
    @Deprecated
    public boolean isExcluded(String app) {
        return thin.isExcluded(app);
    }

    public ContentResult capture(long handle, String app, String title,
                                 UiaNode uiaTree, String uiaText) {
        if (uiaText == null) uiaText = "";
        int uiaChars = uiaText.length();

        // Build tree list from single root (or empty)
        List<UiaNode> tree = (uiaTree != null) ? List.of(uiaTree) : List.of();

        if (thin.isThin(tree, app, title, uiaChars)) {
            // Step 1: UIA Document title (SPEC-THN-005)
            // Electron/Tauri apps expose page title via ControlType=Document.Name when
            // accessibility is enabled. No screenshot needed — zero privacy risk.
            String docTitle = ThinDetector.extractDocumentTitle(tree);
            if (docTitle != null) {
                log.debug("Thin window resolved via UIA Document title [{}]", app);
                return ContentResult.noSample(docTitle, "uia", docTitle.length(), 0);
            }

            // Step 2: OCR title strip (SPEC-OCR-005)
            // Screenshot is cropped to the top titleStripHeight pixels only.
            // Body content is intentionally excluded to protect user privacy.
            try {
                String ocrText = "";
                String sampleId = null;
                if (ocr != null && ocr.isAvailable() && screen != null && handle != 0) {
                    HWND hwnd = new HWND(new com.sun.jna.Pointer(handle));
                    BufferedImage image = screen.captureWindow(hwnd);
                    if (image != null && !isBlank(image)) {
                        BufferedImage cropped = cropForOcr(app, image);
                        long t0 = System.currentTimeMillis();
                        ocrText = ocr.recognize(cropped);
                        long ocrMs = System.currentTimeMillis() - t0;
                        if (ocrText == null) ocrText = "";
                        log.debug("OCR title strip: {}ms, {} chars [{}]", ocrMs, ocrText.length(), app);
                        if (sampleStore != null) {
                            sampleId = UUID.randomUUID().toString();
                            sampleStore.submit(image, app, title, ocrText, uiaChars, sampleId, ocrMs);
                        }
                    }
                }
                int ocrChars = ocrText.length();
                String merged = merger.merge(uiaText, ocrText);
                String source = resolveSource(uiaText, ocrText);
                return new ContentResult(merged, source, uiaChars, ocrChars, sampleId);
            } catch (Exception e) {
                // SPEC-WCH-003: OCR failure → fall back to UIA
                return ContentResult.noSample(uiaText, uiaText.isEmpty() ? "" : "uia", uiaChars, 0);
            }
        } else {
            // Not thin → UIA text only
            return ContentResult.noSample(uiaText, "uia", uiaChars, 0);
        }
    }

    /** Returns true if the image is essentially all-black (failed or off-screen capture). */
    private static boolean isBlank(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int step = Math.max(1, Math.min(w, h) / 20);
        long sum = 0, count = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int rgb = image.getRGB(x, y);
                sum += ((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff);
                count++;
            }
        }
        return count == 0 || sum / count < 15;
    }

    /**
     * Trim large black borders before OCR.
     *
     * Some Electron apps (e.g. WXWork) use a full-screen transparent/black
     * window and render content only in a small region.  PaddleOCR's
     * limit_side_len=960 then downscales the full screen so the actual content
     * becomes ~90 px tall — too small for reliable recognition.
     *
     * Only applied to images larger than 800 K pixels.  Scans every {@code step}
     * pixels, excluding the bottom 50 px (taskbar area), to find the bounding
     * box of non-black content, then sub-images to that box.  If the content
     * already fills ≥ 85 % of the scan area the image is returned unchanged.
     */
    private static BufferedImage trimBlackBorders(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        if ((long) w * h < 800_000L) return image;

        int scanH = h - 50; // ignore taskbar strip at very bottom
        if (scanH <= 0) return image;
        int step = Math.max(4, Math.min(w, scanH) / 60);

        int minX = w, maxX = -1, minY = scanH, maxY = -1;
        for (int y = 0; y < scanH; y += step) {
            for (int x = 0; x < w; x += step) {
                int rgb = image.getRGB(x, y);
                int b = ((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff);
                if (b > 30) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0 || maxY < 0 || maxX <= minX || maxY <= minY) return image;
        // Skip trim if content already fills most of the image
        if ((maxX - minX) >= w * 0.85 && (maxY - minY) >= scanH * 0.85) return image;

        int pad = step * 2;
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(w - 1, maxX + pad);
        maxY = Math.min(h - 1, maxY + pad);
        return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /**
     * Crop known UI chrome from the screenshot before OCR.
     * Removes browser tab/address/bookmarks bars at the top, and IME candidate
     * strips at the bottom of terminal windows, which otherwise produce noise.
     * Pixel values are physical screen pixels at 100 % DPI (96 ppi); they are
     * generous enough to remain effective at up to 125 % scaling.
     */
    /** PaddleOCR's internal limit_side_len — pre-scale to match so PNG I/O is smaller. */
    private static final int PADDLE_LIMIT_SIDE = 960;

    private BufferedImage cropForOcr(String app, BufferedImage image) {
        image = trimBlackBorders(image);
        int w = image.getWidth(), h = image.getHeight();

        // Title-strip mode: only keep the topmost N pixels (app header / tab bar)
        // to capture context (what window/document is open) without body content.
        if (titleStripHeight > 0) {
            int stripH = Math.min(titleStripHeight, h);
            return scaleToOcrLimit(image.getSubimage(0, 0, w, stripH));
        }

        if (app == null) return scaleToOcrLimit(image);
        String lower = app.toLowerCase();
        int top = 0, bottom = 0;
        if (lower.contains("chrome") || lower.contains("msedge") || lower.contains("firefox")) {
            top = 110;   // tab bar + address bar + bookmarks bar
            bottom = 40; // taskbar bleed when window maximised over auto-hide taskbar
        } else if (lower.contains("windowsterminal")) {
            top = 38;    // terminal tab strip
            bottom = 80; // IME candidate bar + taskbar bleed
        }
        if (top + bottom >= h) return scaleToOcrLimit(image);
        return scaleToOcrLimit(image.getSubimage(0, top, w, h - top - bottom));
    }

    private static int parseTitleStripHeight() {
        try {
            return Math.max(0, Integer.parseInt(System.getProperty("ocr.title-strip-height", "80")));
        } catch (NumberFormatException e) {
            return 80;
        }
    }

    /** Scale so the longer side equals PADDLE_LIMIT_SIDE; no-op if already smaller. */
    private static BufferedImage scaleToOcrLimit(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int maxDim = Math.max(w, h);
        if (maxDim <= PADDLE_LIMIT_SIDE) return image;
        double scale = (double) PADDLE_LIMIT_SIDE / maxDim;
        int nw = (int) Math.round(w * scale);
        int nh = (int) Math.round(h * scale);
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static String resolveSource(String uiaText, String ocrText) {
        boolean hasUia = uiaText != null && !uiaText.isBlank();
        boolean hasOcr = ocrText != null && !ocrText.isBlank();
        if (hasUia && hasOcr) return "hybrid";
        if (hasUia) return "uia";
        if (hasOcr) return "ocr";
        return "uia"; // both empty, default
    }
}
