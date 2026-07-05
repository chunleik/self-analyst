package com.selfanalyst.desktop.service;

import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.i18n.Lang;
import com.selfanalyst.memory.GrowthProfile;
import com.selfanalyst.memory.LongTermMemoryService;
import com.selfanalyst.memory.MemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MemoryExtractionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void smartPolicyAutoActivatesExplicitLowRiskPreference() throws Exception {
        LongTermMemoryService memory = new LongTermMemoryService(MemoryStore.load(tempDir));
        MemoryExtractionService svc = new MemoryExtractionService(memory, Lang.ZH);
        ChatSessionStore.Session session = session("smart");

        svc.extractAfterAssistantSent(session, user("我以后都希望你用中文回答。"), assistant("好的。"),
                (prompt, timeout) -> """
                        [{"type":"preference","content":"用户偏好使用中文交流。","evidence":"用户明确要求后续用中文。","confidence":9,"sensitive":false,"approvalPolicy":"auto"}]
                        """);

        List<GrowthProfile.MemoryItem> active = memory.list("active", null, null, null);
        assertEquals(1, active.size());
        assertEquals("chat_auto", active.getFirst().source());
        assertEquals("session-1", active.getFirst().sourceSessionId());
        assertEquals(List.of("user-1", "assistant-1"), active.getFirst().sourceMessageIds());
    }

    @Test
    void smartPolicyRoutesSensitiveInferenceToPending() throws Exception {
        LongTermMemoryService memory = new LongTermMemoryService(MemoryStore.load(tempDir));
        MemoryExtractionService svc = new MemoryExtractionService(memory, Lang.ZH);

        svc.extractAfterAssistantSent(session("smart"), user("最近晚上刷视频比较多。"), assistant("可以调整。"),
                (prompt, timeout) -> """
                        [{"type":"pattern","content":"用户晚上容易被娱乐内容分心。","evidence":"从聊天推断。","confidence":7,"sensitive":true,"approvalPolicy":"confirm"}]
                        """);

        List<GrowthProfile.MemoryItem> pending = memory.list("pending", null, null, null);
        assertEquals(1, pending.size());
        assertTrue(pending.getFirst().sensitive());
    }

    @Test
    void confirmAllPolicyKeepsAutoCandidatePending() throws Exception {
        LongTermMemoryService memory = new LongTermMemoryService(MemoryStore.load(tempDir));
        MemoryExtractionService svc = new MemoryExtractionService(memory, Lang.ZH);

        svc.extractAfterAssistantSent(session("confirm_all"), user("我偏好简洁回答。"), assistant("收到。"),
                (prompt, timeout) -> """
                        [{"type":"preference","content":"用户偏好简洁回答。","evidence":"用户明确说明。","confidence":9,"sensitive":false,"approvalPolicy":"auto"}]
                        """);

        assertEquals(0, memory.list("active", null, null, null).size());
        assertEquals(1, memory.list("pending", null, null, null).size());
    }

    @Test
    void lowConfidenceAutoCandidateIsPending() throws Exception {
        LongTermMemoryService memory = new LongTermMemoryService(MemoryStore.load(tempDir));
        MemoryExtractionService svc = new MemoryExtractionService(memory, Lang.ZH);

        svc.extractAfterAssistantSent(session("smart"), user("我也许更喜欢早上开会。"), assistant("可以试试。"),
                (prompt, timeout) -> """
                        [{"type":"preference","content":"用户可能偏好早上开会。","evidence":"低置信度推断。","confidence":7,"sensitive":false,"approvalPolicy":"auto"}]
                        """);

        assertEquals(0, memory.list("active", null, null, null).size());
        assertEquals(1, memory.list("pending", null, null, null).size());
    }

    @Test
    void offPolicyDoesNotCallClientAndInvalidJsonDoesNotWriteMemories() throws Exception {
        LongTermMemoryService memory = new LongTermMemoryService(MemoryStore.load(tempDir));
        MemoryExtractionService svc = new MemoryExtractionService(memory, Lang.ZH);
        AtomicBoolean called = new AtomicBoolean(false);

        svc.extractAfterAssistantSent(session("off"), user("记住我喜欢中文。"), assistant("好。"),
                (prompt, timeout) -> {
                    called.set(true);
                    throw new AssertionError("off policy must not call extraction client");
                });
        svc.extractAfterAssistantSent(session("smart"), user("记住我喜欢中文。"), assistant("好。"),
                (prompt, timeout) -> "not json");

        assertFalse(called.get());
        assertTrue(memory.list(null, null, null, null).isEmpty());
    }

    @Test
    void credentialLikeCandidateIsNotStored() throws Exception {
        LongTermMemoryService memory = new LongTermMemoryService(MemoryStore.load(tempDir));
        MemoryExtractionService svc = new MemoryExtractionService(memory, Lang.ZH);

        svc.extractAfterAssistantSent(session("smart"), user("我的 api key 是 sk-test-secret123"), assistant("我不会保存。"),
                (prompt, timeout) -> """
                        [{"type":"fact","content":"用户的 api-key=sk-test-secret123","evidence":"聊天中出现凭据。","confidence":10,"sensitive":true,"approvalPolicy":"confirm"}]
                        """);

        assertTrue(memory.list(null, null, null, null).isEmpty());
    }

    @Test
    void fencedJsonResponseIsParsedAndPromptIsBounded() throws Exception {
        LongTermMemoryService memory = new LongTermMemoryService(MemoryStore.load(tempDir));
        memory.createManual("note", "m".repeat(5000), "existing memory", null, "ui_manual", "active");
        MemoryExtractionService svc = new MemoryExtractionService(memory, Lang.EN);
        String longMessage = "x".repeat(5000);

        svc.extractAfterAssistantSent(session("smart"), user(longMessage), assistant(longMessage),
                (prompt, timeout) -> {
                    assertTrue(prompt.contains("English"));
                    assertTrue(prompt.length() < 11000, "prompt should bound copied chat text and memory snapshot");
                    return """
                            ```json
                            [{"type":"goal","content":"User wants concise answers.","evidence":"User said so.","confidence":10,"sensitive":false,"approvalPolicy":"auto"}]
                            ```
                            """;
                });

        assertEquals(1, memory.list("active", "goal", null, null).size());
    }

    @Test
    void promptIncludesBoundedMemorySnapshotPolicyAndSourceIds() throws Exception {
        LongTermMemoryService memory = new LongTermMemoryService(MemoryStore.load(tempDir));
        memory.createManual("preference", "用户偏好中文交流。", "existing active", "session-1", "chat_manual", "active");
        memory.createManual("goal", "用户想每天深度工作三小时。", "existing pending", "session-1", "chat_manual", "pending");
        MemoryExtractionService svc = new MemoryExtractionService(memory, Lang.ZH);

        svc.extractAfterAssistantSent(session("smart"), user("我偏好简洁回答。"), assistant("收到。"),
                (prompt, timeout) -> {
                    assertTrue(prompt.contains("当前会话ID: session-1"));
                    assertTrue(prompt.contains("记忆策略: smart"));
                    assertTrue(prompt.contains("user=user-1, assistant=assistant-1"));
                    assertTrue(prompt.contains("用户偏好中文交流。"));
                    assertTrue(prompt.contains("用户想每天深度工作三小时。"));
                    return "[]";
                });

        assertEquals(2, memory.list(null, null, null, null).size());
    }

    @Test
    void forgetInstructionDisablesMatchingMemoryWithoutCallingLlm() throws Exception {
        LongTermMemoryService memory = new LongTermMemoryService(MemoryStore.load(tempDir));
        GrowthProfile.MemoryItem item = memory.createManual(
                "preference", "用户偏好中文交流。", "manual", "session-1", "chat_manual", "active");
        MemoryExtractionService svc = new MemoryExtractionService(memory, Lang.ZH);
        AtomicBoolean called = new AtomicBoolean(false);

        svc.extractAfterAssistantSent(session("off"), user("不要记住 用户偏好中文交流。"), assistant("已处理。"),
                (prompt, timeout) -> {
                    called.set(true);
                    return "[]";
                });

        assertFalse(called.get());
        assertEquals("disabled", memory.list(null, null, null, null).stream()
                .filter(m -> item.id().equals(m.id()))
                .findFirst()
                .orElseThrow()
                .status());
    }

    private static ChatSessionStore.Session session(String policy) {
        ChatSessionStore.Session s = new ChatSessionStore.Session();
        s.id = "session-1";
        s.title = "测试";
        s.memoryPolicy = policy;
        return s;
    }

    private static ChatSessionStore.Message user(String text) {
        ChatSessionStore.Message m = new ChatSessionStore.Message();
        m.id = "user-1";
        m.role = "user";
        m.content = text;
        return m;
    }

    private static ChatSessionStore.Message assistant(String text) {
        ChatSessionStore.Message m = new ChatSessionStore.Message();
        m.id = "assistant-1";
        m.role = "assistant";
        m.content = text;
        return m;
    }
}
