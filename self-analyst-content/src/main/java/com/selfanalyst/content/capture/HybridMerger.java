package com.selfanalyst.content.capture;

/**
 * SPEC-HYB-001: Merge UIA-extracted text with OCR-extracted text.
 */
public class HybridMerger {

    /**
     * Merge UIA text and OCR text according to SPEC-HYB-001 rules.
     *
     * <ul>
     *   <li>SPEC-HYB-001a: uiaText non-empty → "{uiaText}\n--- OCR ---\n{ocrText}"</li>
     *   <li>SPEC-HYB-001b: uiaText empty → ocrText only</li>
     *   <li>SPEC-HYB-001c: both empty → ""</li>
     * </ul>
     *
     * @param uiaText UIA-extracted text (null-safe).
     * @param ocrText OCR-extracted text (null-safe).
     * @return Merged text.
     */
    public String merge(String uiaText, String ocrText) {
        if (uiaText == null) uiaText = "";
        if (ocrText == null) ocrText = "";

        if (!uiaText.isEmpty() && !ocrText.isEmpty()) {
            // SPEC-HYB-001a: both non-empty → merge with separator
            return uiaText + "\n--- OCR ---\n" + ocrText;
        } else if (!uiaText.isEmpty()) {
            // SPEC-HYB-001a: only UIA → UIA text, no separator
            return uiaText;
        } else {
            // SPEC-HYB-001b: only OCR (or both empty)
            return ocrText;
        }
    }
}
