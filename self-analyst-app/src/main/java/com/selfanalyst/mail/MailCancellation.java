package com.selfanalyst.mail;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation is per operation; callbacks close the underlying sockets, not mailboxes with expunge. */
public final class MailCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();
    public boolean isCancelled() { return cancelled.get(); }
    public void check() {
        if (isCancelled() || Thread.currentThread().isInterrupted()) throw new MailException(MailException.Code.CANCELLED);
    }
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) callbacks.forEach(r -> { try { r.run(); } catch (RuntimeException ignored) { } });
    }
    public AutoCloseable onCancel(Runnable action) {
        callbacks.add(action);
        if (cancelled.get()) action.run();
        return () -> callbacks.remove(action);
    }
}
