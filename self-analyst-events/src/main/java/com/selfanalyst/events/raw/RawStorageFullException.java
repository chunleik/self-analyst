package com.selfanalyst.events.raw;

/** 原始存储因阈值或 SQLite/filesystem 空间不足而拒绝新提交。 */
public final class RawStorageFullException extends IllegalStateException {
    private final long usableBytes;

    RawStorageFullException(String message, long usableBytes, Throwable cause) {
        super(message, cause);
        this.usableBytes = usableBytes;
    }

    public long usableBytes() {
        return usableBytes;
    }
}
