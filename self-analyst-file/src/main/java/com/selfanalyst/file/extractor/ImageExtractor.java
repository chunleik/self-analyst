package com.selfanalyst.file.extractor;

import net.sourceforge.tess4j.Tesseract;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Image OCR via Tess4j (SPEC-FILE-014). When tessdata/Tesseract is unavailable
 * the extractor degrades to empty output rather than throwing, so an image file
 * still produces a metadata-style summary upstream.
 */
public class ImageExtractor implements FileContentExtractor {

    private final Tesseract tesseract;
    private volatile boolean available;

    public ImageExtractor() {
        Tesseract t = new Tesseract();
        t.setLanguage("chi_sim+eng");
        t.setOcrEngineMode(1);
        t.setVariable("user_defined_dpi", "300");

        String tessdata = System.getenv("TESSDATA_PREFIX");
        if (tessdata == null) tessdata = findTessdata();
        if (tessdata != null) t.setDatapath(tessdata);

        try {
            t.doOCR(new BufferedImage(100, 1, BufferedImage.TYPE_BYTE_GRAY));
            available = true;
        } catch (Throwable e) {
            available = false;
        }
        this.tesseract = t;
    }

    public boolean isAvailable() {
        return available;
    }

    @Override
    public String extract(Path file) throws Exception {
        if (!available) return "";
        BufferedImage image;
        try (var in = Files.newInputStream(file)) {
            image = ImageIO.read(in);
        }
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return "";
        try {
            String result = tesseract.doOCR(toGrayscale(image));
            return result != null ? result.trim() : "";
        } catch (Throwable e) {
            available = false; // disable on crash
            return "";
        }
    }

    private static BufferedImage toGrayscale(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) return image;
        BufferedImage gray = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null)
                .filter(image, gray);
        return gray;
    }

    private static String findTessdata() {
        String[] candidates = {
            "C:\\Program Files\\Tesseract-OCR\\tessdata",
            "C:\\Program Files (x86)\\Tesseract-OCR\\tessdata",
            System.getProperty("user.home") + "\\AppData\\Local\\Tesseract-OCR\\tessdata",
            "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4/tessdata",
            "/usr/local/share/tessdata"
        };
        for (String path : candidates) {
            Path p = Path.of(path);
            if (Files.isDirectory(p) && Files.exists(p.resolve("eng.traineddata"))) {
                return path;
            }
        }
        return null;
    }
}
