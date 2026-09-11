package com.selfanalyst.desktop.service;

import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.i18n.Lang;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Summary / chat-summary prompt i18n (SPEC-I18N-PROMPT-001/003b, TST-008/010). */
class SummaryPromptI18nTest {

    private static SummaryService.LocalFacts facts() {
        return new SummaryService.LocalFacts(
                "headline", List.of("ev"), List.of("Chrome 1h"), "1h", "0s", 12, "goal");
    }

    private static ChatSessionStore.Session session() {
        ChatSessionStore.Session s = new ChatSessionStore.Session();
        s.title = "title";
        s.messages = new ArrayList<>();
        ChatSessionStore.Message m = new ChatSessionStore.Message();
        m.role = "user";
        m.content = "hello";
        s.messages.add(m);
        return s;
    }

    @Test
    void activitySummaryEnglishVariant() { // TST-010
        String en = SummaryPromptService.buildPrompt(facts(), Lang.english());
        assertTrue(en.contains("one English sentence"), "english summary instruction");
        assertFalse(en.contains("用一句"), "english variant must not contain Chinese summary phrase");
    }

    @Test
    void activitySummaryChineseVariant() { // TST-010
        String zh = SummaryPromptService.buildPrompt(facts(), Lang.chinese());
        assertTrue(zh.contains("用中文概括") || zh.contains("活动数据"), "chinese summary instruction");
    }

    @Test
    void chatSummaryEnglishHasNoHardcodedChinese() { // TST-008, PROMPT-003b
        String en = ChatSummaryService.buildPrompt(session(), Lang.english());
        assertTrue(en.contains("one English sentence"), "english chat-summary instruction");
        assertFalse(en.contains("中文"), "english chat-summary must not hardcode 中文");
    }

    @Test
    void chatSummaryChineseVariant() {
        String zh = ChatSummaryService.buildPrompt(session(), Lang.chinese());
        assertTrue(zh.contains("用一句中文概括"), "chinese chat-summary instruction");
    }
}
