package com.selfanalyst.content.capture;

/**
 * Result of a content-capture operation.
 *
 * @param textContent The merged / captured text content.
 * @param source      Source label: "uia", "ocr", or "hybrid".
 * @param uiaChars    Number of characters captured from UIA.
 * @param ocrChars    Number of characters captured from OCR.
 * @param titleCandidate A single persistable title derived from the transient text.
 * @param sampleId    UUID used only by the opt-in OCR sample store. It is transient
 *                    capture metadata and is never included in the persisted title event.
 */
public record ContentResult(String textContent, String source, int uiaChars, int ocrChars,
                            ContextTitleCandidate titleCandidate, String sampleId) {

    /** Backwards-compatible constructor for callers that do not provide semantic context. */
    public ContentResult(String textContent, String source, int uiaChars, int ocrChars,
                         String sampleId) {
        this(textContent, source, uiaChars, ocrChars, (ContextTitleCandidate) null, sampleId);
    }

    /** Compatibility constructor for tests and callers that still provide a bare title. */
    public ContentResult(String textContent, String source, int uiaChars, int ocrChars,
                         String contextTitle, String sampleId) {
        this(textContent, source, uiaChars, ocrChars,
                ContextTitleCandidate.legacy(contextTitle), sampleId);
    }

    /** Convenience factory when no OCR sample is stored (UIA-only path). */
    public static ContentResult noSample(String textContent, String source,
                                         int uiaChars, int ocrChars) {
        return noSample(textContent, source, uiaChars, ocrChars,
                (ContextTitleCandidate) null);
    }

    /** Convenience factory for a UIA result with semantic context. */
    public static ContentResult noSample(String textContent, String source,
                                         int uiaChars, int ocrChars, String contextTitle) {
        return new ContentResult(textContent, source, uiaChars, ocrChars,
                ContextTitleCandidate.legacy(contextTitle), null);
    }

    public static ContentResult noSample(String textContent, String source,
                                         int uiaChars, int ocrChars,
                                         ContextTitleCandidate titleCandidate) {
        return new ContentResult(textContent, source, uiaChars, ocrChars, titleCandidate, null);
    }

    /** Bare title accessor retained while capture internals migrate to structured candidates. */
    public String contextTitle() {
        return titleCandidate != null ? titleCandidate.value() : null;
    }
}
