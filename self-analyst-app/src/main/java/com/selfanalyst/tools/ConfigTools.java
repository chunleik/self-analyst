package com.selfanalyst.tools;

import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.config.RemovedEventConfig;
import com.selfanalyst.config.TomlValidationException;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.Properties;
import java.util.Set;

/**
 * Agent tools for reading and writing SelfAnalyst user configuration.
 * Exposed to the ReActAgent so users can modify settings through chat.
 */
public class ConfigTools {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "app.language",
            "llm.api-key", "llm.base-url", "llm.model", "llm.temperature",
            "events.mode", "events.port",
            "events.collection.window", "events.collection.afk", "events.collection.title.enabled",
            "agent.summaryRefreshMinutes", "agent.allowAgentTasks", "agent.cacheSummaries",
            "desktop.hideToTray", "desktop.autoOpenWindow", "desktop.autoStartBackend",
            "embedding.enabled", "embedding.base-url", "embedding.api-key",
            "embedding.model", "embedding.dimensions", "embedding.send-encoding-format",
            "websearch.enabled", "websearch.mcp-url", "websearch.api-key",
            "llm.max-tokens", "llm.agent.maxIters", "desktop.summary.maxTimelineLlm",
            "llm.budget.mode", "llm.budget.dailyTokens", "llm.budget.warnRatio"
    );



    private final UserConfigStore userStore;
    public ConfigTools(UserConfigStore userStore) {
        this.userStore = userStore;
    }

    @Tool(description = "获取 SelfAnalyst 当前所有配置项的有效值（含默认值）。" +
            "可修改的配置键包括：app.language（zh/en/auto，需重启后端生效）；" +
            "llm.api-key、llm.base-url、llm.model、llm.temperature；" +
            "websearch.enabled、websearch.mcp-url、websearch.api-key；" +
            "agent.summaryRefreshMinutes、agent.allowAgentTasks、agent.cacheSummaries；" +
            "desktop.hideToTray、desktop.autoOpenWindow、desktop.autoStartBackend；" +
            "events.collection.window、events.collection.afk、events.collection.title.enabled；" +
            "embedding.enabled、embedding.model、embedding.base-url、embedding.api-key；" +
            "token 用量限制 llm.max-tokens、llm.agent.maxIters、desktop.summary.maxTimelineLlm、" +
            "llm.budget.mode（off/warn/block）、llm.budget.dailyTokens、llm.budget.warnRatio。")
    public String getConfig() {
        Properties eff = userStore.application().saved().properties();
        StringBuilder sb = new StringBuilder("当前 SelfAnalyst 配置：\n\n");

        appendSection(sb, "应用", new String[][]{
                {"app.language", eff.getProperty("app.language", "auto"), null},
        });
        appendSection(sb, "LLM", new String[][]{
                {"llm.api-key",     eff.getProperty("llm.api-key",     ""),                          "masked"},
                {"llm.base-url",    eff.getProperty("llm.base-url",    "https://api.openai.com/v1"), null},
                {"llm.model",       eff.getProperty("llm.model",       "gpt-4o"),                    null},
                {"llm.temperature", eff.getProperty("llm.temperature", "0.7"),                       null},
        });
        appendSection(sb, "联网搜索", new String[][]{
                {"websearch.enabled", eff.getProperty("websearch.enabled", "false"),                                 null},
                {"websearch.mcp-url", eff.getProperty("websearch.mcp-url", "https://search.parallel.ai/mcp"),       null},
                {"websearch.api-key", eff.getProperty("websearch.api-key", ""),                                      "masked"},
        });
        appendSection(sb, "Agent", new String[][]{
                {"agent.summaryRefreshMinutes", eff.getProperty("agent.summaryRefreshMinutes", "5"),     null},
                {"agent.allowAgentTasks",       eff.getProperty("agent.allowAgentTasks",       "false"), null},
                {"agent.cacheSummaries",        eff.getProperty("agent.cacheSummaries",        "true"),  null},
        });
        appendSection(sb, "桌面", new String[][]{
                {"desktop.hideToTray",       eff.getProperty("desktop.hideToTray",       "true"), null},
                {"desktop.autoOpenWindow",   eff.getProperty("desktop.autoOpenWindow",   "true"), null},
                {"desktop.autoStartBackend", eff.getProperty("desktop.autoStartBackend", "true"), null},
        });
        appendSection(sb, "采集", new String[][]{
                {"events.collection.window",  eff.getProperty("events.collection.window",  "true"),  null},
                {"events.collection.afk",     eff.getProperty("events.collection.afk",     "true"),  null},
                {"events.collection.title.enabled", eff.getProperty("events.collection.title.enabled", "true"),  null},
        });
        appendSection(sb, "Embedding", new String[][]{
                {"embedding.enabled",  eff.getProperty("embedding.enabled",  "true"),                          null},
                {"embedding.model",    eff.getProperty("embedding.model",    "text-embedding-3-small"),        null},
                {"embedding.base-url", eff.getProperty("embedding.base-url", "https://api.openai.com/v1"),    null},
                {"embedding.api-key",  eff.getProperty("embedding.api-key",  ""),                              "masked"},
        });
        appendSection(sb, "用量限制", new String[][]{
                {"llm.max-tokens",                  eff.getProperty("llm.max-tokens",                  "2048"),    null},
                {"llm.agent.maxIters",              eff.getProperty("llm.agent.maxIters",              "8"),       null},
                {"desktop.summary.maxTimelineLlm",  eff.getProperty("desktop.summary.maxTimelineLlm",  "4"),       null},
                {"llm.budget.mode",                 eff.getProperty("llm.budget.mode",                 "warn"),    null},
                {"llm.budget.dailyTokens",          eff.getProperty("llm.budget.dailyTokens",          "100000000"), null},
                {"llm.budget.warnRatio",            eff.getProperty("llm.budget.warnRatio",            "0.8"),     null},
        });

        sb.append("\n配置来源：").append(userStore.application().sourceSummary());
        return sb.toString();
    }

    @Tool(description = "更新 SelfAnalyst 的一个配置项并持久化到文件。" +
            "key 为点分配置键（如 llm.model、websearch.enabled）；" +
            "value 为新值，布尔值用 true/false，数字直接写数字，字符串直接写值。" +
            "LLM 配置在新一轮生效，部分其它配置（如 websearch.enabled）需重启，返回信息中会注明。" +
            "value 为空字符串时删除用户覆盖，回退到环境变量或默认值。")
    public String setConfigValue(
            @ToolParam(name = "key", description = "配置键，如 llm.model、websearch.enabled、agent.summaryRefreshMinutes")
            String key,
            @ToolParam(name = "value", description = "新配置值，留空则删除用户覆盖并使用默认值")
            String value
    ) {
        if (key == null || key.isBlank()) {
            return "错误：配置键不能为空。";
        }
        try {
            RemovedEventConfig.rejectKeys(java.util.List.of(key));
        } catch (TomlValidationException invalid) {
            return "错误：" + invalid.getMessage();
        }
        if (!ALLOWED_KEYS.contains(key)) {
            return "不支持修改配置键 '" + key + "'。支持的键：" +
                    String.join("、", ALLOWED_KEYS.stream().sorted().toList()) + "。";
        }
        try {
            var result = userStore.application().update(java.util.Map.of(key, value == null ? "" : value));
            return "配置已保存：" + key + "\n应用结果：" + result.application()
                    + (result.restartRequired().isEmpty() ? "" : "\n需重启：" + result.restartRequired());

        } catch (TomlValidationException invalid) {
            return "保存失败：" + invalid.getMessage();
        } catch (Exception e) {
            return "保存失败：请检查配置参数或文件写入权限。";
        }
    }

    private static void appendSection(StringBuilder sb, String title, String[][] rows) {
        sb.append("[").append(title).append("]\n");
        for (String[] row : rows) {
            String val = "masked".equals(row[2]) ? maskKey(row[1]) : row[1];
            sb.append(row[0]).append(" = ").append(val).append("\n");
        }
        sb.append("\n");
    }

    private static String maskKey(String key) {
        if (key == null || key.isBlank()) return "(未设置)";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
