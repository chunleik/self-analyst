package com.selfanalyst.content.capture;

/**
 * A single persistable title derived from transient window content.
 *
 * <p>This type deliberately carries no source text. Raw UIA content must stay inside
 * the capture call and must never cross the persistence boundary.</p>
 */
public record ContextTitleCandidate(
        String value,
        String kind,
        String source,
        String confidence) {

    public ContextTitleCandidate {
        if (value != null && (value.contains("\n") || value.contains("\r"))) {
            throw new IllegalArgumentException("context title must be single-line");
        }
        value = normalize(value);
        kind = normalizeToken(kind, "unknown");
        source = normalizeToken(source, "uia_context");
        confidence = normalizeToken(confidence, "medium");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("context title must not be blank");
        }
        if (!ContextTitleExtractor.isValidTitleCandidate(value)) {
            throw new IllegalArgumentException("context title is not a valid title candidate");
        }
    }

    static ContextTitleCandidate legacy(String value) {
        if (value == null || value.isBlank()) return null;
        return new ContextTitleCandidate(value, "unknown", "uia_context", "medium");
    }

    private static String normalize(String value) {
        if (value == null) return null;
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String normalizeToken(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip().toLowerCase();
    }
}
