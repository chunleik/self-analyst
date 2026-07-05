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

    // Thursday, 2026-01-15, in UTC — used to assert locale-dependent weekday text.
    private static final ZonedDateTime FIXED = ZonedDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    @Test
    void englishPromptHasRespondInEnglishAndNoCjk() { // TST-006, PROMPT-002a/003a
        String p = AgentPrompts.systemPrompt(Lang.EN, "", true, true, true, true, FIXED);
        assertTrue(p.contains("Respond to the user in English."), "missing respond-in-English directive");
        assertTrue(p.contains("Suggest recording the following finding"), "missing english finding phrase");
        assertFalse(CJK.matcher(p).find(), "english prompt must not contain CJK characters");
    }

    @Test
    void chinesePromptHasRespondInChinese() { // TST-007, PROMPT-002b
        String p = AgentPrompts.systemPrompt(Lang.ZH, "", true, true, true, true, FIXED);
        assertTrue(p.contains("用中文回复用户。"), "missing respond-in-Chinese directive");
    }

    @Test
    void englishDateLineUsesEnglishLocale() { // TST-009, PROMPT-004
        String p = AgentPrompts.systemPrompt(Lang.EN, "", false, false, false, false, FIXED);
        assertTrue(p.contains("Thursday"), "english date line should use english weekday name");
        assertTrue(p.contains("Current local time:"), "english date line header");
    }

    @Test
    void chineseDateLineUsesChineseLocale() {
        String p = AgentPrompts.systemPrompt(Lang.ZH, "", false, false, false, false, FIXED);
        assertTrue(p.contains("星期四"), "chinese date line should use chinese weekday name");
    }

    @Test
    void plainCompletionPromptFollowsLanguage() {
        assertTrue(AgentPrompts.plainCompletionPrompt(Lang.EN).contains("summary rewriter"));
        assertTrue(AgentPrompts.plainCompletionPrompt(Lang.ZH).contains("摘要改写器"));
    }

    @Test
    void dynamicMemoryContextUsesCurrentSummary() {
        String block = AgentPrompts.dynamicMemoryContext(Lang.ZH, "## 长期记忆\n- 用户偏好中文。");
        assertTrue(block.contains("当前长期记忆"));
        assertTrue(block.contains("用户偏好中文"));
    }
}
