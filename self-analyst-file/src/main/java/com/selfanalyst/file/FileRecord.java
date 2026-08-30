package com.selfanalyst.file;

import java.time.Instant;
/**
 * One metadata-only row of the {@code file_metadata} table (SPEC-FILE-010).
 *
 * <p>The type deliberately cannot represent file content, content hashes,
 * summaries, topics, prompts, models, or embeddings.
 */
public record FileRecord(
        long id,
        String absolutePath,
        String relativePath,
        String watchRoot,
        String extension,
        long sizeBytes,
        Instant fileCreatedAt,
        Instant lastModified,
        Instant firstSeenAt,
        Instant lastCollectedAt,
        FileStatus status,
        int retryCount,
        Instant nextRetryAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {
}
