package com.selfanalyst.config;

import java.net.URI;
import java.util.*;

/** 一次模型版本的全部可热更新参数；toString 永不包含凭证。 */
public record LlmSettings(String apiKey, String baseUrl, String model, double temperature, int maxTokens) {
    public static LlmSettings from(Config config) {
        return new LlmSettings(config.llmApiKey(), config.llmBaseUrl(), config.llmModel(),
                config.llmTemperature(), config.llmMaxTokens());
    }
    public boolean available() { return apiKey != null && !apiKey.isBlank() && !apiKey.contains("CHANGE_ME"); }
    public static void validate(Properties values) {
        try {
            double temperature = Double.parseDouble(values.getProperty("llm.temperature", "0.7"));
            if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)
                throw new IllegalArgumentException("llm.temperature 必须位于 0..2");
            int tokens = Integer.parseInt(values.getProperty("llm.max-tokens", "2048"));
            if (tokens < 0) throw new IllegalArgumentException("llm.max-tokens 必须为非负整数");
            URI url = URI.create(values.getProperty("llm.base-url", "https://api.openai.com/v1"));
            if (!Set.of("http", "https").contains(String.valueOf(url.getScheme()).toLowerCase(Locale.ROOT))
                    || url.getHost() == null || url.getUserInfo() != null)
                throw new IllegalArgumentException("llm.base-url 必须是带主机的 HTTP(S) 地址");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("LLM 温度或输出上限格式无效");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("LLM 参数无效：请检查 llm.temperature、llm.max-tokens 和 llm.base-url");
        }
    }
    public Map<String, String> properties() {
        return Map.of("llm.api-key", apiKey, "llm.base-url", baseUrl, "llm.model", model,
                "llm.temperature", Double.toString(temperature), "llm.max-tokens", Integer.toString(maxTokens));
    }
    @Override public String toString() { return "LlmSettings[redacted]"; }
}
