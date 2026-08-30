package com.selfanalyst.content.capture;

import com.selfanalyst.content.ocr.OcrEngine;
import com.selfanalyst.content.thin.ThinDetector;
import com.selfanalyst.content.uia.UiaNode;
import com.sun.jna.platform.win32.WinDef.HWND;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Orchestrator that ties UIA tree analysis, thin detection,
 * screen capture, OCR, and hybrid merging together.
 * <p>
 * SPEC-WCH-002: capture() flow.
 */
public class ContentCapture implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ContentCapture.class);

    private final ThinDetector thin = new ThinDetector();
    private final HybridMerger merger = new HybridMerger();
    private final int titleStripHeight = parseTitleStripHeight();
    private final ScreenCapturer screen;
    private final OcrEngine ocr;
    private final OcrSampleStore sampleStore;
    private final LongSupplier nanoTime;
    private final long forceRefreshNanos;
    private OcrCacheEntry ocrCache;

    private static final long DEFAULT_FORCE_REFRESH_MS = 60_000L;
    private static final long EMPTY_OCR_RETRY_MS = 5_000L;

    private record OcrCacheEntry(long handle, String app, String title,
                                 String fingerprint, String text, long recognizedAtNanos) {}

    /**
     * Create a ContentCapture with explicit dependencies.
     *
     * @param screen      Screen capturer (may be null).
     * @param ocr         OCR engine (may be null; OCR step skipped if null).
     * @param sampleStore OCR sample store (may be null; samples skipped if null).
     */
    public ContentCapture(ScreenCapturer screen, OcrEngine ocr, OcrSampleStore sampleStore) {
        this(screen, ocr, sampleStore, System::nanoTime);
    }

    ContentCapture(ScreenCapturer screen, OcrEngine ocr, OcrSampleStore sampleStore,
                   LongSupplier nanoTime) {
        this.screen = screen;
        this.ocr = ocr;
        this.sampleStore = sampleStore;
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.forceRefreshNanos = parseForceRefreshMs() * 1_000_000L;
    }

    /** Backwards-compatible constructor — no sample store. */
    public ContentCapture(ScreenCapturer screen, OcrEngine ocr) {
        this(screen, ocr, null);
    }

    /**
     * Create a UIA-only ContentCapture with the default ScreenCapturer.
     * Optional OCR is selected by the platform integration, not by this convenience
     * constructor, so the privacy-safe default cannot be bypassed.
     */
    public ContentCapture() {
        this.screen = new ScreenCapturer();
        this.ocr = null;
        this.sampleStore = null;
        this.nanoTime = System::nanoTime;
        this.forceRefreshNanos = parseForceRefreshMs() * 1_000_000L;
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
        String contextTitle = ContextTitleExtractor.extract(app, uiaText);

        // Build tree list from single root (or empty)
        List<UiaNode> tree = (uiaTree != null) ? List.of(uiaTree) : List.of();

        if (thin.isThin(tree, app, title, uiaChars)) {
            // Step 1: UIA Document title (SPEC-THN-005)
            // Electron/Tauri apps expose page title via ControlType=Document.Name when
            // accessibility is enabled. No screenshot needed — zero privacy risk.
            String docTitle = ThinDetector.extractDocumentTitle(tree);
            if (docTitle != null) {
                log.debug("Thin window resolved via UIA Document title [{}]", app);
                return ContentResult.noSample(
                        docTitle, "uia", docTitle.length(), 0, contextTitle);
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
                        List<BufferedImage> ocrInputs = cropForOcr(app, image);
                        String fingerprint = fingerprintOcrInputs(ocrInputs);
                        long nowNanos = nanoTime.getAsLong();
                        String cachedText = reusableOcrText(
                                handle, app, title, fingerprint, nowNanos);
                        long ocrMs = 0L;
                        boolean recognizedNow = cachedText == null;
                        if (recognizedNow) {
                            long t0 = System.currentTimeMillis();
                            ocrText = recognizeOcrInputs(ocrInputs, titleStripHeight > 0);
                            ocrMs = System.currentTimeMillis() - t0;
                            if (ocrText == null) ocrText = "";
                            ocrCache = new OcrCacheEntry(
                                    handle, app, title, fingerprint, ocrText,
                                    nanoTime.getAsLong());
                            log.debug("OCR title strip: {}ms, {} chars, {} tile(s) [{}]",
                                    ocrMs, ocrText.length(), ocrInputs.size(), app);
                        } else {
                            ocrText = cachedText;
                            log.debug("OCR title strip cache hit: {} chars [{}]",
                                    ocrText.length(), app);
                        }
                        if (recognizedNow && sampleStore != null) {
                            sampleId = UUID.randomUUID().toString();
                            if (!sampleStore.submit(
                                    image, app, title, ocrText, uiaChars, sampleId, ocrMs)) {
                                sampleId = null;
                            }
                        }
                    }
                }
                int ocrChars = ocrText.length();
                String merged = merger.merge(uiaText, ocrText);
                String source = resolveSource(uiaText, ocrText);
                return new ContentResult(
                        merged, source, uiaChars, ocrChars, contextTitle, sampleId);
            } catch (Exception e) {
                // SPEC-WCH-003: OCR failure → fall back to UIA
                return ContentResult.noSample(
                        uiaText, uiaText.isEmpty() ? "" : "uia", uiaChars, 0, contextTitle);
            }
        } else {
            // Not thin → UIA text only
            return ContentResult.noSample(uiaText, "uia", uiaChars, 0, contextTitle);
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
    /** Context shared by adjacent wide-title-strip tiles to avoid cutting text at a boundary. */
    private static final int OCR_TILE_OVERLAP = 64;

    private List<BufferedImage> cropForOcr(String app, BufferedImage image) {
        image = trimBlackBorders(image);
        int w = image.getWidth(), h = image.getHeight();

        // Title-strip mode: only keep the topmost N pixels (app header / tab bar)
        // to capture context (what window/document is open) without body content. Wide,
        // shallow strips are tiled before OCR so the 960px detector limit does not shrink
        // an 80px-high strip into unreadably small text.
        if (titleStripHeight > 0) {
            int stripH = Math.min(titleStripHeight, h);
            return tileWideTitleStrip(image.getSubimage(0, 0, w, stripH));
        }

        if (app == null) return List.of(scaleToOcrLimit(image));
        String lower = app.toLowerCase();
        int top = 0, bottom = 0;
        if (lower.contains("chrome") || lower.contains("msedge") || lower.contains("firefox")) {
            top = 110;   // tab bar + address bar + bookmarks bar
            bottom = 40; // taskbar bleed when window maximised over auto-hide taskbar
        } else if (lower.contains("windowsterminal")) {
            top = 38;    // terminal tab strip
            bottom = 80; // IME candidate bar + taskbar bleed
        }
        if (top + bottom >= h) return List.of(scaleToOcrLimit(image));
        return List.of(scaleToOcrLimit(image.getSubimage(0, top, w, h - top - bottom)));
    }

    private static List<BufferedImage> tileWideTitleStrip(BufferedImage strip) {
        if (strip.getWidth() <= PADDLE_LIMIT_SIDE) return List.of(strip);

        List<BufferedImage> tiles = new ArrayList<>();
        int step = PADDLE_LIMIT_SIDE - OCR_TILE_OVERLAP;
        for (int x = 0; x < strip.getWidth(); x += step) {
            int tileWidth = Math.min(PADDLE_LIMIT_SIDE, strip.getWidth() - x);
            tiles.add(strip.getSubimage(x, 0, tileWidth, strip.getHeight()));
            if (x + tileWidth >= strip.getWidth()) break;
        }
        return tiles;
    }

    private String reusableOcrText(long handle, String app, String title,
                                   String fingerprint, long nowNanos) {
        OcrCacheEntry cached = ocrCache;
        if (cached == null
                || cached.handle() != handle
                || !Objects.equals(cached.app(), app)
                || !Objects.equals(cached.title(), title)
                || !cached.fingerprint().equals(fingerprint)) {
            return null;
        }

        long ageNanos = nowNanos - cached.recognizedAtNanos();
        long reuseNanos = cached.text().isBlank()
                ? EMPTY_OCR_RETRY_MS * 1_000_000L
                : forceRefreshNanos;
        return ageNanos >= 0 && ageNanos < reuseNanos ? cached.text() : null;
    }

    private static String fingerprintOcrInputs(List<BufferedImage> inputs) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }

        for (BufferedImage input : inputs) {
            updateDigestInt(digest, input.getWidth());
            updateDigestInt(digest, input.getHeight());
            int[] pixels = input.getRGB(
                    0, 0, input.getWidth(), input.getHeight(), null, 0, input.getWidth());
            for (int rgb : pixels) {
                // Four-bit color quantization ignores tiny rendering noise while preserving
                // text strokes and other meaningful title-strip changes.
                digest.update((byte) ((rgb >>> 16) & 0xf0));
                digest.update((byte) ((rgb >>> 8) & 0xf0));
                digest.update((byte) (rgb & 0xf0));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateDigestInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private String recognizeOcrInputs(List<BufferedImage> inputs, boolean filterSingleCharacters) {
        List<String> mergedLines = new ArrayList<>();
        for (BufferedImage input : inputs) {
            String recognized = ocr.recognize(input);
            List<String> chunkLines = new ArrayList<>(
                    normalizedLines(recognized, filterSingleCharacters));
            int duplicatePrefix = commonBoundaryLineCount(mergedLines, chunkLines);
            mergedLines.addAll(chunkLines.subList(duplicatePrefix, chunkLines.size()));
        }
        return String.join("\n", mergedLines);
    }

    private static List<String> normalizedLines(String text, boolean filterSingleCharacters) {
        if (text == null || text.isBlank()) return List.of();
        return text.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                // Title-strip toolbars frequently turn icons into one-character OCR noise.
                .filter(line -> !filterSingleCharacters
                        || line.codePointCount(0, line.length()) > 1)
                .toList();
    }

    /** Remove only exact suffix/prefix duplicates introduced by adjacent tile overlap. */
    private static int commonBoundaryLineCount(List<String> accumulated, List<String> next) {
        int max = Math.min(accumulated.size(), next.size());
        for (int count = max; count > 0; count--) {
            if (accumulated.subList(accumulated.size() - count, accumulated.size())
                    .equals(next.subList(0, count))) {
                return count;
            }
        }
        return 0;
    }

    private static int parseTitleStripHeight() {
        try {
            return Math.max(0, Integer.parseInt(System.getProperty("ocr.title-strip-height", "80")));
        } catch (NumberFormatException e) {
            return 80;
        }
    }

    private static long parseForceRefreshMs() {
        try {
            long value = Long.parseLong(System.getProperty(
                    "ocr.force-refresh-ms", String.valueOf(DEFAULT_FORCE_REFRESH_MS)));
            return value >= 30_000L && value <= 60_000L
                    ? value : DEFAULT_FORCE_REFRESH_MS;
        } catch (NumberFormatException e) {
            return DEFAULT_FORCE_REFRESH_MS;
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

    @Override
    public void close() {
        if (sampleStore != null) sampleStore.close();
    }
}
