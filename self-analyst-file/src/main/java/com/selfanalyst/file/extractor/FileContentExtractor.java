package com.selfanalyst.file.extractor;

import java.nio.file.Path;

/**
 * Extracts plain text from a file (SPEC-FILE-014). Implementations do NOT
 * truncate — truncation by codepoint happens in the worker (SPEC-FILE-018f).
 */
@FunctionalInterface
public interface FileContentExtractor {

    /**
     * @return extracted text (never null; may be empty)
     * @throws Exception extraction failures propagate to the worker, which
     *                   applies backoff/fallback (SPEC-FILE-013a/013b, 018e/018k)
     */
    String extract(Path file) throws Exception;
}
