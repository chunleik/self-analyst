package com.selfanalyst.file;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.selfanalyst.file.semantic.FileSemanticIndex;
import com.selfanalyst.wiki.semantic.EmbeddingClient;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent-facing file tools (SPEC-FILE-017). JSON-string returns, matching the
 * {@code WikiTools} shape (SPEC-FILE-017a). When embedding is unavailable,
 * {@link #searchFiles} degrades to keyword/time queries (SPEC-FILE-017b).
 */
public class FileTools {

    private static final Logger log = LoggerFactory.getLogger(FileTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final FileWatchStore store;
    private final FileSemanticIndex semanticIndex; // nullable
    private final EmbeddingClient embeddingClient;  // nullable
    private final int defaultTopK;

    public FileTools(FileWatchStore store) {
        this(store, null, null, 8);
    }

    public FileTools(FileWatchStore store, FileSemanticIndex semanticIndex,
                     EmbeddingClient embeddingClient, int defaultTopK) {
        this.store = store;
        this.semanticIndex = semanticIndex;
        this.embeddingClient = embeddingClient;
        this.defaultTopK = defaultTopK > 0 ? defaultTopK : 8;
    }

    public boolean hasSemanticIndex() {
        return semanticIndex != null && embeddingClient != null;
    }

    @Tool(description = """
            按自然语言主题语义检索被监控目录中的文件摘要，并可按目录、扩展名、修改时间过滤。
            query 为自然语言查询（如"关于数据库迁移的文档"）。
            watchRoot/extension/start/end 均可选；start/end 为 ISO-8601 时间字符串。
            topK 可选，默认取配置值。embedding 不可用时自动降级为按时间/目录的关键词检索。""")
    public String searchFiles(
            @ToolParam(name = "query", description = "自然语言查询文本") String query,
            @ToolParam(name = "watchRoot", description = "监控根目录绝对路径过滤，可选") String watchRoot,
            @ToolParam(name = "extension", description = "扩展名过滤（不含点，如 pdf），可选") String extension,
            @ToolParam(name = "start", description = "起始时间 ISO-8601 字符串，可选") String start,
            @ToolParam(name = "end", description = "结束时间 ISO-8601 字符串，可选") String end,
            @ToolParam(name = "topK", description = "返回结果数量，可选，默认8") int topK) {

        Instant startInstant = parseInstantNullable(start);
        Instant endInstant = parseInstantNullable(end);
        int k = topK > 0 && topK <= 50 ? topK : defaultTopK;

        // SPEC-FILE-017b: degrade to time/dir listing when semantic search unavailable.
        if (!hasSemanticIndex()) {
            return fallbackListing(watchRoot, extension, startInstant, endInstant, k,
                    "Semantic index not available, returned recent files instead");
        }
        try {
            float[] vector = embeddingClient.embedSingle(query);
            List<FileSemanticIndex.SearchHit> hits = semanticIndex.search(
                    vector, k, startInstant, endInstant, watchRoot, extension);
            List<Map<String, Object>> results = new ArrayList<>();
            for (FileSemanticIndex.SearchHit hit : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("score", hit.score());
                item.put("path", hit.path());
                item.put("watchRoot", hit.watchRoot());
                item.put("extension", hit.extension());
                item.put("summary", hit.summary());
                item.put("mainTopics", hit.mainTopics());
                results.add(item);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("results", results);
            return writeJson(out);
        } catch (Exception e) {
            log.warn("searchFiles semantic failed, falling back: {}", e.getMessage());
            return fallbackListing(watchRoot, extension, startInstant, endInstant, k,
                    "Semantic search failed: " + e.getMessage());
        }
    }

    @Tool(description = """
            按最后修改时间列出被监控目录中已索引的文件（无需 embedding）。
            watchRoot/start/end 均可选；start/end 为 ISO-8601 时间字符串。limit 可选，默认20。""")
    public String listRecentFiles(
            @ToolParam(name = "watchRoot", description = "监控根目录绝对路径过滤，可选") String watchRoot,
            @ToolParam(name = "start", description = "起始时间 ISO-8601 字符串，可选") String start,
            @ToolParam(name = "end", description = "结束时间 ISO-8601 字符串，可选") String end,
            @ToolParam(name = "limit", description = "返回数量上限，可选，默认20") int limit) {
        int lim = limit > 0 && limit <= 200 ? limit : 20;
        return fallbackListing(watchRoot, null, parseInstantNullable(start),
                parseInstantNullable(end), lim, null);
    }

    @Tool(description = "查询指定文件路径的已生成摘要、主题关键词与索引状态。path 为文件绝对路径。")
    public String getFileSummary(
            @ToolParam(name = "path", description = "文件绝对路径") String path) {
        try {
            FileRecord rec = store.findByPath(path);
            if (rec == null) {
                return "{\"error\": \"File not tracked: " + safe(path) + "\"}";
            }
            return writeJson(formatRecord(rec, true));
        } catch (Exception e) {
            return "{\"error\": \"" + safe(e.getMessage()) + "\"}";
        }
    }

    @Tool(description = "查询各监控根目录下文件索引的状态计数（PENDING/INDEXED/FAILED/SKIPPED/DELETED）。")
    public String fileIndexStatus() {
        try {
            Map<String, Map<String, Long>> counts = store.statusCountsByWatchRoot();
            return writeJson(counts);
        } catch (Exception e) {
            return "{\"error\": \"" + safe(e.getMessage()) + "\"}";
        }
    }

    // ── helpers ──

    private String fallbackListing(String watchRoot, String extension, Instant start,
                                   Instant end, int limit, String note) {
        try {
            List<FileRecord> records = store.queryByTime(start, end, watchRoot, extension, limit);
            List<Map<String, Object>> results = new ArrayList<>();
            for (FileRecord rec : records) {
                results.add(formatRecord(rec, false));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("results", results);
            if (note != null) out.put("note", note);
            return writeJson(out);
        } catch (Exception e) {
            return "{\"error\": \"" + safe(e.getMessage()) + "\"}";
        }
    }

    private static Map<String, Object> formatRecord(FileRecord rec, boolean detailed) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", rec.absolutePath());
        map.put("relativePath", rec.relativePath());
        map.put("watchRoot", rec.watchRoot());
        map.put("extension", rec.extension());
        map.put("status", rec.status().name());
        map.put("summary", rec.summary());
        map.put("mainTopics", rec.mainTopics());
        map.put("lastModified", rec.lastModified() != null ? rec.lastModified().toString() : null);
        if (detailed) {
            map.put("sizeBytes", rec.sizeBytes());
            map.put("lastIndexedAt", rec.lastIndexedAt() != null ? rec.lastIndexedAt().toString() : null);
            map.put("model", rec.model());
            map.put("promptVersion", rec.promptVersion());
            if (rec.lastError() != null) map.put("lastError", rec.lastError());
        }
        return map;
    }

    private static String writeJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{\"error\": \"Failed to serialize result\"}";
        }
    }

    /** Accepts "2026-06-15T00:00:00Z" and "2026-06-15T00:00:00" (treated as local). */
    private static Instant parseInstantNullable(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.length() > 19 && (s.charAt(19) == 'Z' || s.charAt(19) == '+' || s.charAt(19) == '-')) {
            return Instant.parse(s);
        }
        return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant();
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }
}
