package com.selfanalyst.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ActivityWatchTools {

    static final int MAX_EVENT_LIMIT = 500;
    static final int MAX_TOOL_RESULT_CHARS = 80_000;

    private final HttpClient client;
    private final String baseUrl;
    private final Duration timeout;

    public ActivityWatchTools(String baseUrl, int timeoutMillis) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    @Tool(description = "列出所有 ActivityWatch 数据桶及其元信息，包括 bucket ID、类型、事件数量等")
    public String listBuckets() {
        return get("buckets/?include_hidden=true");
    }

    @Tool(description = "从指定的 bucket 获取 heartbeat 合并后的 ActivityWatch 投影事件。limit 控制返回条数，" +
            "startTime 和 endTime 为 UTC 时间的 ISO-8601 格式（如 2026-06-14T16:00:00 对应北京时间6月15日0点），可选")
    public String queryEvents(
            @ToolParam(name = "bucketId", description = "bucket ID，如 watcher-window_<hostname>")
            String bucketId,
            @ToolParam(name = "limit", description = "返回事件数上限，默认 100")
            int limit,
            @ToolParam(name = "startTime", description = "起始时间 ISO-8601，可选")
            String startTime,
            @ToolParam(name = "endTime", description = "结束时间 ISO-8601，可选")
            String endTime) {

        int boundedLimit = limit <= 0 ? 100 : Math.min(limit, MAX_EVENT_LIMIT);
        StringBuilder url = new StringBuilder("buckets/")
                .append(bucketId).append("/events?limit=").append(boundedLimit);
        if (startTime != null && !startTime.isBlank()) {
            url.append("&start=").append(startTime);
        }
        if (endTime != null && !endTime.isBlank()) {
            url.append("&end=").append(endTime);
        }
        return get(url.toString());
    }

    @Tool(description = "执行 ActivityWatch 的 AQL 查询。这是最灵活的数据查询方式。" +
            "重要：AW 所有时间戳均为 UTC，timeperiod 也必须用 UTC 时间。" +
            "例如北京时间 2026-06-15 00:00—23:59 对应 UTC 范围为 2026-06-14T16:00:00/2026-06-15T16:00:00。" +
            "aqlQuery 为 AQL 查询语句，多条语句用分号分隔，最后一条必须是 RETURN = ...;" +
            "常用 AQL 函数：query_bucket(), find_bucket(), filter_keyvals(), " +
            "filter_period_intersect(), merge_events_by_keys(), sort_by_duration(), categorize()")
    public String executeAQL(
            @ToolParam(name = "aqlQuery", description = "AQL 查询语句")
            String aqlQuery,
            @ToolParam(name = "timeperiod", description = "时间范围（UTC），如 2026-06-14T16:00:00/2026-06-15T16:00:00 对应北京时间6月15日全天")
            String timeperiod) {

        String[] queryLines = aqlQuery.split(";");
        StringBuilder body = new StringBuilder("{")
                .append("\"timeperiods\": [\"").append(timeperiod).append("\"],")
                .append("\"query\": [");
        int added = 0;
        for (String rawLine : queryLines) {
            String line = rawLine.trim();
            if (!line.isEmpty()) {
                if (added > 0) body.append(", ");
                body.append("\"").append(line.replace("\"", "\\\"")).append(";\"");
                added++;
            }
        }
        body.append("]}");
        return post("query/", body.toString());
    }

    @Tool(description = "获取 ActivityWatch 服务器运行信息，包括版本、主机名等")
    public String getServerInfo() {
        return get("info");
    }

    @Tool(description = "获取 ActivityWatch 当前的设置，包括自定义分类规则")
    public String getSettings() {
        return get("settings");
    }

    private String get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return boundResult(resp.body());
        } catch (IOException | InterruptedException e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String post(String path, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return boundResult(resp.body());
        } catch (IOException | InterruptedException e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private static String boundResult(String result) {
        if (result == null || result.length() <= MAX_TOOL_RESULT_CHARS) return result;
        int preview = (MAX_TOOL_RESULT_CHARS - 200) / 2;
        return result.substring(0, preview)
                + "\n...(ActivityWatch tool result truncated; narrow the time range or limit)...\n"
                + result.substring(result.length() - preview);
    }
}
