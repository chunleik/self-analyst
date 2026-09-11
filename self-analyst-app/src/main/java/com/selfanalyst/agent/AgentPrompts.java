package com.selfanalyst.agent;

import com.selfanalyst.i18n.Lang;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 系统提示词的可测装配点（SPEC-I18N-PROMPT-001/002/003a/004/005）。
 *
 * <p>提示词正文位于 {@code src/main/resources/prompts/agent/}；本类仅负责按语言、
 * 当前时间和运行时能力选择资源并替换占位符。
 */
final class AgentPrompts {

    private AgentPrompts() {}

    /** 装配主 Agent 的基础系统提示；能力片段由 Agent 初始化时的可用组件决定。 */
    static String systemPrompt(Lang lang, String memorySummary, boolean wikiEnabled,
                               boolean semanticEnabled, boolean hasFileTools,
                               boolean hasConfigTools, ZonedDateTime now) {
        String code = languageCode(lang);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                "yyyy-MM-dd HH:mm:ss EEEE (z, OOOO)", (lang != null ? lang : Lang.english()).locale());

        return PromptResources.render("system." + code + ".md", Map.of(
                "current_time", now.format(formatter),
                "initial_memory_summary", memorySummary == null ? "" : memorySummary,
                "wiki_context", wikiContext(code, wikiEnabled, semanticEnabled),
                "file_tools_context", hasFileTools
                        ? section(literal("file-tools." + code + ".md")) : "",
                "web_search_context", section(literal("web-search." + code + ".md")),
                "config_tools_context", hasConfigTools
                        ? section(literal("config-tools." + code + ".md")) : ""));
    }

    /** 纯改写系统提示，按有效语言选用（SPEC-I18N-PROMPT-001）。 */
    static String plainCompletionPrompt(Lang lang) {
        return literal("plain-completion." + languageCode(lang) + ".md") + "\n";
    }

    /** 每次 Agent 调用时追加最新长期记忆，不复用初始化时的旧快照。 */
    public static String transientMemoryContext(Lang lang, String memorySummary) {
        String code = languageCode(lang);
        String summary = memorySummary == null || memorySummary.isBlank()
                ? literal("memory-empty." + code + ".md").strip()
                : memorySummary;
        return PromptResources.render("memory-context." + code + ".md",
                Map.of("memory_summary", summary)) + "\n";
    }

    private static String wikiContext(String code, boolean wikiEnabled,
                                      boolean semanticEnabled) {
        if (!wikiEnabled) {
            return section(literal("wiki-disabled." + code + ".md"));
        }
        String semanticContext = semanticEnabled
                ? literal("wiki-semantic." + code + ".md") : "";
        return section(PromptResources.render("wiki-enabled." + code + ".md",
                Map.of("wiki_semantic_context", semanticContext)));
    }

    private static String literal(String resourceName) {
        return PromptResources.render(resourceName, Map.of());
    }

    private static String section(String content) {
        return "\n\n" + content;
    }

    private static String languageCode(Lang lang) {
        return lang != null ? lang.resource() : "en";
    }
}
