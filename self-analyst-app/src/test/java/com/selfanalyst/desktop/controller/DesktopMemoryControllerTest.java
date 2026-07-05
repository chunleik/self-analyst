package com.selfanalyst.desktop.controller;

import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.service.ChatSummaryService;
import com.selfanalyst.desktop.service.MemoryExtractionService;
import com.selfanalyst.desktop.service.SummaryPromptService;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.i18n.Lang;
import com.selfanalyst.memory.GrowthProfile;
import com.selfanalyst.memory.LongTermMemoryService;
import com.selfanalyst.memory.MemoryStore;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DesktopMemoryControllerTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void memoryControllerCreatesAndListsManualMemory() throws Exception {
        LongTermMemoryService service = new LongTermMemoryService(MemoryStore.load(tempDir));
        DesktopMemoryController controller = new DesktopMemoryController(service);

        FakeContext create = FakeContext.withBody("""
                {"type":"preference","content":"用户偏好中文回答。","evidence":"用户手动添加"}
                """);
        controller.create(create.ctx());

        assertEquals(201, create.status);
        assertInstanceOf(GrowthProfile.MemoryItem.class, create.json);

        FakeContext list = FakeContext.empty();
        controller.list(list.ctx());

        Map<String, Object> body = (Map<String, Object>) list.json;
        assertEquals(1, ((java.util.List<?>) body.get("memories")).size());
        assertTrue(((Map<String, Object>) body.get("legacy")).containsKey("goals"));
    }

    @Test
    void chatControllerSetsSessionMemoryPolicy() {
        ChatSessionStore store = new ChatSessionStore(tempDir);
        ChatSessionStore.Session session = store.create(req("memory"));
        DesktopChatSessionController controller = new DesktopChatSessionController(
                store, new ChatSummaryService(Lang.ZH), null, Config.testDefaults(tempDir), null);

        FakeContext ctx = FakeContext.withBody("{\"memoryPolicy\":\"confirm_all\"}");
        ctx.pathParams.put("id", session.id);

        controller.setMemoryPolicy(ctx.ctx());

        assertEquals(200, ctx.status);
        assertEquals("confirm_all", ((ChatSessionStore.Session) ctx.json).memoryPolicy);
        assertEquals("confirm_all", store.getSession(session.id).memoryPolicy);
    }

    @Test
    void chatControllerCreateSessionMapsInvalidMemoryPolicyTo400() {
        ChatSessionStore store = new ChatSessionStore(tempDir);
        DesktopChatSessionController controller = new DesktopChatSessionController(
                store, new ChatSummaryService(Lang.ZH), null, Config.testDefaults(tempDir), null);
        FakeContext ctx = FakeContext.withBody("{\"title\":\"bad\",\"memoryPolicy\":\"always\"}");

        controller.createSession(ctx.ctx());

        assertEquals(400, ctx.status);
    }

    @Test
    void memoryUpdateTreatsExplicitJsonNullAsUnspecified() throws Exception {
        LongTermMemoryService service = new LongTermMemoryService(MemoryStore.load(tempDir));
        GrowthProfile.MemoryItem item = service.createExtracted(
                "pattern", "用户晚上容易分心。", "inferred", 9,
                true, "confirm", "pending", "session-1", List.of("msg-1"));
        DesktopMemoryController controller = new DesktopMemoryController(service);
        FakeContext ctx = FakeContext.withBody("{\"confidence\":null,\"sensitive\":null}");
        ctx.pathParams.put("id", item.id());

        controller.update(ctx.ctx());

        assertEquals(200, ctx.status);
        GrowthProfile.MemoryItem updated = (GrowthProfile.MemoryItem) ctx.json;
        assertEquals(9, updated.confidence());
        assertTrue(updated.sensitive());
    }

    @Test
    void assistantSentUpdateSchedulesMemoryExtraction() throws Exception {
        ChatSessionStore store = new ChatSessionStore(tempDir);
        ChatSessionStore.Session session = store.create(req("memory"));
        ChatSessionStore.Message user = msg("user", "请记住我偏好中文。", null);
        ChatSessionStore.Message assistant = msg("assistant", "", "pending");
        store.appendMessages(session.id, List.of(user, assistant));
        ChatSessionStore.Session loaded = store.getSession(session.id);
        String assistantId = loaded.messages.get(1).id;
        CapturingExtractionService extraction = new CapturingExtractionService(tempDir.resolve("extraction-memory"));
        DesktopChatSessionController controller = new DesktopChatSessionController(
                store, new ChatSummaryService(Lang.ZH), null, Config.testDefaults(tempDir), extraction);

        FakeContext ctx = FakeContext.withBody("{\"content\":\"好的。\",\"status\":\"sent\"}");
        ctx.pathParams.put("id", session.id);
        ctx.pathParams.put("msgId", assistantId);

        controller.updateMessage(ctx.ctx());

        assertEquals(200, ctx.status);
        assertTrue(extraction.await(), "memory extraction should be scheduled for assistant sent update");
        assertEquals(session.id, extraction.session.id);
        assertEquals("user", extraction.user.role);
        assertEquals(assistantId, extraction.assistant.id);

        FakeContext second = FakeContext.withBody("{\"content\":\"好的，补充一句。\",\"status\":\"sent\"}");
        second.pathParams.put("id", session.id);
        second.pathParams.put("msgId", assistantId);
        controller.updateMessage(second.ctx());
        Thread.sleep(200);

        assertEquals(1, extraction.count(), "sent->sent updates must not schedule duplicate extraction");
    }

    private static ChatSessionStore.CreateRequest req(String title) {
        ChatSessionStore.CreateRequest r = new ChatSessionStore.CreateRequest();
        r.title = title;
        return r;
    }

    private static ChatSessionStore.Message msg(String role, String content, String status) {
        ChatSessionStore.Message m = new ChatSessionStore.Message();
        m.role = role;
        m.content = content;
        m.status = status;
        return m;
    }

    private static final class CapturingExtractionService extends MemoryExtractionService {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicInteger count = new AtomicInteger();
        ChatSessionStore.Session session;
        ChatSessionStore.Message user;
        ChatSessionStore.Message assistant;

        CapturingExtractionService(Path memoryDir) throws Exception {
            super(new LongTermMemoryService(MemoryStore.load(memoryDir)), Lang.ZH);
        }

        @Override
        public void extractAfterAssistantSent(ChatSessionStore.Session session,
                                              ChatSessionStore.Message user,
                                              ChatSessionStore.Message assistant,
                                              SummaryPromptService.SummaryTextClient client) {
            count.incrementAndGet();
            this.session = session;
            this.user = user;
            this.assistant = assistant;
            latch.countDown();
        }

        boolean await() throws InterruptedException {
            return latch.await(2, TimeUnit.SECONDS);
        }

        int count() {
            return count.get();
        }
    }

    private static final class FakeContext {
        String body = "";
        int status = 200;
        Object json;
        final Map<String, String> pathParams = new HashMap<>();
        final Map<String, String> queryParams = new HashMap<>();

        static FakeContext empty() {
            return new FakeContext();
        }

        static FakeContext withBody(String body) {
            FakeContext ctx = new FakeContext();
            ctx.body = body;
            return ctx;
        }

        Context ctx() {
            return (Context) Proxy.newProxyInstance(
                    Context.class.getClassLoader(),
                    new Class<?>[]{Context.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "body" -> body;
                        case "status" -> {
                            if (args != null && args.length == 1 && args[0] instanceof Integer code) {
                                status = code;
                                yield proxy;
                            }
                            yield null;
                        }
                        case "json" -> {
                            json = args != null && args.length > 0 ? args[0] : null;
                            yield proxy;
                        }
                        case "pathParam" -> pathParams.get((String) args[0]);
                        case "queryParam" -> queryParams.get((String) args[0]);
                        case "toString" -> "FakeContext";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
