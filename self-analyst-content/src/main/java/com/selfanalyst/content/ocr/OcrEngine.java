package com.selfanalyst.content.ocr;

import java.awt.image.BufferedImage;

public interface OcrEngine {
    String recognize(BufferedImage image);
    default boolean isAvailable() { return true; }
}
