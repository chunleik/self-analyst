package com.selfanalyst.integrations;

import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import com.selfanalyst.mcp.ApprovalRegistry;
import com.selfanalyst.mcp.McpManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationDeletionFailureTest {
    @TempDir Path root;

    @Test void failedAtomicCommitRetainsMetadataApprovalsAndCredential() throws Exception {
        try (var credentials = new SessionCredentialStore(); var initialStore = new IntegrationStore(root);
             var sessions = new ChatSessionStore(root.resolve("memory"))) {
            var target = CredentialTarget.mcp("one", "https://example.com/mcp");
            String reference = credentials.write(target, "synthetic-secret".toCharArray(), "session");
            var server = new IntegrationConfig.McpServer("one", "One", false, "streamable-http",
                    "https://example.com/mcp", "bearer", reference, "session", IntegrationConfig.Budget.defaults());
            var saved = initialStore.save(0, new IntegrationConfig(1, 0, List.of(server), List.of(),
                    List.of(new IntegrationConfig.ToolApproval("one", "search", "a".repeat(64), 0))));
            try (var failingStore = new IntegrationStore(root, (from, to) -> { throw new IOException("synthetic atomic failure"); });
                 var manager = new McpManager(failingStore, credentials, new MailSessionPolicy(sessions),
                         Config.testDefaults(root.resolve("memory")), new PinnedHttpsClient(), new ApprovalRegistry())) {
                assertThrows(IOException.class, () -> manager.delete(1, "mcp", "one"));
                assertEquals(saved, failingStore.snapshot());
                assertTrue(credentials.configured(reference, target));
                assertFalse(manager.credentialCleanupPending());
            }
        }
    }
}
