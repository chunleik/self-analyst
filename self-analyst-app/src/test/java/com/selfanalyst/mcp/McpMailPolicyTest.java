package com.selfanalyst.mcp;

import com.selfanalyst.integrations.IntegrationConfig;
import com.selfanalyst.integrations.SessionCredentialStore;
import com.selfanalyst.mail.MailCancellation;
import com.selfanalyst.mail.MailWriteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpMailPolicyTest {
    @TempDir Path root;

    @Test
    void distinguishesFolderNamesThatLookLikePrintedListsWithoutDependingOnOrder() throws Exception {
        var cfg = new AtomicReference<>(config(0, account(List.of("Folder A", "Folder B"), null)));
        try (var manager = new McpMailManagement(root, cfg::get, new SessionCredentialStore(), () -> false)) {
            manager.save(0, List.of(new McpMailManagement.AccountSetting("qq", true, true, false)));
            assertTrue(manager.exposed("qq"));

            cfg.set(config(1, account(List.of("Folder B", "Folder A"), null)));
            assertTrue(manager.exposed("qq"), "Ordering does not change the mailbox scope");

            cfg.set(config(2, account(List.of("Folder A, Folder B"), null)));
            assertFalse(manager.exposed("qq"), "Different scope must not inherit the old sharing permission");
            assertFalse(manager.writeAvailable(cfg.get().mailAccounts().getFirst(), "create_draft"));
        }
    }

    @Test
    void staleRevisionAndDeniedOrRevokedApprovalNeverReachMailTransport() throws Exception {
        var vault = new SessionCredentialStore();
        var row = account(List.of("Drafts"), null);
        String reference = vault.write(row.credentialTarget(), "synthetic-imap-code".toCharArray(), "session");
        var cfg = new AtomicReference<>(config(0, account(List.of("Drafts"), reference)));
        var resolverCalls = new AtomicInteger();
        var writer = new MailWriteService((host, port) -> {
            resolverCalls.incrementAndGet();
            throw new IOException("synthetic_no_network");
        });
        var args = Map.<String, Object>of("to", List.of("example@example.invalid"),
                "subject", "Synthetic subject", "body", "Synthetic body");
        try (var manager = new McpMailManagement(root, cfg::get, vault, () -> false, writer)) {
            var permission = new McpMailManagement.AccountSetting("qq", false, true, false);
            manager.save(0, List.of(permission));
            long revision = manager.writeRevision();
            var staleApproval = new AtomicBoolean();
            assertThrows(IOException.class, () -> manager.internalWrite(revision - 1, "qq", "create_draft",
                    args, new MailCancellation(), () -> true, prepared -> staleApproval.set(true)));
            assertFalse(staleApproval.get());

            var denied = new IllegalStateException("synthetic_denied");
            assertSame(denied, assertThrows(IllegalStateException.class, () -> manager.internalWrite(revision,
                    "qq", "create_draft", args, new MailCancellation(), () -> true, prepared -> { throw denied; })));

            var approvedOldPolicy = new AtomicBoolean();
            assertThrows(IOException.class, () -> manager.internalWrite(revision,
                    "qq", "create_draft", args, new MailCancellation(), () -> true, prepared -> {
                        assertEquals("Synthetic body", prepared.approvalDetails().get("body"));
                        assertEquals(List.of("example@example.invalid"), prepared.approvalDetails().get("to"));
                        approvedOldPolicy.set(true);
                        try {
                            manager.save(revision, List.of(new McpMailManagement.AccountSetting("qq", false, false, false)));
                        } catch (IOException failure) {
                            throw new AssertionError(failure);
                        }
                    }));
            assertTrue(approvedOldPolicy.get());
            assertEquals(0, resolverCalls.get());
        }
    }

    private static IntegrationConfig.MailAccount account(List<String> folders, String reference) {
        return new IntegrationConfig.MailAccount("qq", "qq", "synthetic@qq.com", "Synthetic",
                true, "selected", folders, false, reference, "session", 1);
    }

    private static IntegrationConfig config(long revision, IntegrationConfig.MailAccount row) {
        return new IntegrationConfig(1, revision, List.of(), List.of(row), List.of());
    }
}
