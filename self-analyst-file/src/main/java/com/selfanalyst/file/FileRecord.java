package com.selfanalyst.file;

import java.time.Instant;
import java.util.List;

/**
 * One row of the {@code file_index} table (SPEC-FILE-010).
 *
 * <p>{@code sizeBytes}, {@code lastModified} and {@code fileHash} hold the
 * <em>last successfully indexed</em> snapshot of the file — they are written by
 * {@link FileWatchStore#updateIndexed} / {@link FileWatchStore#updateChecksum},
 * not by {@link FileWatchStore#upsertPending}. This lets the worker cheaply
 * detect "nothing changed since last index" (SPEC-FILE-010d).
 */
public record FileRecord(
        long id,
        String absolutePath,
        String relativePath,
        String watchRoot,
        String extension,
        long sizeBytes,
        String fileHash,
        Instant lastModified,
        Instant firstSeenAt,
        Instant lastIndexedAt,
        FileStatus status,
        String summary,
        List<String> mainTopics,
        String model,
        String promptVersion,
        int retryCount,
        Instant nextRetryAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {
}
