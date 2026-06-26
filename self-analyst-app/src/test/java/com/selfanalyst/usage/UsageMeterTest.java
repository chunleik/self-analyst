package com.selfanalyst.usage;

import com.selfanalyst.usage.UsageMeter.Category;
import com.selfanalyst.usage.UsageMeter.Mode;
import com.selfanalyst.usage.UsageMeter.Status;
import com.selfanalyst.wiki.usage.BudgetExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UsageMeterTest {

    @TempDir
    Path tempDir;

    private final List<UsageMeter> created = new ArrayList<>();

    private UsageMeter meter(Mode mode, long dailyTokens, double warnRatio) {
        UsageMeter m = new UsageMeter(mode, dailyTokens, warnRatio, tempDir);
        created.add(m);
        return m;
    }

    @AfterEach
    void drainPersistWriters() {
        // flush() 关闭并排空后台落盘线程，避免异步写与 @TempDir 清理在 Windows 上争用文件锁
        for (UsageMeter m : created) m.flush();
    }

    // ── 计量 (SPEC-BUDGET-MTR-002) ──

    @Test
    void recordsTokensPerCategoryAndTotal() {
        UsageMeter m = meter(Mode.OFF, 0, 0.8);
        m.record(Category.SUMMARY, 100, 50);
        m.record(Category.AGENT, 10, 5);
        m.recordTokens(20, 0); // UsageRecorder → EMBEDDING

        assertEquals(100 + 50 + 10 + 5 + 20, m.totalTokens());

        Map<String, Object> snap = m.snapshot();
        @SuppressWarnings("unchecked")
        Map<String, Object> cats = (Map<String, Object>) snap.get("categories");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) cats.get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> embedding = (Map<String, Object>) cats.get("embedding");

        assertEquals(100L, ((Number) summary.get("inputTokens")).longValue());
        assertEquals(50L, ((Number) summary.get("outputTokens")).longValue());
        assertEquals(1L, ((Number) summary.get("calls")).longValue());
        assertEquals(20L, ((Number) embedding.get("inputTokens")).longValue());
        assertEquals(0L, ((Number) embedding.get("outputTokens")).longValue());
    }

    @Test
    void negativeTokensAreClampedToZero() {
        UsageMeter m = meter(Mode.OFF, 0, 0.8);
        m.record(Category.SUMMARY, -5, -10);
        assertEquals(0, m.totalTokens());
    }

    // ── 状态判定 (SPEC-BUDGET-ENF-001) ──

    @Test
    void statusCrossesWarnThenExceeded() {
        UsageMeter m = meter(Mode.WARN, 1000, 0.8);
        assertEquals(Status.OK, m.status());

        m.record(Category.SUMMARY, 700, 0); // 70% < 80%
        assertEquals(Status.OK, m.status());

        m.record(Category.SUMMARY, 100, 0); // 80% → WARN
        assertEquals(Status.WARN, m.status());

        m.record(Category.SUMMARY, 200, 0); // 100% → EXCEEDED
        assertEquals(Status.EXCEEDED, m.status());
    }

    @Test
    void zeroDailyTokensMeansUnlimited() {
        UsageMeter m = meter(Mode.BLOCK, 0, 0.8);
        m.record(Category.SUMMARY, 10_000_000, 10_000_000);
        assertEquals(Status.OK, m.status());
        assertFalse(m.isBlocked());
        assertDoesNotThrow(() -> m.enforce(Category.SUMMARY));
    }

    // ── 超额行为 (SPEC-BUDGET-ENF-002/003/004) ──

    @Test
    void blockModeThrowsWhenExceeded() {
        UsageMeter m = meter(Mode.BLOCK, 100, 0.8);
        m.record(Category.SUMMARY, 100, 0); // EXCEEDED

        assertTrue(m.isBlocked());
        BudgetExceededException ex =
                assertThrows(BudgetExceededException.class, () -> m.enforce(Category.SUMMARY));
        assertTrue(ex.getMessage().contains("100"));
    }

    @Test
    void blockModeDoesNotThrowBeforeExceeded() {
        UsageMeter m = meter(Mode.BLOCK, 100, 0.8);
        m.record(Category.SUMMARY, 80, 0); // WARN, not exceeded
        assertFalse(m.isBlocked());
        assertDoesNotThrow(() -> m.enforce(Category.SUMMARY));
    }

    @Test
    void warnModeNeverThrowsOrBlocks() {
        UsageMeter m = meter(Mode.WARN, 100, 0.8);
        m.record(Category.SUMMARY, 500, 0); // far past limit
        assertEquals(Status.EXCEEDED, m.status());
        assertFalse(m.isBlocked());
        assertDoesNotThrow(() -> m.enforce(Category.SUMMARY));
    }

    @Test
    void offModeNeverThrowsOrBlocks() {
        UsageMeter m = meter(Mode.OFF, 100, 0.8);
        m.record(Category.SUMMARY, 500, 0);
        assertFalse(m.isBlocked());
        assertDoesNotThrow(() -> m.enforce(Category.AGENT));
    }

    // ── 持久化 (SPEC-BUDGET-MTR-003) ──

    @Test
    void persistsAndReloadsTodaysTotals() {
        UsageMeter m = meter(Mode.WARN, 1000, 0.8);
        m.record(Category.SUMMARY, 123, 45);
        m.record(Category.EMBEDDING, 7, 0);
        m.flush();

        UsageMeter reloaded = meter(Mode.WARN, 1000, 0.8);
        assertEquals(123 + 45 + 7, reloaded.totalTokens());

        Map<String, Object> snap = reloaded.snapshot();
        @SuppressWarnings("unchecked")
        Map<String, Object> cats = (Map<String, Object>) snap.get("categories");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) cats.get("summary");
        assertEquals(123L, ((Number) summary.get("inputTokens")).longValue());
        assertEquals(45L, ((Number) summary.get("outputTokens")).longValue());
    }

    @Test
    void freshMeterWithNoFileStartsAtZero() {
        UsageMeter m = meter(Mode.WARN, 1000, 0.8);
        assertEquals(0, m.totalTokens());
        assertEquals(Status.OK, m.status());
    }

    // ── 快照 (SPEC-BUDGET-API-001) ──

    @Test
    void snapshotExposesBudgetMetadata() {
        UsageMeter m = meter(Mode.BLOCK, 5000, 0.5);
        m.record(Category.SUMMARY, 1000, 1500); // 50% → WARN

        Map<String, Object> snap = m.snapshot();
        assertEquals("block", snap.get("mode"));
        assertEquals(5000L, ((Number) snap.get("dailyTokens")).longValue());
        assertEquals(0.5, ((Number) snap.get("warnRatio")).doubleValue());
        assertEquals("warn", snap.get("status"));
        assertEquals(2500L, ((Number) snap.get("totalTokens")).longValue());
        assertNotNull(snap.get("date"));
        assertNotNull(snap.get("categories"));
    }

    // ── 配置回退 ──

    @Test
    void invalidWarnRatioFallsBackToDefault() {
        UsageMeter tooHigh = meter(Mode.WARN, 1000, 5.0);
        // 默认 0.8：800 → WARN，700 → OK
        tooHigh.record(Category.SUMMARY, 800, 0);
        assertEquals(Status.WARN, tooHigh.status());
    }

    // ── 跨天滚动后预算自动重置 (SPEC-BUDGET-ENF-001，回归 #1) ──

    @Test
    void blockReleasesAfterDayRollover() {
        AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 6, 20));
        UsageMeter m = new UsageMeter(Mode.BLOCK, 100, 0.8, tempDir, today::get);
        created.add(m); // 让 @AfterEach 排空后台落盘线程

        m.record(Category.SUMMARY, 100, 0); // 第 1 天超额
        assertTrue(m.isBlocked());

        today.set(LocalDate.of(2026, 6, 21)); // 进入新的一天，无任何新调用
        // 修复前：isBlocked() 读到的是昨天的 EXCEEDED 而一直拦截；修复后读取即滚动重置
        assertFalse(m.isBlocked(), "新的一天预算应自动重置");
        assertEquals(Status.OK, m.status());
        assertEquals(0, m.totalTokens());
    }

    @Test
    void modeParseHandlesAliasesAndDefaults() {
        assertEquals(Mode.OFF, Mode.parse("off"));
        assertEquals(Mode.BLOCK, Mode.parse("BLOCK"));
        assertEquals(Mode.WARN, Mode.parse("warn"));
        assertEquals(Mode.WARN, Mode.parse("nonsense"));
        assertEquals(Mode.WARN, Mode.parse(null));
    }
}
