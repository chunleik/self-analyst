package com.selfanalyst.config;

import java.util.Set;

/** 配置生效策略的唯一声明；专用文件设置接口另外报告其实际已应用的键。 */
public final class ConfigPolicy {
    private ConfigPolicy() {}
    public static final Set<String> LLM = Set.of(
            "llm.api-key", "llm.base-url", "llm.model", "llm.temperature");
    public static final Set<String> NEO4J = Set.of(
            "neo4j.enabled", "neo4j.uri", "neo4j.database", "neo4j.username",
            "neo4j.password-env", "neo4j.namespace", "neo4j.timeout-seconds");
    private static final Set<String> DYNAMIC = Set.of(
            "agent.allowAgentTasks", "agent.cacheSummaries", "desktop.hideToTray", "desktop.autoOpenWindow");
    public static boolean requiresRestart(String key) {
        return SupportedKeys.contains(key) && !LLM.contains(key) && !NEO4J.contains(key) && !DYNAMIC.contains(key);
    }
    public static String component(String key) {
        if (NEO4J.contains(key)) return "neo4j";
        if (LLM.contains(key)) return "llm";
        if (key.startsWith("embedding.")) return "embedding";
        if (key.startsWith("file.")) return "file";
        if (key.startsWith("events.")) return "events";
        if (key.startsWith("llm.budget.")) return "budget";
        if (key.startsWith("websearch.")) return "websearch";
        return "application";
    }
}
