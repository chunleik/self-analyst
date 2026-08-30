package com.selfanalyst.wiki;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class WikiSummarizer {

    private static final Logger log = LoggerFactory.getLogger(WikiSummarizer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROMPT_VERSION = "wiki-v3";

    private final Function<String, String> llmClient;

    public WikiSummarizer(Function<String, String> llmClient) {
        this.llmClient = llmClient;
    }

    public SummaryResult summarize(WikiFactBuilder.WikiFacts facts, Duration timeout) {
        String prompt = buildPrompt(facts);
        log.debug("Wiki summarizer prompt ({} chars)", prompt.length());
        String response = llmClient.apply(prompt);
        if (response == null || response.isBlank()) {
            throw new RuntimeException("LLM returned empty response");
        }
        return parseResponse(response, facts);
    }

    private String buildPrompt(WikiFactBuilder.WikiFacts facts) {
        StringBuilder sb = new StringBuilder();
        WikiPeriod period = facts.period();
        ZoneId tz = ZoneId.of(period.timezone());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        String periodLabel = switch (period.level()) {
            case HOUR -> "小时";
            case HALF_DAY -> "半天";
            case DAY -> "天";
            case WEEK -> "周";
            case BIWEEK -> "双周";
            case MONTH -> "月";
        };

        sb.append("你是一个活动数据分析器。请分析以下").append(periodLabel).append("活动数据，生成结构化摘要。\n\n");
        sb.append("时间段: ")
                .append(ZonedDateTime.ofInstant(period.start(), tz).format(fmt))
                .append(" 到 ")
                .append(ZonedDateTime.ofInstant(period.end(), tz).format(fmt))
                .append(" (").append(tz).append(")\n\n");

        sb.append("## 统计指标\n");
        sb.append("- 活跃时长: ").append(formatDuration(facts.activeSeconds())).append("\n");
        sb.append("- 离开时长: ").append(formatDuration(facts.afkSeconds())).append("\n");
        sb.append("- 窗口切换: ").append(facts.switchCount()).append("次\n");
        if (!facts.topApps().isEmpty()) {
            sb.append("- 应用排名:\n");
            for (WikiEntry.AppDuration ad : facts.topApps()) {
                sb.append("  - ").append(ad.app()).append(": ")
                        .append(formatDuration(ad.seconds())).append("\n");
            }
        }
        sb.append("\n");

        if (!facts.childSummaries().isEmpty()) {
            sb.append("## 已总结的子时间段\n");
            for (String s : facts.childSummaries()) {
                sb.append("- ").append(s).append("\n");
            }
            sb.append("\n");
        }

        if (!facts.titleSamples().isEmpty()) {
            sb.append("## 窗口标题样本\n");
            for (String t : facts.titleSamples()) {
                sb.append("- ").append(t).append("\n");
            }
            sb.append("\n");
        }

        if (!facts.contextTitleSamples().isEmpty()) {
            sb.append("## 应用内标题样本\n");
            for (String c : facts.contextTitleSamples()) {
                sb.append("- ").append(c).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## 输出要求\n");
        sb.append("**重要规则**：primaryTask 必须选择该时间段实际花费时间最多的工作任务，" +
                "应与上方应用排名中使用时间最长的应用相对应。" +
                "不得因某项任务的标题更丰富、更有技术特色而偏向它——" +
                "时间才是唯一依据。\n");
        sb.append("请严格按照以下JSON格式输出，不要包含Markdown代码块标记:\n");
        sb.append("""
            {
              "summary": "该时间段的整体任务摘要（1-3句话）",
              "primaryTask": "时间占比最多的工作任务（必须与应用排名中时间最长的应用相关）",
              "taskSegments": [
                {
                  "title": "任务片段标题（不超过80字符）",
                  "summary": "任务片段描述（不超过500字符）",
                  "evidence": ["证据描述（脱敏，不含原文）"],
                  "apps": ["相关应用名"],
                  "confidence": "high|medium|low"
                }
              ],
              "metrics": {
                "activeSeconds": 0,
                "afkSeconds": 0,
                "switchCount": 0,
                "topApps": []
              }
            }
            """);

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private SummaryResult parseResponse(String response, WikiFactBuilder.WikiFacts facts) {
        String json = response.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("```(?:json)?\\s*", "");
            json = json.replaceFirst("```\\s*$", "");
        }

        try {
            Map<String, Object> map = MAPPER.readValue(json, Map.class);

            String summary = (String) map.get("summary");
            String primaryTask = (String) map.get("primaryTask");
            if (summary == null || summary.isBlank() || primaryTask == null || primaryTask.isBlank()) {
                throw new RuntimeException("LLM response missing required fields: summary or primaryTask");
            }

            List<WikiEntry.TaskSegment> segments = parseSegments(map.get("taskSegments"));

            Map<String, Object> llmMetrics = safeGetMap(map, "metrics");
            Map<String, Object> extra = Map.of();
            if (llmMetrics != null) {
                extra = llmMetrics.entrySet().stream()
                        .filter(e -> !List.of("activeSeconds", "afkSeconds", "switchCount", "topApps")
                                .contains(e.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            }

            WikiEntry.WikiMetrics metrics = new WikiEntry.WikiMetrics(
                    facts.activeSeconds(), facts.afkSeconds(), facts.switchCount(),
                    facts.topApps(), extra);

            return new SummaryResult(summary, primaryTask, segments, metrics);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse LLM response as JSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<WikiEntry.TaskSegment> parseSegments(Object segmentsObj) {
        if (!(segmentsObj instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(s -> (Map<String, Object>) s)
                .map(s -> new WikiEntry.TaskSegment(
                        (String) s.getOrDefault("title", ""),
                        (String) s.getOrDefault("summary", ""),
                        safeGetStringList(s, "evidence"),
                        safeGetStringList(s, "apps"),
                        (String) s.getOrDefault("confidence", "low")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeGetMap(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> safeGetStringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof List ? (List<String>) v : List.of();
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分" + (seconds % 60) + "秒";
        return (seconds / 3600) + "时" + ((seconds % 3600) / 60) + "分";
    }

    public String promptVersion() {
        return PROMPT_VERSION;
    }

    public record SummaryResult(
            String summary,
            String primaryTask,
            List<WikiEntry.TaskSegment> taskSegments,
            WikiEntry.WikiMetrics metrics) {}
}
