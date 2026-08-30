package com.selfanalyst.file;

/**
 * Lifecycle status of a watched file (SPEC-FILE-010..013).
 *
 * <pre>
 * PENDING → (COLLECTED | SKIPPED | FAILED)
 * FAILED  → (re-processed after next_retry_at)
 * DELETED → terminal (a re-appearance creates a fresh PENDING upsert)
 * </pre>
 */
public enum FileStatus {
    PENDING, COLLECTED, FAILED, SKIPPED, DELETED
}
