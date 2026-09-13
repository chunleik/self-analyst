package com.selfanalyst.document;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class DocumentBudget {
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private final long deadline;
    private final BooleanSupplier cancelled;

    public DocumentBudget(BooleanSupplier cancelled) { this(cancelled, Duration.ofSeconds(120)); }
    public DocumentBudget(BooleanSupplier cancelled, Duration timeout) {
        this.cancelled = cancelled;
        this.deadline = System.nanoTime() + timeout.toNanos();
    }
    public void check() {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("文档生成已取消");
        if (System.nanoTime() - deadline > 0) throw new IllegalArgumentException("文档生成超过时间限制，请缩小范围");
    }
    public OutputStream bound(OutputStream output) {
        return new FilterOutputStream(output) {
            private long bytes;
            private void reserve(int length) throws IOException {
                check();
                if (length > MAX_FILE_BYTES - bytes) throw new IOException("文档超过 50 MiB，请缩小范围");
                bytes += length;
            }
            @Override public void write(int b) throws IOException { reserve(1); out.write(b); }
            @Override public void write(byte[] b, int off, int len) throws IOException { reserve(len); out.write(b, off, len); }
        };
    }
}
