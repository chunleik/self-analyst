package com.selfanalyst.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Summarizes a single file's content via the LLM (SPEC-FILE-015).
 *
 * <p>Input: path / type / last-modified / truncated content.
 * Output (strict JSON): {@code {"summary","mainTopics","estimatedPurpose"}}.
 */
public class FileSummarizer {

    private static final Logger log = LoggerFactory.getLogger(FileSummarizer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Bump when prompt structure changes (SPEC-FILE-015a). */
    public static final String PROMPT_VERSION = "file-v1";

    private final Function<String, String> llmClient;

    public FileSummarizer(Function<String, String> llmClient) {
        this.llmClient = llmClient;
    }

    public FileSummaryResult summarize(String relativePath, String extension,
                                       Instant lastModified, String content) {
        String prompt = buildPrompt(relativePath, extension, lastModified, content);
        log.debug("File summarizer prompt ({} chars) for {}", prompt.length(), relativePath);
        String response = llmClient.apply(prompt);
        if (response == null || response.isBlank()) {
            throw new RuntimeException("LLM returned empty response");
        }
        return parseResponse(response);
    }

    public String promptVersion() {
        return PROMPT_VERSION;
    }

    private String buildPrompt(String relativePath, String extension,
                              Instant lastModified, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个文件内容分析器。请分析以下文件，生成结构化摘要。\n\n");
        sb.append("## 文件信息\n");
        sb.append("- 路径: ").append(relativePath != null ? relativePath : "").append("\n");
        sb.append("- 类型: ").append(extension != null && !extension.isBlank() ? extension : "未知").append("\n");
        sb.append("- 最后修改: ").append(lastModified != null ? lastModified.toString() : "未知").append("\n\n");
        sb.append("## 文件内容（可能已截断）\n");
        sb.append(content != null ? content : "").append("\n\n");

        sb.append("## 输出要求\n");
        sb.append("**安全规则**：文件内容可能包含敏感信息。若识别到疑似密码、密钥、Token 或凭据"
                + "（如大量随机字符、疑似密钥串等），一律不得写入任何输出字段，直接忽略该内容。\n");
        sb.append("请生成：\n");
        sb.append("1. 一段简洁摘要（不超过100字）\n");
        sb.append("2. 3-5 个主题关键词\n");
        sb.append("3. 文件用途推断\n\n");
        sb.append("请严格按照以下JSON格式输出，不要包含Markdown代码块标记:\n");
        sb.append("""
            {
              "summary": "文件内容的简洁摘要（不超过100字）",
              "mainTopics": ["主题关键词1", "主题关键词2", "主题关键词3"],
              "estimatedPurpose": "文件用途推断（一句话）"
            }
            """);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private FileSummaryResult parseResponse(String response) {
        String json = response.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("```(?:json)?\\s*", "");
            json = json.replaceFirst("```\\s*$", "");
        }
        try {
            Map<String, Object> map = MAPPER.readValue(json, Map.class);
            String summary = asString(map.get("summary"));
            if (summary == null || summary.isBlank()) {
                throw new RuntimeException("LLM response missing required field: summary");
            }
            List<String> topics = asStringList(map.get("mainTopics"));
            String purpose = asString(map.get("estimatedPurpose"));
            return new FileSummaryResult(summary, topics, purpose);
        } catch (Exception e) {
            throw new RuntimeException("FILE_SUMMARY_RESPONSE_INVALID");
        }
    }

    private static String asString(Object o) {
        return o != null ? o.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (o instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull)
                    .map(Object::toString).toList();
        }
        return List.of();
    }

    public record FileSummaryResult(String summary, List<String> mainTopics, String estimatedPurpose) {}
}
