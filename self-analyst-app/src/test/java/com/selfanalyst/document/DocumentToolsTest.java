package com.selfanalyst.document;

import com.selfanalyst.desktop.store.ChatSessionStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentToolsTest {
    @TempDir Path temp;
    @Test void toolkitInjectsTrustedContextAndDoesNotExposeIdentityParameters() throws Exception {
        try (var chats = new ChatSessionStore(temp)) {
            var service = new DocumentService(temp, chats);
            var toolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build()); toolkit.registerTool(new DocumentTools(service));
            String schemas = DocumentRequest.JSON.writeValueAsString(toolkit.getToolSchemas());
            assertFalse(schemas.contains("sessionId")); assertFalse(schemas.contains("userMessageId"));
            var session = chats.create(new ChatSessionStore.CreateRequest());
            var user = new ChatSessionStore.Message(); user.role = "user"; user.content = "生成";
            String turn = chats.appendMessages(session.id, List.of(user)).getFirst().id;
            var execution = new DocumentExecution(session.id, turn, true, () -> false);
            var context = RuntimeContext.builder().userId("desktop").sessionId(session.id).put(DocumentExecution.class, execution).build();
            Map<String, Object> input = Map.of("format", "markdown", "title", "测试",
                    "sourceJson", "{\"schemaVersion\":1,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"工具生成\"}]}");
            var use = ToolUseBlock.builder().id("tool1").name("generate_document").input(input)
                    .content(DocumentRequest.JSON.writeValueAsString(input)).build();
            var result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).runtimeContext(context).build()).block(Duration.ofSeconds(10));
            assertNotNull(result);
            assertEquals(1, service.store().list(session.id, 0, 10).size(), DocumentRequest.JSON.writeValueAsString(result));
            assertEquals("READY", service.store().list(session.id, 0, 10).getFirst().status());
            var page = new DocumentTools(service).list(0, execution).block();
            assertEquals(1, ((List<?>) page.get("documents")).size());
            assertFalse(DocumentRequest.JSON.writeValueAsString(page).contains("工具生成"));
            assertThrows(IllegalArgumentException.class, () -> new DocumentTools(service).list(-1, execution).block());
            assertThrows(IllegalArgumentException.class, () -> new DocumentTools(service).read("a".repeat(32),
                    new DocumentExecution(session.id, turn, false, () -> false)).block());
        }
    }
    @Test void cancellationWaitsForTheWorkerBeforeReleasingTheTurn() throws Exception {
        var execution = new DocumentExecution("a".repeat(32), "b".repeat(12), true, () -> false);
        var entered = new CountDownLatch(1); var stopped = new CountDownLatch(1);
        var worker = CompletableFuture.runAsync(() -> {
            try { execution.run(() -> {
                entered.countDown();
                while (!execution.cancelled()) Thread.sleep(1);
                stopped.countDown(); return null;
            }); } catch (Exception e) { throw new CompletionException(e); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS)); execution.closeAndAwait();
        assertEquals(0, stopped.getCount()); worker.get(2, TimeUnit.SECONDS);
        assertThrows(CancellationException.class, () -> execution.run(() -> "late"));
    }
}
