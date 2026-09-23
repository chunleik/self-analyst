package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WikiNarrativePolicyTest {

    @Test
    void technicalSubjectsDoNotBecomeReportsThroughRegexBacktracking() {
        for (String narrative : List.of(
                "使用30分钟连接超时配置验证重试策略",
                "使用30秒钟的超时配置验证重试策略",
                "修复AFK数据缺失时仍显示高置信度的问题。",
                "调试 AFK 数据缺失情况下的降级逻辑。",
                "调查activeSeconds=0时的除零问题。",
                "修复窗口切换次数为25次时的边界条件。")) {
            assertDoesNotThrow(() -> WikiNarrativePolicy.validate("summary", narrative), narrative);
        }
        for (String report : List.of(
                "使用30分钟处理日常任务。", "使用30秒钟。",
                "调试了采集器，AFK数据缺失。",
                "窗口切换了25次，Chrome排名第一。", "Chrome占比70%。",
                "应用切换次数为12次。", "Chrome ranked first.")) {
            assertThrows(IllegalArgumentException.class,
                    () -> WikiNarrativePolicy.validate("summary", report), report);
        }
    }

    @Test
    void rejectsExplicitCoverageAndDurationReports() {
        List<String> reports = List.of(
                "AFK 覆盖为 partial，相关活动仅为估计。",
                "AFK覆盖完整。",
                "AFK 数据缺失，无法确认活跃时间。",
                "缺少 AFK 覆盖记录。",
                "AFK coverage is complete.",
                "AFK coverage: partial.",
                "AFK is unavailable.",
                "Partial AFK coverage was observed.",
                "Missing AFK data.",
                "活跃时长约 2.5 小时，主要进行开发。",
                "总活跃时间为两小时。",
                "离开 30 分钟。",
                "非活跃时长：0秒。",
                "应用使用时长为 120 分钟。",
                "窗口耗时为 3 小时。",
                "Chrome 使用了 2 小时。",
                "在 IDE 中工作了 3 小时。",
                "Active time: 2 hours.",
                "Idle duration was 20 minutes.",
                "Away for 30 min.",
                "Spent 2 hours on backend development.",
                "Used Chrome for 2 hours.",
                "活动覆盖率为 80%。",
                "覆盖率：50%。",
                "AFK coverage was 75%.",
                "Data coverage is 80%.",
                "Coverage: 50%.",
                "采样候选 120 条，选中 30 条。",
                "样本省略了 12 个区间。",
                "窗口耗时尚未扣除非活跃时间。",
                "应用使用时长属于估计值。",
                "活跃时长：２小时。"
        );
        for (String report : reports) {
            assertThrows(IllegalArgumentException.class,
                    () -> WikiNarrativePolicy.validate("summary", report), report);
        }
    }

    @Test
    void rejectsInternalStatisticsValuesWithoutIncludingThemInErrors() {
        List<String> reports = List.of(
                "activeSeconds=3600",
                "afkSeconds: 0",
                "unknownActivitySeconds 为 120",
                "activeSecondsExact: 3600.5",
                "uncoveredSeconds=120",
                "conflictSeconds: 12",
                "sourceCoverage={afk:partial}",
                "\"coverage\": \"estimated\"",
                "topApps: [Chrome]",
                "switchCount=20",
                "candidateFacts:120, selectedFacts:30",
                "windowCandidates=100",
                "semanticContextCandidates=12",
                "omittedIntervals=30",
                "budgetChars=24000",
                "usedChars: 12400",
                "statisticsVersion: activity-v2"
        );
        for (String report : reports) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> WikiNarrativePolicy.validate("taskSegments[0].evidence[1]", report), report);
            assertEquals("WIKI_NARRATIVE_STATISTICS:taskSegments[0].evidence[1]", failure.getMessage());
        }
    }

    @Test
    void allowsTaskSubjectsAndTechnicalNumbers() {
        List<String> narratives = List.of(
                "调试 AFK 采集器",
                "排查30秒连接超时",
                "优化SQL查询性能",
                "修复 AFK 覆盖判定逻辑",
                "验证 activeSeconds 字段的序列化逻辑",
                "修复活跃时长计算错误",
                "调查数据库查询耗时 30 秒的原因",
                "使用 30 秒连接超时配置验证重试策略",
                "将单元测试覆盖率提升到 80%",
                "检查代码覆盖率为 90% 的测试结果",
                "定位 HTTP 500 错误并查看 3 个相关日志样本",
                "将采样任务的超时设置为 30 秒",
                "围绕 ETL 和 Oracle 驱动进行相关开发",
                "Review the AFK collector implementation.",
                "Investigate a 30-second connection timeout.",
                "Use a 30 second timeout for SQL queries.",
                "Improve code coverage to 80%.",
                "Review test coverage: 80%.",
                "Optimize a query taking 30 seconds.",
                "Tune poolSize=20 and timeoutSeconds=30.",
                ""
        );
        for (String narrative : narratives) {
            assertDoesNotThrow(() -> WikiNarrativePolicy.validate("summary", narrative), narrative);
        }
        assertDoesNotThrow(() -> WikiNarrativePolicy.validate("summary", null));
    }
}
