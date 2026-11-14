package com.selfanalyst.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.controller.DesktopIntegrationController;
import com.selfanalyst.desktop.store.ChatSessionStore;
import io.javalin.Javalin;
import com.selfanalyst.integrations.*;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import com.selfanalyst.mail.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MailFolderDiscoveryTest {
    @TempDir Path root;
    static final String REF = "windows:12345678-1234-1234-1234-123456789abc";
    static final String REQUEST = "12345678-1234-1234-1234-123456789abc";
    static IntegrationConfig.MailAccount account(String ref) {
        return new IntegrationConfig.MailAccount("mail", "163", "synthetic@163.com", "Synthetic", false,
                "selected", List.of("INBOX"), false, ref, ref == null ? "session" : "windows", 0);
    }
    static class Vault implements CredentialStore {
        int writes;
        Runnable beforeRead;
        @Override public String write(CredentialTarget target, char[] secret, String mode) { writes++; throw new AssertionError("discovery must not persist"); }
        @Override public Optional<char[]> read(String reference, CredentialTarget target) {
            if (beforeRead != null) beforeRead.run();
            return REF.equals(reference) && target.equals(account(REF).credentialTarget())
                    ? Optional.of("synthetic-vault".toCharArray()) : Optional.empty();
        }
        @Override public void delete(String ref) { }
        @Override public void close() { }
    }
    static class Transport implements MailTransport {
        volatile List<FolderInfo> folders = List.of(new FolderInfo("INBOX", "/", true), new FolderInfo("自定义/客户A", "/", true));
        final AtomicInteger connects = new AtomicInteger(), closes = new AtomicInteger();
        volatile char[] receivedSecret;
        volatile CountDownLatch entered, release;
        volatile Runnable beforeResult;
        @Override public Connection connect(MailAccount account, char[] secret, MailCancellation cancellation, MailLimits limits) {
            connects.incrementAndGet(); receivedSecret = secret;
            assertEquals("imap.163.com", account.provider().host());
            assertTrue(secret.length > 0);
            return new Connection() {
                @Override public List<String> listFolders() { throw new AssertionError("use detailed listing"); }
                @Override public List<FolderInfo> listFolderDetails() {
                    if (entered != null) entered.countDown();
                    if (release != null) {
                        try { while (!release.await(10, TimeUnit.MILLISECONDS)) cancellation.check(); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); cancellation.check(); }
                    }
                    cancellation.check(); if (beforeResult != null) beforeResult.run(); return folders;
                }
                @Override public Mailbox openReadOnly(String folder) { throw new AssertionError("discovery cannot open a mailbox"); }
                @Override public void close() { closes.incrementAndGet(); }
            };
        }
    }
    class Fixture implements AutoCloseable {
        final IntegrationStore store = new IntegrationStore(root);
        final Vault vault = new Vault();
        final ChatSessionStore sessions = new ChatSessionStore(root.resolve("memory"));
        final Transport transport = new Transport();
        final McpManager manager;
        Fixture(String ref) throws Exception {
            store.save(0, new IntegrationConfig(1, 0, List.of(), List.of(account(ref)), List.of()));
            manager = new McpManager(store, vault, new MailSessionPolicy(sessions), Config.testDefaults(root.resolve("memory")),
                    new ApprovalRegistry(), (a,b,c,d,e) -> { throw new AssertionError("no MCP network"); }, transport);
        }
        @Override public void close() { manager.close(); sessions.close(); }
    }
    @Test void temporaryDiscoveryIsCompleteEvenOutsideSelectedScopeAndDoesNotEnableOrPersist() throws Exception {
        try (var f = new Fixture(null)) {
            var before = f.manager.config(); char[] secret = "temporary-private".toCharArray();
            var result = f.manager.discoverMailFolders(1, "mail", REQUEST, secret);
            assertEquals("mail", result.accountId()); assertEquals(1, result.revision()); assertTrue(result.complete());
            assertEquals(f.transport.folders, result.folders()); assertEquals(before, f.manager.config());
            assertFalse(f.manager.config().mailAccounts().getFirst().enabled()); assertEquals(0, f.vault.writes);
            assertEquals(1, f.transport.closes.get()); assertArrayEquals(new char[secret.length], secret);
            assertArrayEquals(new char[f.transport.receivedSecret.length], f.transport.receivedSecret);
        }
    }
    @Test void matchingSystemCredentialIsReusedAndMissingCredentialDoesNotConnect() throws Exception {
        try (var f = new Fixture(REF)) {
            assertEquals(2, f.manager.discoverMailFolders(1, "mail", REQUEST, null).folders().size());
            assertEquals(0, f.vault.writes);
        }
    }
    @Test void missingCredentialBadIdentityAndStaleRevisionFailBeforeNetwork() throws Exception {
        try (var f = new Fixture(null)) {
            assertEquals("mail_credential_required", assertThrows(IOException.class,
                    () -> f.manager.discoverMailFolders(1, "mail", REQUEST, null)).getMessage());
            assertEquals("revision_conflict", assertThrows(IOException.class,
                    () -> f.manager.discoverMailFolders(0, "mail", REQUEST, "temp".toCharArray())).getMessage());
            assertThrows(IllegalArgumentException.class, () -> f.manager.discoverMailFolders(1, "other", REQUEST, "temp".toCharArray()));
            assertThrows(IllegalArgumentException.class, () -> f.manager.discoverMailFolders(1, "mail", "invalid", "temp".toCharArray()));
            assertEquals(0, f.transport.connects.get());
        }
    }
    @Test void duplicateRequestAndWrongCancellationCannotCancelActiveOperation() throws Exception {
        try (var f = new Fixture(null); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            f.transport.entered = new CountDownLatch(1); f.transport.release = new CountDownLatch(1);
            var pending = executor.submit(() -> f.manager.discoverMailFolders(1, "mail", REQUEST, "temp".toCharArray()));
            assertTrue(f.transport.entered.await(3, TimeUnit.SECONDS));
            assertEquals("mail_discovery_busy", assertThrows(IOException.class,
                    () -> f.manager.discoverMailFolders(1, "mail", UUID.randomUUID().toString(), "temp".toCharArray())).getMessage());
            f.manager.cancelMailFolderDiscovery("mail", UUID.randomUUID().toString());
            assertFalse(pending.isDone());
            f.manager.cancelMailFolderDiscovery("mail", REQUEST);
            assertEquals(MailException.Code.CANCELLED, ((MailException) assertThrows(ExecutionException.class,
                    () -> pending.get(3, TimeUnit.SECONDS)).getCause()).code());
            assertEquals(1, f.transport.connects.get());
        }
    }
    @Test void cancellationBeforeRequestPreventsNetworkAndDoesNotCancelNewRequest() throws Exception {
        try (var f = new Fixture(null)) {
            f.manager.cancelMailFolderDiscovery("mail", REQUEST);
            assertEquals(MailException.Code.CANCELLED, assertThrows(MailException.class,
                    () -> f.manager.discoverMailFolders(1,"mail",REQUEST,"temp".toCharArray())).code());
            assertEquals(0,f.transport.connects.get());
            assertEquals(2,f.manager.discoverMailFolders(1,"mail",UUID.randomUUID().toString(),"temp".toCharArray()).folders().size());
        }
    }
    @Test void deletingAccountCancelsDiscoveryAndNoLateListIsReturned() throws Exception {
        try (var f = new Fixture(null); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            f.transport.entered = new CountDownLatch(1); f.transport.release = new CountDownLatch(1);
            var pending = executor.submit(() -> f.manager.discoverMailFolders(1, "mail", REQUEST, "temp".toCharArray()));
            assertTrue(f.transport.entered.await(3, TimeUnit.SECONDS));
            f.manager.delete(1, "mail", "mail");
            assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
            assertTrue(f.manager.config().mailAccounts().isEmpty());
        }
    }
    @Test void revisionIsRecheckedAfterNetworkEvenWhenChangedOutsideManager() throws Exception {
        try (var f = new Fixture(null)) {
            f.transport.beforeResult = () -> {
                try { f.store.save(1, new IntegrationConfig(1,1,List.of(),List.of(account(null)),List.of())); }
                catch (IOException e) { throw new AssertionError(e); }
            };
            assertEquals("revision_conflict",assertThrows(IOException.class,
                    () -> f.manager.discoverMailFolders(1,"mail",REQUEST,"temp".toCharArray())).getMessage());
        }
    }
    @Test void credentialAndRuntimeAccountUseOneSnapshotWhenIdentityChangesDuringVaultRead() throws Exception {
        try (var f = new Fixture(REF)) {
            f.vault.beforeRead = () -> {
                var changed = new IntegrationConfig.MailAccount("mail","qq","other@qq.com","Changed",false,
                        "all",List.of(),false,null,"session",0);
                try { f.store.save(1,new IntegrationConfig(1,1,List.of(),List.of(changed),List.of())); }
                catch (IOException e) { throw new AssertionError(e); }
            };
            assertEquals("revision_conflict",assertThrows(IOException.class,
                    () -> f.manager.discoverMailFolders(1,"mail",REQUEST,null)).getMessage());
            // The fake transport insists the secret is used only for the original 163 target.
            assertEquals(1,f.transport.connects.get());
        }
    }
    @Test void disconnectCancelsPendingDiscoveryWithoutEnablingOrPersisting() throws Exception {
        try (var f = new Fixture(null); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            f.transport.entered = new CountDownLatch(1); f.transport.release = new CountDownLatch(1);
            var pending = executor.submit(() -> f.manager.discoverMailFolders(1,"mail",REQUEST,"temp".toCharArray()));
            assertTrue(f.transport.entered.await(3,TimeUnit.SECONDS));
            f.manager.disconnect(1,"mail");
            assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));
            assertFalse(f.manager.config().mailAccounts().getFirst().enabled()); assertEquals(0,f.vault.writes);
        }
    }
    @Test void malformedOversizedAndDuplicateListingsNeverReturnPartialSuccess() throws Exception {
        try (var f = new Fixture(null)) {
            f.transport.folders = java.util.stream.IntStream.range(0, 256).mapToObj(i -> new MailTransport.FolderInfo("F"+i, "/", true)).toList();
            assertEquals(256, f.manager.discoverMailFolders(1,"mail",REQUEST,"temp".toCharArray()).folders().size());
            var tooMany = new ArrayList<>(f.transport.folders); tooMany.add(new MailTransport.FolderInfo("extra", "/", true));
            f.transport.folders = tooMany;
            assertEquals(MailException.Code.LIMIT_EXCEEDED, assertThrows(MailException.class,
                    () -> f.manager.discoverMailFolders(1,"mail",REQUEST,"temp".toCharArray())).code());
            for (var malformed : List.of(new MailTransport.FolderInfo("bad\nname","/",true),
                    new MailTransport.FolderInfo("x","//",true), new MailTransport.FolderInfo("x","/",false))) {
                f.transport.folders = List.of(malformed);
                assertThrows(MailException.class, () -> f.manager.discoverMailFolders(1,"mail",REQUEST,"temp".toCharArray()));
            }
            f.transport.folders = List.of(new MailTransport.FolderInfo("x","",true),new MailTransport.FolderInfo("x","",true));
            assertThrows(MailException.class, () -> f.manager.discoverMailFolders(1,"mail",REQUEST,"temp".toCharArray()));
        }
    }
    @Test void managementHttpIsAuthenticatedStrictNoStoreAndReturnsOnlyDirectoryMetadata() throws Exception {
        try (var f = new Fixture(null)) {
            var server = Javalin.create();
            server.before(ctx -> ctx.attribute("selfanalyst.managedDesktop", "synthetic-token".equals(ctx.header("X-SelfAnalyst-Token"))));
            try (AutoCloseable stop = server::stop) {
                new DesktopIntegrationController(f.manager, () -> "https://model.example/v1").register(server); server.start("127.0.0.1", 0);
                String body = "{\"id\":\"mail\",\"expectedRevision\":1,\"requestId\":\""+REQUEST+"\",\"secret\":\"temporary-private\"}";
                assertEquals(403, request(server,"/mail/folders",body,null).statusCode());
                for (String invalid : List.of(body+" {}",body.replace("\"id\":\"mail\"","\"id\":\"mail\",\"id\":\"other\""),
                        body.replace("}",",\"host\":\"localhost\"}"),body.replace("\"expectedRevision\":1","\"expectedRevision\":\"1\"")))
                    assertEquals(400,request(server,"/mail/folders",invalid,"synthetic-token").statusCode());
                assertEquals(0,f.transport.connects.get());
                var response=request(server,"/mail/folders",body,"synthetic-token");
                assertEquals(200,response.statusCode()); assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
                var json=new ObjectMapper().readTree(response.body());
                assertTrue(json.path("complete").asBoolean()); assertEquals(2,json.path("folders").size());
                assertFalse(response.body().contains("temporary-private")); assertFalse(response.body().contains("credential"));
                assertEquals(200,request(server,"/mail/folders/cancel","{\"id\":\"mail\",\"requestId\":\""+REQUEST+"\"}","synthetic-token").statusCode());
            }
        }
    }
    private static HttpResponse<String> request(Javalin server,String path,String body,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/desktop/integrations"+path))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if(token!=null) request.header("X-SelfAnalyst-Token",token);
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
