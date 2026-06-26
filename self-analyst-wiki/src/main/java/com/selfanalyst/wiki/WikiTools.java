package com.selfanalyst.wiki;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.selfanalyst.wiki.semantic.EmbeddingClient;
import com.selfanalyst.wiki.semantic.WikiSemanticIndex;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WikiTools {

    private static final Logger log = LoggerFactory.getLogger(WikiTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final WikiStore store;
    private final WikiSemanticIndex semanticIndex;
    private final EmbeddingClient embeddingClient;
    private final int semanticTopK;

    public WikiTools(WikiStore store) {
        this(store, null, null, 8);
    }

    public WikiTools(WikiStore store, WikiSemanticIndex semanticIndex,
                      EmbeddingClient embeddingClient, int semanticTopK) {
        this.store = store;
        this.semanticIndex = semanticIndex;
        this.embeddingClient = embeddingClient;
        this.semanticTopK = semanticTopK;
    }

    @Tool(description = """
            查询 LLM Wiki 多级时间摘要。可指定时间段和粒度级别。
            用于回答用户关于过去时间段做了什么、任务分布、趋势变化、复盘对比等问题。
            start/end 为 ISO-8601 格式时间字符串（如 2026-06-08T00:00:00）。
            level 可选，为空时自动选择合适粒度：≤6h→HOUR, ≤2d→HALF_DAY/DAY, ≤14d→DAY, ≤60d→WEEK, >60d→MONTH""")
    public String queryWiki(
            @ToolParam(name = "start", description = "起始时间 ISO-8601 字符串")
            String start,
            @ToolParam(name = "end", description = "结束时间 ISO-8601 字符串")
            String end,
            @ToolParam(name = "level", description = "摘要粒度: HOUR/HALF_DAY/DAY/WEEK/BIWEEK/MONTH，可选")
            String level) {

        try {
            Instant startInstant = parseInstant(start);
            Instant endInstant = parseInstant(end);

            WikiLevel wikiLevel = level != null && !level.isBlank()
                    ? WikiLevel.valueOf(level.toUpperCase())
                    : autoSelectLevel(startInstant, endInstant);

            List<Map<String, Object>> entries = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            List<String> pending = new ArrayList<>();
            List<String> failed = new ArrayList<>();

            List<WikiEntry> results = store.query(startInstant, endInstant, wikiLevel);

            for (WikiEntry entry : results) {
                switch (entry.status()) {
                    case SUMMARIZED -> entries.add(formatEntry(entry));
                    case PENDING -> pending.add(entry.id());
                    case FAILED -> failed.add(entry.id());
                    case SKIPPED -> { /* skip */ }
                }
            }

            if (results.isEmpty()) {
                List<WikiPeriod> expected = WikiPeriodFactory.generate(
                        startInstant, endInstant, wikiLevel, ZoneId.systemDefault());
                for (WikiPeriod p : expected) {
                    missing.add(p.start() + "/" + p.end());
                }
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("entries", entries);
            if (!missing.isEmpty()) output.put("missing", missing);
            if (!pending.isEmpty()) output.put("pending", pending);
            if (!failed.isEmpty()) output.put("failed", failed);

            return MAPPER.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            return "{\"error\": \"Failed to serialize result\"}";
        } catch (IllegalArgumentException e) {
            return "{\"error\": \"Invalid level: " + level
                    + ". Valid values: HOUR, HALF_DAY, DAY, WEEK, BIWEEK, MONTH\"}";
        } catch (Exception e) {
            log.warn("Wiki query failed: {}", e.getMessage());
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "查询 LLM Wiki 指定时间段内各状态的摘要统计（已完成、待处理、失败、跳过数量）")
    public String wikiStatus(
            @ToolParam(name = "start", description = "起始时间 ISO-8601 字符串")
            String start,
            @ToolParam(name = "end", description = "结束时间 ISO-8601 字符串")
            String end) {

        try {
            Instant startInstant = parseInstant(start);
            Instant endInstant = parseInstant(end);

            Map<String, Map<String, Long>> stats = new LinkedHashMap<>();
            for (WikiLevel level : WikiLevel.values()) {
                List<WikiEntry> results = store.query(startInstant, endInstant, level);
                long summarized = results.stream().filter(e -> e.status() == WikiStatus.SUMMARIZED).count();
                long pendingCount = results.stream().filter(e -> e.status() == WikiStatus.PENDING).count();
                long failedCount = results.stream().filter(e -> e.status() == WikiStatus.FAILED).count();
                long skippedCount = results.stream().filter(e -> e.status() == WikiStatus.SKIPPED).count();

                Map<String, Long> levelStats = new LinkedHashMap<>();
                levelStats.put("summarized", summarized);
                levelStats.put("pending", pendingCount);
                levelStats.put("failed", failedCount);
                levelStats.put("skipped", skippedCount);
                stats.put(level.name(), levelStats);
            }

            return MAPPER.writeValueAsString(stats);
        } catch (JsonProcessingException e) {
            return "{\"error\": \"Failed to serialize\"}";
        }
    }

    @Tool(description = """
            按自然语言主题语义检索 Wiki 摘要和任务片段。
            query 为自然语言查询（如"最近什么时候在处理配置问题"）。
            start/end 可选，用于过滤时间范围。level 可选，用于过滤粒度。
            topK 可选，默认取配置值。适用于用户只有主题描述而没有明确时间范围的场景。""")
    public String semanticSearchWiki(
            @ToolParam(name = "query", description = "自然语言查询文本")
            String query,
            @ToolParam(name = "start", description = "起始时间 ISO-8601 字符串，可选")
            String start,
            @ToolParam(name = "end", description = "结束时间 ISO-8601 字符串，可选")
            String end,
            @ToolParam(name = "level", description = "摘要粒度过滤，可选")
            String level,
            @ToolParam(name = "topK", description = "返回结果数量，可选，默认8")
            int topK) {

        if (semanticIndex == null || embeddingClient == null) {
            return """
                {"error": "Semantic index not available",
                 "fallbackSuggestion": "Use queryWiki with time range to search by period"}""";
        }

        try {
            int k = topK > 0 && topK <= 50 ? topK : semanticTopK;

            float[] queryVector = embeddingClient.embedSingle(query);

            Instant startInstant = start != null && !start.isBlank()
                    ? parseInstant(start) : null;
            Instant endInstant = end != null && !end.isBlank()
                    ? parseInstant(end) : null;
            WikiLevel wikiLevel = level != null && !level.isBlank()
                    ? WikiLevel.valueOf(level.toUpperCase()) : null;

            List<WikiSemanticIndex.SearchHit> hits = semanticIndex.search(
                    queryVector, k, startInstant, endInstant, wikiLevel);

            List<Map<String, Object>> results = new ArrayList<>();
            for (WikiSemanticIndex.SearchHit hit : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("score", hit.score());
                item.put("docType", hit.docType());
                item.put("entryId", hit.entryId());
                item.put("level", hit.level());
                item.put("periodStart", hit.periodStart());
                item.put("periodEnd", hit.periodEnd());
                item.put("summary", hit.summary());
                item.put("primaryTask", hit.primaryTask());
                item.put("matchedText", hit.matchedText());
                results.add(item);
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("results", results);
            output.put("error", null);
            output.put("fallbackSuggestion", null);

            return MAPPER.writeValueAsString(output);
        } catch (Exception e) {
            log.warn("Semantic search failed: {}", e.getMessage());
            return """
                {"error": "Semantic search failed: %s",
                 "fallbackSuggestion": "Use queryWiki to search by time period"}
                """.replace("%s", e.getMessage().replace("\"", "'"));
        }
    }

    public boolean hasSemanticIndex() {
        return semanticIndex != null && embeddingClient != null;
    }

    /** Accepts both "2026-06-15T00:00:00Z" and "2026-06-15T00:00:00" (treated as local time). */
    private static Instant parseInstant(String s) {
        if (s != null && s.length() > 19 && (s.charAt(19) == 'Z' || s.charAt(19) == '+' || s.charAt(19) == '-')) {
            return Instant.parse(s);
        }
        return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant();
    }

    private WikiLevel autoSelectLevel(Instant start, Instant end) {
        long seconds = Duration.between(start, end).getSeconds();
        if (seconds <= 6 * 3600) return WikiLevel.HOUR;
        if (seconds <= 2 * 24 * 3600) return WikiLevel.HALF_DAY;
        if (seconds <= 14 * 24 * 3600) return WikiLevel.DAY;
        if (seconds <= 60 * 24 * 3600) return WikiLevel.WEEK;
        return WikiLevel.MONTH;
    }

    private static Map<String, Object> formatEntry(WikiEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("level", entry.level().name());
        map.put("periodStart", entry.periodStart().toString());
        map.put("periodEnd", entry.periodEnd().toString());
        map.put("summary", entry.summary());
        map.put("primaryTask", entry.primaryTask());
        map.put("status", entry.status().name());
        if (!entry.taskSegments().isEmpty()) {
            map.put("taskSegments", entry.taskSegments());
        }
        map.put("metrics", entry.metrics());
        return map;
    }
}
