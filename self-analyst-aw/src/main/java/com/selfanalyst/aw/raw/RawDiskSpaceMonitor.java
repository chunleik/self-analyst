package com.selfanalyst.aw.raw;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 原始目录文件系统可用空间采样与背压状态。 */
public final class RawDiskSpaceMonitor {

    public enum State { NORMAL, WARNING, BLOCKED }

    @FunctionalInterface
    public interface SpaceSampler {
        long usableBytes(Path path) throws Exception;
    }

    private final Path rawRoot;
    private final long warnBytes;
    private final long blockBytes;
    private final SpaceSampler sampler;
    private volatile State state = State.NORMAL;
    private volatile long usableBytes = -1;

    public RawDiskSpaceMonitor(Path rawRoot, long warnBytes, long blockBytes) {
        this(rawRoot, warnBytes, blockBytes,
                path -> Files.getFileStore(path).getUsableSpace());
    }

    RawDiskSpaceMonitor(Path rawRoot, long warnBytes, long blockBytes,
                        SpaceSampler sampler) {
        if (blockBytes <= 0 || warnBytes <= blockBytes) {
            throw new IllegalArgumentException("磁盘阈值必须满足 0 < blockBytes < warnBytes");
        }
        this.rawRoot = Objects.requireNonNull(rawRoot, "rawRoot");
        this.warnBytes = warnBytes;
        this.blockBytes = blockBytes;
        this.sampler = Objects.requireNonNull(sampler, "sampler");
    }

    public synchronized State sample() {
        try {
            usableBytes = sampler.usableBytes(rawRoot);
            state = usableBytes < blockBytes ? State.BLOCKED
                    : usableBytes < warnBytes ? State.WARNING : State.NORMAL;
            return state;
        } catch (Exception failure) {
            state = State.BLOCKED;
            throw new RawStorageFullException(
                    "无法可靠采样原始目录可用空间", usableBytes, failure);
        }
    }

    public void requireWritable() {
        if (sample() == State.BLOCKED) {
            throw new RawStorageFullException(
                    "原始目录可用空间低于阻断阈值", usableBytes, null);
        }
    }

    public synchronized void markStorageFull(Throwable cause) {
        state = State.BLOCKED;
    }

    public State state() {
        return state;
    }

    public long usableBytes() {
        return usableBytes;
    }
}
