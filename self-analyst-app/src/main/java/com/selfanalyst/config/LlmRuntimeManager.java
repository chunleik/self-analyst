package com.selfanalyst.config;

import java.util.*;
import java.util.function.Function;

/** 与保存共用提交锁；调用持有租约，旧版本只在最后一个调用离开后退休。 */
public final class LlmRuntimeManager<T extends AutoCloseable> implements AutoCloseable {
    private final Object lock;
    private final Function<LlmSettings, T> factory;
    private final Set<Version> versions = new LinkedHashSet<>();
    private Version current;
    private boolean closed;
    private volatile boolean accepting = true;
    private long nextRevision;
    private final class Version {
        final long revision;
        final LlmSettings settings;
        final T resource;
        int users;
        boolean retired;
        Version(LlmSettings settings, T resource) {
            this.revision = ++nextRevision;
            this.settings = settings;
            this.resource = resource;
        }
    }
    public final class Candidate implements AutoCloseable {
        private T resource;
        private final LlmSettings settings;
        private Candidate(LlmSettings settings, T resource) { this.settings = settings; this.resource = resource; }
        @Override public void close() { T value = resource; resource = null; dispose(value); }
    }
    public final class Lease implements AutoCloseable {
        private Version version;
        private Lease(Version version) { this.version = version; }
        public T resource() { return version.resource; }
        public LlmSettings settings() { return version.settings; }
        public long revision() { return version.revision; }
        @Override public void close() {
            synchronized (lock) {
                if (version == null) return;
                Version released = version;
                version = null;
                released.users--;
                retireIfUnused(released);
                lock.notifyAll();
            }
        }
    }
    public LlmRuntimeManager(Object lock, LlmSettings initial, Function<LlmSettings, T> factory) {
        this.lock = lock;
        this.factory = factory;
        publish(prepare(initial));
    }
    public Candidate prepare(LlmSettings settings) {
        synchronized (lock) {
            if (!accepting) throw new IllegalStateException("应用正在关闭");
            return new Candidate(settings, factory.apply(settings));
        }
    }
    public void publish(Candidate candidate) {
        synchronized (lock) {
            if (closed) {
                candidate.close();
                throw new IllegalStateException("应用正在关闭");
            }
            Version previous = current;
            current = new Version(candidate.settings, candidate.resource);
            candidate.resource = null;
            versions.add(current);
            if (previous != null) {
                previous.retired = true;
                retireIfUnused(previous);
            }
        }
    }
    public Lease acquire() {
        synchronized (lock) {
            if (!accepting) throw new IllegalStateException("应用正在关闭");
            current.users++;
            return new Lease(current);
        }
    }
    public LlmSettings settings() { synchronized (lock) { return current.settings; } }
    public Map<String, Object> status() {
        synchronized (lock) {
            int old = versions.stream().filter(v -> v != current).mapToInt(v -> v.users).sum();
            return Map.of("status", closed || !current.settings.available() ? "unavailable" : old > 0 ? "draining" : "applied",
                    "activeWorkCount", old, "revision", current.revision);
        }
    }
    public boolean available() { synchronized (lock) { return accepting && current.settings.available(); } }
    private void retireIfUnused(Version version) {
        if (version.retired && version.users == 0 && versions.remove(version)) dispose(version.resource);
    }
    private static void dispose(AutoCloseable value) {
        if (value == null) return;
        try { value.close(); } catch (Exception ignored) {
            // 清理失败不回滚已提交配置，不输出可能包含密钥的 SDK 异常。
        }
    }
    public void awaitIdle() {
        boolean interrupted = false;
        synchronized (lock) {
            while (versions.stream().anyMatch(version -> version.users > 0)) {
                try { lock.wait(); } catch (InterruptedException ignored) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    @Override public void close() {
        accepting = false;
        synchronized (lock) {
            closed = true;
            for (Version version : List.copyOf(versions)) {
                version.retired = true;
                retireIfUnused(version);
            }
        }
    }
}
