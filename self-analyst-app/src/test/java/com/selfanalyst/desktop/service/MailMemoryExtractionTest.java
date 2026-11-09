package com.selfanalyst.desktop.service;

import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import com.selfanalyst.i18n.Lang;
import com.selfanalyst.memory.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MailMemoryExtractionTest {
    @TempDir Path root;
    private static final String TARGET = "https://model.example/v1";
    private static final String CANDIDATE = """
            [{"type":"preference","content":"Prefers concise replies", "evidence":"User said so",
              "confidence":9,"durable":true,"supported":true}]
            """;
    @Test void staleQueuedSnapshotIsRejectedAndSmartPolicyPreservedAcrossRestart() throws Exception {
        String id;
        try (var store = new ChatSessionStore(root)) {
            var snapshot = store.create(new ChatSessionStore.CreateRequest()); id = snapshot.id;
            var policy = new MailSessionPolicy(store); policy.consentTarget(id, TARGET); policy.beforeMailResult(id, TARGET);
            var memory = new LongTermMemoryService(MemoryStore.load(root));
            var service = new MemoryExtractionService(memory, Lang.english()); service.setMailSessionPolicy(policy);
            AtomicInteger calls = new AtomicInteger();
            service.extractAfterAssistantSent(snapshot, message("user"), message("assistant"), (p,t) -> { calls.incrementAndGet(); return CANDIDATE; });
            assertEquals(0, calls.get()); assertTrue(memory.list(null,null,null,null).isEmpty());
            assertEquals("smart", store.getSession(id).memoryPolicy);
        }
        try (var store = new ChatSessionStore(root)) {
            assertTrue(store.getSession(id).mailOrigin);
            assertFalse(new MailSessionPolicy(store).automaticMemoryAllowed(id));
        }
    }
    @Test void markerAppearingDuringInferencePreventsCommit() throws Exception {
        try (var store = new ChatSessionStore(root)) {
            var session = store.create(new ChatSessionStore.CreateRequest());
            var policy = new MailSessionPolicy(store); policy.consentTarget(session.id, TARGET);
            var memory = new LongTermMemoryService(MemoryStore.load(root));
            var service = new MemoryExtractionService(memory, Lang.english()); service.setMailSessionPolicy(policy);
            service.extractAfterAssistantSent(session, message("user"), message("assistant"), (p,t) -> {
                policy.beforeMailResult(session.id, TARGET); return CANDIDATE;
            });
            assertTrue(memory.list(null,null,null,null).isEmpty());
            assertEquals("smart", store.getSession(session.id).memoryPolicy);
        }
    }
    @Test void regularSessionsStillExtractOnceAndMailSnapshotCannotUseUnscopedSummaryClient() throws Exception {
        try (var store = new ChatSessionStore(root)) {
            var session = store.create(new ChatSessionStore.CreateRequest());
            var memory = new LongTermMemoryService(MemoryStore.load(root));
            var service = new MemoryExtractionService(memory, Lang.english()); service.setMailSessionPolicy(new MailSessionPolicy(store));
            var calls = new AtomicInteger(); SummaryPromptService.SummaryTextClient client = (p,t) -> { calls.incrementAndGet(); return CANDIDATE; };
            service.extractAfterAssistantSent(session, message("user"), message("assistant"), client);
            service.extractAfterAssistantSent(session, message("user"), message("assistant"), client);
            assertEquals(1, calls.get()); assertEquals(1, memory.list(null,null,null,null).size());
            session.mailOrigin = true; session.title = "Mail history";
            assertEquals("Mail history", new ChatSummaryService(Lang.english()).summarize(session, client));
            assertEquals(1, calls.get());
        }
    }
    private static ChatSessionStore.Message message(String role) {
        var m = new ChatSessionStore.Message(); m.id = role.equals("user") ? "a".repeat(12) : "b".repeat(12); m.role = role; m.content = "Please be concise."; m.status = "sent"; return m;
    }
}
