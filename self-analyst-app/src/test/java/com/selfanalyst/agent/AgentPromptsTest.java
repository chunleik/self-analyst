package com.selfanalyst.agent;

import com.selfanalyst.i18n.Lang;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Agent system prompt i18n (SPEC-I18N-PROMPT-001/002/003a/004, TST-006/007/009). */
class AgentPromptsTest {

    @Test
    void additionalLanguageSelectsResourceAndMissingFragmentsFallBack() {
        var french = new Lang("fr", "Français", "fr-FR", "fr");
        assertTrue(AgentPrompts.plainCompletionPrompt(french).contains("Répondez en français"));
        assertTrue(AgentPrompts.transientMemoryContext(french, "原文").contains("原文"));
    }

    // Thursday, 2026-01-15, in UTC — used to assert locale-dependent weekday text.
    private static final ZonedDateTime FIXED = ZonedDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    @Test
    void englishPromptHasRespondInEnglishAndNoCjk() { // TST-006, PROMPT-002a/003a
        String p = AgentPrompts.systemPrompt(Lang.english(), "", true, true, true, true, FIXED);
        assertTrue(p.contains("Respond to the user in English."), "missing respond-in-English directive");
        assertTrue(p.contains("Suggest recording the following finding"), "missing english finding phrase");
        assertFalse(CJK.matcher(p).find(), "english prompt must not contain CJK characters");
    }

    @Test
    void chinesePromptHasRespondInChinese() { // TST-007, PROMPT-002b
        String p = AgentPrompts.systemPrompt(Lang.chinese(), "", true, true, true, true, FIXED);
        assertTrue(p.contains("用中文回复用户。"), "missing respond-in-Chinese directive");
        assertTrue(p.contains("历史回顾伙伴"), "missing history-review identity");
        assertTrue(p.contains("感知（Perceive）"), "missing perceive layer");
        assertTrue(p.contains("认知（Understand）"), "missing understand layer");
        assertFalse(p.contains("改进（Improve）"), "prompt must not advertise an improve layer");
        assertFalse(p.contains("自我提升伙伴"), "prompt must not use self-improvement identity");
    }

    @Test
    void englishPromptFocusesOnHistoryReviewNotImprovement() {
        String p = AgentPrompts.systemPrompt(Lang.english(), "", true, true, true, true, FIXED);
        assertTrue(p.contains("history-review partner"), "missing history-review identity");
        assertTrue(p.contains("Perceive —"), "missing perceive layer");
        assertTrue(p.contains("Understand —"), "missing understand layer");
        assertFalse(p.contains("Improve —"), "prompt must not advertise an improve layer");
        assertFalse(p.contains("self-improvement partner"), "prompt must not use self-improvement identity");
    }

    @Test
    void englishDateLineUsesEnglishLocale() { // TST-009, PROMPT-004
        String p = AgentPrompts.systemPrompt(Lang.english(), "", false, false, false, false, FIXED);
        assertTrue(p.contains("Thursday"), "english date line should use english weekday name");
        assertTrue(p.contains("Current local time:"), "english date line header");
    }

    @Test
    void chineseDateLineUsesChineseLocale() {
        String p = AgentPrompts.systemPrompt(Lang.chinese(), "", false, false, false, false, FIXED);
        assertTrue(p.contains("星期四"), "chinese date line should use chinese weekday name");
    }

    @Test
    void plainCompletionPromptFollowsLanguage() {
        assertTrue(AgentPrompts.plainCompletionPrompt(Lang.english()).contains("summary rewriter"));
        assertTrue(AgentPrompts.plainCompletionPrompt(Lang.chinese()).contains("摘要改写器"));
    }

    @Test
    void capabilityFragmentsFollowRuntimeAvailability() {
        String enabled = AgentPrompts.systemPrompt(
                Lang.chinese(), "用户偏好简洁回答。", true, true, true, true, FIXED);
        assertTrue(enabled.contains("WikiTools"));
        assertTrue(enabled.contains("semanticSearchWiki"));
        assertTrue(enabled.contains("FileTools"));
        assertTrue(enabled.contains("getConfig"));
        assertTrue(enabled.contains("用户偏好简洁回答。"));

        String disabled = AgentPrompts.systemPrompt(
                Lang.chinese(), "", false, false, false, false, FIXED);
        assertTrue(disabled.contains("Wiki 当前未启用。"));
        assertFalse(disabled.contains("semanticSearchWiki"));
        assertFalse(disabled.contains("FileTools"));
        assertFalse(disabled.contains("getConfig"));
    }

    @Test
    void renderedPromptsDoNotLeakTemplatePlaceholders() {
        String system = AgentPrompts.systemPrompt(
                Lang.english(), "concise answers", true, true, true, true, FIXED);
        assertFalse(system.contains("{{"));
        assertFalse(AgentPrompts.transientMemoryContext(Lang.english(), "concise answers").contains("{{"));
        assertFalse(AgentPrompts.plainCompletionPrompt(Lang.english()).contains("{{"));
    }

    @Test
    void preservesLegacySectionBoundariesAndTrailingNewlines() {
        String system = AgentPrompts.systemPrompt(
                Lang.english(), "MEM", true, true, true, true, FIXED);
        assertTrue(system.contains("Respond to the user in English.\nCurrent local time:"));
        assertTrue(system.contains("when showing them to the user.\n\n\n\n## Long-term memory"));
        assertTrue(system.contains("MEM\n\n## LLM Wiki time-range summaries"));
        assertTrue(system.contains("has failed.When the user's question"));
        assertTrue(system.contains("semantic retrieval.\n\n## File metadata"));
        assertTrue(system.contains("metadata is available.\n\nWhen the user's question needs"));
        assertTrue(system.contains("FileTools cannot read file contents"));
        assertTrue(system.contains("event query / Wiki tools.\n\n## Configuration management"));
        assertFalse(system.endsWith("\n"));

        assertTrue(AgentPrompts.plainCompletionPrompt(Lang.english()).endsWith("\n"));
        assertTrue(AgentPrompts.transientMemoryContext(Lang.english(), "").endsWith("\n"));
    }

    @Test
    void systemPromptsDoNotContainActivityWatchBrandName() {
        String en = AgentPrompts.systemPrompt(Lang.english(), "", true, true, true, true, FIXED);
        String zh = AgentPrompts.systemPrompt(Lang.chinese(), "", true, true, true, true, FIXED);
        assertFalse(en.contains("ActivityWatch"), en);
        assertFalse(zh.contains("ActivityWatch"), zh);
        assertFalse(en.toLowerCase().contains("activitywatch"), en);
        assertFalse(zh.toLowerCase().contains("activitywatch"), zh);
        assertTrue(en.contains("based on event data"));
        assertTrue(en.contains("Call event query tools"));
        assertTrue(en.contains("timestamps stored by the event service"));
        assertTrue(zh.contains("基于事件数据"));
        assertTrue(zh.contains("调用事件查询工具"));
        assertTrue(zh.contains("事件服务存储的所有时间戳"));
    }
}
