package com.selfanalyst.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.Config;
import com.selfanalyst.wiki.usage.BudgetExceededException;
import com.selfanalyst.wiki.usage.UsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 线程安全的 LLM token 用量计量 + 每日预算控制。
 *
 * <p>所有 LLM/embedding 调用的 token 在此按「本地日期」分桶累计，并按类别
 * （{@link Category}）细分。当当天总 token 达到配置的每日上限时，根据预算模式
 * （off/warn/block）决定告警或拦截后续调用。
 *
 * <p>用量持久化到 {@code {memoryDir}/usage/usage-YYYY-MM-DD.json}，进程重启后会重新
 * 载入当天值；跨天自动滚动到新文件。
 *
 * <p>实现 {@link UsageRecorder}：可直接作为 embedding 客户端的用量回调（计入 EMBEDDING）。
 *
 * 见 openspec/specs/llm-budget/spec.md（SPEC-BUDGET-*）。
 */
public class UsageMeter implements UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageMeter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 落盘节流：两次写文件最小间隔，避免高频调用打满磁盘 IO。 */
    private static final long PERSIST_THROTTLE_MS = 2000;

    public enum Category { AGENT, SUMMARY, EMBEDDING }

    public enum Mode {
        OFF, WARN, BLOCK;
        static Mode parse(String s) {
            if (s == null) return WARN;
            return switch (s.trim().toLowerCase()) {
                case "off" -> OFF;
                case "block" -> BLOCK;
                default -> WARN;
            };
        }
    }

    public enum Status { OK, WARN, EXCEEDED }

    private final Mode mode;
    private final long dailyTokens;
    private final double warnRatio;
    private final Path dir;
    private final Supplier<LocalDate> clock;

    private final Object lock = new Object();
    private LocalDate day;
    /** 每类别 [inputTokens, outputTokens, callCount]。 */
    private final EnumMap<Category, long[]> counters = new EnumMap<>(Category.class);
    private boolean warnLogged;
    private long lastPersistMs;
    /** 单线程串行化落盘，使阻塞磁盘 I/O 不在计量热路径的锁内执行。 */
    private final ExecutorService persistExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "usage-persist");
                t.setDaemon(true);
                return t;
            });

    public UsageMeter(Config config, Path memoryDir) {
        this(Mode.parse(config.budgetMode()), config.budgetDailyTokens(),
                config.budgetWarnRatio(), memoryDir);
    }

    /** 直接构造，便于测试与解耦 Config。 */
    UsageMeter(Mode mode, long dailyTokens, double warnRatio, Path memoryDir) {
        this(mode, dailyTokens, warnRatio, memoryDir, LocalDate::now);
    }

    /** 可注入时钟，便于测试跨天滚动。 */
    UsageMeter(Mode mode, long dailyTokens, double warnRatio, Path memoryDir,
               Supplier<LocalDate> clock) {
        this.clock = clock;
        this.mode = mode;
        this.dailyTokens = dailyTokens;
        this.warnRatio = (warnRatio > 0 && warnRatio <= 1) ? warnRatio : 0.8;
        this.dir = memoryDir.resolve("usage");
        this.day = clock.get();
        for (Category c : Category.values()) counters.put(c, new long[3]);
        load();
        log.info("UsageMeter 已初始化 (mode={}, dailyTokens={}, warnRatio={})",
                this.mode, this.dailyTokens, this.warnRatio);
    }

    /** 记录一次调用的 token 用量。 */
    public void record(Category category, long inputTokens, long outputTokens) {
        if (inputTokens < 0) inputTokens = 0;
        if (outputTokens < 0) outputTokens = 0;
        synchronized (lock) {
            rollIfNeeded();
            long[] c = counters.get(category);
            c[0] += inputTokens;
            c[1] += outputTokens;
            c[2] += 1;
            maybeWarn();
            persistThrottled(false);
        }
    }

    /** {@link UsageRecorder} 实现：embedding 用量计入 EMBEDDING 类别。 */
    @Override
    public void recordTokens(long inputTokens, long outputTokens) {
        record(Category.EMBEDDING, inputTokens, outputTokens);
    }

    /** 当前预算模式为 block 且已超额。供用户侧调用方（如 Agent 入口、summary 页）提前判断。 */
    public boolean isBlocked() {
        return mode == Mode.BLOCK && status() == Status.EXCEEDED;
    }

    /**
     * 在发起一次 LLM 调用前调用：block 模式且已超额时抛出 {@link BudgetExceededException}。
     * warn / off 模式下永不抛出。
     */
    public void enforce(Category category) {
        if (isBlocked()) {
            throw new BudgetExceededException(
                    "已达到每日 token 预算 (" + dailyTokens + ")，已暂停 " + category + " 调用。");
        }
    }

    public Status status() {
        synchronized (lock) {
            rollIfNeeded();
            return statusLocked();
        }
    }

    /** 假设已持有 {@code lock} 且已 rollIfNeeded。 */
    private Status statusLocked() {
        if (dailyTokens <= 0) return Status.OK;
        long total = totalTokensLocked();
        if (total >= dailyTokens) return Status.EXCEEDED;
        if (total >= (long) (dailyTokens * warnRatio)) return Status.WARN;
        return Status.OK;
    }

    public long totalTokens() {
        synchronized (lock) {
            rollIfNeeded();
            return totalTokensLocked();
        }
    }

    /** 假设已持有 {@code lock}。 */
    private long totalTokensLocked() {
        long t = 0;
        for (long[] c : counters.values()) t += c[0] + c[1];
        return t;
    }

    /** 当天用量与预算状态快照，供 /desktop/usage 与 Agent 工具读取。 */
    public Map<String, Object> snapshot() {
        synchronized (lock) {
            rollIfNeeded();
            Map<String, Object> root = rawStateLocked(); // date + categories
            root.put("mode", mode.name().toLowerCase());
            root.put("dailyTokens", dailyTokens);
            root.put("warnRatio", warnRatio);
            root.put("status", statusLocked().name().toLowerCase());
            root.put("totalTokens", totalTokensLocked());
            return root;
        }
    }

    /** {@code date} + 每类别原始计数。假设已持有 {@code lock}。用于持久化与快照共用。 */
    private Map<String, Object> rawStateLocked() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("date", day.toString());
        Map<String, Object> cats = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            long[] v = counters.get(c);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("inputTokens", v[0]);
            m.put("outputTokens", v[1]);
            m.put("calls", v[2]);
            cats.put(c.name().toLowerCase(), m);
        }
        root.put("categories", cats);
        return root;
    }

    /** 强制落盘，用于关机时：先停止后台写线程并等待其排空，再同步写入最终值。 */
    public void flush() {
        persistExecutor.shutdown();
        try {
            persistExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes;
        Path target;
        synchronized (lock) {
            target = fileFor(day);
            try {
                bytes = MAPPER.writeValueAsBytes(rawStateLocked());
            } catch (Exception e) {
                log.debug("序列化用量失败: {}", e.getMessage());
                return;
            }
        }
        writeFile(target, bytes); // 在 executor 排空后同步写，保证为最后一次写入
    }

    // ── internals ──────────────────────────────────────────────

    private void rollIfNeeded() {
        LocalDate now = clock.get();
        if (!now.equals(day)) {
            persistThrottled(true); // 落盘当天最终值
            day = now;
            for (long[] c : counters.values()) {
                c[0] = 0;
                c[1] = 0;
                c[2] = 0;
            }
            warnLogged = false;
        }
    }

    /** 假设已持有 {@code lock} 且已 rollIfNeeded。 */
    private void maybeWarn() {
        if (mode == Mode.OFF) return;
        Status s = statusLocked();
        if ((s == Status.WARN || s == Status.EXCEEDED) && !warnLogged) {
            warnLogged = true;
            long total = totalTokensLocked();
            log.warn("LLM token 用量已达每日预算的 {}%（{} / {}，mode={}）",
                    dailyTokens > 0 ? (total * 100 / dailyTokens) : 0,
                    total, dailyTokens, mode.name().toLowerCase());
        }
    }

    private Path fileFor(LocalDate d) {
        return dir.resolve("usage-" + d + ".json");
    }

    @SuppressWarnings("unchecked")
    private void load() {
        Path f = fileFor(day);
        if (!Files.exists(f)) return;
        try {
            Map<String, Object> root = MAPPER.readValue(Files.readAllBytes(f), Map.class);
            Object catsObj = root.get("categories");
            if (catsObj instanceof Map<?, ?> cats) {
                for (Category c : Category.values()) {
                    Object m = cats.get(c.name().toLowerCase());
                    if (m instanceof Map<?, ?> cm) {
                        long[] v = counters.get(c);
                        v[0] = asLong(cm.get("inputTokens"));
                        v[1] = asLong(cm.get("outputTokens"));
                        v[2] = asLong(cm.get("calls"));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("读取用量文件失败，从零开始计量: {}", e.getMessage());
        }
    }

    /**
     * 调用时假设已持有 {@code lock}：在锁内序列化出字节快照，把阻塞磁盘写交给单线程
     * executor 异步执行，避免占着锁做 I/O 拖慢所有并发计量调用。
     */
    private void persistThrottled(boolean force) {
        long nowMs = System.currentTimeMillis();
        if (!force && nowMs - lastPersistMs < PERSIST_THROTTLE_MS) return;
        lastPersistMs = nowMs;
        Path target = fileFor(day);
        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(rawStateLocked());
        } catch (Exception e) {
            log.debug("序列化用量失败: {}", e.getMessage());
            return;
        }
        try {
            persistExecutor.submit(() -> writeFile(target, bytes));
        } catch (RuntimeException e) {
            // executor 已关闭（关机途中）：忽略，flush() 会做最终同步写
            log.debug("用量落盘任务提交失败: {}", e.getMessage());
        }
    }

    /** 原子写入：先写 .tmp 再 ATOMIC_MOVE，避免写到一半崩溃损坏文件（见 TaskStore）。 */
    private void writeFile(Path target, byte[] bytes) {
        try {
            Files.createDirectories(dir);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.debug("写入用量文件失败: {}", e.getMessage());
        }
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return 0;
    }
}
