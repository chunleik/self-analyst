package com.selfanalyst.content.capture;

/** Persistable projection of a transient content-capture result. */
public record TitleCaptureResult(
        String contextTitle,
        String contextKind,
        String titleSource,
        String titleConfidence,
        int uiaChars,
        String urlHost,
        Boolean privateBrowsing) {

    public TitleCaptureResult(String contextTitle, String contextKind, String titleSource,
                              String titleConfidence, int uiaChars) {
        this(contextTitle, contextKind, titleSource, titleConfidence, uiaChars, null, null);
    }
}
