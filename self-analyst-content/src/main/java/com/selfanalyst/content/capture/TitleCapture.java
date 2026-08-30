package com.selfanalyst.content.capture;

import com.selfanalyst.content.uia.UiaNode;

/** Converts transient UIA text/tree data into a persistable context-title projection. */
public final class TitleCapture {

    private final ContextCapturePolicy policy = new ContextCapturePolicy();

    public boolean isExcluded(String app, String title) {
        return policy.isExcluded(app, title);
    }

    public TitleCaptureResult windowOnly() {
        return new TitleCaptureResult(null, null, "window", null, 0);
    }

    public TitleCaptureResult capture(String app, UiaNode root, String uiaText) {
        String text = uiaText != null ? uiaText : "";
        ContextTitleCandidate candidate = ContextTitleExtractor.extractCandidate(app, text);
        if (candidate == null
                && ContextTitleExtractor.supports(app)
                && !ContextTitleExtractor.isChatSurface(text)) {
            candidate = ContextTitleExtractor.fromDocumentTitle(
                    app, ContextCapturePolicy.extractVerifiedDocumentTitle(root));
        }
        return new TitleCaptureResult(
                candidate != null ? candidate.value() : null,
                candidate != null ? candidate.kind() : null,
                candidate != null ? candidate.source() : "window",
                candidate != null ? candidate.confidence() : null,
                text.length());
    }
}
