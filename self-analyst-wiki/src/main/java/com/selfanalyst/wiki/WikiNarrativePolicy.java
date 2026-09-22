package com.selfanalyst.wiki;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/** Detects explicit statistics reports, rather than arbitrary numbers or technical AFK topics. */
final class WikiNarrativePolicy {

    private static final String NUMBER = "(?:\\d+(?:[.,]\\d+)?|[零〇一二两三四五六七八九十百千万]+|半|数|几)";
    private static final String DURATION = NUMBER + "\\s*(?:小时|钟头|分钟|秒钟|分|秒|天|"
            + "hours?\\b|hrs?\\b|h\\b|minutes?\\b|mins?\\b|m\\b|seconds?\\b|secs?\\b|s\\b)";
    private static final String AFK = "(?<![a-z0-9_])afk(?![a-z0-9_])";
    private static final String SEPARATOR = "[\\s\"'`:=]*(?:(?:为|是|仅为|仍为|显示为|is|was|remains?)\\s*[\"'`]*)?";
    private static final String AMOUNT_PREFIX = "[\\s:=]*(?:(?:约|为|是|共|累计|合计|达到|长达)\\s*){0,3}";
    private static final String STATISTIC_FIELDS = "activeSeconds(?:Exact)?|afkSeconds(?:Exact)?|"
            + "unknownActivitySeconds(?:Exact)?|uncoveredSeconds|conflictSeconds|appSecondsExact|"
            + "switchCount|topApps|sourceCoverage|samplingCoverage|statisticsVersion|calendarVersion|"
            + "factBuilderVersion|projectorVersion|candidateFacts|selectedFacts|omittedFacts|"
            + "candidateIntervals|selectedIntervals|omittedIntervals|windowCandidates|windowSelected|"
            + "contextCandidates|contextSelected|semanticContextCandidates|deduplicatedObservations|"
            + "budgetChars|usedChars";

    private static final List<Pattern> REPORTS = List.of(
            // AFK is allowed as a technical subject; only an explicit coverage/state report is rejected.
            pattern(AFK + "(?:\\s*(?:coverage|覆盖(?:率|情况)?|状态|数据|记录|信息))?" + SEPARATOR
                    + "(?:不完整|不全|不足|缺失|缺少|缺口|未知|部分覆盖|部分|未覆盖|完整|充足|"
                    + "(?:missing|partial(?:ly)?|incomplete|complete|unavailable|unknown|estimated|absent)(?![a-z_]))"),
            pattern("(?:缺少|缺失|缺乏|没有|不完整|不足|部分|missing|partial|incomplete|absent|unavailable|no)"
                    + "\\s*(?:完整的?|可用的?)?" + AFK + "\\s*(?:覆盖|数据|记录|状态|coverage|data|records?)"),
            pattern("(?:总|累计)?(?:活跃|非活跃|离开|空闲)(?:使用)?(?:时长|时间)?" + AMOUNT_PREFIX + DURATION),
            pattern("(?:应用(?:使用)?|窗口|前台|在线|使用|活动)(?:时长|时间|耗时)" + AMOUNT_PREFIX + DURATION),
            pattern("(?:应用(?:使用)?|窗口|活跃|非活跃|离开|空闲|使用|活动)(?:时长|时间|耗时)"
                    + "\\s*(?:属于|为|是|仅为)\\s*(?:估计|估算)"),
            pattern("(?:使用|工作|专注|浏览|停留)(?:了|约|共|累计|总计)?\\s*" + DURATION
                    + "(?!\\s*(?:的)?(?:连接|请求|查询|执行|重试|超时|间隔|延迟|周期|阈值|配置))"),
            pattern("\\b(?:active|inactive|idle|away|usage|screen|foreground|online|app(?:lication)?(?: usage)?|window)"
                    + "(?:\\s+(?:time|duration))?\\s*(?:(?:is|was|were|for|of|about|approximately|total(?:ed)?)\\s*)?"
                    + "[:=]?\\s*" + DURATION),
            pattern("\\bspent\\s+(?:about\\s+)?" + DURATION),
            pattern("\\bused\\s+[^\\n,.;:!?]{1,40}\\s+for\\s+" + DURATION),
            // Test/code coverage is a legitimate task topic, unlike activity coverage percentages.
            pattern("(?<!测试)(?<!代码)(?<!分支)(?<!语句)(?<!测试的)(?<!代码的)覆盖率"
                    + "\\s*(?:为|是|约|只有|仅有|达到|不足|:|=)?\\s*" + NUMBER + "\\s*%"),
            pattern("(?<!test )(?<!code )(?<!branch )\\bcoverage\\s*"
                    + "(?:(?:is|was|of|at|only)\\s*)?[:=]?\\s*" + NUMBER + "\\s*%"),
            pattern("(?:采样(?:的)?|样本(?:中)?)(?:候选|选中|省略)(?:了|数|数量)?\\s*[:=]?\\s*" + NUMBER),
            pattern("(?:候选|选中|省略)(?:事实|标题|样本|区间)(?:数|数量)?\\s*[:=为]?\\s*" + NUMBER),
            pattern("\\b(?:candidate|selected|omitted)\\s+(?:facts|titles|samples|intervals)\\s*[:=]?\\s*" + NUMBER),
            pattern("(?:窗口|应用|活动|使用)(?:使用)?(?:时长|时间|耗时)[^\\n。,;；!?]{0,16}"
                    + "未(?:完全)?扣除\\s*(?:AFK|非活跃|离开|空闲)(?:时间|时长)?"),
            // Require a value assignment: merely discussing an internal field name remains valid.
            pattern("(?<![a-z0-9_])(?:" + STATISTIC_FIELDS + ")(?![a-z0-9_])[\"'`]?\\s*"
                    + "(?::|=|为|是)\\s*\\S+"),
            pattern("[\"'`]coverage[\"'`]\\s*[:=]\\s*\\S+"),
            pattern("\\bcoverage\\s*[:=]\\s*[\"'`]?(?:complete|partial|estimated|missing|unknown)\\b")
    );

    private WikiNarrativePolicy() {
    }

    static void validate(String field, String value) {
        if (value == null || value.isBlank()) return;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        for (Pattern report : REPORTS) {
            if (report.matcher(normalized).find()) {
                throw new IllegalArgumentException("WIKI_NARRATIVE_STATISTICS:" + field);
            }
        }
    }

    private static Pattern pattern(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
