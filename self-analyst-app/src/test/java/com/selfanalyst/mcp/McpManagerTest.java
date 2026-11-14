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
    @Test void mailScopeSurvivesManagedSaveCredentialTargetChangeAndAccountToolMapping() throws Exception {
        try (var store = new IntegrationStore(root); var credentials = new SessionCredentialStore();
             var sessions = ChatSessionStore.openExclusive(root.resolve("memory"));
             var approvals = new ApprovalRegistry()) {
            var policy = new MailSessionPolicy(sessions);
            try (var manager = new McpManager(store, credentials, policy, Config.testDefaults(root.resolve("memory")),
                    approvals, (endpoint, timeout, initialization, maximum, bearer) -> { throw new AssertionError("No external MCP connection expected"); })) {
                var account = new IntegrationConfig.MailAccount("mail-one", "qq", "synthetic@qq.com", "Synthetic", true,
                        "all", List.of("INBOX", "Projects/项目"), false, null, "session", 1);
                // Seed a legacy enabled/session account: the new managed route intentionally cannot create it.
                String legacyReference = credentials.write(account.credentialTarget(), "synthetic-secret".toCharArray(), "session");
                var credentialed = store.save(0, new IntegrationConfig(1, 0, List.of(),
                        List.of(account.withCredential(legacyReference, "session")), List.of()));
                var kept = manager.save(1, new IntegrationConfig(1, 1, List.of(server("https://example.com/mcp", null)),
                        credentialed.mailAccounts(), List.of())).mailAccounts().getFirst();
                assertEquals("all", kept.folderScope()); assertEquals(account.allowedFolders(), kept.allowedFolders());
                assertNotNull(kept.credentialRef()); assertFalse(kept.bodyAccess());
                var session = sessions.create(new ChatSessionStore.CreateRequest());
                var turn = new com.selfanalyst.agent.AgentIntegrationGateway.TurnContext(session.id, "a".repeat(12), true, "https://model.example.com/v1");
                policy.consentTarget(session.id, turn.modelTarget());
                var tool = manager.snapshot(turn).stream().filter(t -> t.getName().equals("mail_list_accounts")).findFirst().orElseThrow();
                assertTrue(tool.getDescription().contains("allowedFolders limits access only when folderScope=selected"));
                var result = tool.callAsync(io.agentscope.core.tool.ToolCallParam.builder().input(Map.of()).build()).block();
                var text = (io.agentscope.core.message.TextBlock) result.getOutput().getFirst();
                var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text.getText());
                assertEquals("all", json.get(0).path("folderScope").asText());
                var exposedFolders = new HashSet<String>();
                json.get(0).path("allowedFolders").forEach(folder -> exposedFolders.add(folder.asText()));
                assertEquals(Set.of("INBOX", "Projects/项目"), exposedFolders);
                assertFalse(json.get(0).path("bodyAccess").asBoolean());
                assertFalse(text.getText().contains("credential")); assertTrue(policy.containsMail(session.id));
                var changed = new IntegrationConfig.MailAccount("mail-one", "163", "synthetic@163.com", "Synthetic", true,
                        "all", account.allowedFolders(), false, kept.credentialRef(), "session", 1);
                var saved = manager.save(2, new IntegrationConfig(1, 2, List.of(), List.of(changed), List.of())).mailAccounts().getFirst();
                assertEquals("all", saved.folderScope()); assertEquals(account.allowedFolders(), saved.allowedFolders());
                assertNull(saved.credentialRef()); assertFalse(saved.enabled()); assertEquals(0, saved.disclosureVersion());
                assertFalse(credentials.configured(kept.credentialRef(), kept.credentialTarget()));
                manager.endTurn(turn);
            }
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
