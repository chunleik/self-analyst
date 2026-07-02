package com.selfanalyst.desktop.service;

import com.selfanalyst.desktop.store.ChatSessionStore.Message;
import com.selfanalyst.desktop.store.ChatSessionStore.Session;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One-line summary: deterministic fallback + LLM happy path (SPEC-CSP-TST-015). */
class ChatSummaryServiceTest {

    private static Session session(String title, String... userMessages) {
        Session s = new Session();
        s.title = title;
        s.messages = new ArrayList<>();
        for (String text : userMessages) {
            Message m = new Message();
            m.role = "user";
            m.content = text;
            s.messages.add(m);
        }
        return s;
    }

    @Test
    void fallbackWithoutClientDerivesFromUserMessages() {
        ChatSummaryService svc = new ChatSummaryService();
        String summary = svc.summarize(session("会话A", "怎么提高专注力", "用什么番茄钟"), null);
        assertFalse(summary.isBlank());
        assertTrue(summary.contains("怎么提高专注力"));
    }

    @Test
    void fallbackUsesTitleWhenNoUserMessages() {
        ChatSummaryService svc = new ChatSummaryService();
        String summary = svc.summarize(session("我的标题"), null);
        assertEquals("我的标题", summary);
    }

    @Test
    void llmClientResultIsNormalizedToOneLine() {
        ChatSummaryService svc = new ChatSummaryService();
        Session s = session("会话A", "你好");
        String summary = svc.summarize(s,
                (prompt, timeout) -> "```\n讨论了如何提升专注力\n额外行\n```");
        assertEquals("讨论了如何提升专注力", summary);
    }

    @Test
    void throwingClientFallsBackNonEmpty() {
        ChatSummaryService svc = new ChatSummaryService();
        Session s = session("会话A", "这是用户的问题");
        String summary = svc.summarize(s, (prompt, timeout) -> {
            throw new RuntimeException("LLM down");
        });
        assertFalse(summary.isBlank());
        assertTrue(summary.contains("这是用户的问题"));
    }

    @Test
    void blankClientResultFallsBack() {
        ChatSummaryService svc = new ChatSummaryService();
        Session s = session("会话A", "用户消息X");
        String summary = svc.summarize(s, (prompt, timeout) -> "   ");
        assertTrue(summary.contains("用户消息X"));
    }
}
