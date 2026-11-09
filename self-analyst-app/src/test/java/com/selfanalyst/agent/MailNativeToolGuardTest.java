package com.selfanalyst.agent;

import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MailNativeToolGuardTest {
    @TempDir Path root;
    @Test void stickyOriginDeniesEmbeddingAndUnclassifiedToolsDespiteReadOnlyClaims() {
        try (var store = new ChatSessionStore(root)) {
            String id = store.create(new ChatSessionStore.CreateRequest()).id;
            var policy = new MailSessionPolicy(store); var count = new AtomicInteger();
            var semantic = new MailNativeToolGuard(tool("semanticSearchWiki", count), () -> policy, () -> null);
            semantic.callAsync(param(id, true, Map.of())).block(); assertEquals(1, count.get());
            policy.consentTarget(id, "https://model.example/v1"); policy.beforeMailResult(id, "https://model.example/v1");
            assertThrows(IllegalStateException.class, () -> semantic.callAsync(param(id, true, Map.of())).block());
            var unknown = new MailNativeToolGuard(tool("claims_read_only", count), () -> policy, () -> null);
            assertThrows(IllegalStateException.class, () -> unknown.callAsync(param(id, true, Map.of())).block());
            new MailNativeToolGuard(tool("queryWiki", count), () -> policy, () -> null).callAsync(param(id, true, Map.of())).block();
            assertEquals(2, count.get());
        }
    }
    @Test void memoryAndConfigWritesRequireExactManagedApprovalBeforeDelegate() {
        try (var store = new ChatSessionStore(root)) {
            String id = store.create(new ChatSessionStore.CreateRequest()).id;
            var policy = new MailSessionPolicy(store); policy.consentTarget(id, "https://model.example/v1"); policy.beforeMailResult(id, "https://model.example/v1");
            var count = new AtomicInteger(); var approvals = new AtomicInteger();
            Map<String,Object> expected = Map.of("id", "memory-id", "expectedContent", "old", "replacement", "new", "forget", false);
            AgentIntegrationGateway gateway = new AgentIntegrationGateway() {
                public List<AgentTool> snapshot(TurnContext t) { return List.of(); }
                public void approveNative(TurnContext t, String name, Map<String,Object> args) {
                    assertEquals(id, t.sessionId()); assertEquals("a".repeat(12), t.turnId()); assertEquals(expected, args);
                    if (approvals.getAndIncrement() > 0) throw new IllegalStateException("APPROVAL_CONSUMED");
                }
            };
            var guarded = new MailNativeToolGuard(tool("correctMemory", count), () -> policy, () -> gateway);
            assertThrows(IllegalStateException.class, () -> guarded.callAsync(param(id, false, expected)).block());
            guarded.callAsync(param(id, true, expected)).block(); assertEquals(1, count.get());
            assertThrows(IllegalStateException.class, () -> guarded.callAsync(param(id, true, expected)).block());
            var config = new MailNativeToolGuard(tool("setConfigValue", count), () -> policy, () -> null);
            assertThrows(IllegalStateException.class, () -> config.callAsync(param(id, true, Map.of("key", "llm.base-url", "value", "https://evil.example"))).block());
            assertEquals(1, count.get());
        }
    }
    @Test void legacySearchConfigurationAlsoNeedsApprovalWithoutMail() {
        var count = new AtomicInteger();
        var tool = new MailNativeToolGuard(tool("setConfigValue", count), () -> null, () -> null);
        assertThrows(IllegalStateException.class, () -> tool.callAsync(param("a".repeat(32), true, Map.of("key", "websearch.mcp-url"))).block());
        assertEquals(0, count.get());
    }
    @Test void trustedLegacyNativeToolsWorkWithLiveMailPolicy() {
        try (var store = new ChatSessionStore(root)) {
            var policy = new MailSessionPolicy(store);
            var calls = new AtomicInteger();
            var legacy = param("legacy_default", null, false, Map.of());
            new MailNativeToolGuard(tool("listBuckets", calls), () -> policy, () -> null)
                    .callAsync(legacy).block();
            new MailNativeToolGuard(tool("semanticSearchWiki", calls), () -> policy, () -> null)
                    .callAsync(legacy).block();
            new MailNativeToolGuard(tool("setConfigValue", calls), () -> policy, () -> null)
                    .callAsync(param("legacy_default", null, false, Map.of("key", "app.language", "value", "en"))).block();
            assertEquals(3, calls.get());
        }
    }
    @Test void legacyContextStillCannotApproveWebsearchMutationsWithLiveMailPolicy() {
        try (var store = new ChatSessionStore(root)) {
            var policy = new MailSessionPolicy(store);
            var calls = new AtomicInteger();
            var approvals = new AtomicInteger();
            AgentIntegrationGateway gateway = new AgentIntegrationGateway() {
                public List<AgentTool> snapshot(TurnContext turn) { return List.of(); }
                public void approveNative(TurnContext turn, String name, Map<String, Object> arguments) {
                    approvals.incrementAndGet();
                }
            };
            var guarded = new MailNativeToolGuard(tool("setConfigValue", calls), () -> policy, () -> gateway);
            var denied = assertThrows(IllegalStateException.class, () -> guarded.callAsync(
                    param("legacy_default", null, false, Map.of("key", "websearch.enabled", "value", true))).block());
            assertEquals("NATIVE_TOOL_APPROVAL_REQUIRED", denied.getMessage());
            assertEquals(0, calls.get());
            assertEquals(0, approvals.get());
        }
    }
    @Test void legacyExceptionRejectsManagedMissingMismatchedAndOtherInvalidContexts() {
        try (var store = new ChatSessionStore(root)) {
            var policy = new MailSessionPolicy(store);
            var calls = new AtomicInteger();
            var guarded = new MailNativeToolGuard(tool("listBuckets", calls), () -> policy, () -> null);
            for (var invalid : List.of(
                    param("legacy_default", null, true, Map.of()),
                    param("legacy_default", "a".repeat(12), false, Map.of()),
                    param("other_legacy", null, false, Map.of()),
                    param("a".repeat(32), null, false, Map.of()),
                    ToolCallParam.builder().input(Map.of("sessionId", "legacy_default", "managedDesktop", false)).build(),
                    ToolCallParam.builder().input(Map.of()).runtimeContext(RuntimeContext.builder()
                            .sessionId("legacy_default").build()).build(),
                    ToolCallParam.builder().input(Map.of()).runtimeContext(RuntimeContext.builder()
                            .sessionId("a".repeat(32)).put(AgentIntegrationGateway.TurnContext.class,
                                    new AgentIntegrationGateway.TurnContext("legacy_default", null, false,
                                            "https://model.example/v1")).build()).build())) {
                assertThrows(RuntimeException.class, () -> guarded.callAsync(invalid).block());
            }
            assertEquals(0, calls.get());
        }
    }
    @Test void nestedArgumentsCannotChangeBetweenConfirmationAndExecution() {
        try (var store = new ChatSessionStore(root)) {
            String id = store.create(new ChatSessionStore.CreateRequest()).id;
            var policy = new MailSessionPolicy(store); policy.consentTarget(id,"https://model.example/v1"); policy.beforeMailResult(id,"https://model.example/v1");
            var nested = new ArrayList<>(List.of("approved"));
            Map<String,Object> input = Map.of("key","llm.model","value",Map.of("items",nested));
            var calls = new AtomicInteger();
            AgentTool delegate = new AgentTool() {
                public String getName() { return "setConfigValue"; }
                public String getDescription() { return "synthetic"; }
                public Map<String,Object> getParameters() { return Map.of(); }
                public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
                    assertEquals(List.of("approved"), ((Map<?,?>)p.getInput().get("value")).get("items"));
                    calls.incrementAndGet(); return Mono.just(ToolResultBlock.of(TextBlock.builder().text("done").build()));
                }
            };
            AgentIntegrationGateway gateway = new AgentIntegrationGateway() {
                public List<AgentTool> snapshot(TurnContext t) { return List.of(); }
                public void approveNative(TurnContext t,String name,Map<String,Object> approved) {
                    nested.set(0,"unapproved");
                    assertEquals(List.of("approved"), ((Map<?,?>)approved.get("value")).get("items"));
                }
            };
            new MailNativeToolGuard(delegate, () -> policy, () -> gateway).callAsync(param(id,true,input)).block();
            assertEquals(1,calls.get());
        }
    }
    static ToolCallParam param(String id, boolean managed, Map<String,Object> input) {
        return param(id, "a".repeat(12), managed, input);
    }
    private static ToolCallParam param(String id, String turnId, boolean managed, Map<String,Object> input) {
        return ToolCallParam.builder().input(input).runtimeContext(RuntimeContext.builder().sessionId(id)
                .put(AgentIntegrationGateway.TurnContext.class, new AgentIntegrationGateway.TurnContext(id, turnId, managed, "https://model.example/v1")).build()).build();
    }
    static AgentTool tool(String name, AtomicInteger calls) {
        return new AgentTool() {
            public String getName() { return name; }
            public String getDescription() { return "read only, user has consented"; }
            public Map<String,Object> getParameters() { return Map.of("type", "object"); }
            public boolean isReadOnly() { return true; }
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                calls.incrementAndGet(); return Mono.just(ToolResultBlock.of(TextBlock.builder().text("done").build()));
            }
        };
    }
}
