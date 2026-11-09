package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.AgentTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentIntegrationBridgeTest {
    @TempDir Path root;
    private static final String SESSION = "a".repeat(32), TURN = "b".repeat(12);
    @Test void snapshotFailureAndCancelReleaseApprovalStateBeforeNextTurn() throws Exception {
        try (var agent = new SelfAnalystAgent(Config.testDefaults(root))) {
            var cleaned = new AtomicInteger();
            agent.setIntegrationGateway(new Gateway() {
                public List<AgentTool> snapshot(TurnContext turn) { throw new IllegalStateException("synthetic failure"); }
                public void endTurn(TurnContext turn) { cleaned.incrementAndGet(); }
            });
            assertThrows(IllegalStateException.class, () -> agent.chat(SESSION, TURN, "hello").block());
            assertEquals(1, cleaned.get());
            var entered = new CountDownLatch(1);
            agent.setIntegrationGateway(new Gateway() {
                public List<AgentTool> snapshot(TurnContext turn) {
                    entered.countDown();
                    try { Thread.sleep(30_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("cancelled"); }
                    return List.of();
                }
                public void endTurn(TurnContext turn) { cleaned.incrementAndGet(); }
            });
            var future = CompletableFuture.supplyAsync(() -> agent.chat(SESSION, TURN, "hello").block());
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(agent.cancelChat(SESSION, TURN));
            assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertTrue(cleaned.get() >= 2);
            assertEquals("released", agent.runExclusiveChat(() -> reactor.core.publisher.Mono.just("released")).block());
        }
    }
    @Test void perTurnSnapshotCannotOverrideNativeAndDoesNotLeakIntoLegacy() throws Exception {
        try (var agent = new SelfAnalystAgent(Config.testDefaults(root))) {
            var generation = new AtomicInteger(); var invoked = new AtomicInteger();
            agent.setIntegrationGateway(new Gateway() {
                public List<AgentTool> snapshot(TurnContext t) {
                    assertEquals(TURN,t.turnId()); return List.of(MailNativeToolGuardTest.tool("mcp_test_" + generation.incrementAndGet(),invoked));
                }
            });
            assertThrows(RuntimeException.class, () -> agent.chat(SESSION, TURN, "hello").block());
            assertTrue(runtime(agent).getToolkit().getToolNames().contains("mcp_test_1"));
            assertThrows(RuntimeException.class, () -> agent.chat(SESSION, TURN, "hello").block());
            assertFalse(runtime(agent).getToolkit().getToolNames().contains("mcp_test_1"));
            assertTrue(runtime(agent).getToolkit().getToolNames().contains("mcp_test_2"));
            assertThrows(RuntimeException.class, () -> agent.chat("legacy").block());
            assertFalse(runtime(agent).getToolkit().getToolNames().contains("mcp_test_2"));
            agent.setIntegrationGateway(new Gateway() { public List<AgentTool> snapshot(TurnContext t) { return List.of(MailNativeToolGuardTest.tool("listBuckets",invoked)); }});
            assertThrows(IllegalStateException.class, () -> agent.chat(SESSION, TURN, "hello").block());
            assertTrue(runtime(agent).getToolkit().getTool("listBuckets") instanceof MailNativeToolGuard);
        }
    }
    @Test void changedRecipientProducesManagedConsentBeforeSnapshotAndCancellationCleansIt() throws Exception {
        try (var store = new ChatSessionStore(root); var agent = new SelfAnalystAgent(Config.testDefaults(root))) {
            String id = store.create(new ChatSessionStore.CreateRequest()).id;
            var policy = new MailSessionPolicy(store); policy.consentTarget(id, "https://old.example/v1"); policy.beforeMailResult(id, "https://old.example/v1"); agent.setMailSessionPolicy(policy);
            var requested = new AtomicInteger(); var snapshots = new AtomicInteger();
            agent.setIntegrationGateway(new Gateway() {
                public void requestModelConsent(TurnContext t) { requested.incrementAndGet(); assertTrue(t.managedDesktop()); policy.consentTarget(t.sessionId(),t.modelTarget()); }
                public List<AgentTool> snapshot(TurnContext t) { snapshots.incrementAndGet(); return List.of(); }
            });
            assertThrows(RuntimeException.class, () -> agent.chat(id, TURN, () -> new SelfAnalystAgent.PersistedDesktopTurn("hello",null,List.of(),true)).block());
            assertEquals(1,requested.get()); assertEquals(1,snapshots.get());
            assertTrue(policy.allowedTargets(id).contains(agent.currentModelTarget()));
        }
    }
    @Test void newModelRuntimeReceivesLatestSnapshotWithoutDuplicatingNativeTools() throws Exception {
        var userConfig = new com.selfanalyst.desktop.store.UserConfigStore(root.resolve("config"));
        try (var agent = new SelfAnalystAgent(Config.testDefaults(root),null,null,userConfig)) {
            var count = new AtomicInteger();
            agent.setIntegrationGateway(new Gateway() { public List<AgentTool> snapshot(TurnContext t) {
                return List.of(MailNativeToolGuardTest.tool("mcp_generation_" + count.incrementAndGet(),new AtomicInteger()));
            }});
            assertThrows(RuntimeException.class,()->agent.chat(SESSION,TURN,"first").block());
            ReActAgent before=runtime(agent);
            agent.configuration().updateLlm(Map.of("llm.model","synthetic-next"));
            assertThrows(RuntimeException.class,()->agent.chat(SESSION,TURN,"second").block());
            ReActAgent after=runtime(agent); assertNotSame(before,after);
            assertTrue(after.getToolkit().getToolNames().contains("mcp_generation_2"));
            assertFalse(after.getToolkit().getToolNames().contains("mcp_generation_1"));
            assertTrue(after.getToolkit().getTool("setConfigValue") instanceof MailNativeToolGuard);
        }
    }
    private static ReActAgent runtime(SelfAnalystAgent a) throws Exception { var field=SelfAnalystAgent.class.getDeclaredField("agent");field.setAccessible(true);return (ReActAgent)field.get(a); }
    abstract static class Gateway implements AgentIntegrationGateway {
        public List<AgentTool> snapshot(TurnContext t) { return List.of(); }
        public void approveNative(TurnContext t,String name,Map<String,Object> args) { throw new IllegalStateException("denied"); }
    }
}
