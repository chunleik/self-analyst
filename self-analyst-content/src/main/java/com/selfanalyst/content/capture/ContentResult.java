package com.selfanalyst.content.capture;

/**
 * Result of a content-capture operation.
 *
 * @param textContent The merged / captured text content.
 * @param source      Source label: "uia", "ocr", or "hybrid".
 * @param uiaChars    Number of characters captured from UIA.
 * @param ocrChars    Number of characters captured from OCR.
 * @param contextTitle App-specific semantic context title, such as the active
 *                     Weixin conversation. Null when not available.
 * @param sampleId    UUID that ties this result to an OcrSampleStore slot and the
 *                    corresponding AW heartbeat event (via the {@code sample_id} field).
 *                    Null when no OCR sample was saved.
 */
public record ContentResult(String textContent, String source, int uiaChars, int ocrChars,
                            String contextTitle, String sampleId) {

    /** Backwards-compatible constructor for callers that do not provide semantic context. */
    public ContentResult(String textContent, String source, int uiaChars, int ocrChars,
                         String sampleId) {
        this(textContent, source, uiaChars, ocrChars, null, sampleId);
    }

    /** Convenience factory when no OCR sample is stored (UIA-only path). */
    public static ContentResult noSample(String textContent, String source,
                                         int uiaChars, int ocrChars) {
        return noSample(textContent, source, uiaChars, ocrChars, null);
    }

    /** Convenience factory for a UIA result with semantic context. */
    public static ContentResult noSample(String textContent, String source,
                                         int uiaChars, int ocrChars, String contextTitle) {
        return new ContentResult(textContent, source, uiaChars, ocrChars, contextTitle, null);
    }
}
