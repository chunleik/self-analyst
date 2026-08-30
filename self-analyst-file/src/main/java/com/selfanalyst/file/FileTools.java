package com.selfanalyst.file;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Agent-facing metadata-only file tools (SPEC-FILE-060..062). */
public class FileTools {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private final FileWatchStore store;
    private volatile Set<String> activeWatchRoots = Set.of();

    public FileTools(FileWatchStore store) {
        this.store = store;
    }

    public void updateWatchRoots(List<Path> watchRoots) {
        if (watchRoots == null || watchRoots.isEmpty()) {
            activeWatchRoots = Set.of();
            return;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (Path root : watchRoots) {
            if (root != null) normalized.add(root.toAbsolutePath().normalize().toString());
        }
        activeWatchRoots = Set.copyOf(normalized);
    }

    @Tool(description = """
            按文件名或相对路径检索被监控目录中的文件，并可按目录、扩展名和修改时间过滤。
            仅查询本地文件系统元数据，不读取文件正文，也不调用 LLM 或 embedding 服务。""")
    public String searchFiles(
            @ToolParam(name = "query", description = "文件名或相对路径关键词") String query,
            @ToolParam(name = "watchRoot", description = "监控根目录绝对路径过滤，可选") String watchRoot,
            @ToolParam(name = "extension", description = "扩展名过滤（不含点），可选") String extension,
            @ToolParam(name = "start", description = "修改时间起点 ISO-8601，可选") String start,
            @ToolParam(name = "end", description = "修改时间终点 ISO-8601，可选") String end,
            @ToolParam(name = "limit", description = "返回数量，可选，默认20") int limit) {
        try {
            int lim = limit > 0 && limit <= 200 ? limit : 20;
            List<String> roots = activeRootsForQuery(watchRoot);
            if (roots.isEmpty()) return recordsPayload(List.of());
            List<FileRecord> records = store.queryMetadataForRoots(query, parseInstantNullable(start),
                    parseInstantNullable(end), roots, extension, lim);
            return recordsPayload(records);
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    @Tool(description = "按最后修改时间列出被监控目录中的文件元数据。")
    public String listRecentFiles(
            @ToolParam(name = "watchRoot", description = "监控根目录绝对路径过滤，可选") String watchRoot,
            @ToolParam(name = "start", description = "修改时间起点 ISO-8601，可选") String start,
            @ToolParam(name = "end", description = "修改时间终点 ISO-8601，可选") String end,
            @ToolParam(name = "limit", description = "返回数量，可选，默认20") int limit) {
        try {
            int lim = limit > 0 && limit <= 200 ? limit : 20;
            List<String> roots = activeRootsForQuery(watchRoot);
            if (roots.isEmpty()) return recordsPayload(List.of());
            return recordsPayload(store.queryMetadataForRoots(null, parseInstantNullable(start),
                    parseInstantNullable(end), roots, null, lim));
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    @Tool(description = "查询指定绝对路径的文件名、路径、大小、创建时间和修改时间。")
    public String getFileMetadata(
            @ToolParam(name = "path", description = "文件绝对路径") String path) {
        try {
            FileRecord rec = store.findByPath(path);
            return rec == null || rec.status() != FileStatus.COLLECTED
                    || !isActiveRoot(rec.watchRoot())
                    ? error("File not tracked in active roots: " + safe(path))
                    : writeJson(formatRecord(rec, true));
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    @Tool(description = "查询各监控根目录下文件元数据的采集状态计数。")
    public String fileCollectionStatus() {
        try {
            Map<String, Map<String, Long>> all = store.statusCountsByWatchRoot();
            Map<String, Map<String, Long>> active = new LinkedHashMap<>();
            for (String root : activeWatchRoots) {
                if (all.containsKey(root)) active.put(root, all.get(root));
            }
            return writeJson(active);
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    private String recordsPayload(List<FileRecord> records) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (FileRecord rec : records) results.add(formatRecord(rec, false));
        return writeJson(Map.of("results", results));
    }

    private List<String> activeRootsForQuery(String requestedRoot) {
        Set<String> roots = activeWatchRoots;
        if (roots.isEmpty()) return List.of();
        if (requestedRoot == null || requestedRoot.isBlank()) return List.copyOf(roots);
        try {
            String normalized = Path.of(requestedRoot).toAbsolutePath().normalize().toString();
            return roots.contains(normalized) ? List.of(normalized) : List.of();
        } catch (InvalidPathException ignored) {
            return List.of();
        }
    }

    private boolean isActiveRoot(String watchRoot) {
        if (watchRoot == null) return false;
        try {
            return activeWatchRoots.contains(
                    Path.of(watchRoot).toAbsolutePath().normalize().toString());
        } catch (InvalidPathException ignored) {
            return false;
        }
    }

    private static Map<String, Object> formatRecord(FileRecord rec, boolean detailed) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", fileName(rec));
        map.put("path", rec.absolutePath());
        map.put("relativePath", rec.relativePath());
        map.put("watchRoot", rec.watchRoot());
        map.put("extension", rec.extension());
        map.put("status", rec.status().name());
        map.put("sizeBytes", rec.sizeBytes());
        map.put("fileCreatedAt", text(rec.fileCreatedAt()));
        map.put("lastModified", text(rec.lastModified()));
        map.put("lastCollectedAt", text(rec.lastCollectedAt()));
        if (detailed) {
            map.put("firstSeenAt", text(rec.firstSeenAt()));
            if (rec.lastError() != null) map.put("lastError", rec.lastError());
        }
        return map;
    }

    private static String fileName(FileRecord rec) {
        try {
            Path name = Path.of(rec.absolutePath()).getFileName();
            return name != null ? name.toString() : rec.relativePath();
        } catch (InvalidPathException ignored) {
            return rec.relativePath();
        }
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }

    private static String writeJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"Failed to serialize result\"}";
        }
    }

    private static Instant parseInstantNullable(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 19 && (value.charAt(19) == 'Z'
                || value.charAt(19) == '+' || value.charAt(19) == '-')) {
            return Instant.parse(value);
        }
        return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
    }

    private static String error(String value) {
        return "{\"error\":\"" + safe(value) + "\"}";
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "'")
                .replace("\r", " ").replace("\n", " ");
    }
}
