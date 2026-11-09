package com.selfanalyst.mcp;

import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.integrations.*;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpManagerTest {
    @TempDir Path root;
    private static IntegrationConfig.McpServer server(String url,String ref) {
        return new IntegrationConfig.McpServer("sample","Sample",false,"streamable-http",url,"bearer",ref,"session",IntegrationConfig.Budget.defaults());
    }
    @Test void saveCannotForgeReferencesAndChangingTargetRemovesCredentials() throws Exception {
        try (var store=new IntegrationStore(root); var credentials=new SessionCredentialStore(); var sessions=ChatSessionStore.openExclusive(root.resolve("memory"));
             var manager=new McpManager(store,credentials,new MailSessionPolicy(sessions),Config.load(root.resolve("config")),new PinnedHttpsClient(),new ApprovalRegistry())) {
            var initial=new IntegrationConfig(1,0,List.of(server("https://example.com/mcp",null)),List.of(),List.of());
            manager.save(0,initial);
            var withSecret=manager.credential(1,"sample","synthetic-secret".toCharArray(),false);
            String oldRef=withSecret.mcpServers().getFirst().credentialRef();
            assertTrue(credentials.configured(oldRef,withSecret.mcpServers().getFirst().credentialTarget()));
            var changed=new IntegrationConfig(1,2,List.of(server("https://example.org/mcp",oldRef)),List.of(),List.of());
            var saved=manager.save(2,changed);
            assertNull(saved.mcpServers().getFirst().credentialRef());
            assertFalse(credentials.configured(oldRef,withSecret.mcpServers().getFirst().credentialTarget()));
            assertFalse(manager.exportMetadata().contains("synthetic-secret"));
            assertThrows(java.io.IOException.class,()->manager.save(2,changed));
        }
    }
    @Test void metadataSaveCannotCreateApprovalsAndDoesNotConnect() throws Exception {
        try (var store=new IntegrationStore(root); var credentials=new SessionCredentialStore(); var sessions=ChatSessionStore.openExclusive(root.resolve("memory"));
             var manager=new McpManager(store,credentials,new MailSessionPolicy(sessions),Config.load(root.resolve("config")),new PinnedHttpsClient(),new ApprovalRegistry())) {
            var forged=new IntegrationConfig.ToolApproval("sample","fake","a".repeat(64),0);
            var config=new IntegrationConfig(1,0,List.of(server("https://example.com/mcp",null)),List.of(),List.of(forged));
            assertTrue(manager.save(0,config).toolApprovals().isEmpty());
            assertEquals(List.of(),manager.toolDefinitions("sample"));
            assertTrue(manager.statuses().toString().contains("needs_auth"));
        }
    }
    @Test void savingEnabledMetadataDoesNotStartDiscovery() throws Exception {
        var connections = new java.util.concurrent.atomic.AtomicInteger();
        try (var store = new IntegrationStore(root); var sessions = ChatSessionStore.openExclusive(root.resolve("memory"));
             var manager = new McpManager(store, new SessionCredentialStore(), new MailSessionPolicy(sessions),
                     Config.testDefaults(root.resolve("memory")), new ApprovalRegistry(),
                     (endpoint, timeout, initialization, maximum, bearer) -> { connections.incrementAndGet(); throw new AssertionError("save must not connect"); })) {
            var enabled = new IntegrationConfig.McpServer("sample", "Sample", true, "streamable-http",
                    "https://example.com/mcp", "anonymous", null, "session", IntegrationConfig.Budget.defaults());
            manager.save(0, new IntegrationConfig(1, 0, List.of(enabled), List.of(), List.of()));
            assertEquals(0, connections.get());
            assertTrue(manager.toolDefinitions("sample").isEmpty());
        }
    }

}
