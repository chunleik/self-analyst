package com.selfanalyst.document;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** 随 RuntimeContext 注入，模型不能设置身份；取消后等待实际写入任务退出。 */
public final class DocumentExecution {
    public final String sessionId;
    public final String userMessageId;
    public final boolean managed;
    private final BooleanSupplier turnCancelled;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantLock worker = new ReentrantLock();
    public DocumentExecution(String sessionId, String userMessageId, boolean managed, BooleanSupplier cancelled) {
        this.sessionId = sessionId; this.userMessageId = userMessageId; this.managed = managed; this.turnCancelled = cancelled;
    }
    public boolean cancelled() { return closed.get() || turnCancelled.getAsBoolean(); }
    public <T> T run(Callable<T> action) throws Exception {
        worker.lock();
        try {
            if (!managed) throw new IllegalArgumentException("文档生成需要受管桌面会话");
            if (cancelled()) throw new java.util.concurrent.CancellationException("文档生成已取消");
            return action.call();
        } finally { worker.unlock(); }
    }
    public void closeAndAwait() {
        closed.set(true); worker.lock(); worker.unlock();
    }
}
