package com.selfanalyst.content.ocr;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.nio.file.Files;
import java.nio.file.Path;

public class TesseractOcrEngine implements OcrEngine {

    private final Tesseract tesseract;
    private volatile boolean available;

    public TesseractOcrEngine() {
        Tesseract t = new Tesseract();
        t.setLanguage("chi_sim+eng");
        t.setOcrEngineMode(1);
        t.setVariable("user_defined_dpi", "300");

        // Detect tessdata from env or common install paths
        String tessdata = System.getenv("TESSDATA_PREFIX");
        if (tessdata == null) {
            tessdata = findTessdata();
        }
        if (tessdata != null) {
            t.setDatapath(tessdata);
        }

        // Quick smoke test
        try {
            BufferedImage img = new BufferedImage(100, 1, BufferedImage.TYPE_BYTE_GRAY);
            t.doOCR(img);
            available = true;
        } catch (Throwable e) {
            available = false;
        }

        this.tesseract = t;
    }

    public boolean isAvailable() { return available; }

    @Override
    public String recognize(BufferedImage image) {
        if (!available) return "";
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return "";
        try {
            BufferedImage grayscale = toGrayscale(image);
            String result = tesseract.doOCR(grayscale);
            return result != null ? result.trim() : "";
        } catch (Throwable e) {
            available = false; // disable on crash
            return "";
        }
    }

    private BufferedImage toGrayscale(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) return image;
        BufferedImage gray = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        ColorConvertOp op = new ColorConvertOp(
                ColorSpace.getInstance(ColorSpace.CS_GRAY), null);
        op.filter(image, gray);
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
