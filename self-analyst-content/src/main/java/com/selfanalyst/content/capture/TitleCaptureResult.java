package com.selfanalyst.content.capture;

/** Persistable projection of a transient content-capture result. */
public record TitleCaptureResult(
        String contextTitle,
        String contextKind,
        String titleSource,
        String titleConfidence,
        int uiaChars,
        int ocrChars) {

    public static TitleCaptureResult from(ContentResult result) {
        ContextTitleCandidate candidate = result != null ? result.titleCandidate() : null;
        return new TitleCaptureResult(
                candidate != null ? candidate.value() : null,
                candidate != null ? candidate.kind() : null,
                candidate != null ? candidate.source() : "window",
                candidate != null ? candidate.confidence() : null,
                result != null ? result.uiaChars() : 0,
                result != null ? result.ocrChars() : 0);
    }
}
