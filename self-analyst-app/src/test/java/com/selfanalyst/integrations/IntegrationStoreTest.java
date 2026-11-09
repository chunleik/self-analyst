package com.selfanalyst.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationStoreTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();
    static IntegrationConfig.McpServer server(String reference) {
        return new IntegrationConfig.McpServer("mcp-one", "示例 MCP", false, "streamable-http",
                "https://mcp.example.com/api", "bearer", reference, "session", IntegrationConfig.Budget.defaults());
    }
    static IntegrationConfig.MailAccount account(String reference) {
        return new IntegrationConfig.MailAccount("mail-one", "qq", "synthetic@qq.com", "合成账号", false,
                List.of("INBOX", "工作/项目"), false, reference, "session", 0);
    }
    static IntegrationConfig config(long revision, String reference) {
        return new IntegrationConfig(1, revision, List.of(server(reference)), List.of(account(null)), List.of());
    }
    @Test void missingFileHasNoSideEffectsAndRoundTripsComplexArrays() throws Exception {
        try (var store = new IntegrationStore(root)) {
            assertEquals(IntegrationConfig.empty(), store.snapshot());
            assertFalse(Files.exists(root.resolve("config")));
            IntegrationConfig saved = store.save(0, config(0, null));
            assertEquals(1, saved.revision());
            assertEquals(saved, store.snapshot());
            assertEquals(List.of("INBOX", "工作/项目"), saved.mailAccounts().getFirst().allowedFolders());
        }
    }
    @Test void corruptedUnknownAndUnknownFieldsAreNotOverwritten() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Path file = root.resolve("config/integrations.json");
        for (String invalid : List.of("{", "{\"schemaVersion\":2,\"revision\":0,\"mcpServers\":[],\"mailAccounts\":[],\"toolApprovals\":[]}",
                "{\"schemaVersion\":1,\"revision\":0,\"mcpServers\":[],\"mailAccounts\":[],\"toolApprovals\":[],\"secret\":\"SYNTHETIC\"}")) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> new IntegrationStore(root));
            assertEquals(invalid, Files.readString(file));
        }
    }
    @Test void strictParserRejectsCoercionsDuplicatesTrailingAndCommands() throws Exception {
        String valid = JSON.writeValueAsString(config(0, null));
        assertEquals(config(0, null), IntegrationStore.parse(valid));
        for (String invalid : List.of(valid.replace("\"revision\":0", "\"revision\":\"0\""),
                valid.replace("\"revision\":0", "\"revision\":0,\"revision\":1"), valid + " {}",
                valid.replace("\"transport\":\"streamable-http\"", "\"transport\":\"stdio\",\"command\":\"sh\""),
                valid.replace("\"enabled\":false", "\"enabled\":null"))) {
            assertThrows(IOException.class, () -> IntegrationStore.parse(invalid));
        }
    }
    @Test void duplicateAndReservedIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new IntegrationConfig(1, 0, List.of(server(null), server(null)), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new IntegrationConfig.McpServer("legacy-websearch", "Legacy", false,
                "streamable-http", "https://search.example.com", "anonymous", null, "session", IntegrationConfig.Budget.defaults()));
    }
    @Test void optimisticWritersCannotOverwriteNewerRevision() throws Exception {
        var first = new IntegrationStore(root); var second = new IntegrationStore(root);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> a = () -> { go.await(); try { first.save(0, config(0, null)); return true; } catch (IOException conflict) { return false; } };
            java.util.concurrent.Callable<Boolean> b = () -> { go.await(); try { second.save(0, config(0, null)); return true; } catch (IOException conflict) { return false; } };
            Future<Boolean> fa = executor.submit(a), fb = executor.submit(b); go.countDown();
            assertNotEquals(fa.get(), fb.get());
        }
        assertEquals(1, first.snapshot().revision());
    }
    @Test void replaceFailureKeepsOldFileAndBackupAndCleansStaging() throws Exception {
        IntegrationConfig original = new IntegrationStore(root).save(0, config(0, null));
        var failing = new IntegrationStore(root, (from, to) -> { throw new IOException("synthetic_failure"); });
        assertThrows(IOException.class, () -> failing.save(1, config(1, null)));
        assertEquals(original, failing.snapshot());
        assertEquals(original, IntegrationStore.readConfig(root.resolve("config/integrations.json.bak")));
        try (var files = Files.list(root.resolve("config"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
    @Test void closedStoreCannotSaveAndTomlIsUntouched() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Path toml = root.resolve("config/config.toml"); Files.writeString(toml, "[example]\nvalue = 'unchanged'\n");
        var store = new IntegrationStore(root); store.save(0, config(0, null)); store.close();
        assertThrows(IOException.class, () -> store.save(1, config(1, null)));
        assertEquals("[example]\nvalue = 'unchanged'\n", Files.readString(toml));
    }
    @Test void portableImportExportClearsCredentialsAndApprovals() throws Exception {
        String ref = "session:12345678-1234-1234-1234-123456789abc";
        var connected = new IntegrationConfig.McpServer("mcp-one", "Example", true, "streamable-http",
                "https://mcp.example.com/api", "bearer", ref, "session", IntegrationConfig.Budget.defaults());
        var approval = new IntegrationConfig.ToolApproval("mcp-one", "search", "a".repeat(64), 0);
        var store = new IntegrationStore(root);
        store.save(0, new IntegrationConfig(1, 0, List.of(connected), List.of(account(null)), List.of(approval)));
        String exported = store.exportMetadata();
        assertFalse(exported.contains(ref)); assertFalse(exported.contains("a".repeat(64)));
        var imported = store.importMetadata(1, JSON.writeValueAsString(new IntegrationConfig(1, 0, List.of(connected), List.of(), List.of(approval))));
        assertFalse(imported.mcpServers().getFirst().enabled()); assertNull(imported.mcpServers().getFirst().credentialRef());
        assertTrue(imported.toolApprovals().isEmpty());
    }
    @Test void publicStatusIsWriteOnlyAndRestoredSessionNeedsCredentials() throws Exception {
        var credentials = new SessionCredentialStore();
        String ref = credentials.write(server(null).credentialTarget(), "synthetic-secret".toCharArray(), "session");
        var store = new IntegrationStore(root); store.save(0, config(0, ref));
        String status = JSON.writeValueAsString(store.publicMetadata(credentials));
        assertTrue(status.contains("\"credentialConfigured\":true"));
        assertFalse(status.contains(ref)); assertFalse(status.contains("synthetic-secret"));
        credentials.close();
        String restored = JSON.writeValueAsString(store.publicMetadata(new SessionCredentialStore()));
        assertFalse(restored.contains("\"credentialConfigured\":true"));
    }
    @Test void symlinkConfigurationIsRejectedWithoutFollowingIt() throws Exception {
        Path elsewhere = root.resolve("elsewhere"); Files.createDirectories(elsewhere);
        try { Files.createSymbolicLink(root.resolve("config"), elsewhere); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) { return; }
        assertThrows(IOException.class, () -> new IntegrationStore(root));
        try (var files = Files.list(elsewhere)) { assertEquals(0, files.count()); }
    }
}
