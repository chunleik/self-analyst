package com.selfanalyst.config;

import java.net.URI;
import java.util.*;

/** 一次模型版本的全部可热更新参数；toString 永不包含凭证。 */
public record LlmSettings(String apiKey, String baseUrl, String model, double temperature,
                          String provider, String chatgptAccount, String chatgptModel) {
    public LlmSettings(String apiKey, String baseUrl, String model, double temperature) {
        this(apiKey, baseUrl, model, temperature, "api-key", "", "");
    }
    public boolean usesChatGpt() { return "chatgpt".equals(provider); }
    public String requestModel() { return usesChatGpt() ? chatgptModel : model; }
    public String requestBaseUrl() { return usesChatGpt() ? "https://api.openai.com/v1" : baseUrl; }
    public boolean apiKeyConfigured() { return apiKey != null && !apiKey.isBlank() && !apiKey.contains("CHANGE_ME"); }
    public static LlmSettings from(Config config) {
        return new LlmSettings(config.llmApiKey(), config.llmBaseUrl(), config.llmModel(),
                config.llmTemperature(), config.llmProvider(), config.llmChatgptAccount(), config.llmChatgptModel());
    }
    /** Configuration readiness only; runtime also checks the selected account session. */
    public boolean available() {
        return usesChatGpt() ? !chatgptAccount.isBlank() && !chatgptModel.isBlank() : apiKeyConfigured();
    }
    public static void validate(Properties values) {
        try {
            if (!Set.of("api-key", "chatgpt").contains(values.getProperty("llm.provider", "api-key")))
                throw new IllegalArgumentException("Unsupported LLM provider");
            for (String key : List.of("llm.chatgpt-account", "llm.chatgpt-model")) {
                String value = values.getProperty(key, "");
                if (value.length() > 256 || value.chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("Invalid ChatGPT selection");
            }
            double temperature = Double.parseDouble(values.getProperty("llm.temperature", "0.7"));
            if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)
                throw new IllegalArgumentException("llm.temperature 必须位于 0..2");
            URI url = URI.create(values.getProperty("llm.base-url", "https://api.openai.com/v1"));
            if (!Set.of("http", "https").contains(String.valueOf(url.getScheme()).toLowerCase(Locale.ROOT))
                    || url.getHost() == null || url.getUserInfo() != null)
                throw new IllegalArgumentException("llm.base-url 必须是带主机的 HTTP(S) 地址");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("LLM 温度格式无效");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("LLM 参数无效：请检查连接方式、账户、模型、温度和地址");
        }
    }
    public Map<String, String> properties() {
        return Map.of("llm.api-key", apiKey, "llm.base-url", baseUrl, "llm.model", model,
                "llm.temperature", Double.toString(temperature), "llm.provider", provider,
                "llm.chatgpt-account", chatgptAccount, "llm.chatgpt-model", chatgptModel);
    }
    @Override public String toString() { return "LlmSettings[redacted]"; }
}
