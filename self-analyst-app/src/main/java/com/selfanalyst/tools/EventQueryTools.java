package com.selfanalyst.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import com.selfanalyst.wiki.WikiPrivacyPolicy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.function.Supplier;

public class EventQueryTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final int MAX_EVENT_LIMIT = 500;
    static final int MAX_TOOL_RESULT_CHARS = 80_000;

    private final HttpClient client;
    private final String baseUrl;
    private final Duration timeout;
    private final Supplier<WikiPrivacyPolicy> privacy;

    public EventQueryTools(String baseUrl, int timeoutMillis) {
        this(baseUrl, timeoutMillis, WikiPrivacyPolicy::none);
    }

    public EventQueryTools(String baseUrl, int timeoutMillis, Supplier<WikiPrivacyPolicy> privacy) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.privacy = Objects.requireNonNull(privacy);
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    @Tool(description = "列出所有事件桶及其元信息，包括 bucket ID、类型、事件数量等")
    public String listBuckets() {
        return get("buckets/?include_hidden=true");
    }

    @Tool(description = "从指定的事件桶获取 heartbeat 合并后的投影事件。limit 控制返回条数，" +
            "startTime 和 endTime 为 UTC 时间的 ISO-8601 格式（如 2026-06-14T16:00:00 对应北京时间6月15日0点），可选。结果按最新隐私配置过滤。")
    public String queryEvents(
            @ToolParam(name = "bucketId", description = "事件桶 ID，如 watcher-window_<hostname>")
            String bucketId,
            @ToolParam(name = "limit", description = "返回事件数上限，默认 100")
            int limit,
            @ToolParam(name = "startTime", description = "起始时间 ISO-8601，可选")
            String startTime,
            @ToolParam(name = "endTime", description = "结束时间 ISO-8601，可选")
            String endTime) {

        WikiPrivacyPolicy policy = privacy.get();
        if (policy == null) return "{\"error\":\"EVENT_PRIVACY_UNAVAILABLE\"}";
        int boundedLimit = limit <= 0 ? 100 : Math.min(limit, MAX_EVENT_LIMIT);
        StringBuilder url = new StringBuilder("buckets/")
                .append(bucketId).append("/events?limit=").append(boundedLimit);
        if (startTime != null && !startTime.isBlank()) {
            url.append("&start=").append(startTime);
        }
        if (endTime != null && !endTime.isBlank()) {
            url.append("&end=").append(endTime);
        }
        return get(url.toString(), policy);
    }

    @Tool(description = "执行事件投影的 AQL 查询。这是最灵活的数据查询方式。" +
            "重要：事件服务所有时间戳均为 UTC，timeperiod 也必须用 UTC 时间。" +
            "例如北京时间 2026-06-15 00:00—23:59 对应 UTC 范围为 2026-06-14T16:00:00/2026-06-15T16:00:00。" +
            "aqlQuery 为 AQL 查询语句，多条语句用分号分隔，最后一条必须是 RETURN = ...;" +
            "常用 AQL 函数：query_bucket(), find_bucket(), filter_keyvals(), " +
            "filter_period_intersect(), merge_events_by_keys(), sort_by_duration(), categorize()。" +
            "事件结果按最新隐私配置过滤；服务端聚合数值仍可能包含被隐藏活动的时长，不代表可见活动总量。" +
            "禁止使用 tag 覆盖 app、title、context_title、url_host、private_browsing；其他自定义标签可用。")
    public String executeAQL(
            @ToolParam(name = "aqlQuery", description = "AQL 查询语句")
            String aqlQuery,
            @ToolParam(name = "timeperiod", description = "时间范围（UTC），如 2026-06-14T16:00:00/2026-06-15T16:00:00 对应北京时间6月15日全天")
            String timeperiod) {

        WikiPrivacyPolicy policy = privacy.get();
        if (policy == null) return "{\"error\":\"EVENT_PRIVACY_UNAVAILABLE\"}";
        try {
            List<String> lines = Arrays.stream(aqlQuery.split(";"))
                    .map(String::trim).filter(line -> !line.isEmpty()).map(line -> line + ";").toList();
            AqlPrivacyGuard.validate(String.join(" ", lines));
            String body = JSON.writeValueAsString(Map.of("timeperiods", List.of(timeperiod), "query", lines));
            return post("query/", body, policy);
        } catch (AqlPrivacyGuard.UnsafeQueryException unsafe) {
            return "{\"error\":\"EVENT_PRIVACY_UNSAFE_AQL\"}";
        } catch (IOException | IllegalArgumentException | NullPointerException invalid) {
            return "{\"error\":\"EVENT_SERVICE_INVALID_AQL\"}";
        }
    }

    @Tool(description = "获取事件服务运行信息，包括版本、主机名等")
    public String getServerInfo() {
        return get("info");
    }

    @Tool(description = "获取事件服务当前的设置，包括自定义分类规则")
    public String getSettings() {
        return get("settings");
    }

    private String get(String path) {
        return get(path, null);
    }

    private String get(String path, WikiPrivacyPolicy policy) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return response(resp, policy);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\":\"EVENT_SERVICE_INTERRUPTED\"}";
        } catch (IOException | IllegalArgumentException e) {
            return "{\"error\":\"EVENT_SERVICE_REQUEST_FAILED\"}";
        }
    }

    private String post(String path, String body, WikiPrivacyPolicy policy) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return response(resp, policy, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\":\"EVENT_SERVICE_INTERRUPTED\"}";
        } catch (IOException | IllegalArgumentException e) {
            return "{\"error\":\"EVENT_SERVICE_REQUEST_FAILED\"}";
        }
    }

    private static String response(HttpResponse<String> response, WikiPrivacyPolicy policy) {
        return response(response, policy, false);
    }

    private static String response(HttpResponse<String> response, WikiPrivacyPolicy policy, boolean aql) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return "{\"error\":\"EVENT_SERVICE_HTTP_" + response.statusCode() + "\"}";
        }
        String body = policy == null ? response.body() : aql
                ? EventPrivacyFilter.filterAql(response.body(), policy)
                : EventPrivacyFilter.filter(response.body(), policy);
        return boundResult(body);
    }

    private static String boundResult(String result) {
        if (result == null || result.length() <= MAX_TOOL_RESULT_CHARS) return result;
        int preview = (MAX_TOOL_RESULT_CHARS - 200) / 2;
        return result.substring(0, preview)
                + "\n...(事件查询工具结果已截断；请缩小时间范围或降低 limit)...\n"
                + result.substring(result.length() - preview);
    }
}
