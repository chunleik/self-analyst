package com.selfanalyst.wiki.semantic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.wiki.usage.UsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final String url;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final Duration timeout;
    private final boolean sendEncodingFormat;
    private final UsageRecorder usageRecorder;

    public OpenAiCompatibleEmbeddingClient(String baseUrl, String apiKey, String model,
                                            int dimensions, Duration timeout,
                                            boolean sendEncodingFormat) {
        this(baseUrl, apiKey, model, dimensions, timeout, sendEncodingFormat, UsageRecorder.NOOP);
    }

    public OpenAiCompatibleEmbeddingClient(String baseUrl, String apiKey, String model,
                                            int dimensions, Duration timeout,
                                            boolean sendEncodingFormat, UsageRecorder usageRecorder) {
        this.url = baseUrl.replaceAll("/+$", "") + "/embeddings";
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.timeout = timeout;
        this.sendEncodingFormat = sendEncodingFormat;
        this.usageRecorder = usageRecorder != null ? usageRecorder : UsageRecorder.NOOP;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", model);
            body.put("input", texts);
            if (sendEncodingFormat) {
                body.put("encoding_format", "float");
            }
            body.put("dimensions", dimensions);

            String bodyJson = MAPPER.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Embedding API returned HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body(), 200));
            }

            return parseResponse(resp.body(), texts.size());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Embedding request failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<float[]> parseResponse(String body, int expectedCount) {
        try {
            Map<String, Object> root = MAPPER.readValue(body, Map.class);
            recordUsage(root.get("usage"));
            List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");
            if (data == null || data.size() != expectedCount) {
                throw new RuntimeException("Expected " + expectedCount + " embeddings, got "
                        + (data != null ? data.size() : 0));
            }

            List<float[]> result = new ArrayList<>();
            for (Map<String, Object> item : data) {
                List<Number> embedding = (List<Number>) item.get("embedding");
                if (embedding.size() != dimensions) {
                    throw new RuntimeException("Embedding dimension mismatch: expected "
                            + dimensions + ", got " + embedding.size());
                }
                float[] vec = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vec[i] = embedding.get(i).floatValue();
                }
                result.add(vec);
            }
            return result;
        } catch (JsonProcessingException | ClassCastException e) {
            throw new RuntimeException("Failed to parse embedding response: " + e.getMessage(), e);
        }
    }

    /** 从 OpenAI 兼容响应的 usage 字段提取 token 并上报（embedding 仅有输入 token）。 */
    private void recordUsage(Object usageObj) {
        if (!(usageObj instanceof Map<?, ?> usage)) return;
        long tokens = asLong(usage.get("prompt_tokens"));
        if (tokens == 0) tokens = asLong(usage.get("total_tokens"));
        if (tokens > 0) {
            try {
                usageRecorder.recordTokens(tokens, 0);
            } catch (RuntimeException e) {
                log.debug("用量上报失败: {}", e.getMessage());
            }
        }
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
